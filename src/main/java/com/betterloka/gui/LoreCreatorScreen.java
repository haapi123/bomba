package com.betterloka.gui;

import com.betterloka.lore.LoreGenerator;
import com.betterloka.lore.LoreScheme;
import com.betterloka.lore.MiniMessage;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lore Creator: describe an item, pick its colours, and the mod writes its lore in MiniMessage — the
 * markup Loka's lore editor takes — and shows it as the game will, in a tooltip, while it is edited.
 *
 * <p>The left side is what the lore is to be: what it is about, a name, a colour scheme, and how it
 * is laid out. The right is the result: the tooltip, and the MiniMessage itself, which can be edited
 * by hand and is copied with one press for pasting into Loka's editor. Nothing is sent anywhere.
 */
public class LoreCreatorScreen extends Screen {
    private static final int MAX_CONTENT_WIDTH = 500;
    private static final int TOP = 22;
    private static final int ROW = 18;
    private static final int GAP = 3;
    private static final int LABEL = 10;
    private static final int CHIP_HEIGHT = 16;
    private static final int SCHEMES_PER_ROW = 6;
    /** The game's own colour for lore: what a line with no colour of its own is drawn in. */
    private static final int LORE_PURPLE = 0xAA00AA;
    private static final String LOKA_EDITOR = "https://lokamc.com/lore";

    /** What the preview holds up: one item per kind of thing a lore can be for. */
    private static final LoreGenerator.Kind[] KINDS = LoreGenerator.Kind.values();

    // Kept between openings, so a lore half written is still there when the screen comes back.
    private static String savedDescription = "";
    private static String savedName = "";
    private static String savedCode;
    private static LoreScheme scheme = LoreScheme.FIRE;
    private static int kindIndex;
    private static boolean polish = true;
    private static int sentences = 3;
    private static boolean ownText;
    private static boolean italic;
    private static boolean titleLine = true;
    private static boolean dividers = true;
    private static LoreGenerator.Rarity rarity = LoreGenerator.Rarity.LEGENDARY;
    private static long seed = 1;

    private final Screen parent;
    private MultiLineEditBox description;
    private EditBox name;
    private MultiLineEditBox code;
    private final List<Button> optionButtons = new ArrayList<>();

    /** The code as last parsed, and what it parsed to; the preview reads this every frame. */
    private String parsedCode;
    private List<Component> previewLines = List.of();
    private long copiedAt;

    public LoreCreatorScreen(Screen parent) {
        super(Component.translatable("betterloka.module.lore_creator"));
        this.parent = parent;
    }

    private int contentWidth() {
        return Math.min(this.width - 24, MAX_CONTENT_WIDTH);
    }

    private int contentLeft() {
        return (this.width - contentWidth()) / 2;
    }

    private int leftWidth() {
        return contentWidth() * 46 / 100;
    }

    private int rightX() {
        return contentLeft() + leftWidth() + 12;
    }

    private int rightWidth() {
        return contentLeft() + contentWidth() - rightX();
    }

    private int bottom() {
        return this.height - 30;
    }

    // Where the left column's parts are, worked out once in init.
    private int descriptionLabelY;
    private int nameLabelY;
    private int schemesLabelY;
    private int schemesY;
    private int previewLabelY;
    private int previewY;
    private int codeLabelY;

