package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.translate.ChatChannel;
import com.betterloka.translate.ChatLog;
import com.betterloka.translate.TranslationService;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets players follow and join Loka's chat across a language barrier.
 *
 * <p>One choice at the top: the language chat is translated into. Each message is shown as a card —
 * who said it, what they said labelled with the language they said it in, and under it the
 * translation labelled with the reader's language. Messages already in that language are shown as
 * they are.
 *
 * <p>The section at the bottom is for writing: text in any language, translated into the language
 * picked there — English unless changed — and offered for copying. Nothing is ever sent to the
 * server by the mod; the player pastes the result into chat themselves.
 *
 * <p>Chat is only translated while this screen is open; see {@link ChatLog}.
 */
public class TranslatorScreen extends Screen {
    private static final int MAX_CONTENT_WIDTH = 420;
    private static final int ROW_HEIGHT = 10;
    private static final int HEADER_HEIGHT = 12;
    private static final int CARD_PADDING = 5;
    private static final int CARD_GAP = 4;

    /** The most lines a message or its translation is wrapped to before it is cut. */
    private static final int MAX_LINES = 4;

    private static final int TOP_ROW_Y = 26;
    private static final int TOP_ROW_HEIGHT = 16;
    private static final int CHAT_TOP = TOP_ROW_Y + TOP_ROW_HEIGHT + 6;

    /** The writing section: a header row, the text, and the translation. */
    private static final int SECTION_HEIGHT = 80;
    private static final int COMPOSE_HEIGHT = 18;
    private static final int OUTPUT_HEIGHT = 18;
    private static final int ACTION_WIDTH = 62;
    private static final int COMPOSE_TARGET_WIDTH = 150;

    /** How many messages the list holds; the rest scroll off the bottom. */
    private static final int VISIBLE_HISTORY = 40;

    // The language picker, drawn over everything while open.
    private static final int PICKER_CELL_WIDTH = 104;
    private static final int PICKER_CELL_HEIGHT = 15;
    private static final int PICKER_GAP = 3;
    private static final int PICKER_PADDING = 8;
    private static final int PICKER_TITLE = 14;

    /** Which language the picker is choosing. */
    private enum Picking { CHAT, COMPOSE }

    private final Screen parent;
    private final ScrollPanel scrollPanel = new ScrollPanel();

    private EditBox composeField;
    private Button translateButton;
    private Button copyButton;

    private String translated = "";
    private String translatedFrom;
    private boolean translating;
    private Component status;
    private int translateGeneration;

    private Picking picking;

    public TranslatorScreen(Screen parent) {
        super(Component.translatable("betterloka.module.translator"));
        this.parent = parent;
    }

    private BetterLokaConfig config() {
        return BetterLokaClient.config();
    }

    private int contentWidth() {
        return Math.min(this.width - 40, MAX_CONTENT_WIDTH);
    }

    private int contentLeft() {
        return (this.width - contentWidth()) / 2;
    }

    private int sectionTop() {
        return this.height - 30 - SECTION_HEIGHT;
    }

    private int composeY() {
        return sectionTop() + 24;
    }

    private int outputY() {
        return composeY() + COMPOSE_HEIGHT + 4;
    }

