package com.betterloka.gui;

import com.betterloka.brewing.Brewing;
import com.betterloka.brewing.Brewing.Effect;
import com.betterloka.brewing.Brewing.Fit;
import com.betterloka.brewing.Brewing.Form;
import com.betterloka.brewing.Brewing.Level;
import com.betterloka.brewing.Brewing.Line;
import com.betterloka.brewing.Brewing.Material;
import com.betterloka.brewing.Brewing.Plan;
import com.betterloka.brewing.Brewing.Recipe;
import com.betterloka.brewing.Brewing.Suggestion;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loka Calculator's Brewer Calculator: what a potion machine makes and what it takes.
 *
 * <p>Two ways round. From mats: so many of every ingredient in each brewing stand, so many stands,
 * and out come the potions, the empty bottles in stacks, every ingredient, the blaze powder each stand
 * burns, the time, the shulker boxes the potions fill and the raw materials behind it all. For a
 * target: so many potions wanted, and the screen offers the fills that bring the bottles to whole
 * stacks — the nearest above, the nearest below, and whole stacks of every ingredient.
 *
 * <p>The arithmetic is {@link Brewing}; this screen only turns it into items, names and a layout.
 */
public class BrewerCalculatorScreen extends Screen {
    private static final int MAX_WIDTH = 520;
    private static final int TOP = 24;
    private static final int LINE = 10;
    private static final int PAD = 6;
    private static final int ROW = 22;
    private static final int OFF = 0xFF3A3A46;
    private static final int DISABLED = 0xFF26262E;
    private static final int PICKER_COLUMNS = 7;
    private static final int PICKER_CELL = 20;

    private record Hit(int x, int y, int width, int height, Runnable action) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    // Kept between openings: the machine does not change from one look to the next.
    private static boolean targetMode;
    private static Effect effect = Effect.HEALING;
    private static Form form = Form.SPLASH;
    private static Level level = Level.STRONG;
    private static String perStandText = "64";
    private static String standsText = "8";
    private static String targetText = "20000";
    private static Fit fit = Fit.ABOVE;
    private static BrewerCalculatorScreen devInstance;

    private final Screen parent;
    private final ScrollPanel scroll = new ScrollPanel();
    /** Clickable things outside the list, and inside it (which only count inside its window). */
    private final List<Hit> hits = new ArrayList<>();
    private final List<Hit> listHits = new ArrayList<>();
    private final Map<Material, ItemStack> stacks = new EnumMap<>(Material.class);

    private EditBox amountField;
    private EditBox standsField;
    private boolean pickerOpen;
    private long copiedAt;
    /** What the list shows, as last worked out, for the copy button. */
    private Plan shown;

    public BrewerCalculatorScreen(Screen parent) {
        super(Component.translatable("betterloka.module.loka_calculator"));
        this.parent = parent;
    }

    // ---- layout ----

    private int contentWidth() {
        return Math.min(this.width - 24, MAX_WIDTH);
    }

    private int left() {
        return (this.width - contentWidth()) / 2;
    }

    private int leftWidth() {
        return Math.max(150, Math.min(196, contentWidth() * 2 / 5));
    }

    private int rightX() {
        return left() + leftWidth() + 10;
    }

    private int rightWidth() {
        return left() + contentWidth() - rightX();
    }

    private int bottom() {
        return this.height - 30;
    }

    private int modeY() {
        return TOP;
    }

    private int potionY() {
        return modeY() + 18;
    }

    private int formY() {
        return potionY() + 22;
    }

    private int levelY() {
        return formY() + 16;
    }

    private int chainY() {
        return levelY() + 19;
    }

    private int inputLabelY() {
        return chainY() + 22;
    }

    private int inputY() {
        return inputLabelY() + LINE;
    }

    private int hintY() {
        return inputY() + 21;
    }