    @Override
    protected void init() {
        if (description != null) {
            savedDescription = description.getValue();
            savedName = name.getValue();
            savedCode = code.getValue();
        }
        int left = contentLeft();
        int width = leftWidth();
        int y = TOP;

        descriptionLabelY = y;
        y += LABEL;
        description = MultiLineEditBox.builder()
                .setX(left).setY(y)
                .setPlaceholder(Component.translatable("betterloka.lore.about_hint").withStyle(ChatFormatting.DARK_GRAY))
                .build(this.font, width, 44, Component.translatable("betterloka.lore.about"));
        description.setCharacterLimit(400);
        description.setValue(savedDescription);
        addRenderableWidget(description);
        y += 44 + GAP + 1;

        nameLabelY = y;
        y += LABEL;
        name = new EditBox(this.font, left, y, width, ROW, Component.translatable("betterloka.lore.name"));
        name.setMaxLength(40);
        name.setHint(Component.translatable("betterloka.lore.name_hint").withStyle(ChatFormatting.DARK_GRAY));
        name.setValue(savedName);
        addRenderableWidget(name);
        y += ROW + GAP + 1;

        schemesLabelY = y;
        y += LABEL;
        schemesY = y;
        int schemeRows = (LoreScheme.values().length + SCHEMES_PER_ROW - 1) / SCHEMES_PER_ROW;
        y += schemeRows * (CHIP_HEIGHT + GAP) + 2;

        // The layout switches, two to a row.
        optionButtons.clear();
        int half = (width - 4) / 2;
        y = optionRow(y, left, half, width,
                // The item's own name alone: "Item: Netherite Sword" does not fit half a row in Polish.
                () -> new ItemStack(itemOf(KINDS[kindIndex])).getHoverName().copy().withStyle(ChatFormatting.YELLOW),
                () -> kindIndex = (kindIndex + 1) % KINDS.length,
                () -> option("betterloka.lore.language", Component.literal(polish ? "Polski" : "English")),
                () -> polish = !polish);
        y = optionRow(y, left, half, width,
                () -> option("betterloka.lore.length", Component.literal(String.valueOf(sentences))),
                () -> sentences = sentences % 4 + 1,
                () -> option("betterloka.lore.rarity", rarity == LoreGenerator.Rarity.NONE
                        ? Component.translatable("betterloka.lore.none") : Component.literal(rarity.label())),
                () -> rarity = LoreGenerator.Rarity.values()[(rarity.ordinal() + 1) % LoreGenerator.Rarity.values().length]);
        y = optionRow(y, left, half, width,
                () -> option("betterloka.lore.text", Component.translatable(ownText
                        ? "betterloka.lore.text_own" : "betterloka.lore.text_written")),
                () -> ownText = !ownText,
                () -> onOff("betterloka.lore.italic", italic),
                () -> italic = !italic);
        y = optionRow(y, left, half, width,
                () -> onOff("betterloka.lore.title_line", titleLine),
                () -> titleLine = !titleLine,
                () -> onOff("betterloka.lore.dividers", dividers),
                () -> dividers = !dividers);
        y += 2;
        addRenderableWidget(Button.builder(Component.translatable("betterloka.lore.generate")
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), button -> {
                    seed = ThreadLocalRandom.current().nextLong();
                    generate();
                })
                .bounds(left, y, width, ROW + 2).build());