    @Override
    protected void init() {
        int left = contentLeft();
        int width = contentWidth();

        scrollPanel.setViewport(left, CHAT_TOP, width, Math.max(20, sectionTop() - 6 - CHAT_TOP));
        // From here on, and only while this is open, chat is translated as it arrives.
        BetterLokaClient.chatLog().setWatching(true);

        addRenderableWidget(Button.builder(targetLabel(config().nativeLanguage()), button -> picking = Picking.CHAT)
                .bounds(left, TOP_ROW_Y, width, TOP_ROW_HEIGHT).build());

        addRenderableWidget(Button.builder(targetLabel(config().chatLanguage()), button -> picking = Picking.COMPOSE)
                .bounds(left + width - COMPOSE_TARGET_WIDTH - 4, sectionTop() + 4,
                        COMPOSE_TARGET_WIDTH, TOP_ROW_HEIGHT).build());

        String previous = composeField != null ? composeField.getValue() : "";
        composeField = new EditBox(this.font, left + 4, composeY(), width - ACTION_WIDTH - 12,
                COMPOSE_HEIGHT, Component.translatable("betterloka.translator.compose"));
        composeField.setMaxLength(TranslationService.MAX_LENGTH);
        composeField.setHint(Component.translatable("betterloka.translator.compose_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
        composeField.setValue(previous);
        addRenderableWidget(composeField);

        translateButton = Button.builder(Component.translatable("betterloka.translator.translate"), button -> translate())
                .bounds(left + width - ACTION_WIDTH - 4, composeY(), ACTION_WIDTH, COMPOSE_HEIGHT).build();
        addRenderableWidget(translateButton);

        copyButton = Button.builder(Component.translatable("betterloka.translator.copy"), button -> copy())
                .bounds(left + width - ACTION_WIDTH - 4, outputY(), ACTION_WIDTH, OUTPUT_HEIGHT).build();
        addRenderableWidget(copyButton);

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());

        setInitialFocus(composeField);
    }

    private static Component targetLabel(String code) {
        return Component.translatable("betterloka.translator.translate_to",
                Component.literal(TranslationService.languageName(code)).withStyle(ChatFormatting.YELLOW));
    }

    private void pick(String code) {
        if (picking == Picking.CHAT) {
            config().setNativeLanguage(code);
            BetterLokaClient.chatLog().translateAll();
        } else if (picking == Picking.COMPOSE) {
            config().setChatLanguage(code);
            // What is in the box is translated again into the new language straight away.
            translated = "";
            if (!composeField.getValue().isBlank()) {
                translate();
            }
        }
        picking = null;
        rebuildWidgets();
    }

    private void translate() {
        String text = composeField.getValue().trim();
        if (text.isEmpty() || translating) {
            return;
        }
        translating = true;
        status = null;
        translated = "";
        int generation = ++translateGeneration;

        // The source is detected rather than assumed: somebody may type in a language other than
        // their own, and a wrong source makes a worse translation than none.
        BetterLokaClient.translations()
                .translate(text, "auto", config().chatLanguage())
                .whenComplete((result, error) -> {
                    if (this.minecraft == null) {
                        return;
                    }
                    this.minecraft.execute(() -> {
                        if (generation != translateGeneration || this.minecraft.gui.screen() != this) {
                            return;
                        }
                        translating = false;
                        if (error != null) {
                            status = Component.translatable("betterloka.translator.failed").withStyle(ChatFormatting.RED);
                        } else {
                            translated = result.text();
                            translatedFrom = result.source();
                        }
                    });
                });
    }

    private void copy() {
        if (translated.isEmpty()) {
            return;
        }
        this.minecraft.keyboardHandler.setClipboard(translated);
        status = Component.translatable("betterloka.translator.copied").withStyle(ChatFormatting.GREEN);
    }

    // --- input ---

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (picking != null && input.isEscape()) {
            picking = null;
            return true;
        }
        if (input.isConfirmation() && composeField.isFocused()) {
            translate();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (picking != null) {
            return true;
        }
        return scrollPanel.mouseScrolled(mouseX, mouseY, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (picking != null) {
            // The picker takes every click while it is open: a language, or anywhere else to close it.
            if (click.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                String code = pickerCodeAt(click.x(), click.y());
                if (code != null) {
                    pick(code);
                    return true;
                }
            }
            picking = null;
            return true;
        }
        return scrollPanel.mouseClicked(click.x(), click.y()) || super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        return scrollPanel.mouseDragged(click.y()) || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        scrollPanel.mouseReleased();
        return super.mouseReleased(click);
    }

    // --- drawing ---

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // With the picker open nothing underneath reacts to the pointer.
        int underX = picking != null ? -1 : mouseX;
        int underY = picking != null ? -1 : mouseY;
        renderSection(context);
        super.extractRenderState(context, underX, underY, delta);
        translateButton.active = !translating && !composeField.getValue().trim().isEmpty();
        copyButton.active = !translated.isEmpty();

        context.centeredText(this.font, this.title, this.width / 2, 10, GuiTheme.TEXT);
        renderChat(context, underX, underY);
        renderOutput(context);
        if (picking != null) {
            renderPicker(context, mouseX, mouseY);
        }
    }

    private void renderChat(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int left = contentLeft();
        List<ChatLog.Line> lines = BetterLokaClient.chatLog().recent(VISIBLE_HISTORY);

        context.enableScissor(left, scrollPanel.viewportTop(), left + contentWidth(), scrollPanel.viewportBottom());
        if (lines.isEmpty()) {
            context.text(this.font, Component.translatable("betterloka.translator.no_chat"),
                    left, scrollPanel.viewportTop() + 4, GuiTheme.MUTED);
            context.disableScissor();
            scrollPanel.setContentHeight(0);
            return;
        }

        String target = config().nativeLanguage();
        int cardWidth = scrollPanel.contentWidth();
        int y = scrollPanel.contentTop();
        int start = y;
        for (ChatLog.Line line : lines) {
            y += renderCard(context, line, left, y, cardWidth, target) + CARD_GAP;
        }
        context.disableScissor();
        scrollPanel.setContentHeight(y - start);
        scrollPanel.render(context, mouseX, mouseY);
    }

    /** One message as a card. @return its height. */
    private int renderCard(GuiGraphicsExtractor context, ChatLog.Line line, int left, int y, int width,
                           String target) {
        int inner = width - CARD_PADDING * 2;
        ChatChannel channel = line.channel();
        int nickColor = channel.isTeamChannel() ? GuiTheme.channelColor(channel) : GuiTheme.LIVE;

        // What goes on the card, worked out first so the card can be sized to it.
        String sourceName = line.source() == null ? null : TranslationService.languageName(line.source());
        Component originalLabel = sourceName == null ? null : Component.literal(sourceName + ":");
        List<String> original = wrap(line.message(), inner - labelWidth(originalLabel));

        Component translationLabel = null;
        List<String> translation = List.of();
        int translationColor = GuiTheme.ACCENT;
        if (line.hasTranslation(target)) {
            translationLabel = Component.literal(TranslationService.languageName(target) + ":");
            translation = wrap(line.translated(), inner - labelWidth(translationLabel));
        } else if (line.failed()) {
            translationLabel = Component.literal(TranslationService.languageName(target) + ":");
            translation = List.of(Component.translatable("betterloka.translator.failed").getString());
            translationColor = GuiTheme.BAD;
        } else if (!line.needsNoTranslation(target)) {
            translationLabel = Component.literal(TranslationService.languageName(target) + ":");
            translation = List.of(Component.translatable("betterloka.translator.translating").getString());
            translationColor = GuiTheme.MUTED;
        }

        int height = CARD_PADDING * 2 + HEADER_HEIGHT + 2 + ROW_HEIGHT * (original.size() + translation.size())
                + (translation.isEmpty() ? 0 : 2);
        GuiTheme.panel(context, left, y, width, height);
        context.fill(left, y, left + 2, y + height, nickColor);

        // Who said it: the channel, the name in a pill of its colour, and when.
        int x = left + CARD_PADDING + 2;
        int top = y + CARD_PADDING;
        Component badge = channelBadge(channel);
        if (badge != null) {
            context.text(this.font, badge, x, top + 2, nickColor);
            x += this.font.width(badge) + 4;
        }
        Component nick = Component.literal(line.sender()).withStyle(ChatFormatting.BOLD);
        int nickWidth = this.font.width(nick);
        GuiTheme.chip(context, x, top, nickWidth + 8, HEADER_HEIGHT, nickColor);
        context.text(this.font, nick, x + 4, top + 2, nickColor);
        String when = TimeFormat.ago(line.receivedAt());
        context.text(this.font, when, left + width - CARD_PADDING - this.font.width(when), top + 2, GuiTheme.MUTED);

        // What they said, labelled with the language they said it in.
        int row = top + HEADER_HEIGHT + 2;
        row = renderLabelled(context, originalLabel, GuiTheme.MUTED, original, GuiTheme.TEXT, left + CARD_PADDING + 2, row);
        // And what it means, labelled with the reader's language.
        if (!translation.isEmpty()) {
            row += 2;
            renderLabelled(context, translationLabel, 0xFF7FA7C9, translation, translationColor,
                    left + CARD_PADDING + 2, row);
        }
        return height;
    }

    private int labelWidth(Component label) {
        return label == null ? 0 : this.font.width(label) + 4;
    }

    /** A label, then the text beside it, continuation lines lined up under the text. @return the next row. */
    private int renderLabelled(GuiGraphicsExtractor context, Component label, int labelColor, List<String> text,
                               int textColor, int x, int row) {
        int textX = x;
        if (label != null) {
            context.text(this.font, label, x, row, labelColor);
            textX += labelWidth(label);
        }
        for (String part : text) {
            context.text(this.font, part, textX, row, textColor);
            row += ROW_HEIGHT;
        }
        return row;
    }

    /** Text broken between words into lines no wider than {@code width}, at most {@link #MAX_LINES}. */
    private List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (this.font.width(candidate) <= width) {
                line = new StringBuilder(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
            // A word longer than the line — a link, a spammed letter — is cut where it has to be.
            String rest = word;
            while (this.font.width(rest) > width) {
                String piece = this.font.plainSubstrByWidth(rest, width);
                if (piece.isEmpty()) {
                    break;
                }
                lines.add(piece);
                rest = rest.substring(piece.length());
            }
            line = new StringBuilder(rest);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        if (lines.size() > MAX_LINES) {
            List<String> cut = new ArrayList<>(lines.subList(0, MAX_LINES));
            String last = cut.get(MAX_LINES - 1);
            cut.set(MAX_LINES - 1, this.font.plainSubstrByWidth(last, width - this.font.width("…")) + "…");
            return cut;
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    /** @return the {@code [Town]} / {@code [Alliance]} marker, or {@code null} for public chat. */
    private static Component channelBadge(ChatChannel channel) {
        return switch (channel) {
            case TOWN -> Component.translatable("betterloka.translator.channel.town");
            case ALLIANCE -> Component.translatable("betterloka.translator.channel.alliance");
            case PUBLIC -> null;
        };
    }

    /** The writing section's own panel and heading, under its widgets. */
    private void renderSection(GuiGraphicsExtractor context) {
        int left = contentLeft();
        int width = contentWidth();
        GuiTheme.panel(context, left, sectionTop(), width, SECTION_HEIGHT);
        context.fill(left, sectionTop(), left + width, sectionTop() + 1, GuiTheme.ACCENT);
        context.text(this.font, Component.translatable("betterloka.translator.your_text"),
                left + 6, sectionTop() + 8, GuiTheme.ACCENT);
    }

    private void renderOutput(GuiGraphicsExtractor context) {
        int left = contentLeft() + 4;
        int panelWidth = contentWidth() - ACTION_WIDTH - 12;
        GuiTheme.panel(context, left, outputY(), panelWidth, OUTPUT_HEIGHT);

        String shown;
        int color;
        if (translating) {
            shown = Component.translatable("betterloka.translator.translating").getString();
            color = GuiTheme.MUTED;
        } else if (!translated.isEmpty()) {
            shown = translated;
            color = GuiTheme.ACCENT;
        } else {
            shown = Component.translatable("betterloka.translator.output_hint",
                    TranslationService.languageName(config().chatLanguage())).getString();
            color = GuiTheme.MUTED;
        }
        context.text(this.font,
                this.font.plainSubstrByWidth(shown, panelWidth - CARD_PADDING * 2),
                left + CARD_PADDING, outputY() + (OUTPUT_HEIGHT - 8) / 2, color);

        // Under the box: what was copied or went wrong, or which way the last translation went.
        Component under = status;
        if (under == null && !translated.isEmpty() && translatedFrom != null) {
            under = Component.literal(TranslationService.languageName(translatedFrom) + " → "
                    + TranslationService.languageName(config().chatLanguage()));
        }
        if (under != null) {
            context.text(this.font, under, left + 2, outputY() + OUTPUT_HEIGHT + 3, GuiTheme.MUTED);
        }
    }

    // --- the language picker ---

    private int pickerColumns() {
        return Math.max(1, Math.min(4, (this.width - 40) / (PICKER_CELL_WIDTH + PICKER_GAP)));
    }

    /** The picker's corner and size, as {x, y, width, height}. */
    private int[] pickerBox() {
        int columns = pickerColumns();
        int rows = (TranslationService.LANGUAGES.size() + columns - 1) / columns;
        int width = PICKER_PADDING * 2 + columns * PICKER_CELL_WIDTH + (columns - 1) * PICKER_GAP;
        int height = PICKER_PADDING * 2 + PICKER_TITLE + rows * PICKER_CELL_HEIGHT + (rows - 1) * PICKER_GAP;
        return new int[] {(this.width - width) / 2, Math.max(4, (this.height - height) / 2), width, height};
    }

    private int[] pickerCell(int index) {
        int[] box = pickerBox();
        int columns = pickerColumns();
        int x = box[0] + PICKER_PADDING + (index % columns) * (PICKER_CELL_WIDTH + PICKER_GAP);
        int y = box[1] + PICKER_PADDING + PICKER_TITLE + (index / columns) * (PICKER_CELL_HEIGHT + PICKER_GAP);
        return new int[] {x, y};
    }

    private String pickerCodeAt(double mouseX, double mouseY) {
        List<String> codes = new ArrayList<>(TranslationService.LANGUAGES.keySet());
        for (int i = 0; i < codes.size(); i++) {
            int[] cell = pickerCell(i);
            if (mouseX >= cell[0] && mouseX < cell[0] + PICKER_CELL_WIDTH
                    && mouseY >= cell[1] && mouseY < cell[1] + PICKER_CELL_HEIGHT) {
                return codes.get(i);
            }
        }
        return null;
    }

    private void renderPicker(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        // A layer of its own: within one layer text is drawn after every fill, so without this the
        // chat underneath would show through the picker.
        context.nextStratum();
        context.fill(0, 0, this.width, this.height, 0x99000000);
        int[] box = pickerBox();
        MapStyle.card(context, box[0], box[1], box[2], box[3], GuiTheme.ACCENT);
        Component title = Component.translatable(picking == Picking.CHAT
                ? "betterloka.translator.pick_chat" : "betterloka.translator.pick_compose");
        context.centeredText(this.font, title, box[0] + box[2] / 2, box[1] + PICKER_PADDING, GuiTheme.TEXT);

        String current = picking == Picking.CHAT ? config().nativeLanguage() : config().chatLanguage();
        List<String> codes = new ArrayList<>(TranslationService.LANGUAGES.keySet());
        for (int i = 0; i < codes.size(); i++) {
            int[] cell = pickerCell(i);
            String code = codes.get(i);
            boolean selected = code.equals(current);
            boolean hovered = mouseX >= cell[0] && mouseX < cell[0] + PICKER_CELL_WIDTH
                    && mouseY >= cell[1] && mouseY < cell[1] + PICKER_CELL_HEIGHT;
            int fill = selected ? 0xE0203A4A : hovered ? MapStyle.BUTTON_FILL_HOVER : MapStyle.BUTTON_FILL;
            int border = selected ? GuiTheme.ACCENT : hovered ? MapStyle.BUTTON_BORDER_HOVER : MapStyle.BUTTON_BORDER;
            context.fill(cell[0], cell[1], cell[0] + PICKER_CELL_WIDTH, cell[1] + PICKER_CELL_HEIGHT, fill);
            context.fill(cell[0], cell[1], cell[0] + PICKER_CELL_WIDTH, cell[1] + 1, border);
            context.fill(cell[0], cell[1] + PICKER_CELL_HEIGHT - 1, cell[0] + PICKER_CELL_WIDTH,
                    cell[1] + PICKER_CELL_HEIGHT, border);
            context.fill(cell[0], cell[1], cell[0] + 1, cell[1] + PICKER_CELL_HEIGHT, border);
            context.fill(cell[0] + PICKER_CELL_WIDTH - 1, cell[1], cell[0] + PICKER_CELL_WIDTH,
                    cell[1] + PICKER_CELL_HEIGHT, border);
            String name = TranslationService.languageName(code);
            context.centeredText(this.font, Component.literal(name), cell[0] + PICKER_CELL_WIDTH / 2,
                    cell[1] + (PICKER_CELL_HEIGHT - 8) / 2, selected ? 0xFFFFE066 : GuiTheme.TEXT);
        }
    }

    // --- development ---

    /** Development only: the middle of a language's cell in the open picker, or {@code null}. */
    public int[] devPickerCell(String code) {
        if (picking == null) {
            return null;
        }
        int index = new ArrayList<>(TranslationService.LANGUAGES.keySet()).indexOf(code);
        if (index < 0) {
            return null;
        }
        int[] cell = pickerCell(index);
        return new int[] {cell[0] + PICKER_CELL_WIDTH / 2, cell[1] + PICKER_CELL_HEIGHT / 2};
    }

    /** Development only: the translation in the output box. */
    public String devTranslated() {
        return translated;
    }

    @Override
    public void removed() {
        // Closed by any route, chat stops being sent off for translation.
        BetterLokaClient.chatLog().setWatching(false);
        super.removed();
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
