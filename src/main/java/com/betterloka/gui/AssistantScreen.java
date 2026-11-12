package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.assistant.AssistantClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * Loka Assistant: ask anything about Loka and an AI answers from what Loka has published and what
 * players taught it.
 *
 * <p>Two tabs. <b>Ask</b> is a chat; each answer can be marked good or wrong. <b>Help it learn</b>
 * lists what the assistant could not answer (or answered wrong) so players can write the answer.
 * The team approves those answers on Discord, and only then does the assistant learn them.
 *
 * <p>Questions go to the BetterLoka assistant server ({@link AssistantClient#SERVER}, not a setting),
 * never to Loka's server, and only when the player asks; before the first one the screen says so and
 * waits for the player to agree. Under the chat a red line says the assistant can be wrong, and that
 * punishments are a question for Loka's staff, not for it.
 */
public class AssistantScreen extends Screen {
    private static final int MAX_WIDTH = 480;
    /** Minecraft's own red (§c): the disclaimer has to read as a warning, not as a hint. */
    private static final int DISCLAIMER_RED = 0xFFFF5555;
    private static final int TOP = 22;
    private static final int TAB_HEIGHT = 16;
    private static final int LINE = 10;
    private static final int PAD = 5;
    private static final int GAP = 6;
    private static final int MAX_ENTRIES = 30;
    private static final int HISTORY = 3;
    private static final long TOAST_MILLIS = 4500;
    private static final int HELP_ANSWER_HEIGHT = 44;

    private enum Tab { ASK, HELP }

    /** One question and what came back. */
    private static final class Entry {
        final String question;
        boolean pending = true;
        String answer;
        String answerId;
        String status;
        String error;
        /** 0 not rated, 1 good, -1 wrong. */
        int rated;

        Entry(String question) {
            this.question = question;
        }
    }

    private record Hit(int x, int y, int width, int height, Runnable action) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    // Kept between openings, as a chat is.
    private static final List<Entry> conversation = new ArrayList<>();
    private static Tab tab = Tab.ASK;
    private static String draft = "";
    private static String helpDraft = "";
    private static AssistantScreen devInstance;

    private final Screen parent;
    private final AssistantClient client = BetterLokaClient.assistant();
    private final ScrollPanel scroll = new ScrollPanel();
    private final List<Hit> hits = new ArrayList<>();

    private int activeTabX;
    private int activeTabWidth;
    private EditBox input;
    private EditBox helpQuestion;
    private MultiLineEditBox helpAnswer;
    private boolean connecting;
    private String connectError;
    private boolean stickToEnd = true;

    private List<AssistantClient.OpenQuestion> open = List.of();
    private boolean openLoading;
    private String openError;
    private String selectedOpenId;


    private Component toast;
    private boolean toastGood;
    private long toastAt;

    public AssistantScreen(Screen parent) {
        super(Component.translatable("betterloka.module.assistant"));
        this.parent = parent;
    }

    private int contentWidth() {
        return Math.min(this.width - 24, MAX_WIDTH);
    }

    private int left() {
        return (this.width - contentWidth()) / 2;
    }

    private int backY() {
        return this.height - 24;
    }

    /** The input sits above the red disclaimer, however many lines it wraps to at this width. */
    private int inputY() {
        return backY() - 24 - disclaimerLines().size() * 10;
    }

    /** "The assistant can be wrong; ask staff about bans", wrapped to the chat's width, at most three lines. */
    private List<FormattedCharSequence> disclaimerLines() {
        List<FormattedCharSequence> lines = this.font.split(Component.translatable("betterloka.assistant.disclaimer"), contentWidth());
        return lines.size() > 3 ? lines.subList(0, 3) : lines;
    }

    private int listTop() {
        return TOP + TAB_HEIGHT + GAP;
    }

    private boolean needsNotice() {
        return !BetterLokaClient.config().assistantNoticeAccepted();
    }

    private boolean ready() {
        return !needsNotice();
    }

    @Override
    protected void init() {
        devInstance = this;
        int left = left();
        int width = contentWidth();
        if (input != null) {
            draft = input.getValue();
        }
        if (helpAnswer != null) {
            helpDraft = helpAnswer.getValue();
        }
        input = null;
        helpQuestion = null;
        helpAnswer = null;

        if (needsNotice()) {
            int panelY = this.height / 2 + 30;
            addRenderableWidget(Button.builder(Component.translatable("betterloka.assistant.accept")
                            .withStyle(ChatFormatting.GREEN), button -> {
                        BetterLokaClient.config().setAssistantNoticeAccepted(true);
                        rebuildWidgets();
                        connect();
                    })
                    .bounds(this.width / 2 - 75, panelY, 150, 20).build());
        } else {
            initTabs(left, width);
            switch (tab) {
                case ASK -> initAsk(left, width);
                case HELP -> initHelp(left, width);
            }
            scroll.setViewport(left, listTop(), width, listBottom() - listTop());
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(this.width / 2 - 50, backY(), 100, 20).build());

        if (ready() && client.loggedInAs() == null && !connecting && connectError == null) {
            connect();
        }
    }

    private int listBottom() {
        return switch (tab) {
            case ASK -> inputY() - GAP;
            case HELP -> helpComposerTop() - GAP;
        };
    }

    /** The composer under the open questions: two labels, the question, the answer and its counter. */
    private int helpComposerTop() {
        return backY() - 4 - (LINE + 18 + 2 + LINE + HELP_ANSWER_HEIGHT + LINE);
    }

    private void initTabs(int left, int width) {
        List<Tab> tabs = List.of(Tab.ASK, Tab.HELP);
        int tabWidth = Math.min(150, (width - (tabs.size() - 1) * 4) / tabs.size());
        int x = left;
        for (Tab each : tabs) {
            Component label = switch (each) {
                case ASK -> Component.translatable("betterloka.assistant.tab.ask");
                case HELP -> Component.translatable("betterloka.assistant.tab.help", open.size());
            };
            Button button = Button.builder(label, b -> switchTab(each)).bounds(x, TOP, tabWidth, TAB_HEIGHT).build();
            button.active = tab != each;
            if (tab == each) {
                activeTabX = x;
                activeTabWidth = tabWidth;
            }
            addRenderableWidget(button);
            x += tabWidth + 4;
        }
    }

    private void switchTab(Tab next) {
        tab = next;
        scroll.reset();
        stickToEnd = next == Tab.ASK;
        rebuildWidgets();
        if (next == Tab.HELP) {
            loadOpen();
        }
    }

    private void initAsk(int left, int width) {
        int buttonWidth = 70;
        input = new EditBox(this.font, left, inputY(), width - buttonWidth - 4, 20, Component.translatable("betterloka.assistant.input_hint"));
        input.setMaxLength(300);
        input.setHint(Component.translatable("betterloka.assistant.input_hint").withStyle(ChatFormatting.DARK_GRAY));
        input.setValue(draft);
        addRenderableWidget(input);
        setInitialFocus(input);
        addRenderableWidget(Button.builder(Component.translatable("betterloka.assistant.send").withStyle(ChatFormatting.AQUA),
                        button -> ask())
                .bounds(left + width - buttonWidth, inputY(), buttonWidth, 20).build());
    }

    private void initHelp(int left, int width) {
        int y = helpComposerTop() + LINE;
        helpQuestion = new EditBox(this.font, left, y, width, 18, Component.translatable("betterloka.assistant.question"));
        helpQuestion.setMaxLength(300);
        helpQuestion.setHint(Component.translatable("betterloka.assistant.question_hint").withStyle(ChatFormatting.DARK_GRAY));
        AssistantClient.OpenQuestion selected = selectedOpen();
        if (selected != null) {
            helpQuestion.setValue(selected.question());
        }
        helpQuestion.setResponder(value -> {
            AssistantClient.OpenQuestion current = selectedOpen();
            if (current != null && !current.question().equals(value)) {
                // A question of the player's own now, not the open one.
                selectedOpenId = null;
            }
        });
        addRenderableWidget(helpQuestion);
        y += 18 + LINE + 2;
        int buttonWidth = 110;
        helpAnswer = MultiLineEditBox.builder()
                .setX(left).setY(y)
                .setPlaceholder(Component.translatable("betterloka.assistant.answer_hint").withStyle(ChatFormatting.DARK_GRAY))
                .build(this.font, width - buttonWidth - 4, HELP_ANSWER_HEIGHT, Component.translatable("betterloka.assistant.your_answer"));
        helpAnswer.setCharacterLimit(1200);
        helpAnswer.setValue(helpDraft);
        addRenderableWidget(helpAnswer);
        addRenderableWidget(Button.builder(Component.translatable("betterloka.assistant.submit").withStyle(ChatFormatting.GREEN),
                        button -> propose())
                .bounds(left + width - buttonWidth, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("betterloka.assistant.refresh"), button -> loadOpen())
                .bounds(left + width - buttonWidth, y + 24, buttonWidth, 20).build());
    }

    // ---- talking to the server ----

    private void connect() {
        connecting = true;
        connectError = null;
        client.connect().whenComplete((name, error) -> onGame(() -> {
            connecting = false;
            if (error != null) {
                connectError = code(error);
                return;
            }
            rebuildWidgets();
            loadOpen();
        }));
    }

    private void ask() {
        if (input == null) {
            return;
        }
        String question = input.getValue().strip();
        if (question.isEmpty() || conversation.stream().anyMatch(entry -> entry.pending)) {
            return;
        }
        List<AssistantClient.Turn> history = new ArrayList<>();
        for (Entry entry : conversation) {
            if (entry.answer != null && entry.error == null) {
                history.add(new AssistantClient.Turn(entry.question, entry.answer));
            }
        }
        if (history.size() > HISTORY) {
            history = new ArrayList<>(history.subList(history.size() - HISTORY, history.size()));
        }
        Entry entry = new Entry(question);
        conversation.add(entry);
        while (conversation.size() > MAX_ENTRIES) {
            conversation.remove(0);
        }
        input.setValue("");
        draft = "";
        stickToEnd = true;
        String language = Minecraft.getInstance().options.languageCode;
        client.ask(question, language, history).whenComplete((answer, error) -> onGame(() -> {
            entry.pending = false;
            stickToEnd = true;
            if (error != null) {
                entry.error = code(error);
                return;
            }
            entry.answer = answer.text();
            entry.answerId = answer.id();
            entry.status = answer.status();
            if ("refused".equals(answer.status()) || answer.text() == null || answer.text().isBlank()) {
                entry.error = "refused";
                entry.answer = null;
            }
            if (answer.unknown()) {
                loadOpen();
            }
        }));
    }

    private void rate(Entry entry, boolean good) {
        if (entry.answerId == null || entry.rated != 0) {
            return;
        }
        entry.rated = good ? 1 : -1;
        client.feedback(entry.answerId, good).whenComplete((ignored, error) -> onGame(() -> {
            if (error != null) {
                showToast(errorText(code(error)), false);
            } else if (!good) {
                loadOpen();
            }
        }));
    }

    private void loadOpen() {
        if (!ready()) {
            return;
        }
        openLoading = true;
        client.openQuestions().whenComplete((list, error) -> onGame(() -> {
            openLoading = false;
            openError = error != null ? code(error) : null;
            if (list != null) {
                open = list;
                if (selectedOpenId != null && open.stream().noneMatch(q -> q.id().equals(selectedOpenId))) {
                    selectedOpenId = null;
                }
            }
            refreshTabLabels();
        }));
    }

    private void refreshTabLabels() {
        if (this.minecraft != null && this.minecraft.gui.screen() == this && ready()) {
            rebuildWidgets();
        }
    }

    private void propose() {
        if (helpQuestion == null || helpAnswer == null) {
            return;
        }
        String question = helpQuestion.getValue().strip();
        String answer = helpAnswer.getValue().strip();
        if (question.isEmpty() || answer.isEmpty()) {
            showToast(errorText("empty"), false);
            return;
        }
        client.propose(selectedOpenId, question, answer).whenComplete((id, error) -> onGame(() -> {
            if (error != null) {
                showToast(errorText(code(error)), false);
                return;
            }
            helpDraft = "";
            selectedOpenId = null;
            if (helpAnswer != null) {
                helpAnswer.setValue("");
            }
            if (helpQuestion != null) {
                helpQuestion.setValue("");
            }
            showToast(Component.translatable("betterloka.assistant.submitted"), true);
        }));
    }

    private AssistantClient.OpenQuestion selectedOpen() {
        if (selectedOpenId == null) {
            return null;
        }
        return open.stream().filter(q -> q.id().equals(selectedOpenId)).findFirst().orElse(null);
    }

    private void select(AssistantClient.OpenQuestion question) {
        selectedOpenId = question.id();
        if (helpQuestion != null) {
            helpQuestion.setValue(question.question());
            selectedOpenId = question.id();
        }
        if (helpAnswer != null) {
            setFocused(helpAnswer);
        }
    }

    private void showToast(Component text, boolean good) {
        toast = text;
        toastGood = good;
        toastAt = System.currentTimeMillis();
    }

    private static void onGame(Runnable runnable) {
        Minecraft.getInstance().execute(runnable);
    }

    private static String code(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause instanceof AssistantClient.Failure failure ? failure.code() : "offline";
    }

    private static Component errorText(String code) {
        String key = "betterloka.assistant.error." + code;
        return Language.getInstance().has(key) ? Component.translatable(key)
                : Component.translatable("betterloka.assistant.error.generic", code);
    }

    // ---- input ----

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation()) {
            if (input != null && input.isFocused()) {
                ask();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (ready() && click.y() >= scroll.viewportTop() && click.y() < scroll.viewportBottom()) {
            for (Hit hit : List.copyOf(hits)) {
                if (hit.contains(click.x(), click.y())) {
                    hit.action().run();
                    return true;
                }
            }
        }
        if (ready() && scroll.mouseClicked(click.x(), click.y())) {
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (ready() && scroll.mouseScrolled(mouseX, mouseY, verticalAmount)) {
            stickToEnd = tab == Tab.ASK && scroll.atEnd();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        return (ready() && scroll.mouseDragged(click.y())) || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        scroll.mouseReleased();
        if (tab == Tab.ASK) {
            stickToEnd = scroll.atEnd();
        }
        return super.mouseReleased(click);
    }

    // ---- drawing ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(this.font, this.title, this.width / 2, 8, GuiTheme.TEXT);
        hits.clear();
        if (needsNotice()) {
            drawNotice(context);
        } else {
            drawStatus(context);
            context.fill(activeTabX + 2, TOP + TAB_HEIGHT, activeTabX + activeTabWidth - 2, TOP + TAB_HEIGHT + 2, GuiTheme.ACCENT);
            int left = left();
            int top = scroll.viewportTop();
            int bottom = scroll.viewportBottom();
            context.enableScissor(left, top, left + contentWidth(), bottom);
            int height = switch (tab) {
                case ASK -> drawConversation(context, mouseX, mouseY);
                case HELP -> drawOpen(context, mouseX, mouseY);
            };
            context.disableScissor();
            scroll.setContentHeight(height);
            if (stickToEnd && tab == Tab.ASK) {
                scroll.scrollToEnd();
            }
            scroll.render(context, mouseX, mouseY);
            if (tab == Tab.HELP) {
                int y = helpComposerTop();
                context.text(this.font, Component.translatable("betterloka.assistant.question"), left, y, GuiTheme.MUTED);
                context.text(this.font, Component.translatable("betterloka.assistant.your_answer"), left, y + LINE + 18 + 2, GuiTheme.MUTED);
            }
            if (tab == Tab.ASK) {
                int footerY = inputY() + 23;
                for (FormattedCharSequence line : disclaimerLines()) {
                    context.centeredText(this.font, line, this.width / 2, footerY, DISCLAIMER_RED);
                    footerY += 10;
                }
            }
        }
        if (toast != null && System.currentTimeMillis() - toastAt < TOAST_MILLIS) {
            int toastWidth = this.font.width(toast) + 12;
            int x = (this.width - toastWidth) / 2;
            int y = backY() - 18;
            context.fill(x, y, x + toastWidth, y + 14, 0xE0101015);
            GuiTheme.chip(context, x, y, toastWidth, 14, toastGood ? GuiTheme.GOOD : GuiTheme.BAD);
            context.text(this.font, toast, x + 6, y + 3, toastGood ? GuiTheme.GOOD : GuiTheme.BAD);
        }
    }

    /** The overlays' panels go under the widgets, so their buttons are not dimmed by them. */
    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractBackground(context, mouseX, mouseY, delta);
        if (ready()) {
            // A shade under the chat and the lists, so text reads over a bright sky too.
            int left = left();
            context.fill(left - 4, listTop() - 3, left + contentWidth() + 4, listBottom() + 3, 0x70000000);
        }
        if (needsNotice()) {
            int panelWidth = Math.min(340, this.width - 40);
            int x = (this.width - panelWidth) / 2;
            int textHeight = this.font.split(Component.translatable("betterloka.assistant.notice"), panelWidth).size() * LINE;
            int y = this.height / 2 + 30 - 12 - textHeight - 16;
            GuiTheme.panel(context, x - 10, y - 10, panelWidth + 20, textHeight + 16 + 12 + 20 + 20);
        }
    }

    private void drawStatus(GuiGraphicsExtractor context) {
        Component status;
        int color = GuiTheme.MUTED;
        if (connecting) {
            status = Component.translatable("betterloka.assistant.status.connecting");
        } else if (connectError != null) {
            status = errorText(connectError);
            color = GuiTheme.BAD;
        } else if (client.loggedInAs() != null) {
            int left = client.questionsLeft();
            status = client.admin() || left > 999
                    ? Component.translatable("betterloka.assistant.status.admin", client.loggedInAs())
                    : Component.translatable("betterloka.assistant.status.who", client.loggedInAs(), Math.max(0, left));
        } else {
            status = Component.empty();
        }
        int right = left() + contentWidth();
        int x = Math.max(this.width / 2 + this.font.width(this.title) / 2 + 8, right - this.font.width(status));
        context.text(this.font, status, x, 8, color);
    }

    private void drawNotice(GuiGraphicsExtractor context) {
        int panelWidth = Math.min(340, this.width - 40);
        int x = (this.width - panelWidth) / 2;
        List<FormattedCharSequence> lines = this.font.split(Component.translatable("betterloka.assistant.notice"), panelWidth);
        int textHeight = lines.size() * LINE;
        int y = this.height / 2 + 30 - 12 - textHeight - 16;
        context.text(this.font, Component.translatable("betterloka.assistant.notice_title").withStyle(ChatFormatting.BOLD),
                x, y, GuiTheme.ACCENT);
        y += 16;
        for (FormattedCharSequence line : lines) {
            context.text(this.font, line, x, y, GuiTheme.TEXT);
            y += LINE;
        }
    }

    private int drawConversation(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int left = left();
        int width = scroll.contentWidth();
        int top = scroll.contentTop();
        int y = top;
        if (conversation.isEmpty()) {
            return drawWelcome(context, left, width, y, mouseX, mouseY);
        }
        int bubbleMax = width * 82 / 100;
        for (Entry entry : conversation) {
            // The player's question, on the right.
            List<FormattedCharSequence> asked = this.font.split(Component.literal(entry.question), bubbleMax - 2 * PAD);
            int askedWidth = 0;
            for (FormattedCharSequence line : asked) {
                askedWidth = Math.max(askedWidth, this.font.width(line));
            }
            int boxWidth = askedWidth + 2 * PAD;
            int boxHeight = asked.size() * LINE + 2 * PAD - 1;
            int boxX = left + width - boxWidth;
            context.fill(boxX, y, boxX + boxWidth, y + boxHeight, 0x5530607A);
            GuiTheme.chip(context, boxX, y, boxWidth, boxHeight, 0xFF3A7FA8);
            int lineY = y + PAD;
            for (FormattedCharSequence line : asked) {
                context.text(this.font, line, boxX + PAD, lineY, GuiTheme.TEXT);
                lineY += LINE;
            }
            y += boxHeight + 4;

            // What came back, on the left.
            y = drawAnswer(context, entry, left, bubbleMax, y, mouseX, mouseY) + 8;
        }
        return y - top;
    }

    private int drawWelcome(GuiGraphicsExtractor context, int left, int width, int y, int mouseX, int mouseY) {
        int top = y;
        y += 18;
        for (FormattedCharSequence line : this.font.split(Component.translatable("betterloka.assistant.empty"), width - 40)) {
            context.centeredText(this.font, line, left + width / 2, y, GuiTheme.MUTED);
            y += LINE;
        }
        y += 8;
        for (int i = 1; i <= 4; i++) {
            String key = "betterloka.assistant.example." + i;
            if (!Language.getInstance().has(key)) {
                continue;
            }
            String example = Language.getInstance().getOrDefault(key);
            int chipWidth = this.font.width(example) + 12;
            int x = left + (width - chipWidth) / 2;
            boolean hover = mouseX >= x && mouseX < x + chipWidth && mouseY >= y && mouseY < y + 14;
            GuiTheme.chip(context, x, y, chipWidth, 14, hover ? GuiTheme.ACCENT : 0xFF4A4A5A);
            context.text(this.font, example, x + 6, y + 3, hover ? GuiTheme.TEXT : GuiTheme.MUTED);
            hits.add(new Hit(x, y, chipWidth, 14, () -> {
                if (input != null) {
                    input.setValue(example);
                    ask();
                }
            }));
            y += 18;
        }
        return y - top;
    }

    private int drawAnswer(GuiGraphicsExtractor context, Entry entry, int x, int maxWidth, int y, int mouseX, int mouseY) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        int textColor = GuiTheme.TEXT;
        int inner = maxWidth - 2 * PAD;
        if (entry.pending) {
            int dots = (int) (System.currentTimeMillis() / 400 % 4);
            lines.add(Component.translatable("betterloka.assistant.thinking").append(".".repeat(dots)).getVisualOrderText());
            textColor = GuiTheme.MUTED;
        } else if (entry.error != null) {
            lines.addAll(this.font.split(errorText(entry.error), inner));
            textColor = GuiTheme.BAD;
        } else {
            lines.addAll(formatted(entry.answer, inner));
        }
        List<FormattedCharSequence> note = new ArrayList<>();
        if (!entry.pending && entry.error == null && "unknown".equals(entry.status)) {
            note.addAll(this.font.split(Component.translatable("betterloka.assistant.unknown_note"), inner));
        }
        boolean canRate = !entry.pending && entry.error == null && entry.answerId != null;
        int width = 0;
        for (FormattedCharSequence line : lines) {
            width = Math.max(width, this.font.width(line));
        }
        for (FormattedCharSequence line : note) {
            width = Math.max(width, this.font.width(line));
        }
        Component good = Component.translatable("betterloka.assistant.rate_good");
        Component bad = Component.translatable("betterloka.assistant.rate_bad");
        Component thanks = Component.translatable(entry.rated < 0 ? "betterloka.assistant.rated_bad" : "betterloka.assistant.rated_good");
        int rateWidth = entry.rated == 0 ? this.font.width(good) + this.font.width(bad) + 24 : this.font.width(thanks);
        if (canRate) {
            width = Math.max(width, Math.min(inner, rateWidth));
        }
        int boxWidth = Math.min(maxWidth, width + 2 * PAD);
        int boxHeight = 2 * PAD - 1 + lines.size() * LINE + (note.isEmpty() ? 0 : note.size() * LINE + 4) + (canRate ? 16 : 0);
        GuiTheme.panel(context, x, y, boxWidth, boxHeight);
        context.fill(x, y + 1, x + 2, y + boxHeight - 1, entry.error != null ? GuiTheme.BAD : GuiTheme.ACCENT);
        int lineY = y + PAD;
        for (FormattedCharSequence line : lines) {
            context.text(this.font, line, x + PAD + 1, lineY, textColor);
            lineY += LINE;
        }
        if (!note.isEmpty()) {
            lineY += 4;
            for (FormattedCharSequence line : note) {
                context.text(this.font, line, x + PAD + 1, lineY, GuiTheme.LIVE);
                lineY += LINE;
            }
        }
        if (canRate) {
            lineY += 2;
            if (entry.rated == 0) {
                int chipX = x + PAD + 1;
                chipX = rateChip(context, good, chipX, lineY, GuiTheme.GOOD, mouseX, mouseY, () -> rate(entry, true));
                rateChip(context, bad, chipX + 4, lineY, GuiTheme.BAD, mouseX, mouseY, () -> rate(entry, false));
            } else {
                context.text(this.font, thanks, x + PAD + 1, lineY + 2, entry.rated > 0 ? GuiTheme.GOOD : GuiTheme.MUTED);
            }
        }
        return y + boxHeight;
    }

    private int rateChip(GuiGraphicsExtractor context, Component label, int x, int y, int color, int mouseX, int mouseY,
                         Runnable action) {
        int chipWidth = this.font.width(label) + 8;
        boolean hover = mouseX >= x && mouseX < x + chipWidth && mouseY >= y && mouseY < y + 12;
        GuiTheme.chip(context, x, y, chipWidth, 12, hover ? color : 0xFF4A4A5A);
        context.text(this.font, label, x + 4, y + 2, hover ? color : GuiTheme.MUTED);
        hits.add(new Hit(x, y, chipWidth, 12, action));
        return x + chipWidth;
    }

    /** The answer's text with its {@code **bold**} drawn bold, wrapped to the width. */
    private List<FormattedCharSequence> formatted(String text, int width) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (String paragraph : (text == null ? "" : text).split("\n")) {
            if (paragraph.isBlank()) {
                lines.add(FormattedCharSequence.EMPTY);
                continue;
            }
            lines.addAll(this.font.split(bold(paragraph), width));
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1) == FormattedCharSequence.EMPTY) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    static MutableComponent bold(String paragraph) {
        MutableComponent out = Component.empty();
        String[] parts = paragraph.split("\\*\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            // An unmatched ** at the end stays plain.
            boolean isBold = i % 2 == 1 && i < parts.length - 1;
            out.append(Component.literal(parts[i]).withStyle(isBold ? Style.EMPTY.withBold(true) : Style.EMPTY));
        }
        return out;
    }

    private int drawOpen(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int left = left();
        int width = scroll.contentWidth();
        int top = scroll.contentTop();
        int y = top;
        for (FormattedCharSequence line : this.font.split(Component.translatable("betterloka.assistant.help_intro"), width)) {
            context.text(this.font, line, left, y, GuiTheme.MUTED);
            y += LINE;
        }
        y += 6;
        if (open.isEmpty()) {
            Component message = openLoading ? Component.translatable("betterloka.assistant.loading")
                    : openError != null ? errorText(openError) : Component.translatable("betterloka.assistant.help_empty");
            context.text(this.font, message, left, y, openError != null ? GuiTheme.BAD : GuiTheme.MUTED);
            return y + LINE - top;
        }
        for (AssistantClient.OpenQuestion question : open) {
            boolean selected = question.id().equals(selectedOpenId);
            int textWidth = width - 2 * PAD;
            List<FormattedCharSequence> lines = this.font.split(Component.literal(question.question()), textWidth);
            Component meta = Component.translatable("betterloka.assistant.asked_times", question.count())
                    .append(" · ")
                    .append(Component.translatable(question.disputed() ? "betterloka.assistant.kind_disputed"
                            : "betterloka.assistant.kind_unknown"));
            List<FormattedCharSequence> said = question.disputed() && question.botAnswer() != null
                    ? this.font.split(Component.translatable("betterloka.assistant.bot_said", question.botAnswer()), textWidth)
                    : List.of();
            if (said.size() > 2) {
                said = said.subList(0, 2);
            }
            int rowHeight = 2 * PAD + lines.size() * LINE + LINE + said.size() * LINE - 1;
            boolean hover = mouseX >= left && mouseX < left + width && mouseY >= y && mouseY < y + rowHeight
                    && mouseY >= scroll.viewportTop() && mouseY < scroll.viewportBottom();
            GuiTheme.panel(context, left, y, width, rowHeight);
            if (selected || hover) {
                GuiTheme.chip(context, left, y, width, rowHeight, selected ? GuiTheme.ACCENT : 0xFF5A5A6E);
            }
            int lineY = y + PAD;
            for (FormattedCharSequence line : lines) {
                context.text(this.font, line, left + PAD, lineY, GuiTheme.TEXT);
                lineY += LINE;
            }
            context.text(this.font, meta, left + PAD, lineY, question.disputed() ? GuiTheme.BAD : GuiTheme.LIVE);
            lineY += LINE;
            for (FormattedCharSequence line : said) {
                context.text(this.font, line, left + PAD, lineY, GuiTheme.MUTED);
                lineY += LINE;
            }
            hits.add(new Hit(left, y, width, rowHeight, () -> select(question)));
            y += rowHeight + 4;
        }
        return y - top;
    }

    @Override
    public void onClose() {
        if (input != null) {
            draft = input.getValue();
        }
        if (helpAnswer != null) {
            helpDraft = helpAnswer.getValue();
        }
        if (devInstance == this) {
            devInstance = null;
        }
        this.minecraft.gui.setScreen(parent);
    }

    // ---- for the dev diagnostics run ----

    public static void devAccept() {
        if (devInstance != null) {
            BetterLokaClient.config().setAssistantNoticeAccepted(true);
            devInstance.rebuildWidgets();
            devInstance.connect();
        }
    }

    public static void devAsk(String question) {
        if (devInstance != null && devInstance.input != null) {
            devInstance.input.setValue(question);
            devInstance.ask();
        }
    }

    public static void devRate(int index, boolean good) {
        if (devInstance != null && index < conversation.size()) {
            devInstance.rate(conversation.get(index), good);
        }
    }

    public static void devTab(String name) {
        if (devInstance != null) {
            devInstance.switchTab(Tab.valueOf(name));
        }
    }

    public static void devSelectOpen(String containing) {
        if (devInstance != null) {
            devInstance.open.stream().filter(q -> q.question().contains(containing)).findFirst().ifPresent(devInstance::select);
        }
    }

    public static void devWriteAnswer(String answer) {
        if (devInstance != null && devInstance.helpAnswer != null) {
            devInstance.helpAnswer.setValue(answer);
        }
    }

    public static void devPropose() {
        if (devInstance != null) {
            devInstance.propose();
        }
    }
}
