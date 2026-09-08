package com.betterloka;

import com.betterloka.gui.LokaGrinderScreen;
import com.betterloka.gui.LokaMapScreen;
import com.betterloka.map.Continent;
import com.betterloka.map.MapTerritory;
import com.betterloka.gui.PlayerFinderScreen;
import com.betterloka.gui.TownFinderScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.world.gen.WorldPresets;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Development-only screenshot driver. Enabled with {@code BETTERLOKA_SHOTS=1}. */
public final class DevShots {
    private final List<Runnable> steps = new ArrayList<>();
    private final List<Integer> waits = new ArrayList<>();
    private int index;
    private int ticks;

    public static void register() {
        new DevShots().start();
    }

    private void start() {
        step(80, () -> { });
        step(5, this::createWorld);
        step(400, () -> { });
        step(20, () -> {
            BetterLokaClient.grindTimers().shulker().start();
            BetterLokaClient.grindTimers().glowstone().start();
            client().setScreen(null);
        });
        shot(20, "grinder-hud");
        step(5, () -> client().setScreen(new LokaGrinderScreen(null)));
        shot(20, "grinder-shulker");
        step(5, () -> click(client().currentScreen, "Glowstone"));
        shot(20, "grinder-glowstone");

        step(5, () -> client().setScreen(new PlayerFinderScreen(null)));
        step(5, () -> {
            type(client().currentScreen, "haapi");
            click(client().currentScreen, "Search");
        });
        step(400, () -> { });
        step(5, () -> scroll(-4000));
        shot(20, "player-finder-identity");
        step(200, () -> { });
        shot(20, "player-finder-names");

        step(5, () -> client().setScreen(new TownFinderScreen(null)));
        step(5, () -> {
            type(client().currentScreen, "Hilo");
            click(client().currentScreen, "Search");
        });
        step(300, () -> { });
        shot(20, "town-finder-founded");

        // The tutorial toast owns the top right and would sit over the map's card.
        step(5, () -> client().getToastManager().clear());
        // How long the map takes to have something to draw, now that the data is not fetched on
        // opening it. Measured across the open itself, not from a warm cache read.
        step(5, () -> {
            // Asked of the store, not of the screen: the screen has not laid itself out until its
            // first render, so anything that needs its geometry would report a false negative.
            int held = BetterLokaClient.mapData().snapshot(Continent.KALROS).territories().size();
            long before = System.nanoTime();
            client().setScreen(new LokaMapScreen(null));
            BetterLoka.LOGGER.info("SHOT map opened in {} ms with {} territories already in hand",
                    String.format("%.2f", (System.nanoTime() - before) / 1_000_000.0), held);
        });
        step(300, () -> { });
        // The ground arrives a tile at a time; give it long enough to cover the window.
        step(1200, () -> { });
        shot(20, "map-borders");

        // One card per case: held ground, a town's seat, and ground nobody holds. The cursor cannot
        // be moved here, so the map is dragged until the territory wanted is under it.
        step(5, () -> bringUnderCursor(territory -> !territory.neutral()));
        step(20, () -> { });
        shot(20, "card-held");

        step(5, () -> bringUnderCursor(territory ->
                BetterLokaClient.mapData().seatOf(Continent.KALROS, territory) != null));
        step(20, () -> { });
        shot(20, "card-town-seat");

        step(5, () -> bringUnderCursor(territory -> territory.neutral()
                && BetterLokaClient.mapData().seatOf(Continent.KALROS, territory) == null));
        step(20, () -> { });
        shot(20, "card-unclaimed");

        // The zoom buttons, clicked rather than scrolled, so the buttons themselves are exercised.
        step(5, () -> {
            for (int i = 0; i < 5; i++) {
                clickZoom(true);
            }
        });
        step(1200, () -> { });
        shot(20, "map-zoomed-in");

        step(5, () -> {
            for (int i = 0; i < 8; i++) {
                clickZoom(false);
            }
        });
        step(600, () -> { });
        shot(20, "map-zoomed-out");

        // How the map holds up while being dragged, measured both ways in the same conditions.
        // Absolute numbers here are software rendering under a headless X server; the comparison
        // between them is the part that means anything.
        step(5, () -> LokaMapScreen.devLinearHitTest = true);
        step(60, () -> { });
        step(5, () -> BetterLoka.LOGGER.info("SHOT fps dragging, linear hit test: {}",
                client().getCurrentFps()));
        step(5, () -> LokaMapScreen.devLinearHitTest = false);
        step(60, () -> { });
        step(5, () -> BetterLoka.LOGGER.info("SHOT fps dragging, indexed hit test: {}",
                client().getCurrentFps()));

        // Tyralnia's seat on Ascalon: the hex this was reported on.
        step(5, () -> click(client().currentScreen, "Ascalon"));
        step(1200, () -> { });
        step(5, () -> bringUnderCursor(t -> t.seat() && "Tyralnia".equals(t.owner())));
        step(20, () -> { });
        shot(20, "card-capital");
        // Close in, so the seat's heavier ring against its ordinary claims is visible.
        step(5, () -> {
            for (int i = 0; i < 6; i++) {
                clickZoom(true);
            }
        });
        step(5, () -> bringUnderCursor(t -> t.seat() && "Tyralnia".equals(t.owner())));
        step(400, () -> { });
        shot(20, "map-capital-close");
        step(5, () -> {
            var snap = BetterLokaClient.mapData().snapshot(Continent.ASCALON);
            for (MapTerritory t : snap.territories()) {
                if (t.seat()) {
                    BetterLoka.LOGGER.info("SHOT seat #{} owner={} alliance={} icon={}",
                            t.number(), t.owner(), t.alliance(), t.icon());
                }
            }
            for (var town : snap.towns()) {
                if (town.hasTitle()) {
                    BetterLoka.LOGGER.info("SHOT capital: {} - {}", town.name(), town.title());
                }
            }
        });

        step(5, () -> click(client().currentScreen, "Balak"));
        step(1200, () -> { });
        shot(20, "map-balak");

        step(5, () -> click(client().currentScreen, "Rivina"));
        step(1200, () -> { });
        shot(20, "map-rivina");

        // The card on Rivina, the one continent that publishes conquest points.
        step(5, () -> bringUnderCursor(MapTerritory::hasConquestPoints));
        step(20, () -> { });
        shot(20, "card-rivina-cp");

        // A right click anywhere asks where that is, and offers to copy it.
        step(5, () -> rightClickMiddle());
        step(20, () -> { });
        shot(20, "map-coordinates");
        step(5, () -> checkClipboard());

        // A capture, fed through the same merge a real refresh uses. The bubble is dismissed and
        // the whole continent framed first, or its own fading "Copied" would be the only thing the
        // two screenshots differ by — which is exactly what happened the first time.
        step(5, () -> dismissBubble());
        step(5, () -> click(client().currentScreen, "Fit continent"));
        step(60, () -> { });
        shot(20, "capture-before");
        step(5, () -> simulateCapture());
        step(20, () -> { });
        shot(20, "capture-after");

        // Nothing is usually declared on Loka, so the screenshot is taken over battles already
        // fought — real names, real turnouts, drawn by the same code.
        step(5, () -> com.betterloka.gui.FightManagerScreen.devShowRecentBattles = true);
        step(5, () -> client().setScreen(new com.betterloka.gui.FightManagerScreen(null)));
        step(600, () -> { });
        shot(20, "fight-manager");

        step(5, () -> {
            BetterLoka.LOGGER.info("SHOT done");
            client().scheduleStop();
        });

        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tick());
    }

    private static MinecraftClient client() {
        return MinecraftClient.getInstance();
    }

    private void step(int wait, Runnable action) {
        waits.add(wait);
        steps.add(action);
    }

    private void shot(int wait, String name) {
        step(wait, () -> ScreenshotRecorder.saveScreenshot(client().runDirectory, name + ".png",
                client().getFramebuffer(),
                1,
                message -> BetterLoka.LOGGER.info("SHOT {} -> {}", name, message.getString())));
    }

    private void tick() {
        if (index >= steps.size()) {
            return;
        }
        if (ticks++ < waits.get(index)) {
            return;
        }
        ticks = 0;
        Runnable action = steps.get(index++);
        try {
            action.run();
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.error("SHOT step {} failed", index - 1, e);
        }
    }

    private void createWorld() {
        MinecraftClient client = client();
        Screen old = client.currentScreen;
        CreateWorldScreen.show(client, () -> client.setScreen(old));
        if (!(client.currentScreen instanceof CreateWorldScreen create)) {
            BetterLoka.LOGGER.error("SHOT could not open the world creator");
            return;
        }
        var creator = create.getWorldCreator();
        creator.setWorldType(new net.minecraft.client.gui.screen.world.WorldCreator.WorldType(
                creator.getGeneratorOptionsHolder().getCombinedRegistryManager()
                        .getOrThrow(net.minecraft.registry.RegistryKeys.WORLD_PRESET)
                        .getOrThrow(WorldPresets.FLAT)));
        creator.setSeed("1");
        creator.setGenerateStructures(false);
        click(client.currentScreen, "Create New World");
    }

    private static void forEachChild(Screen screen, Consumer<Element> action) {
        if (screen == null) {
            return;
        }
        for (Element element : screen.children()) {
            action.accept(element);
        }
    }

    private static void click(Screen screen, String label) {
        // Collected first: pressing a tab button rebuilds the screen's widget list, and doing that
        // while iterating it throws.
        List<ButtonWidget> matches = new ArrayList<>();
        forEachChild(screen, element -> {
            if (element instanceof ButtonWidget button
                    && button.getMessage().getString().contains(label)) {
                matches.add(button);
            }
        });
        for (ButtonWidget button : matches) {
            button.onPress(null);
        }
    }

    private static void type(Screen screen, String text) {
        forEachChild(screen, element -> {
            if (element instanceof TextFieldWidget field) {
                field.setText(text);
            }
        });
    }

    /**
     * Drags the map until a chosen territory lies under the pointer.
     *
     * <p>The obvious way round is to move the pointer, and it cannot be done here: GLFW documents
     * {@code glfwSetCursorPos} as failing silently without input focus, and a headless X server
     * gives the window none — so every shot taken that way photographed whatever happened to sit
     * under the middle of the window, including three that looked convincing. Dragging goes through
     * the screen's own press, drag and release, so what is photographed is the real thing.
     */
    private static void bringUnderCursor(java.util.function.Predicate<MapTerritory> match) {
        MinecraftClient client = client();
        if (!(client.currentScreen instanceof LokaMapScreen map)) {
            return;
        }
        int[] point = map.devPointAt(match);
        if (point == null) {
            BetterLoka.LOGGER.warn("SHOT no territory matched");
            return;
        }
        double cursorX = client.mouse.getScaledX(client.getWindow());
        double cursorY = client.mouse.getScaledY(client.getWindow());
        map.mouseClicked(new Click(point[0], point[1], new MouseInput(0, 0)), false);
        map.mouseDragged(new Click(cursorX, cursorY, new MouseInput(0, 0)),
                cursorX - point[0], cursorY - point[1]);
        map.mouseReleased(new Click(cursorX, cursorY, new MouseInput(0, 0)));
    }

    private static void dismissBubble() {
        if (client().currentScreen instanceof LokaMapScreen map) {
            map.keyPressed(new net.minecraft.client.input.KeyInput(
                    org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0));
        }
    }

    private static void rightClickMiddle() {
        if (client().currentScreen instanceof LokaMapScreen map) {
            double x = client().mouse.getScaledX(client().getWindow());
            double y = client().mouse.getScaledY(client().getWindow());
            map.mouseClicked(new Click(x, y, new MouseInput(1, 0)), false);
        }
    }

    /** Presses the bubble's Copy button and reads the clipboard back. */
    private static void checkClipboard() {
        if (!(client().currentScreen instanceof LokaMapScreen map)) {
            return;
        }
        int[] point = map.devCopyButton();
        if (point == null) {
            BetterLoka.LOGGER.warn("SHOT no coordinate bubble to copy from");
            return;
        }
        map.mouseClicked(new Click(point[0], point[1], new MouseInput(0, 0)), false);
        BetterLoka.LOGGER.info("SHOT clipboard now holds: '{}'", client().keyboard.getClipboard());
    }

    /** Hands the store a captured territory and reports what it noticed. */
    private static void simulateCapture() {
        var store = BetterLokaClient.mapData();
        var snapshot = store.snapshot(Continent.RIVINA);
        List<MapTerritory> changed = new ArrayList<>();
        boolean done = false;
        for (MapTerritory territory : snapshot.territories()) {
            if (!done && territory.neutral()) {
                changed.add(new MapTerritory(territory.number(), territory.areaName(),
                        "BetterLoka Test Co", "BetterLoka Test Co", territory.mutator(),
                        territory.xs(), territory.zs(), territory.centerX(), territory.centerZ(),
                        territory.fillColor(), territory.strokeColor(), "territory_owned",
                        territory.conquestPoints(), territory.seat(), territory.bonus()));
                done = true;
            } else {
                changed.add(territory);
            }
        }
        var changes = store.devApply(Continent.RIVINA, changed);
        BetterLoka.LOGGER.info("SHOT capture simulated: {} of {} territories changed{}",
                changes.size(), changed.size(),
                changes.isEmpty() ? "" : " (" + changes.get(0).after().label() + ")");
    }

    private static void clickZoom(boolean in) {
        if (!(client().currentScreen instanceof LokaMapScreen map)) {
            return;
        }
        int[] point = in ? map.devZoomInButton() : map.devZoomOutButton();
        map.mouseClicked(new Click(point[0], point[1], new MouseInput(0, 0)), false);
    }

    private static void scroll(int amount) {
        Screen screen = client().currentScreen;
        if (screen != null) {
            screen.mouseScrolled(screen.width / 2.0, screen.height / 2.0, 0, amount);
        }
    }
}
