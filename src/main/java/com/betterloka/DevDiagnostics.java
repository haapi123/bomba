package com.betterloka;

import com.betterloka.gui.FightManagerScreen;
import com.betterloka.gui.LokaMarketScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Development-only driver that reproduces the reported stall and prints numbers for it. Enabled
 * with {@code BETTERLOKA_DIAG=1}.
 *
 * <p>Reads the screens' own fields by reflection rather than through the rendering, because the
 * question is what state a screen is actually in — whether a view is loading, whether its data ever
 * arrived, whether an answer landed in a view that did not ask for it — and a screenshot of an empty
 * panel cannot tell those apart.
 */
public final class DevDiagnostics {
    private final List<Runnable> steps = new ArrayList<>();
    private final List<Integer> waits = new ArrayList<>();
    private int index;
    private int ticks;

    /** When the current timed wait began, for measuring how long a view takes to fill. */
    private long timerNanos;

    public static void register() {
        new DevDiagnostics().start();
    }

    private void start() {
        step(80, () -> { });
        step(5, this::createWorld);
        step(300, () -> { });

        step(1, () -> log("baseline, no screen open"));
        step(40, () -> { });
        step(1, () -> fps("baseline, no screen open"));

        // A clean Fight Manager refresh, before the Market has queued anything.
        step(2, () -> client().setScreen(new FightManagerScreen(null)));
        step(1, this::startTimer);
        step(2, () -> { });
        step(1, () -> fightState("2 ticks after opening"));
        step(60, () -> { });
        step(1, () -> fightState("3 s after opening (control)"));
        step(1, () -> fps("fight manager, idle"));

        // One honest Market load, so the seller fan-out this screen really produces is measured
        // rather than guessed at.
        step(2, () -> client().setScreen(new LokaMarketScreen(null)));
        step(2, () -> click(client().currentScreen, "Deals"));
        step(1, this::startTimer);
        step(20, () -> { });
        step(1, () -> { marketState("1 s into a clean Deals load"); pools("1 s in"); });
        step(100, () -> { });
        step(1, () -> { marketState("6 s into a clean Deals load"); pools("6 s in"); });
        step(200, () -> { });
        step(1, () -> { marketState("16 s into a clean Deals load"); pools("16 s in"); });
        step(1, () -> dumpBetterLokaThreads("16 s into a clean Deals load"));
        step(1, () -> fps("market, deals loading"));

        // Scenario B measured where it actually hurts: a refresh submitted while that fan-out is
        // still draining the shared pool.
        step(2, () -> client().setScreen(new FightManagerScreen(null)));
        step(1, this::startTimer);
        step(2, () -> { });
        step(1, () -> fightState("2 ticks after opening, during the Market fan-out"));
        step(60, () -> { });
        step(1, () -> fightState("3 s after opening, during the Market fan-out"));
        step(200, () -> { });
        step(1, () -> fightState("13 s after opening, during the Market fan-out"));
        step(400, () -> { });
        step(1, () -> fightState("33 s after opening, during the Market fan-out"));
        step(1, () -> pools("33 s in"));

        step(400, () -> { });
        step(1, () -> { log("after the fan-out has drained"); pools("drained"); });

        // Scenario A twenty times: search, then switch view before the answer comes back.
        for (int round = 1; round <= 20; round++) {
            int number = round;
            step(2, () -> client().setScreen(new LokaMarketScreen(null)));
            step(2, () -> {
                type(client().currentScreen, "Diamond Sword");
                click(client().currentScreen, "Search");
            });
            // Deliberately no wait: switching before the search lands is the reported trigger.
            step(1, () -> click(client().currentScreen, "Deals"));
            step(60, () -> { });
            step(1, () -> {
                marketState("scenario A round " + number);
                if (number % 5 == 0) {
                    log("after scenario A round " + number);
                    pools("after round " + number);
                    fps("after scenario A round " + number);
                }
            });
        }

        step(1, () -> log("after 20 rounds of scenario A"));
        step(1, () -> dumpBetterLokaThreads("after 20 rounds of scenario A"));

        step(2, () -> client().setScreen(null));
        step(60, () -> { });
        step(1, () -> fps("no screen open, after everything"));
        step(1, () -> log("end"));

        step(5, () -> {
            BetterLoka.LOGGER.info("DIAG done");
            client().scheduleStop();
        });

        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tick());
    }

    private void startTimer() {
        timerNanos = System.nanoTime();
    }

    private long sinceTimerMillis() {
        return (System.nanoTime() - timerNanos) / 1_000_000;
    }

    /** What the Market screen actually holds, as opposed to what it happens to be drawing. */
    private void marketState(String label) {
        Screen screen = client().currentScreen;
        if (!(screen instanceof LokaMarketScreen market)) {
            BetterLoka.LOGGER.info("DIAG {}: market screen not open (is {})", label,
                    screen == null ? "null" : screen.getClass().getSimpleName());
            return;
        }
        BetterLoka.LOGGER.info(
                "DIAG {} [+{} ms]: tab={} loading={} snapshot={} results={} sellersResolved={} message={}",
                label, sinceTimerMillis(), field(market, "tab"), field(market, "loading"),
                field(market, "snapshot") == null ? "null" : "loaded",
                sizeOf(field(market, "results")), sizeOf(field(market, "sellers")),
                field(market, "message"));
    }

    private void fightState(String label) {
        Screen screen = client().currentScreen;
        if (!(screen instanceof FightManagerScreen fights)) {
            BetterLoka.LOGGER.info("DIAG {}: fight manager not open", label);
            return;
        }
        BetterLoka.LOGGER.info("DIAG {} [+{} ms]: loading={} rows={} message={}", label,
                sinceTimerMillis(), field(fights, "loading"), sizeOf(field(fights, "rows")),
                field(fights, "message"));
    }

    /** How much work is sitting in each shared pool — the queue a new screen has to get through. */
    private static void pools(String label) {
        BetterLoka.LOGGER.info("DIAG pools {}: api={} bulk={}", label,
                describe("executor"), describe("bulkExecutor"));
    }

    private static String describe(String fieldName) {
        Object transport = transport();
        if (transport == null) {
            return "?";
        }
        try {
            Field field = transport.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            if (field.get(transport) instanceof ThreadPoolExecutor pool) {
                return "active=" + pool.getActiveCount() + " queued=" + pool.getQueue().size()
                        + " done=" + pool.getCompletedTaskCount();
            }
            return "not a ThreadPoolExecutor";
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "<" + e.getClass().getSimpleName() + ">";
        }
    }

    private static Object transport() {
        try {
            Field field = BetterLokaClient.class.getDeclaredField("transport");
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Where the mod's own threads are stopped, and on what. */
    private static void dumpBetterLokaThreads(String label) {
        BetterLoka.LOGGER.info("DIAG thread dump {}:", label);
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            String name = thread.getName();
            if (!name.startsWith("BetterLoka-") && !name.startsWith("betterloka-")) {
                return;
            }
            String top = "";
            for (StackTraceElement element : stack) {
                // The first frame in the mod's own code says more than the sleep it is sitting in.
                if (element.getClassName().startsWith("com.betterloka")) {
                    top = "  <- " + element;
                    break;
                }
            }
            BetterLoka.LOGGER.info("DIAG   {} {} {}{}", name, thread.getState(),
                    stack.length == 0 ? "" : stack[0], top);
        });
    }

    private static void fps(String label) {
        BetterLoka.LOGGER.info("DIAG fps {}: {}", label, client().getCurrentFps());
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "<" + e.getClass().getSimpleName() + ">";
        }
    }

    private static String sizeOf(Object value) {
        if (value instanceof List<?> list) {
            return String.valueOf(list.size());
        }
        if (value instanceof Map<?, ?> map) {
            return String.valueOf(map.size());
        }
        return String.valueOf(value);
    }

    private static void log(String label) {
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        Map<String, Integer> threads = new TreeMap<>();
        int total = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            total++;
            String name = thread.getName();
            if (name.startsWith("BetterLoka-") || name.startsWith("betterloka-")) {
                threads.merge(name.replaceAll("\\d+$", ""), 1, Integer::sum);
            }
        }
        Object transport = transport();
        String requests = "?";
        if (transport != null) {
            try {
                requests = String.valueOf(
                        transport.getClass().getMethod("requestCount").invoke(transport));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Left as "?" — a missing counter is not worth failing a diagnostic run over.
            }
        }
        BetterLoka.LOGGER.info("DIAG {}: modThreads={} allThreads={} heap={} MB httpRequests={}",
                label, threads, total, usedMb, requests);
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
            BetterLoka.LOGGER.error("DIAG step {} failed", index - 1, e);
        }
    }

    private void step(int wait, Runnable action) {
        waits.add(wait);
        steps.add(action);
    }

    private static MinecraftClient client() {
        return MinecraftClient.getInstance();
    }

    private void createWorld() {
        MinecraftClient client = client();
        Screen old = client.currentScreen;
        net.minecraft.client.gui.screen.world.CreateWorldScreen.show(client, () -> client.setScreen(old));
        if (!(client.currentScreen
                instanceof net.minecraft.client.gui.screen.world.CreateWorldScreen create)) {
            BetterLoka.LOGGER.error("DIAG could not open the world creator");
            return;
        }
        var creator = create.getWorldCreator();
        creator.setWorldType(new net.minecraft.client.gui.screen.world.WorldCreator.WorldType(
                creator.getGeneratorOptionsHolder().getCombinedRegistryManager()
                        .getOrThrow(net.minecraft.registry.RegistryKeys.WORLD_PRESET)
                        .getOrThrow(net.minecraft.world.gen.WorldPresets.FLAT)));
        creator.setSeed("1");
        creator.setGenerateStructures(false);
        click(client.currentScreen, "Create New World");
    }

    private static void click(Screen screen, String label) {
        if (screen == null) {
            return;
        }
        // Collected first: pressing a tab button rebuilds the screen's widget list.
        List<ButtonWidget> matches = new ArrayList<>();
        for (Element element : screen.children()) {
            if (element instanceof ButtonWidget button
                    && button.getMessage().getString().contains(label)) {
                matches.add(button);
            }
        }
        for (ButtonWidget button : matches) {
            button.onPress(null);
        }
    }

    private static void type(Screen screen, String text) {
        if (screen == null) {
            return;
        }
        for (Element element : screen.children()) {
            if (element instanceof TextFieldWidget field) {
                field.setText(text);
            }
        }
    }

}
