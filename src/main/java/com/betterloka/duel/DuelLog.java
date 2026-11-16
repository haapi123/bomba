package com.betterloka.duel;

import com.betterloka.data.JsonStore;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Every duel this mod has counted, kept on this computer.
 *
 * <p>Loka publishes ladders — this season's wins and losses, and each past season's final table —
 * but not who played whom. So the matches behind "your last five", the record between two players,
 * and somebody's usual ping exist only where the mod was there to see them: here. Nothing in it is
 * sent anywhere.
 */
public final class DuelLog {
    /** How many duels are kept; the oldest go first. */
    static final int KEEP = 300;

    /** One side of a duel, as counted. */
    public record Side(int swings, int hits, int crits, int sweeps, int combo) {
        static Side of(DuelStats.Side side) {
            return new Side(side.swings(), side.hits(), side.crits(), side.sweeps(), side.bestCombo());
        }

        /** Hits of swings, 0–100, or -1 when fewer swings were seen than hits (see {@link DuelStats.Side}). */
        public int accuracy() {
            if (swings < hits) {
                return -1;
            }
            return swings == 0 ? 0 : Math.round(hits * 100f / swings);
        }

        public int critShare() {
            return hits == 0 ? 0 : Math.round(crits * 100f / hits);
        }

        public int sweepShare() {
            return hits == 0 ? 0 : Math.round(sweeps * 100f / hits);
        }
    }

    /** A ping over one duel, or every number -1 when the server gave none. */
    public record Ping(int readings, int average, int max, int spikes, int up, int down, int biggest, double every) {
        public static final Ping NONE = new Ping(0, -1, -1, 0, 0, 0, 0, -1);

        static Ping of(PingTrace.Summary summary) {
            return summary == null ? NONE : new Ping(summary.readings(), summary.average(), summary.max(),
                    summary.spikeCount(), summary.up(), summary.down(), summary.biggest(), summary.everySeconds());
        }

        public boolean known() {
            return average >= 0;
        }
    }

    /**
     * One duel.
     *
     * @param id      its number in this log, for the chat line that opens it
     * @param self    the account that played it: an alt keeps its own record
     * @param result  {@code WON}, {@code LOST} or {@code UNKNOWN}
     */
    public record Entry(int id, long at, String selfName, String self, String opponentName, String opponent,
                        String result, long millis, Side you, Side them, Ping yourPing, Ping theirPing) {
        public DuelStats.Result outcome() {
            try {
                return DuelStats.Result.valueOf(result);
            } catch (RuntimeException unknown) {
                return DuelStats.Result.UNKNOWN;
            }
        }

        public UUID opponentUuid() {
            return parse(opponent);
        }

        public UUID selfUuid() {
            return parse(self);
        }

        private static UUID parse(String raw) {
            try {
                return raw == null ? null : UUID.fromString(raw);
            } catch (IllegalArgumentException bad) {
                return null;
            }
        }
    }

    /** Wins and losses between two players, and the matches behind them, newest first. */
    public record Record(int wins, int losses, List<Entry> matches) {
        public int games() {
            return wins + losses;
        }
    }

    private record Saved(int next, List<Entry> entries) {
    }

    private static final Type SAVED_TYPE = JsonStore.envelopeOf(new TypeToken<Saved>() {
    }.getType());

    private final JsonStore<Saved> disk;
    private final List<Entry> entries = new ArrayList<>();
    private int next = 1;
    private boolean loaded;

    /** @param file where the log is kept, or {@code null} to keep it only for this session */
    public DuelLog(Path file) {
        this.disk = file == null ? null : new JsonStore<>(file, SAVED_TYPE, Long.MAX_VALUE);
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Saved saved = disk == null ? null : disk.read();
        if (saved != null && saved.entries() != null) {
            entries.addAll(saved.entries());
            next = Math.max(saved.next(), entries.stream().mapToInt(Entry::id).max().orElse(0) + 1);
        }
    }

    /** Adds a finished duel and saves the log. @return the duel as kept, with its number */
    public synchronized Entry add(long at, String selfName, UUID self, String opponentName, UUID opponent,
                                  DuelStats.Summary summary, PingTrace.Summary yourPing, PingTrace.Summary theirPing) {
        load();
        Entry entry = new Entry(next++, at, selfName, self == null ? null : self.toString(), opponentName,
                opponent == null ? null : opponent.toString(), summary.result().name(), summary.millis(),
                Side.of(summary.you()), Side.of(summary.them()), Ping.of(yourPing), Ping.of(theirPing));
        entries.add(entry);
        while (entries.size() > KEEP) {
            entries.remove(0);
        }
        if (disk != null) {
            disk.write(new Saved(next, List.copyOf(entries)));
        }
        return entry;
    }

    public synchronized Entry byId(int id) {
        load();
        for (Entry entry : entries) {
            if (entry.id() == id) {
                return entry;
            }
        }
        return null;
    }

    /** The account's latest duels, newest first. */
    public synchronized List<Entry> recent(UUID self, int count) {
        load();
        List<Entry> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && out.size() < count; i--) {
            Entry entry = entries.get(i);
            if (self == null || self.equals(entry.selfUuid())) {
                out.add(entry);
            }
        }
        return out;
    }

    /** Every duel between the account and this opponent (by UUID, or by name with none), newest first. */
    public synchronized Record between(UUID self, UUID opponent, String opponentName) {
        load();
        int wins = 0;
        int losses = 0;
        List<Entry> matches = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (self != null && !self.equals(entry.selfUuid())) {
                continue;
            }
            if (!isOpponent(entry, opponent, opponentName)) {
                continue;
            }
            matches.add(entry);
            switch (entry.outcome()) {
                case WON -> wins++;
                case LOST -> losses++;
                default -> {
                }
            }
        }
        return new Record(wins, losses, matches);
    }

    /**
     * The average of a player's ping over the duels it was read in: the opponent's, or with
     * {@code opponent} the account's own UUID, the player's own.
     *
     * @return the average and how many duels it is of, or {@code null} with none
     */
    public synchronized int[] averagePing(UUID player, String name) {
        load();
        long sum = 0;
        int duels = 0;
        for (Entry entry : entries) {
            Ping ping = null;
            if (isOpponent(entry, player, name)) {
                ping = entry.theirPing();
            } else if (player != null && player.equals(entry.selfUuid())) {
                ping = entry.yourPing();
            }
            if (ping != null && ping.known()) {
                sum += ping.average();
                duels++;
            }
        }
        return duels == 0 ? null : new int[]{(int) Math.round((double) sum / duels), duels};
    }

    private static boolean isOpponent(Entry entry, UUID opponent, String name) {
        if (opponent != null && entry.opponentUuid() != null) {
            return opponent.equals(entry.opponentUuid());
        }
        return name != null && entry.opponentName() != null
                && entry.opponentName().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT));
    }
}
