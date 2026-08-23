package com.betterloka.towns;

import com.betterloka.BetterLoka;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the {@code /town info} panel while it is open, and remembers what it said.
 *
 * <p>Loka deletes a town once it has gone a month with no active members, and the active count is
 * published nowhere: it is not on the town record, not on a member entry, and not behind any search
 * endpoint — every field of both was checked. The one place it exists is the panel {@code /town
 * info} opens, as a line of item lore.
 *
 * <p>So this watches for that panel being open and reads it. Nothing is sent: the mod never runs the
 * command, it only looks at a screen the player opened themselves, which is the same thing the
 * player is looking at.
 */
public final class TownInfoReader {
    /** {@code Members: 69 (0 active)} — the line the whole feature exists for. */
    private static final Pattern MEMBERS =
            Pattern.compile("Members:\\s*(\\d+)\\s*(?:\\(\\s*(\\d+)\\s*active\\s*\\))?",
                    Pattern.CASE_INSENSITIVE);

    /** {@code Owner: bleood123abc}, used to tell a town panel from any other lore that has members. */
    private static final Pattern OWNER = Pattern.compile("Owner:\\s*(\\S+)", Pattern.CASE_INSENSITIVE);

    /** How often the open screen is inspected. Cheap, but there is no reason to do it every frame. */
    private static final int TICKS_BETWEEN_SCANS = 10;

    private final TownActivityStore store;
    private int ticks;

    /** The screen last read, so a panel left open is not re-read every half second. */
    private Screen lastScanned;

    public TownInfoReader(TownActivityStore store) {
        this.store = store;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++ticks < TICKS_BETWEEN_SCANS) {
                return;
            }
            ticks = 0;
            scan(client);
        });
    }

    private void scan(MinecraftClient client) {
        Screen screen = client == null ? null : client.currentScreen;
        if (!(screen instanceof HandledScreen<?> handled)) {
            lastScanned = null;
            return;
        }
        if (screen == lastScanned) {
            return;
        }
        lastScanned = screen;

        // The panel's title is the town's name; the lore lives on one of the items inside it.
        String title = screen.getTitle() == null ? null : screen.getTitle().getString();
        for (Slot slot : handled.getScreenHandler().slots) {
            TownInfoReading reading = read(slot.getStack(), title);
            if (reading != null) {
                if (store.record(reading)) {
                    BetterLoka.LOGGER.info("Read {}: {} members, {} active", reading.townName(),
                            reading.members(), reading.hasActive() ? reading.active() : "?");
                }
                return;
            }
        }
    }

    /**
     * @param fallbackName the screen title, used when the panel's own lore does not name the town
     * @return the reading, or {@code null} if this item is not a town info panel
     */
    static TownInfoReading read(ItemStack stack, String fallbackName) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null || lore.lines().isEmpty()) {
            return null;
        }

        List<String> lines = new ArrayList<>(lore.lines().size());
        for (Text line : lore.lines()) {
            lines.add(line.getString());
        }
        return parse(lines, name(stack, fallbackName));
    }

    private static String name(ItemStack stack, String fallbackName) {
        Text custom = stack.get(DataComponentTypes.CUSTOM_NAME);
        String named = custom == null ? null : custom.getString();
        if (named != null && !named.isBlank()) {
            // "Duskfall — Neutral": the panel puts the town's standing after a dash.
            return named.split("[—–-]")[0].trim();
        }
        return fallbackName == null ? null : fallbackName.trim();
    }

    /**
     * Pulls the counts out of the panel's lore.
     *
     * <p>Requires an owner line as well as a members line. Loka has several panels that list members
     * — an alliance's, for one — and recording one of those under a town's name would put a number
     * in the history that Loka's deletion timer has nothing to do with.
     */
    static TownInfoReading parse(List<String> loreLines, String townName) {
        if (townName == null || townName.isBlank()) {
            return null;
        }
        boolean hasOwner = false;
        int members = -1;
        int active = -1;

        for (String line : loreLines) {
            String plain = strip(line);
            if (OWNER.matcher(plain).find()) {
                hasOwner = true;
            }
            Matcher matcher = MEMBERS.matcher(plain);
            if (matcher.find()) {
                members = Integer.parseInt(matcher.group(1));
                active = matcher.group(2) == null ? -1 : Integer.parseInt(matcher.group(2));
            }
        }

        if (!hasOwner || members < 0) {
            return null;
        }
        return new TownInfoReading(townName, members, active, System.currentTimeMillis());
    }

    /** Loka colours the panel with section signs, which survive {@code getString}. */
    private static String strip(String line) {
        return line == null ? "" : line.replaceAll("§.", "").trim();
    }
}
