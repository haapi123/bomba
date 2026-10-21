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
 * <p>The top half lists what people have said, newest first, translated into the player's language
 * with the original underneath; the bottom half takes a reply in that language, translates it into
 * the language the server speaks, and offers it for copying. Nothing is ever sent to the server by
 * the mod — the player pastes the result into chat themselves.
 */
public class TranslatorScreen extends Screen {
    private static final int MAX_CONTENT_WIDTH = 400;
    private static final int ROW_HEIGHT = 10;
    private static final int CARD_PADDING = 5;
    private static final int CARD_GAP = 4;

    private static final int CONTROL_ROW_Y = 26;
    private static final int CONTROL_ROW_HEIGHT = 16;
    private static final int CHAT_TOP = CONTROL_ROW_Y + CONTROL_ROW_HEIGHT + 6;

    private static final int COMPOSE_HEIGHT = 18;
    private static final int OUTPUT_HEIGHT = 20;
    private static final int ACTION_WIDTH = 62;

    /** How many messages the list holds; the rest scroll off the bottom. */
    private static final int VISIBLE_HISTORY = 40;

    private final Screen parent;
    private final ScrollPanel scrollPanel = new ScrollPanel();

    private EditBox composeField;
    private Button translateButton;
    private Button copyButton;

    private String translated = "";
    private boolean translating;
    private Component status;
    private int translateGeneration;

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

    private int composeY() {
        return this.height - 86;
    }

    private int outputY() {
        return this.height - 62;
    }

    @Override
    protected void init() {
        int left = contentLeft();
        int width = contentWidth();
        int third = (width - 8) / 3;

        scrollPanel.setViewport(left, CHAT_TOP, width, Math.max(20, composeY() - 6 - CHAT_TOP));

        addRenderableWidget(Button.builder(nativeLanguageLabel(), button -> {
                    config().setNativeLanguage(nextLanguage(config().nativeLanguage()));
                    button.setMessage(nativeLanguageLabel());
                    retranslateChat();
                })
                .bounds(left, CONTROL_ROW_Y, third, CONTROL_ROW_HEIGHT).build());

        addRenderableWidget(Button.builder(chatLanguageLabel(), button -> {
                    config().setChatLanguage(nextLanguage(config().chatLanguage()));
                    button.setMessage(chatLanguageLabel());
                })
                .bounds(left + third + 4, CONTROL_ROW_Y, third, CONTROL_ROW_HEIGHT).build());

        addRenderableWidget(Button.builder(autoLabel(), button -> {
                    boolean on = !config().translateIncoming();
                    config().setTranslateIncoming(on);
                    button.setMessage(autoLabel());
                    if (on) {
                        BetterLokaClient.chatLog().translateAll();
                    }
                })
                .bounds(left + (third + 4) * 2, CONTROL_ROW_Y, width - (third + 4) * 2, CONTROL_ROW_HEIGHT).build());

        String previous = composeField != null ? composeField.getValue() : "";
        composeField = new EditBox(this.font, left, composeY(), width - ACTION_WIDTH - 4,
                COMPOSE_HEIGHT, Component.translatable("betterloka.translator.compose"));
        composeField.setMaxLength(TranslationService.MAX_LENGTH);
        composeField.setHint(Component.translatable("betterloka.translator.compose_hint").withStyle(ChatFormatting.DARK_GRAY));
        composeField.setValue(previous);
        addRenderableWidget(composeField);

        translateButton = Button.builder(Component.translatable("betterloka.translator.translate"), button -> translate())
                .bounds(left + width - ACTION_WIDTH, composeY(), ACTION_WIDTH, COMPOSE_HEIGHT).build();
        addRenderableWidget(translateButton);

        copyButton = Button.builder(Component.translatable("betterloka.translator.copy"), button -> copy())
                .bounds(left + width - ACTION_WIDTH, outputY(), ACTION_WIDTH, OUTPUT_HEIGHT).build();
        addRenderableWidget(copyButton);

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());

