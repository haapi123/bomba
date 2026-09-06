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
            type(client().currentScreen, "xPabloFights_YT");
            click(client().currentScreen, "Search");
        });
        step(400, () -> { });
        step(5, () -> scroll(-4000));
        shot(20, "player-finder-identity");

        step(5, () -> client().setScreen(new TownFinderScreen(null)));
        step(5, () -> {
            type(client().currentScreen, "Hilo");
            click(client().currentScreen, "Search");
        });
        step(300, () -> { });
        shot(20, "town-finder-founded");

        // The tutorial toast owns the top right and would sit over the map's card.
        step(5, () -> client().getToastManager().clear());
        step(5, () -> client().setScreen(new com.betterloka.gui.LokaMapScreen(null)));
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
                BetterLokaClient.map().seatOf(Continent.KALROS, territory) != null));
        step(20, () -> { });
        shot(20, "card-town-seat");

        step(5, () -> bringUnderCursor(territory -> territory.neutral()
                && BetterLokaClient.map().seatOf(Continent.KALROS, territory) == null));
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

        step(5, () -> click(client().currentScreen, "Balak"));
        step(1200, () -> { });
        shot(20, "map-balak");

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