        // The right side: the preview, the code, and what to do with it.
        int rightX = rightX();
        int rightWidth = rightWidth();
        previewLabelY = TOP;
        previewY = TOP + LABEL;
        int codeHeight = Math.max(50, (bottom() - ROW - GAP - LABEL) - (previewY + previewHeight()) - 6);
        codeLabelY = bottom() - ROW - GAP - codeHeight - LABEL;
        code = MultiLineEditBox.builder()
                .setX(rightX).setY(codeLabelY + LABEL)
                .setPlaceholder(Component.translatable("betterloka.lore.code_hint").withStyle(ChatFormatting.DARK_GRAY))
                .build(this.font, rightWidth, codeHeight, Component.translatable("betterloka.lore.code"));
        code.setCharacterLimit(4000);
        addRenderableWidget(code);
        int buttonWidth = (rightWidth - 4) / 2;
        addRenderableWidget(Button.builder(Component.translatable("betterloka.lore.copy"), button -> copy())
                .bounds(rightX, bottom() - ROW, buttonWidth, ROW).build());
        addRenderableWidget(Button.builder(Component.translatable("betterloka.lore.open_editor"),
                        button -> ConfirmLinkScreen.confirmLinkNow(this, URI.create(LOKA_EDITOR)))
                .bounds(rightX + buttonWidth + 4, bottom() - ROW, rightWidth - buttonWidth - 4, ROW).build());

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 25, 200, 20).build());

        if (savedCode == null) {
            generate();
        } else {
            code.setValue(savedCode);
        }
        // What is typed on the left is written into the lore as it is typed; the code box is for
        // the finishing touches after.
        description.setValueListener(value -> generate());
        name.setResponder(value -> generate());
        setInitialFocus(description);
    }

    /** How tall the preview may be: a little under half of the right side. */
    private int previewHeight() {
        return Math.max(70, (bottom() - TOP) * 50 / 100);
    }

    private int optionRow(int y, int left, int half, int width, java.util.function.Supplier<Component> firstLabel,
                          Runnable firstAction, java.util.function.Supplier<Component> secondLabel,
                          Runnable secondAction) {
        Button first = Button.builder(firstLabel.get(), button -> {
            firstAction.run();
            button.setMessage(firstLabel.get());
            generate();
        }).bounds(left, y, half, ROW).build();
        Button second = Button.builder(secondLabel.get(), button -> {
            secondAction.run();
            button.setMessage(secondLabel.get());
            generate();
        }).bounds(left + half + 4, y, width - half - 4, ROW).build();
        optionButtons.add(addRenderableWidget(first));
        optionButtons.add(addRenderableWidget(second));
        return y + ROW + GAP;
    }

    private static Component option(String key, Component value) {
        return Component.translatable(key).append(": ").append(value.copy().withStyle(ChatFormatting.YELLOW));
    }

    private static Component onOff(String key, boolean on) {
        return Component.translatable(key).append(": ").append(Component.translatable(
                on ? "betterloka.toggle.on" : "betterloka.toggle.off").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
    }

    private static Item itemOf(LoreGenerator.Kind kind) {
        return switch (kind) {
            case SWORD -> Items.NETHERITE_SWORD;
            case AXE -> Items.NETHERITE_AXE;
            case BOW -> Items.BOW;
            case PICKAXE -> Items.DIAMOND_PICKAXE;
            case ARMOR -> Items.DIAMOND_CHESTPLATE;
            case SHIELD -> Items.SHIELD;
            case TRIDENT -> Items.TRIDENT;
            case MACE -> Items.MACE;
            case STAFF -> Items.BLAZE_ROD;
            case RELIC -> Items.TOTEM_OF_UNDYING;
            case BOOK -> Items.WRITTEN_BOOK;
        };
    }

    /** Writes the lore again from what is on the left, into the code box. */
    private void generate() {
        if (code == null) {
            return;
        }
        String text = LoreGenerator.write(new LoreGenerator.Request(description.getValue(), name.getValue(),
                KINDS[kindIndex], scheme, polish, sentences, ownText, titleLine, dividers, italic, rarity, seed));
        code.setValue(text);
    }

    private void copy() {
        if (this.minecraft != null) {
            this.minecraft.keyboardHandler.setClipboard(code.getValue());
            copiedAt = System.currentTimeMillis();
        }
    }

    // --- the colour schemes ---

    private int schemeWidth() {
        return (leftWidth() - GAP * (SCHEMES_PER_ROW - 1)) / SCHEMES_PER_ROW;
    }

    private int[] schemeBounds(int index) {
        int w = schemeWidth();
        int x = contentLeft() + (index % SCHEMES_PER_ROW) * (w + GAP);
        int y = schemesY + (index / SCHEMES_PER_ROW) * (CHIP_HEIGHT + GAP);
        return new int[]{x, y, w, CHIP_HEIGHT};
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            LoreScheme[] schemes = LoreScheme.values();
            for (int i = 0; i < schemes.length; i++) {
                int[] b = schemeBounds(i);
                if (click.x() >= b[0] && click.x() < b[0] + b[2] && click.y() >= b[1] && click.y() < b[1] + b[3]) {
                    scheme = schemes[i];
                    generate();
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private void drawSchemes(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        LoreScheme[] schemes = LoreScheme.values();
        LoreScheme hovered = null;
        for (int i = 0; i < schemes.length; i++) {
            int[] b = schemeBounds(i);
            LoreScheme candidate = schemes[i];
            // The title's gradient across the chip, a column at a time.
            for (int column = 0; column < b[2]; column++) {
                double t = b[2] <= 1 ? 0 : column / (double) (b[2] - 1);
                int rgb = candidate.rainbow()
                        ? java.awt.Color.HSBtoRGB((float) t, 0.85f, 1f) & 0xFFFFFF
                        : lerp(candidate.titleFrom(), candidate.titleTo(), t);
                context.fill(b[0] + column, b[1], b[0] + column + 1, b[1] + b[3], 0xFF000000 | rgb);
            }
            boolean over = mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3];
            int frame = candidate == scheme ? 0xFFFFFFFF : over ? 0xFFB0B0C0 : 0xFF202028;
            FilterMenu.outline(context, b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, frame);
            if (candidate == scheme) {
                FilterMenu.outline(context, b[0] - 2, b[1] - 2, b[2] + 4, b[3] + 4, 0xFF000000);
            }
            if (over) {
                hovered = candidate;
            }
        }
        if (hovered != null) {
            context.setTooltipForNextFrame(this.font, Component.translatable(hovered.nameKey()), mouseX, mouseY);
        }
    }

    private static int lerp(int from, int to, double t) {
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }

    // --- the preview ---

    /** The lore as the game will draw it: its own purple italics underneath whatever the code sets. */
    private List<Component> preview() {
        String current = code.getValue();
        if (!current.equals(parsedCode)) {
            parsedCode = current;
            List<Component> lines = new ArrayList<>();
            for (List<MiniMessage.Span> line : MiniMessage.parse(current)) {
                MutableComponent component = Component.empty();
                for (MiniMessage.Span span : line) {
                    Style style = Style.EMPTY
                            .withColor(span.color() >= 0 ? span.color() : LORE_PURPLE)
                            .withItalic(span.italic() != null ? span.italic() : Boolean.TRUE)
                            .withBold(span.bold())
                            .withUnderlined(span.underlined())
                            .withStrikethrough(span.strikethrough())
                            .withObfuscated(span.obfuscated());
                    component.append(Component.literal(span.text()).withStyle(style));
                }
                lines.add(component);
            }
            previewLines = lines;
        }
        return previewLines;
    }

    private void drawPreview(GuiGraphicsExtractor context) {
        int x = rightX();
        int y = previewY;
        int width = rightWidth();
        int height = previewHeight();
        MapStyle.card(context, x, y, width, height, 0xFF2C2C38);

        // The item, in a slot, the way it is held up to be hovered — the kind the description names,
        // when it names one, as the lore's title does.
        LoreGenerator.Kind named = LoreGenerator.Kind.in(description.getValue());
        ItemStack stack = new ItemStack(itemOf(named != null ? named : KINDS[kindIndex]));
        context.fill(x + 6, y + 6, x + 24, y + 24, 0xFF8B8B8B);
        context.fill(x + 7, y + 7, x + 24, y + 24, 0xFF373737);
        context.fill(x + 7, y + 7, x + 23, y + 23, 0xFF8B8B8B);
        context.item(stack, x + 7, y + 7);

        // The tooltip beside it, clipped to the card.
        List<Component> lines = new ArrayList<>();
        String itemName = name.getValue().isBlank() ? null : name.getValue().strip();
        lines.add(itemName == null ? stack.getHoverName() : Component.literal(itemName).withStyle(ChatFormatting.AQUA));
        lines.addAll(preview());
        int textWidth = 0;
        for (Component line : lines) {
            textWidth = Math.max(textWidth, this.font.width(line));
        }
        int tipX = x + 34;
        int tipY = y + 10;
        int tipHeight = lines.size() * 10 + (lines.size() > 1 ? 2 : 0) - 2;
        context.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);
        TooltipRenderUtil.extractTooltipBackground(context, tipX, tipY, textWidth, tipHeight, null);
        int lineY = tipY;
        for (int i = 0; i < lines.size(); i++) {
            context.text(this.font, lines.get(i), tipX, lineY, 0xFFFFFFFF);
            lineY += i == 0 ? 12 : 10;
        }
        context.disableScissor();

        // How to use it, along the foot of the card, when the tooltip leaves room.
        List<net.minecraft.util.FormattedCharSequence> hint = this.font.split(
                Component.translatable("betterloka.lore.how_to"), width - 12);
        int hintY = y + height - 6 - hint.size() * 10;
        if (hintY > tipY + tipHeight + 10) {
            for (net.minecraft.util.FormattedCharSequence line : hint) {
                context.text(this.font, line, x + 6, hintY, 0xFF7A7A8A);
                hintY += 10;
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(this.font, this.title, this.width / 2, 8, GuiTheme.TEXT);
        int left = contentLeft();
        context.text(this.font, Component.translatable("betterloka.lore.about"), left, descriptionLabelY, GuiTheme.MUTED);
        context.text(this.font, Component.translatable("betterloka.lore.name"), left, nameLabelY, GuiTheme.MUTED);
        context.text(this.font, Component.translatable("betterloka.lore.colors"), left, schemesLabelY, GuiTheme.MUTED);
        drawSchemes(context, mouseX, mouseY);

        context.text(this.font, Component.translatable("betterloka.lore.preview"), rightX(), previewLabelY, GuiTheme.MUTED);
        context.text(this.font, Component.translatable("betterloka.lore.code"), rightX(), codeLabelY, GuiTheme.MUTED);
        drawPreview(context);
        if (System.currentTimeMillis() - copiedAt < MapStyle.COPIED_NOTICE_MILLIS) {
            Component copied = Component.translatable("betterloka.lore.copied");
            context.text(this.font, copied, rightX() + rightWidth() - this.font.width(copied), codeLabelY, GuiTheme.GOOD);
        }
    }

    @Override
    public void onClose() {
        savedDescription = description.getValue();
        savedName = name.getValue();
        savedCode = code.getValue();
        this.minecraft.gui.setScreen(parent);
    }

    // --- for the dev harness ---

    /** Types a description and presses Generate, as a player would. */
    public void devWrite(String about, LoreScheme chosen, boolean inPolish) {
        description.setValue(about);
        scheme = chosen;
        polish = inPolish;
        seed = about.hashCode();
        generate();
        for (Button button : optionButtons) {
            button.setFocused(false);
        }
    }

    public String devCode() {
        return code.getValue();
    }
}