    @Override
    protected void init() {
        devInstance = this;
        int left = left();
        int boxWidth = (leftWidth() - 6) / 2;
        amountField = numberBox(left, inputY(), boxWidth, targetMode ? targetText : perStandText,
                targetMode ? "betterloka.brewer.target" : "betterloka.brewer.per_stand", value -> {
                    if (targetMode) {
                        targetText = value;
                    } else {
                        perStandText = value;
                    }
                });
        standsField = numberBox(left + boxWidth + 6, inputY(), leftWidth() - boxWidth - 6, standsText,
                "betterloka.brewer.stands", value -> standsText = value);
        scroll.setViewport(rightX(), TOP, rightWidth(), bottom() - TOP);
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(this.width / 2 - 50, this.height - 24, 100, 20).build());
    }

    private EditBox numberBox(int x, int y, int width, String value, String labelKey,
                              java.util.function.Consumer<String> onChange) {
        EditBox box = new EditBox(this.font, x, y, width, 16, Component.translatable(labelKey));
        box.setMaxLength(12);
        box.setValue(value);
        box.setResponder(onChange);
        addRenderableWidget(box);
        return box;
    }

    // ---- what is being worked out ----

    private static Recipe recipe() {
        return new Recipe(effect, form, level);
    }

    /**
     * A count as typed: {@code 20000}, {@code 20 000}, {@code 20,000} and {@code 20k} are all twenty
     * thousand. {@code -1} for nothing usable.
     */
    static long parseCount(String text) {
        if (text == null) {
            return -1;
        }
        String clean = text.strip().toLowerCase(Locale.ROOT).replace(" ", "").replace(",", "").replace("_", "")
                .replace(".", "");
        long multiplier = 1;
        if (clean.endsWith("k")) {
            multiplier = 1000;
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.isEmpty() || clean.length() > 9 || !clean.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        return Long.parseLong(clean) * multiplier;
    }

    private static int stands() {
        long stands = parseCount(standsText);
        return stands <= 0 ? 0 : (int) Math.min(stands, 10_000);
    }

    private static List<Suggestion> suggestions() {
        long target = parseCount(targetText);
        return target <= 0 ? List.of() : Brewing.suggest(Math.min(target, 100_000_000L), stands());
    }

    private static Suggestion chosen(List<Suggestion> suggestions) {
        for (Suggestion suggestion : suggestions) {
            if (suggestion.fit() == fit) {
                return suggestion;
            }
        }
        return suggestions.isEmpty() ? null : suggestions.get(0);
    }

    /** The machine as it stands: from the boxes, or from the fill picked for the target. */
    private static Plan plan() {
        int stands = stands();
        if (stands <= 0) {
            return null;
        }
        if (targetMode) {
            Suggestion suggestion = chosen(suggestions());
            return suggestion == null ? null : new Plan(recipe(), suggestion.perStand(), stands);
        }
        long perStand = parseCount(perStandText);
        return perStand <= 0 ? null : new Plan(recipe(), (int) Math.min(perStand, 1_000_000), stands);
    }

    // ---- items and names ----

    private static Item itemOf(Material material) {
        return switch (material) {
            case NETHER_WART -> Items.NETHER_WART;
            case BLAZE_POWDER -> Items.BLAZE_POWDER;
            case SUGAR -> Items.SUGAR;
            case MAGMA_CREAM -> Items.MAGMA_CREAM;
            case GLISTERING_MELON_SLICE -> Items.GLISTERING_MELON_SLICE;
            case GHAST_TEAR -> Items.GHAST_TEAR;
            case GOLDEN_CARROT -> Items.GOLDEN_CARROT;
            case PUFFERFISH -> Items.PUFFERFISH;
            case RABBIT_FOOT -> Items.RABBIT_FOOT;
            case SPIDER_EYE -> Items.SPIDER_EYE;
            case PHANTOM_MEMBRANE -> Items.PHANTOM_MEMBRANE;
            case TURTLE_HELMET -> Items.TURTLE_HELMET;
            case FERMENTED_SPIDER_EYE -> Items.FERMENTED_SPIDER_EYE;
            case BREEZE_ROD -> Items.BREEZE_ROD;
            case COBWEB -> Items.COBWEB;
            case SLIME_BLOCK -> Items.SLIME_BLOCK;
            case STONE -> Items.STONE;
            case GLOWSTONE_DUST -> Items.GLOWSTONE_DUST;
            case REDSTONE -> Items.REDSTONE;
            case GUNPOWDER -> Items.GUNPOWDER;
            case DRAGON_BREATH -> Items.DRAGON_BREATH;
            case GLASS_BOTTLE -> Items.GLASS_BOTTLE;
            case GLASS -> Items.GLASS;
            case BLAZE_ROD -> Items.BLAZE_ROD;
            case GOLD_INGOT -> Items.GOLD_INGOT;
            case MELON_SLICE -> Items.MELON_SLICE;
            case CARROT -> Items.CARROT;
            case BROWN_MUSHROOM -> Items.BROWN_MUSHROOM;
            case SUGAR_CANE -> Items.SUGAR_CANE;
            case SLIME_BALL -> Items.SLIME_BALL;
        };
    }

    private ItemStack stack(Material material) {
        return stacks.computeIfAbsent(material, key -> new ItemStack(itemOf(key)));
    }

    private static Holder<Potion> potion(Effect effect, Level level) {
        boolean strong = level == Level.STRONG;
        boolean extended = level == Level.LONG;
        return switch (effect) {
            case HEALING -> strong ? Potions.STRONG_HEALING : Potions.HEALING;
            case STRENGTH -> strong ? Potions.STRONG_STRENGTH : extended ? Potions.LONG_STRENGTH : Potions.STRENGTH;
            case SWIFTNESS -> strong ? Potions.STRONG_SWIFTNESS : extended ? Potions.LONG_SWIFTNESS : Potions.SWIFTNESS;
            case FIRE_RESISTANCE -> extended ? Potions.LONG_FIRE_RESISTANCE : Potions.FIRE_RESISTANCE;
            case REGENERATION -> strong ? Potions.STRONG_REGENERATION
                    : extended ? Potions.LONG_REGENERATION : Potions.REGENERATION;
            case HARMING -> strong ? Potions.STRONG_HARMING : Potions.HARMING;
            case POISON -> strong ? Potions.STRONG_POISON : extended ? Potions.LONG_POISON : Potions.POISON;
            case SLOWNESS -> strong ? Potions.STRONG_SLOWNESS : extended ? Potions.LONG_SLOWNESS : Potions.SLOWNESS;
            case WEAKNESS -> extended ? Potions.LONG_WEAKNESS : Potions.WEAKNESS;
            case INVISIBILITY -> extended ? Potions.LONG_INVISIBILITY : Potions.INVISIBILITY;
            case NIGHT_VISION -> extended ? Potions.LONG_NIGHT_VISION : Potions.NIGHT_VISION;
            case WATER_BREATHING -> extended ? Potions.LONG_WATER_BREATHING : Potions.WATER_BREATHING;
            case LEAPING -> strong ? Potions.STRONG_LEAPING : extended ? Potions.LONG_LEAPING : Potions.LEAPING;
            case SLOW_FALLING -> extended ? Potions.LONG_SLOW_FALLING : Potions.SLOW_FALLING;
            case TURTLE_MASTER -> strong ? Potions.STRONG_TURTLE_MASTER
                    : extended ? Potions.LONG_TURTLE_MASTER : Potions.TURTLE_MASTER;
            case WIND_CHARGED -> Potions.WIND_CHARGED;
            case WEAVING -> Potions.WEAVING;
            case OOZING -> Potions.OOZING;
            case INFESTED -> Potions.INFESTED;
        };
    }

    private static ItemStack potionStack(Effect effect, Form form, Level level) {
        Item item = switch (form) {
            case DRINK -> Items.POTION;
            case SPLASH -> Items.SPLASH_POTION;
            case LINGERING -> Items.LINGERING_POTION;
        };
        return PotionContents.createItemStack(item, potion(effect, level));
    }

    private static ItemStack waterBottle() {
        return PotionContents.createItemStack(Items.POTION, Potions.WATER);
    }

    /** The effect's own name, as the game gives it in the player's language. */
    private static Component effectName(Effect effect) {
        Holder<Potion> holder = potion(effect, Level.NORMAL);
        var effects = holder.value().getEffects();
        return effects.isEmpty() ? Component.literal(effect.name()) : effects.get(0).getEffect().value().getDisplayName();
    }

    /** "Splash Potion of Healing II", "Potion of Strength (long)". */
    private static Component potionName(Recipe recipe) {
        Component base = potionStack(recipe.effect(), recipe.form(), recipe.level()).getHoverName();
        return switch (recipe.level()) {
            case STRONG -> Component.empty().append(base).append(" II");
            case LONG -> Component.empty().append(base).append(Component.translatable("betterloka.brewer.long_suffix"));
            case NORMAL -> base;
        };
    }

    private Component materialName(Material material) {
        return stack(material).getHoverName();
    }

    // ---- numbers ----

    private static String num(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** {@code 24×64}, {@code 40×64 + 5}, or just {@code 13} when it is less than a stack. */
    static String inStacks(long count) {
        long whole = count / Brewing.STACK;
        long rest = count % Brewing.STACK;
        if (whole == 0) {
            return num(rest);
        }
        return rest == 0 ? num(whole) + "×64" : num(whole) + "×64 + " + rest;
    }

    /** {@code 1 h 25 min}, {@code 5 min 20 s}, {@code 40 s}. */
    static String duration(long seconds) {
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long rest = seconds % 60;
        if (hours > 0) {
            return minutes == 0 ? hours + " h" : hours + " h " + minutes + " min";
        }
        if (minutes > 0) {
            return rest == 0 ? minutes + " min" : minutes + " min " + rest + " s";
        }
        return rest + " s";
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (CalculatorTabs.click(this.font, this.width, CalculatorTabs.Tab.BREWER, click.x(), click.y(), parent)) {
            return true;
        }
        boolean wasOpen = pickerOpen;
        for (Hit hit : List.copyOf(hits)) {
            if (hit.contains(click.x(), click.y())) {
                hit.action().run();
                if (wasOpen && pickerOpen) {
                    pickerOpen = false;
                }
                return true;
            }
        }
        if (wasOpen) {
            // A click anywhere else puts the picker away, and does nothing more.
            pickerOpen = false;
            return true;
        }
        if (click.y() >= scroll.viewportTop() && click.y() < scroll.viewportBottom()) {
            for (Hit hit : List.copyOf(listHits)) {
                if (hit.contains(click.x(), click.y())) {
                    hit.action().run();
                    return true;
                }
            }
        }
        if (scroll.mouseClicked(click.x(), click.y())) {
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Over the potion, the wheel turns through the potions.
        if (!pickerOpen && mouseX >= left() && mouseX < left() + leftWidth()
                && mouseY >= potionY() && mouseY < potionY() + 18 && verticalAmount != 0) {
            cycleEffect(verticalAmount < 0 ? 1 : -1);
            return true;
        }
        return scroll.mouseScrolled(mouseX, mouseY, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        return scroll.mouseDragged(click.y()) || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        scroll.mouseReleased();
        return super.mouseReleased(click);
    }

    private static void cycleEffect(int by) {
        Effect[] all = Effect.values();
        effect = all[Math.floorMod(effect.ordinal() + by, all.length)];
        if (!effect.allows(level)) {
            level = Level.NORMAL;
        }
    }

    private void setMode(boolean target) {
        if (targetMode == target) {
            return;
        }
        targetMode = target;
        scroll.reset();
        rebuildWidgets();
    }

    private void setPerStand(int perStand) {
        perStandText = Integer.toString(perStand);
        if (amountField != null && !targetMode) {
            amountField.setValue(perStandText);
        }
    }

    @Override
    public void onClose() {
        if (devInstance == this) {
            devInstance = null;
        }
        this.minecraft.gui.setScreen(parent);
    }

    // ---- drawing ----

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractBackground(context, mouseX, mouseY, delta);
        context.fill(left() - 4, TOP - 4, left() + leftWidth() + 4, bottom() + 2, 0x60000000);
        context.fill(rightX() - 4, TOP - 4, rightX() + rightWidth() + 4, bottom() + 2, 0x70000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        hits.clear();
        listHits.clear();
        super.extractRenderState(context, mouseX, mouseY, delta);
        CalculatorTabs.draw(context, this.font, this.width, CalculatorTabs.Tab.BREWER, mouseX, mouseY);

        Plan plan = plan();
        shown = plan;
        drawSettings(context, plan, mouseX, mouseY);

        int x = rightX();
        context.enableScissor(x, scroll.viewportTop(), x + rightWidth(), scroll.viewportBottom());
        int top = scroll.contentTop();
        int height = drawResults(context, plan, x, top, scroll.contentWidth(), mouseX, mouseY) - top;
        context.disableScissor();
        scroll.setContentHeight(height);
        scroll.render(context, mouseX, mouseY);

        if (pickerOpen) {
            context.nextStratum();
            drawPicker(context, mouseX, mouseY);
        }
    }

    // ---- the left side: what is brewed, and how much ----

    private void drawSettings(GuiGraphicsExtractor context, Plan plan, int mouseX, int mouseY) {
        int left = left();
        int width = leftWidth();
        boolean live = !pickerOpen;

        segments(context, left, modeY(), width, 14,
                new Component[]{Component.translatable("betterloka.brewer.mode_mats"),
                        Component.translatable("betterloka.brewer.mode_target")},
                targetMode ? 1 : 0, new boolean[]{true, true},
                new Runnable[]{() -> setMode(false), () -> setMode(true)}, live ? mouseX : -1, mouseY);

        drawPotionSelector(context, left, potionY(), width, live ? mouseX : -1, mouseY);

        segments(context, left, formY(), width, 13,
                new Component[]{Component.translatable("betterloka.brewer.form.drink"),
                        Component.translatable("betterloka.brewer.form.splash"),
                        Component.translatable("betterloka.brewer.form.lingering")},
                form.ordinal(), new boolean[]{true, true, true},
                new Runnable[]{() -> form = Form.DRINK, () -> form = Form.SPLASH, () -> form = Form.LINGERING},
                live ? mouseX : -1, mouseY);
        segments(context, left, levelY(), width, 13,
                new Component[]{Component.translatable("betterloka.brewer.level.normal"),
                        Component.translatable("betterloka.brewer.level.strong"),
                        Component.translatable("betterloka.brewer.level.long")},
                level.ordinal(), new boolean[]{true, effect.hasStrong(), effect.hasLong()},
                new Runnable[]{() -> level = Level.NORMAL, () -> level = Level.STRONG, () -> level = Level.LONG},
                live ? mouseX : -1, mouseY);

        drawChain(context, left, chainY(), width, live ? mouseX : -1, mouseY);

        int boxWidth = (width - 6) / 2;
        context.text(this.font, cut(Component.translatable(targetMode ? "betterloka.brewer.target"
                : "betterloka.brewer.per_stand"), boxWidth), left, inputLabelY(), GuiTheme.MUTED);
        context.text(this.font, cut(Component.translatable("betterloka.brewer.stands"), width - boxWidth - 6),
                left + boxWidth + 6, inputLabelY(), GuiTheme.MUTED);

        int y = hintY();
        if (!targetMode && plan != null) {
            if (plan.fullStacks()) {
                context.text(this.font, cut(Component.literal("✔ ").append(
                        Component.translatable("betterloka.brewer.whole_stacks")), width), left, y + 2, GuiTheme.GOOD);
            } else {
                Component label = Component.translatable("betterloka.brewer.round_to");
                context.text(this.font, label, left, y + 2, GuiTheme.LIVE);
                int x = left + this.font.width(label) + 4;
                for (int value : plan.fullStackNeighbours()) {
                    if (value <= 0) {
                        continue;
                    }
                    String text = num(value);
                    int chipWidth = this.font.width(text) + 8;
                    boolean hover = live && mouseX >= x && mouseX < x + chipWidth && mouseY >= y && mouseY < y + 12;
                    GuiTheme.chip(context, x, y, chipWidth, 12, hover ? GuiTheme.TEXT : GuiTheme.LIVE);
                    context.text(this.font, text, x + 4, y + 2, GuiTheme.TEXT);
                    int value0 = value;
                    hits.add(new Hit(x, y, chipWidth, 12, () -> setPerStand(value0)));
                    x += chipWidth + 3;
                }
            }
        } else if (targetMode) {
            context.text(this.font, cut(Component.translatable("betterloka.brewer.pick"), width), left, y + 2,
                    GuiTheme.MUTED);
        }

        // The rules everything here follows, where they can be read.
        int rulesY = y + 16;
        for (FormattedCharSequence line : this.font.split(Component.translatable("betterloka.brewer.rules"), width)) {
            if (rulesY + 9 > bottom()) {
                break;
            }
            context.text(this.font, line, left, rulesY, 0xFF6E6E7A);
            rulesY += 9;
        }
    }

    private String cut(Component text, int width) {
        return this.font.plainSubstrByWidth(text.getString(), width);
    }

    /** A row of buttons of which one is picked; a disabled one is dim and does nothing. */
    private void segments(GuiGraphicsExtractor context, int x, int y, int width, int height, Component[] labels,
                          int picked, boolean[] enabled, Runnable[] actions, int mouseX, int mouseY) {
        int count = labels.length;
        int gap = 2;
        int each = (width - gap * (count - 1)) / count;
        for (int i = 0; i < count; i++) {
            int segmentX = x + i * (each + gap);
            int segmentWidth = i == count - 1 ? x + width - segmentX : each;
            boolean on = i == picked;
            boolean hover = enabled[i] && !on && mouseX >= segmentX && mouseX < segmentX + segmentWidth
                    && mouseY >= y && mouseY < y + height;
            int border = on ? GuiTheme.ACCENT : !enabled[i] ? DISABLED : hover ? 0xFF6A6A7A : OFF;
            GuiTheme.chip(context, segmentX, y, segmentWidth, height, border);
            String text = this.font.plainSubstrByWidth(labels[i].getString(), segmentWidth - 4);
            int color = on ? GuiTheme.TEXT : !enabled[i] ? 0xFF55555F : hover ? GuiTheme.TEXT : GuiTheme.MUTED;
            context.text(this.font, text, segmentX + (segmentWidth - this.font.width(text)) / 2,
                    y + (height - 8) / 2, color);
            if (enabled[i]) {
                hits.add(new Hit(segmentX, y, segmentWidth, height, actions[i]));
            }
        }
    }

    /** The potion: arrows either side, and its picture and name, which open every potion to pick from. */
    private void drawPotionSelector(GuiGraphicsExtractor context, int x, int y, int width, int mouseX, int mouseY) {
        int height = 18;
        int arrow = 14;
        boolean overMiddle = mouseX >= x + arrow && mouseX < x + width - arrow && mouseY >= y && mouseY < y + height;
        GuiTheme.chip(context, x, y, width, height, pickerOpen || overMiddle ? GuiTheme.ACCENT : OFF);
        for (int side = 0; side < 2; side++) {
            int arrowX = side == 0 ? x : x + width - arrow;
            boolean hover = mouseX >= arrowX && mouseX < arrowX + arrow && mouseY >= y && mouseY < y + height;
            String glyph = side == 0 ? "<" : ">";
            context.text(this.font, glyph, arrowX + (arrow - this.font.width(glyph)) / 2, y + 5,
                    hover ? GuiTheme.ACCENT : GuiTheme.MUTED);
            int by = side == 0 ? -1 : 1;
            hits.add(new Hit(arrowX, y, arrow, height, () -> cycleEffect(by)));
        }
        context.item(potionStack(effect, form, level), x + arrow + 1, y + 1);
        String name = this.font.plainSubstrByWidth(effectName(effect).getString(), width - arrow * 2 - 22);
        context.text(this.font, name, x + arrow + 20, y + 5, GuiTheme.TEXT);
        hits.add(new Hit(x + arrow, y, width - arrow * 2, height, () -> pickerOpen = !pickerOpen));
    }

    /** Every potion, to pick one from, under the selector. */
    private void drawPicker(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        Effect[] all = Effect.values();
        int rows = (all.length + PICKER_COLUMNS - 1) / PICKER_COLUMNS;
        int width = PICKER_COLUMNS * PICKER_CELL + PAD * 2;
        int height = rows * PICKER_CELL + PAD * 2;
        int x = left();
        int y = potionY() + 20;
        context.fill(x - 1, y - 1, x + width + 1, y + height + 1, GuiTheme.ACCENT);
        context.fill(x, y, x + width, y + height, 0xF0121218);
        Effect hovered = null;
        for (int i = 0; i < all.length; i++) {
            int cellX = x + PAD + (i % PICKER_COLUMNS) * PICKER_CELL;
            int cellY = y + PAD + (i / PICKER_COLUMNS) * PICKER_CELL;
            boolean hover = mouseX >= cellX && mouseX < cellX + PICKER_CELL && mouseY >= cellY
                    && mouseY < cellY + PICKER_CELL;
            if (all[i] == effect || hover) {
                GuiTheme.chip(context, cellX, cellY, PICKER_CELL, PICKER_CELL, all[i] == effect ? GuiTheme.ACCENT : GuiTheme.MUTED);
            }
            context.item(potionStack(all[i], Form.DRINK, Level.NORMAL), cellX + 2, cellY + 2);
            Effect pick = all[i];
            hits.add(new Hit(cellX, cellY, PICKER_CELL, PICKER_CELL, () -> {
                effect = pick;
                if (!effect.allows(level)) {
                    level = Level.NORMAL;
                }
            }));
            if (hover) {
                hovered = all[i];
            }
        }
        if (hovered != null) {
            context.setTooltipForNextFrame(this.font, List.of(effectName(hovered).getVisualOrderText()), mouseX, mouseY);
        }
    }

    /** Water, then each ingredient in turn, then the potion: the order they go in. */
    private void drawChain(GuiGraphicsExtractor context, int x, int y, int width, int mouseX, int mouseY) {
        List<Material> steps = recipe().steps();
        List<ItemStack> icons = new ArrayList<>();
        icons.add(waterBottle());
        for (Material step : steps) {
            icons.add(stack(step));
        }
        icons.add(potionStack(effect, form, level));
        int count = icons.size();
        int gap = Math.max(0, Math.min(12, (width - count * 16) / Math.max(1, count - 1)));
        int iconX = x;
        ItemStack hovered = null;
        for (int i = 0; i < count; i++) {
            if (i == count - 1) {
                // The finished potion stands out from what goes into it.
                GuiTheme.chip(context, iconX - 1, y - 1, 18, 18, GuiTheme.ACCENT);
            }
            context.item(icons.get(i), iconX, y);
            if (mouseX >= iconX && mouseX < iconX + 16 && mouseY >= y && mouseY < y + 16) {
                hovered = icons.get(i);
            }
            if (i < count - 1 && gap >= 6) {
                String glyph = i == count - 2 ? "=" : "›";
                context.text(this.font, glyph, iconX + 16 + (gap - this.font.width(glyph)) / 2 + 1, y + 4,
                        GuiTheme.MUTED);
            }
            iconX += 16 + gap;
        }
        if (hovered != null) {
            context.setTooltipForNextFrame(this.font, hovered, mouseX, mouseY);
        }
    }

    // ---- the right side: what it all comes to ----

    private int drawResults(GuiGraphicsExtractor context, Plan plan, int x, int y, int width, int mouseX, int mouseY) {
        boolean inList = !pickerOpen && mouseY >= scroll.viewportTop() && mouseY < scroll.viewportBottom();
        int pointerX = inList ? mouseX : -1;
        if (targetMode) {
            y = drawSuggestions(context, x, y, width, pointerX, mouseY);
        }
        if (plan == null) {
            if (!targetMode) {
                y = wrap(context, Component.translatable(stands() <= 0 ? "betterloka.brewer.type_stands"
                        : "betterloka.brewer.type_amount"), x, y, width, GuiTheme.MUTED);
            } else if (stands() <= 0) {
                y = wrap(context, Component.translatable("betterloka.brewer.type_stands"), x, y, width, GuiTheme.MUTED);
            }
            return y;
        }
        y = drawHeadline(context, plan, x, y, width);
        y = drawMachine(context, plan, x, y + 8, width, pointerX, mouseY);
        y = drawTimeAndStorage(context, plan, x, y + 8, width);
        y = drawRaw(context, plan, x, y + 8, width);
        return y + 4;
    }

    private int wrap(GuiGraphicsExtractor context, Component text, int x, int y, int width, int color) {
        for (FormattedCharSequence line : this.font.split(text, width)) {
            context.text(this.font, line, x, y, color);
            y += LINE;
        }
        return y + 2;
    }

    private int heading(GuiGraphicsExtractor context, Component text, int x, int y, int width) {
        context.text(this.font, text, x, y, GuiTheme.ACCENT);
        context.fill(x, y + 10, x + width, y + 11, 0xFF33333F);
        return y + 14;
    }

    private int drawSuggestions(GuiGraphicsExtractor context, int x, int y, int width, int mouseX, int mouseY) {
        List<Suggestion> suggestions = suggestions();
        y = heading(context, Component.translatable("betterloka.brewer.fill_with"), x, y, width);
        if (stands() <= 0) {
            return y;
        }
        if (suggestions.isEmpty()) {
            return wrap(context, Component.translatable("betterloka.brewer.type_target"), x, y, width, GuiTheme.MUTED);
        }
        Suggestion picked = chosen(suggestions);
        for (Suggestion suggestion : suggestions) {
            int height = 24;
            boolean on = suggestion == picked;
            boolean hover = !on && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
            GuiTheme.chip(context, x, y, width, height, on ? GuiTheme.ACCENT : hover ? 0xFF6A6A7A : OFF);
            Component potions = Component.translatable("betterloka.brewer.potions_n", num(suggestion.potions()));
            long difference = suggestion.difference();
            String off = difference == 0 ? Component.translatable("betterloka.brewer.exact").getString()
                    : (difference > 0 ? "+" : "−") + num(Math.abs(difference));
            int offColor = difference == 0 ? GuiTheme.GOOD : difference > 0 ? GuiTheme.LIVE : GuiTheme.BAD;
            String stacks = inStacks(suggestion.potions()) + "  ";
            int rightWidth = Math.max(this.font.width(potions), this.font.width(stacks) + this.font.width(off));
            int leftWidth = width - PAD * 2 - rightWidth - 6;

            Component title = Component.translatable("betterloka.brewer.mats_per_stand", num(suggestion.perStand()));
            context.text(this.font, this.font.plainSubstrByWidth(title.getString(), leftWidth), x + PAD, y + 4,
                    GuiTheme.TEXT);
            String stacksNote = suggestion.perStand() >= Brewing.STACK ? " · " + inStacks(suggestion.perStand()) : "";
            Component fitName = Component.translatable("betterloka.brewer.fit." + suggestion.fit().name().toLowerCase(Locale.ROOT));
            context.text(this.font, this.font.plainSubstrByWidth(fitName.getString() + stacksNote, leftWidth),
                    x + PAD, y + 14, GuiTheme.MUTED);

            context.text(this.font, potions, x + width - PAD - this.font.width(potions), y + 4, GuiTheme.TEXT);
            context.text(this.font, off, x + width - PAD - this.font.width(off), y + 14, offColor);
            context.text(this.font, stacks, x + width - PAD - this.font.width(off) - this.font.width(stacks), y + 14,
                    GuiTheme.MUTED);
            Fit fitOf = suggestion.fit();
            listHits.add(new Hit(x, y, width, height, () -> fit = fitOf));
            y += height + 3;
        }
        return y + 5;
    }

    /** The potion, how many, and the stands that make them. */
    private int drawHeadline(GuiGraphicsExtractor context, Plan plan, int x, int y, int width) {
        int height = 50;
        MapStyle.card(context, x, y, width, height, GuiTheme.ACCENT);
        var pose = context.pose();
        pose.pushMatrix();
        pose.translate(x + 7, y + 9);
        pose.scale(2f, 2f);
        context.item(potionStack(effect, form, level), 0, 0);
        pose.popMatrix();

        int textX = x + 46;
        int inner = width - 46 - PAD;
        context.text(this.font, this.font.plainSubstrByWidth(potionName(plan.recipe()).getString(), inner),
                textX, y + 5, GuiTheme.MUTED);
        String count = num(plan.potions());
        pose.pushMatrix();
        pose.translate(textX, y + 16);
        pose.scale(1.5f, 1.5f);
        context.text(this.font, count, 0, 0, GuiTheme.TEXT);
        pose.popMatrix();
        context.text(this.font, Component.translatable("betterloka.brewer.potions"),
                textX + (int) Math.ceil(this.font.width(count) * 1.5f) + 4, y + 20, GuiTheme.TEXT);
        Component summary = Component.translatable("betterloka.brewer.summary", inStacks(plan.bottles()),
                num(plan.stands()), num(plan.perStand()));
        context.text(this.font, this.font.plainSubstrByWidth(summary.getString(), inner), textX, y + 30,
                plan.fullStacks() ? GuiTheme.GOOD : GuiTheme.LIVE);

        // The machine itself: one stand a picture, as many as fit.
        int iconX = textX;
        int shownStands = (int) Math.min(plan.stands(), Math.max(1, (inner - 24) / 11));
        var stand = new ItemStack(Items.BREWING_STAND);
        for (int i = 0; i < shownStands; i++) {
            pose.pushMatrix();
            pose.translate(iconX, y + 39);
            pose.scale(0.625f, 0.625f);
            context.item(stand, 0, 0);
            pose.popMatrix();
            iconX += 11;
        }
        if (shownStands < plan.stands()) {
            context.text(this.font, "+" + num(plan.stands() - shownStands), iconX + 2, y + 41, GuiTheme.MUTED);
        }
        return y + height;
    }

    /** Everything that goes into the stands, with the copy button. */
    private int drawMachine(GuiGraphicsExtractor context, Plan plan, int x, int y, int width, int mouseX, int mouseY) {
        int headingY = y;
        y = heading(context, Component.translatable("betterloka.brewer.machine"), x, y, width);
        boolean copied = System.currentTimeMillis() - copiedAt < 2000;
        Component copy = Component.translatable(copied ? "betterloka.brewer.copied" : "betterloka.brewer.copy");
        int copyWidth = this.font.width(copy) + 8;
        int copyX = x + width - copyWidth;
        boolean hover = mouseX >= copyX && mouseX < copyX + copyWidth && mouseY >= headingY - 2 && mouseY < headingY + 10;
        GuiTheme.chip(context, copyX, headingY - 2, copyWidth, 11, copied ? GuiTheme.GOOD : hover ? GuiTheme.TEXT : OFF);
        context.text(this.font, copy, copyX + 4, headingY, copied ? GuiTheme.GOOD : GuiTheme.TEXT);
        listHits.add(new Hit(copyX, headingY - 2, copyWidth, 11, this::copyList));

        for (Line line : plan.materials()) {
            Component name = materialName(line.material());
            if (line.fuel()) {
                name = Component.empty().append(name).append(" ").append(
                        Component.translatable("betterloka.brewer.fuel").withStyle(ChatFormatting.GOLD));
            }
            Component sub = line.material() == Material.GLASS_BOTTLE
                    ? Component.translatable("betterloka.brewer.per_stand_water", num(line.perStand()))
                    : Component.translatable("betterloka.brewer.per_stand_n", num(line.perStand()));
            y = row(context, stack(line.material()), name, sub, line.total(), x, y, width);
        }
        return wrap(context, Component.translatable("betterloka.brewer.fuel_note"), x, y + 2, width, 0xFF6E6E7A);
    }

    /**
     * One material: its picture, its name with a note under it, and on the right how many and in
     * stacks.
     */
    private int row(GuiGraphicsExtractor context, ItemStack icon, Component name, Component sub, long total,
                    int x, int y, int width) {
        context.item(icon, x + 2, y + 3);
        String totalText = num(total);
        String stacksText = total >= Brewing.STACK ? inStacks(total) : "";
        int right = x + width - 2;
        int numbersWidth = Math.max(this.font.width(totalText), this.font.width(stacksText));
        int nameWidth = width - 24 - numbersWidth - 6;
        // Whole, with its colours, where it fits; cut to plain text where it does not.
        if (this.font.width(name) <= nameWidth) {
            context.text(this.font, name, x + 22, y + 2, GuiTheme.TEXT);
        } else {
            context.text(this.font, this.font.plainSubstrByWidth(name.getString(), nameWidth), x + 22, y + 2,
                    GuiTheme.TEXT);
        }
        if (sub != null) {
            context.text(this.font, this.font.plainSubstrByWidth(sub.getString(), nameWidth), x + 22, y + 12,
                    GuiTheme.MUTED);
        }
        context.text(this.font, totalText, right - this.font.width(totalText), y + 2, GuiTheme.TEXT);
        if (!stacksText.isEmpty()) {
            context.text(this.font, stacksText, right - this.font.width(stacksText), y + 12, GuiTheme.ACCENT);
        }
        return y + ROW;
    }

    private int drawTimeAndStorage(GuiGraphicsExtractor context, Plan plan, int x, int y, int width) {
        y = heading(context, Component.translatable("betterloka.brewer.time_storage"), x, y, width);
        y = fact(context, new ItemStack(Items.CLOCK),
                Component.translatable("betterloka.brewer.time", duration(plan.seconds())),
                Component.translatable("betterloka.brewer.time_detail", num(plan.brewsPerStand())), x, y, width);
        y = fact(context, new ItemStack(Items.SHULKER_BOX),
                Component.translatable("betterloka.brewer.shulkers", num(plan.shulkers())),
                Component.translatable("betterloka.brewer.shulkers_detail"), x, y, width);
        return y;
    }

    private int fact(GuiGraphicsExtractor context, ItemStack icon, Component text, Component sub, int x, int y, int width) {
        context.item(icon, x + 2, y + 3);
        context.text(this.font, this.font.plainSubstrByWidth(text.getString(), width - 24), x + 22, y + 2, GuiTheme.TEXT);
        context.text(this.font, this.font.plainSubstrByWidth(sub.getString(), width - 24), x + 22, y + 12, GuiTheme.MUTED);
        return y + ROW;
    }

    /** What it all comes to before any crafting, in two columns where there is room. */
    private int drawRaw(GuiGraphicsExtractor context, Plan plan, int x, int y, int width) {
        y = heading(context, Component.translatable("betterloka.brewer.raw"), x, y, width);
        y = wrap(context, Component.translatable("betterloka.brewer.raw_hint"), x, y, width, 0xFF6E6E7A);
        List<Line> raw = plan.raw();
        boolean twoColumns = width >= 220;
        int columnWidth = twoColumns ? (width - 8) / 2 : width;
        for (int i = 0; i < raw.size(); i++) {
            Line line = raw.get(i);
            int column = twoColumns ? i % 2 : 0;
            int columnX = x + column * (columnWidth + 8);
            row(context, stack(line.material()), materialName(line.material()), null, line.total(),
                    columnX, y, columnWidth);
            if (!twoColumns || column == 1 || i == raw.size() - 1) {
                y += ROW;
            }
        }
        return y;
    }

    // ---- copying ----

    private void copyList() {
        if (shown == null) {
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append(potionName(shown.recipe()).getString()).append(": ").append(num(shown.potions()))
                .append(" (").append(num(shown.stands())).append(" × ").append(num(shown.perStand())).append(")\n");
        for (Line line : shown.materials()) {
            text.append("- ").append(materialName(line.material()).getString());
            if (line.fuel()) {
                text.append(' ').append(Component.translatable("betterloka.brewer.fuel").getString());
            }
            text.append(": ").append(num(line.total()));
            if (line.total() >= Brewing.STACK) {
                text.append(" (").append(inStacks(line.total())).append(')');
            }
            text.append('\n');
        }
        text.append(Component.translatable("betterloka.brewer.time", duration(shown.seconds())).getString());
        this.minecraft.keyboardHandler.setClipboard(text.toString());
        copiedAt = System.currentTimeMillis();
    }

    // ---- for the dev harness ----

    public static void devSet(boolean target, Effect newEffect, Form newForm, Level newLevel, String amount,
                              String stands) {
        effect = newEffect;
        form = newForm;
        level = newEffect.allows(newLevel) ? newLevel : Level.NORMAL;
        standsText = stands;
        if (target) {
            targetText = amount;
        } else {
            perStandText = amount;
        }
        targetMode = target;
        fit = Fit.ABOVE;
        if (devInstance != null) {
            devInstance.scroll.reset();
            devInstance.pickerOpen = false;
            devInstance.rebuildWidgets();
        }
    }

    public static void devFit(Fit newFit) {
        fit = newFit;
    }

    public static void devPicker(boolean open) {
        if (devInstance != null) {
            devInstance.pickerOpen = open;
        }
    }

    public static void devScroll(double amount) {
        if (devInstance != null) {
            devInstance.scroll.mouseScrolled(devInstance.rightX() + 10, TOP + 10, amount);
        }
    }

    public static String devState() {
        Plan plan = plan();
        if (plan == null) {
            return "no plan (target=" + targetMode + ")";
        }
        return "target=" + targetMode + " recipe=" + plan.recipe() + " perStand=" + plan.perStand() + " stands="
                + plan.stands() + " potions=" + plan.potions() + " bottles=" + inStacks(plan.bottles())
                + " fuel=" + plan.fuel() + " time=" + duration(plan.seconds()) + " shulkers=" + plan.shulkers()
                + " suggestions=" + (targetMode ? suggestions() : List.of());
    }
}
