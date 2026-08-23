package com.betterloka;

import com.betterloka.gui.LokaGrinderScreen;
import com.betterloka.gui.PlayerFinderScreen;
import com.betterloka.gui.TownFinderScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
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

        step(5, () -> client().setScreen(new com.betterloka.gui.LokaMapScreen(null)));
        step(200, () -> { });
        // Park the cursor over the middle of the map so the hover card is on screen for the shot.
        step(5, () -> centreCursor());
        step(20, () -> { });
        shot(20, "map-kalros");
        step(5, () -> click(client().currentScreen, "Rivina"));
        step(200, () -> { });
        step(5, () -> centreCursor());
        shot(20, "map-rivina");

        // A waypoint a short walk away, then out to the world to see it drawn.
        step(5, () -> {
            var player = client().player;
            if (player != null) {
                com.betterloka.BetterLokaClient.map().toggle(new com.betterloka.map.Waypoint(
                        "Ice Wastes 119", "lilboi",
                        player.getX() + 180, player.getY(), player.getZ() + 60, 0x3AB3DA));
            }
            client().setScreen(null);
        });
        step(30, () -> { });
        shot(20, "waypoint-world");

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

    /** Moves the real cursor, which is what a screen reads its hover position from. */
    private static void centreCursor() {
        MinecraftClient client = client();
        if (client.currentScreen == null) {
            return;
        }
        double scale = client.getWindow().getScaleFactor();
        org.lwjgl.glfw.GLFW.glfwSetCursorPos(client.getWindow().getHandle(),
                client.currentScreen.width / 2.0 * scale,
                client.currentScreen.height * 0.33 * scale);
    }

    private static void scroll(int amount) {
        Screen screen = client().currentScreen;
        if (screen != null) {
            screen.mouseScrolled(screen.width / 2.0, screen.height / 2.0, 0, amount);
        }
    }
}
