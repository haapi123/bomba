package com.betterloka;

import com.betterloka.gui.FightManagerScreen;
import com.betterloka.gui.LokaMarketScreen;
import com.betterloka.gui.PlayerFinderScreen;
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

        step(1, () -> heldPerContinent());
        step(1, () -> bonusesOnBalak());
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

        // A search, which is the whole Market now.
        step(2, () -> client().setScreen(new LokaMarketScreen(null)));
        step(2, () -> {
            type(client().currentScreen, "Diamond Sword");
            click(client().currentScreen, "Search");
        });
        step(1, this::startTimer);
        step(20, () -> { });
        step(1, () -> { marketState("1 s into a search"); pools("1 s in"); });
        step(100, () -> { });
        step(1, () -> { marketState("6 s into a search"); pools("6 s in"); });
        step(1, () -> dumpBetterLokaThreads("6 s into a search"));
        step(1, () -> fps("market, search loaded"));

        // A refresh submitted straight after the Market has been used, which is where the two
        // screens used to collide.
        step(2, () -> client().setScreen(new FightManagerScreen(null)));
        step(1, this::startTimer);
        step(2, () -> { });
        step(1, () -> fightState("2 ticks after opening, after using the Market"));
        step(60, () -> { });
        step(1, () -> fightState("3 s after opening, after using the Market"));
        step(200, () -> { });
        step(1, () -> fightState("13 s after opening, after using the Market"));
        step(400, () -> { });
        step(1, () -> fightState("33 s after opening, after using the Market"));
        step(1, () -> pools("33 s in"));

        step(400, () -> { });
        step(1, () -> { log("after everything has drained"); pools("drained"); });

        // Scenario A twenty times: search, then move the view before the answer comes back.
        for (int round = 1; round <= 20; round++) {
            int number = round;
            step(2, () -> client().setScreen(new LokaMarketScreen(null)));
            step(2, () -> {
                type(client().currentScreen, "Diamond Sword");
                click(client().currentScreen, "Search");
            });
            // Deliberately no wait: changing the order before the answer lands is the same
            // shape as the reported trigger — the view moves under an in-flight request.
            step(1, () -> click(client().currentScreen, "Oldest"));
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

        // Each order photographed with real offers on it, and the profile without the alts
        // section, so the screens are checked rather than assumed.
        step(2, () -> client().setScreen(new LokaMarketScreen(null)));
        step(2, () -> {
            type(client().currentScreen, "Diamond Sword");
            click(client().currentScreen, "Search");
        });
        step(120, () -> { });
        shot(20, "market-cheapest");
        step(2, () -> click(client().currentScreen, "Dearest"));
        step(20, () -> { });
        shot(20, "market-dearest");
        step(2, () -> click(client().currentScreen, "Newest"));
        step(20, () -> { });
        shot(20, "market-newest");
        step(2, () -> click(client().currentScreen, "Oldest"));
        step(20, () -> { });
        shot(20, "market-oldest");
        step(1, () -> marketState("after cycling every order"));
        step(2, () -> client().setScreen(new PlayerFinderScreen(null)));
        step(2, () -> {
            type(client().currentScreen, "haapi");
            click(client().currentScreen, "Search");
        });
        step(200, () -> { });
        step(1, () -> battleIndex("just after a search"));
        step(600, () -> { });
        step(1, () -> battleIndex("30 s later"));
        step(600, () -> { });
        step(1, () -> battleIndex("60 s later"));
        step(5, () -> scroll(-4000));
        shot(20, "player-finder-no-alts");
        step(5, () -> scroll(4000));
        step(5, () -> scroll(-14));
        shot(20, "player-finder-split");
        step(2, () -> click(client().currentScreen, "Month"));
        step(40, () -> { });
        shot(20, "player-finder-month");
        // A Balak hex that carries a bonus, which is the point of this round's map change.
        step(2, () -> client().setScreen(new com.betterloka.gui.LokaMapScreen(null)));
        step(5, () -> selectContinent("Balak"));
        step(60, () -> { });
        step(5, () -> bringUnderCursor(com.betterloka.map.MapTerritory::hasBonus));
        step(20, () -> { });
        shot(20, "map-balak-bonus");

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

    /** How many hexes each continent now reads as held, against what Loka draws as held. */
    private static void heldPerContinent() {
        for (com.betterloka.map.Continent continent : com.betterloka.map.Continent.values()) {
            var snapshot = BetterLokaClient.mapData().snapshot(continent);
            int held = 0;
            int seats = 0;
            var owners = new java.util.TreeSet<String>();
            for (var territory : snapshot.territories()) {
                if (!territory.neutral()) {
                    held++;
                    if (territory.owner() != null) {
                        owners.add(territory.owner());
                    }
                }
                if (territory.seat()) {
                    seats++;
                }
            }
            BetterLoka.LOGGER.info("DIAG map {}: {} territories, {} held, {} seats, holders={}",
                    continent.displayName(), snapshot.territories().size(), held, seats, owners);
        }
    }

    /** Which Balak hexes carry a bonus, and what the parser made of each. */
    private static void bonusesOnBalak() {
        var snapshot = BetterLokaClient.mapData().snapshot(com.betterloka.map.Continent.BALAK);
        int withBonus = 0;
        for (var territory : snapshot.territories()) {
            if (territory.hasBonus()) {
                withBonus++;
                BetterLoka.LOGGER.info("DIAG bonus #{} ({}): {}", territory.number(),
                        territory.neutral() ? "neutral" : territory.owner(), territory.bonus());
            }
        }
        BetterLoka.LOGGER.info("DIAG Balak bonuses: {} of {} hexes",
                withBonus, snapshot.territories().size());
    }

    /** Switches the map to a named continent by pressing its button. */
    private static void selectContinent(String name) {
        click(client().currentScreen, name);
    }

    /**
     * Drags the map until a hex the predicate accepts sits under the pointer.
     *
     * <p>The card describes what is hovered, and the pointer cannot be moved: GLFW documents
     * {@code glfwSetCursorPos} as failing silently without input focus, which a headless X server
     * never grants. So the map is moved instead, through the screen's own press, drag and release.
     */
    private static void bringUnderCursor(java.util.function.Predicate<com.betterloka.map.MapTerritory> match) {
        MinecraftClient client = client();
        if (!(client.currentScreen instanceof com.betterloka.gui.LokaMapScreen map)) {
            return;
        }
        int[] point = map.devPointAt(match);
        if (point == null) {
            BetterLoka.LOGGER.info("DIAG no territory matched");
            return;
        }
        double cursorX = client.mouse.getScaledX(client.getWindow());
        double cursorY = client.mouse.getScaledY(client.getWindow());
        map.mouseClicked(new net.minecraft.client.gui.Click(point[0], point[1],
                new net.minecraft.client.input.MouseInput(0, 0)), false);
        map.mouseDragged(new net.minecraft.client.gui.Click(cursorX, cursorY,
                new net.minecraft.client.input.MouseInput(0, 0)),
                cursorX - point[0], cursorY - point[1]);
        map.mouseReleased(new net.minecraft.client.gui.Click(cursorX, cursorY,
                new net.minecraft.client.input.MouseInput(0, 0)));
        BetterLoka.LOGGER.info("DIAG dragged a bonus hex under the pointer");
    }

    /** How far the battle archive has been read, and what it says about one player. */
    private static void battleIndex(String label) {
        var index = BetterLokaClient.battleIndex();
        var progress = index.progress();
        BetterLoka.LOGGER.info("DIAG battle index {}: sweeping={} indexed={}/{} ({}%)", label,
                progress.sweeping(), progress.battlesIndexed(), progress.battlesTotal(),
                progress.percent());
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
        BetterLoka.LOGGER.info("DIAG {} [+{} ms]: search={} sort={} suggestions={}",
                label, sinceTimerMillis(),
                slot(field(market, "searchSlot")), field(market, "sort"),
                slot(field(market, "suggestSlot")));
    }

    /**
     * One view's slot: what state it is in and what it holds.
     *
     * <p>The whole question the fix turns on is whether a view's own data is its own, so each slot
     * is read separately rather than through a shared field that no longer exists.
     */
    private static String slot(Object slot) {
        if (slot == null) {
            return "absent";
        }
        Object value = field(slot, "value");
        return field(slot, "state") + "/" + sizeOf(value);
    }

    private void fightState(String label) {
        Screen screen = client().currentScreen;
        if (!(screen instanceof FightManagerScreen fights)) {
            BetterLoka.LOGGER.info("DIAG {}: fight manager not open", label);
            return;
        }
        BetterLoka.LOGGER.info("DIAG {} [+{} ms]: slot={}", label, sinceTimerMillis(),
                slot(field(fights, "slot")));
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

    /** How much a slot holds, in one word. Never the value's own toString: a snapshot is enormous. */
    private static String sizeOf(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof List<?> list) {
            return list.size() + " rows";
        }
        if (value instanceof Map<?, ?> map) {
            return map.size() + " entries";
        }
        if (value instanceof Boolean || value instanceof Number || value instanceof Enum<?>) {
            return String.valueOf(value);
        }
        return value.getClass().getSimpleName();
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
        BetterLoka.LOGGER.info(
                "DIAG {}: modThreads={} allThreads={} heap={} MB httpRequests={} cancelled={}",
                label, threads, total, usedMb, counter("requestCount"), counter("cancelledCount"));
    }

    private static String counter(String method) {
        Object transport = transport();
        if (transport == null) {
            return "?";
        }
        try {
            return String.valueOf(transport.getClass().getMethod(method).invoke(transport));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A missing counter is not worth failing a diagnostic run over.
            return "?";
        }
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

    private void shot(int wait, String name) {
        step(wait, () -> net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(
                client().runDirectory, name + ".png", client().getFramebuffer(), 1,
                message -> BetterLoka.LOGGER.info("DIAG shot {} -> {}", name, message.getString())));
    }

    /** Scrolls the open screen, for a profile whose interesting part is below the fold. */
    private static void scroll(int amount) {
        Screen screen = client().currentScreen;
        if (screen != null) {
            screen.mouseScrolled(client().getWindow().getScaledWidth() / 2.0,
                    client().getWindow().getScaledHeight() / 2.0, 0, amount);
        }
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
