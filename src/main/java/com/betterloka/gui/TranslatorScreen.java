package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.translate.ChatLog;
import com.betterloka.translate.TranslationService;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

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

    private TextFieldWidget composeField;
    private ButtonWidget translateButton;
    private ButtonWidget copyButton;

    private String translated = "";
    private boolean translating;
    private Text status;
    private int translateGeneration;

    public TranslatorScreen(Screen parent) {
        super(Text.translatable("betterloka.module.translator"));
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

        addDrawableChild(ButtonWidget.builder(nativeLanguageLabel(), button -> {
                    config().setNativeLanguage(nextLanguage(config().nativeLanguage()));
                    button.setMessage(nativeLanguageLabel());
                    retranslateChat();
                })
                .dimensions(left, CONTROL_ROW_Y, third, CONTROL_ROW_HEIGHT).build());

        addDrawableChild(ButtonWidget.builder(chatLanguageLabel(), button -> {
                    config().setChatLanguage(nextLanguage(config().chatLanguage()));
                    button.setMessage(chatLanguageLabel());
                })
                .dimensions(left + third + 4, CONTROL_ROW_Y, third, CONTROL_ROW_HEIGHT).build());

        addDrawableChild(ButtonWidget.builder(autoLabel(), button -> {
                    boolean on = !config().translateIncoming();
                    config().setTranslateIncoming(on);
                    button.setMessage(autoLabel());
                    if (on) {
                        BetterLokaClient.chatLog().translateAll();
                    }
                })
                .dimensions(left + (third + 4) * 2, CONTROL_ROW_Y, width - (third + 4) * 2, CONTROL_ROW_HEIGHT).build());

        String previous = composeField != null ? composeField.getText() : "";
        composeField = new TextFieldWidget(this.textRenderer, left, composeY(), width - ACTION_WIDTH - 4,
                COMPOSE_HEIGHT, Text.translatable("betterloka.translator.compose"));
        composeField.setMaxLength(TranslationService.MAX_LENGTH);
        composeField.setPlaceholder(Text.translatable("betterloka.translator.compose_hint").formatted(Formatting.DARK_GRAY));
        composeField.setText(previous);
        addDrawableChild(composeField);

        translateButton = ButtonWidget.builder(Text.translatable("betterloka.translator.translate"), button -> translate())
                .dimensions(left + width - ACTION_WIDTH, composeY(), ACTION_WIDTH, COMPOSE_HEIGHT).build();
        addDrawableChild(translateButton);

        copyButton = ButtonWidget.builder(Text.translatable("betterloka.translator.copy"), button -> copy())
                .dimensions(left + width - ACTION_WIDTH, outputY(), ACTION_WIDTH, OUTPUT_HEIGHT).build();
        addDrawableChild(copyButton);

        addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, button -> close())
                .dimensions(this.width / 2 - 100, this.height - 26, 200, 20).build());

        setInitialFocus(composeField);
    }

    private Text nativeLanguageLabel() {
        return Text.translatable("betterloka.translator.my_language",
                TranslationService.languageName(config().nativeLanguage()));
    }

    private Text chatLanguageLabel() {
        return Text.translatable("betterloka.translator.chat_language",
                TranslationService.languageName(config().chatLanguage()));
    }

    private Text autoLabel() {
        boolean on = config().translateIncoming();
        return Text.translatable("betterloka.translator.auto",
                Text.translatable(on ? "betterloka.toggle.on" : "betterloka.toggle.off")
                        .formatted(on ? Formatting.GREEN : Formatting.GRAY));
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
        String text = composeField.getText().trim();
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
                    if (this.client == null) {
                        return;
                    }
                    this.client.execute(() -> {
                        if (generation != translateGeneration || this.client.currentScreen != this) {
                            return;
                        }
                        translating = false;
                        if (error != null) {
                            status = Text.translatable("betterloka.translator.failed").formatted(Formatting.RED);
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
        this.client.keyboard.setClipboard(translated);
        status = Text.translatable("betterloka.translator.copied").formatted(Formatting.GREEN);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if ((input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) && composeField.isFocused()) {
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
    public boolean mouseClicked(Click click, boolean doubled) {
        return scrollPanel.mouseClicked(click.x(), click.y()) || super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        return scrollPanel.mouseDragged(click.y()) || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        scrollPanel.mouseReleased();
        return super.mouseReleased(click);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        translateButton.active = !translating && !composeField.getText().trim().isEmpty();
        copyButton.active = !translated.isEmpty();

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, GuiTheme.TEXT);

        renderChat(context, mouseX, mouseY);
        renderOutput(context, contentLeft(), contentWidth());
    }

    private void renderChat(DrawContext context, int mouseX, int mouseY) {
        int left = contentLeft();
        int width = contentWidth();
        List<ChatLog.Line> lines = BetterLokaClient.chatLog().recent(VISIBLE_HISTORY);

        context.enableScissor(left, scrollPanel.viewportTop(), left + width, scrollPanel.viewportBottom());

        if (lines.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("betterloka.translator.no_chat"),
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
            GuiTheme.panel(context, left, y, cardWidth, height);

            int textX = left + CARD_PADDING;
            int textY = y + CARD_PADDING;

            // Name and time share the first line; the message body sits under them.
            Text sender = Text.literal(line.sender()).formatted(Formatting.BOLD);
            context.drawTextWithShadow(this.textRenderer, sender, textX, textY, GuiTheme.ACCENT);
            String when = TimeFormat.ago(line.receivedAt());
            context.drawTextWithShadow(this.textRenderer, when,
                    left + cardWidth - CARD_PADDING - this.textRenderer.getWidth(when), textY, GuiTheme.MUTED);

            // Measured on the styled text: bold is wider than the plain string, and measuring the
            // plain one runs the message into the name.
            int senderWidth = this.textRenderer.getWidth(sender) + 6;
            String headline = showTranslation ? line.translated() : line.message();
            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(headline, inner - senderWidth - 30),
                    textX + senderWidth, textY, line.failed() ? GuiTheme.BAD : GuiTheme.TEXT);

            if (showTranslation) {
                context.drawTextWithShadow(this.textRenderer,
                        this.textRenderer.trimToWidth(line.message(), inner), textX, textY + ROW_HEIGHT, GuiTheme.MUTED);
            }

            y += height + CARD_GAP;
        }

        context.disableScissor();
        scrollPanel.setContentHeight(y - start);
        scrollPanel.render(context, mouseX, mouseY);
    }

    private void renderOutput(DrawContext context, int left, int width) {
        int panelWidth = width - ACTION_WIDTH - 4;
        GuiTheme.panel(context, left, outputY(), panelWidth, OUTPUT_HEIGHT);

        String shown;
        int color;
        if (translating) {
            shown = Text.translatable("betterloka.translator.translating").getString();
            color = GuiTheme.MUTED;
        } else if (!translated.isEmpty()) {
            shown = translated;
            color = GuiTheme.ACCENT;
        } else {
            shown = Text.translatable("betterloka.translator.output_hint").getString();
            color = GuiTheme.MUTED;
        }
        context.drawTextWithShadow(this.textRenderer,
                this.textRenderer.trimToWidth(shown, panelWidth - CARD_PADDING * 2),
                left + CARD_PADDING, outputY() + (OUTPUT_HEIGHT - 8) / 2, color);

        if (status != null) {
            context.drawTextWithShadow(this.textRenderer, status, left, outputY() + OUTPUT_HEIGHT + 3, GuiTheme.MUTED);
        }
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