        setInitialFocus(composeField);
    }

    private Component nativeLanguageLabel() {
        return Component.translatable("betterloka.translator.my_language",
                TranslationService.languageName(config().nativeLanguage()));
    }

    private Component chatLanguageLabel() {
        return Component.translatable("betterloka.translator.chat_language",
                TranslationService.languageName(config().chatLanguage()));
    }

    private Component autoLabel() {
        boolean on = config().translateIncoming();
        return Component.translatable("betterloka.translator.auto",
                Component.translatable(on ? "betterloka.toggle.on" : "betterloka.toggle.off")
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
    }

    /** Cycles through the offered languages, so the picker needs no dropdown widget. */
    private static String nextLanguage(String current) {
        List<String> codes = new ArrayList<>(TranslationService.LANGUAGES.keySet());
        int index = codes.indexOf(current);
        return codes.get((index + 1) % codes.size());
    }

    private void retranslateChat() {
        if (config().translateIncoming()) {
            BetterLokaClient.chatLog().translateAll();
        }
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

        BetterLokaClient.translations()
                .translate(text, config().nativeLanguage(), config().chatLanguage())
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
                            translated = result;
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

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (input.isConfirmation() && composeField.isFocused()) {
            translate();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return scrollPanel.mouseScrolled(mouseX, mouseY, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
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

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        translateButton.active = !translating && !composeField.getValue().trim().isEmpty();
        copyButton.active = !translated.isEmpty();

        context.centeredText(this.font, this.title, this.width / 2, 10, GuiTheme.TEXT);

        renderChat(context, mouseX, mouseY);
        renderOutput(context, contentLeft(), contentWidth());
    }

    private void renderChat(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int left = contentLeft();
        int width = contentWidth();
        List<ChatLog.Line> lines = BetterLokaClient.chatLog().recent(VISIBLE_HISTORY);

        context.enableScissor(left, scrollPanel.viewportTop(), left + width, scrollPanel.viewportBottom());

        if (lines.isEmpty()) {
            context.text(this.font, Component.translatable("betterloka.translator.no_chat"),
                    left, scrollPanel.viewportTop() + 4, GuiTheme.MUTED);
            context.disableScissor();
            scrollPanel.setContentHeight(0);
            return;
        }

        int cardWidth = scrollPanel.contentWidth();
        int inner = cardWidth - CARD_PADDING * 2;
        int y = scrollPanel.contentTop();
        int start = y;

        for (ChatLog.Line line : lines) {
            boolean showTranslation = line.hasUsefulTranslation();
            int rows = showTranslation ? 2 : 1;
            int height = CARD_PADDING * 2 + ROW_HEIGHT * rows + 1;
            ChatChannel channel = line.channel();
            int channelColor = GuiTheme.channelColor(channel);

            GuiTheme.panel(context, left, y, cardWidth, height);
            // Town and alliance cards get a coloured spine, so the channel reads at a glance even
            // before the badge is looked at.
            if (channel.isTeamChannel()) {
                context.fill(left, y, left + 2, y + height, channelColor);
            }

            int textX = left + CARD_PADDING;
            int textY = y + CARD_PADDING;
            int used = 0;

            Component badge = channelBadge(channel);
            if (badge != null) {
                context.text(this.font, badge, textX, textY, channelColor);
                int badgeWidth = this.font.width(badge) + 4;
                textX += badgeWidth;
                used += badgeWidth;
            }

            // Name and time share the first line; the message body sits under them.
            Component sender = Component.literal(line.sender()).withStyle(ChatFormatting.BOLD);
            context.text(this.font, sender, textX, textY, channelColor);
            String when = TimeFormat.ago(line.receivedAt());
            context.text(this.font, when,
                    left + cardWidth - CARD_PADDING - this.font.width(when), textY, GuiTheme.MUTED);

            // Measured on the styled text: bold is wider than the plain string, and measuring the
            // plain one runs the message into the name.
            int senderWidth = this.font.width(sender) + 6;
            used += senderWidth;
            // Measured, not guessed: "just now" and "12m ago" are different widths, and a fixed
            // reserve runs the message under the timestamp.
            used += this.font.width(when) + 8;
            String headline = showTranslation ? line.translated() : line.message();
            context.text(this.font, elide(headline, Math.max(20, inner - used)),
                    textX + senderWidth, textY, line.failed() ? GuiTheme.BAD : GuiTheme.TEXT);

            if (showTranslation) {
                context.text(this.font, elide(line.message(), inner),
                        left + CARD_PADDING, textY + ROW_HEIGHT, GuiTheme.MUTED);
            }

            y += height + CARD_GAP;
        }

        context.disableScissor();
        scrollPanel.setContentHeight(y - start);
        scrollPanel.render(context, mouseX, mouseY);
    }

    /** Trims to width, marking the cut — a message that simply stops mid-word reads as a bug. */
    private String elide(String text, int width) {
        String trimmed = this.font.plainSubstrByWidth(text, width);
        if (trimmed.length() == text.length()) {
            return text;
        }
        return this.font.plainSubstrByWidth(text, Math.max(0, width - this.font.width("…"))) + "…";
    }

    /** @return the {@code [Town]} / {@code [Alliance]} marker, or {@code null} for public chat. */
    private static Component channelBadge(ChatChannel channel) {
        return switch (channel) {
            case TOWN -> Component.translatable("betterloka.translator.channel.town");
            case ALLIANCE -> Component.translatable("betterloka.translator.channel.alliance");
            case PUBLIC -> null;
        };
    }

    private void renderOutput(GuiGraphicsExtractor context, int left, int width) {
        int panelWidth = width - ACTION_WIDTH - 4;
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
            shown = Component.translatable("betterloka.translator.output_hint").getString();
            color = GuiTheme.MUTED;
        }
        context.text(this.font,
                this.font.plainSubstrByWidth(shown, panelWidth - CARD_PADDING * 2),
                left + CARD_PADDING, outputY() + (OUTPUT_HEIGHT - 8) / 2, color);

        if (status != null) {
            context.text(this.font, status, left, outputY() + OUTPUT_HEIGHT + 3, GuiTheme.MUTED);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
