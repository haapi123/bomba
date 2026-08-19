package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.translate.ChatLog;
import com.betterloka.translate.TranslationService;
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
 * <p>The top half shows recent server chat with each line translated into the player's own language;
 * the bottom half takes a reply in that language, translates it into the language the server speaks,
 * and offers it for copying. Nothing is ever sent to the server by the mod — the player pastes the
 * result into chat themselves.
 */
public class TranslatorScreen extends Screen {
    private static final int MAX_CONTENT_WIDTH = 380;
    private static final int ROW_HEIGHT = 10;
    private static final int CARD_PADDING = 4;
    private static final int CARD_GAP = 4;

    private static final int CONTROL_ROW_Y = 24;
    private static final int CONTROL_ROW_HEIGHT = 16;
    private static final int CHAT_TOP = CONTROL_ROW_Y + CONTROL_ROW_HEIGHT + 6;

    private static final int COMPOSE_HEIGHT = 18;
    private static final int OUTPUT_HEIGHT = 20;
    private static final int ACTION_WIDTH = 60;

    private final Screen parent;

    private TextFieldWidget composeField;
    private ButtonWidget translateButton;
    private ButtonWidget copyButton;

    private String translated = "";
    private boolean translating;
    private Text status;
    private int translateGeneration;

    private int scroll;
    private int contentHeight;

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
        return this.height - 88;
    }

    private int outputY() {
        return this.height - 64;
    }

    private int chatBottom() {
        return composeY() - 6;
    }

    @Override
    protected void init() {
        int left = contentLeft();
        int width = contentWidth();
        int third = (width - 8) / 3;

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
        if (mouseY >= CHAT_TOP && mouseY <= chatBottom()) {
            int max = Math.max(0, contentHeight - (chatBottom() - CHAT_TOP));
            scroll = Math.max(0, Math.min(max, scroll - (int) (verticalAmount * 12)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        translateButton.active = !translating && !composeField.getText().trim().isEmpty();
        copyButton.active = !translated.isEmpty();

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, GuiTheme.TEXT);

        int left = contentLeft();
        int width = contentWidth();

        renderChat(context, left, width);
        renderOutput(context, left, width);
    }

    private void renderChat(DrawContext context, int left, int width) {
        context.enableScissor(left, CHAT_TOP, left + width, chatBottom());

        List<ChatLog.Line> lines = BetterLokaClient.chatLog().recent(40);
        int y = CHAT_TOP - scroll;
        int start = y;

        if (lines.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("betterloka.translator.no_chat"),
                    left, y + 4, GuiTheme.MUTED);
            contentHeight = 20;
            context.disableScissor();
            return;
        }

        int inner = width - CARD_PADDING * 2;
        for (ChatLog.Line line : lines) {
            boolean showTranslation = line.hasUsefulTranslation();
            int rows = showTranslation ? 2 : 1;
            int height = CARD_PADDING * 2 + ROW_HEIGHT * rows;
            GuiTheme.panel(context, left, y, width, height);

            int textX = left + CARD_PADDING;
            int textY = y + CARD_PADDING;

            if (showTranslation) {
                context.drawTextWithShadow(this.textRenderer,
                        this.textRenderer.trimToWidth(line.translated(), inner), textX, textY, GuiTheme.TEXT);
                context.drawTextWithShadow(this.textRenderer,
                        this.textRenderer.trimToWidth(line.original(), inner), textX, textY + ROW_HEIGHT, GuiTheme.MUTED);
            } else {
                int color = line.failed() ? GuiTheme.BAD : GuiTheme.TEXT;
                context.drawTextWithShadow(this.textRenderer,
                        this.textRenderer.trimToWidth(line.original(), inner), textX, textY, color);
            }

            y += height + CARD_GAP;
        }

        contentHeight = y - start;
        context.disableScissor();
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
