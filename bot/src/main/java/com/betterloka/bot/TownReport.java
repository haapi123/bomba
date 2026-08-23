package com.betterloka.bot;

import com.betterloka.api.ApiException;
import com.betterloka.api.EldritchApi;
import com.betterloka.api.LokaApi;
import com.betterloka.api.model.EldritchStats;
import com.betterloka.api.model.LokaPlayer;
import com.betterloka.api.model.LokaTown;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Everything {@code /sprawdz} reports about one town: when it was founded, who is in it, and how
 * recently each of them has been seen doing anything.
 *
 * <p>The point of the command is guessing when a town is about to be deleted, which turns on whether
 * its people still play. Loka publishes no last-login field anywhere — see {@link #lastSeen} — so
 * this assembles the best lower bound its API and EldritchBot will answer for, and the embed says
 * which signal each date came from rather than passing it off as a login.
 */
public final class TownReport {
    /**
     * How many members are looked up.
     *
     * <p>Each one is three requests, one of them a 30 KB page, so a fifty-person town would be a
     * megabyte and a half and a long wait. The report says how many it left out.
     */
    private static final int MAX_MEMBERS = 40;

    private final LokaApi loka;
    private final EldritchApi eldritch;

    public TownReport(LokaApi loka, EldritchApi eldritch) {
        this.loka = loka;
        this.eldritch = eldritch;
    }

    /**
     * One member of the town.
     *
     * @param joinedLoka  when the account first appeared on Loka, from its identity ObjectID
     * @param lastFight   their most recent Conquest fight, per EldritchBot
     * @param lastListing when they last put something on the market, per Loka
     */
    public record Member(String name, String rank, boolean owner, boolean subOwner,
                         Instant joinedLoka, Instant lastFight, Instant lastListing) {

        /**
         * The most recent moment this person is known to have been on the server.
         *
         * <p>A lower bound, not a last login: somebody who plays every day but neither fights nor
         * trades leaves no trace in anything Loka publishes.
         */
        public Instant lastSeen() {
            if (lastFight == null) {
                return lastListing;
            }
            if (lastListing == null) {
                return lastFight;
            }
            return lastFight.isAfter(lastListing) ? lastFight : lastListing;
        }

        /** Which record the {@link #lastSeen()} date came from, for the report to name. */
        public String lastSeenSource() {
            Instant seen = lastSeen();
            if (seen == null) {
                return null;
            }
            return seen.equals(lastFight) ? "fight" : "market";
        }
    }

    /**
     * @param members      the roster, owner first, then sub-owners, then everybody else
     * @param skipped      members not looked up because the roster is longer than {@link #MAX_MEMBERS}
     */
    public record Report(LokaTown town, List<Member> members, int skipped) {

        /** The newest activity anywhere in the town — the whole roster's clock. */
        public Instant lastActive() {
            Instant newest = null;
            for (Member member : members) {
                Instant seen = member.lastSeen();
                if (seen != null && (newest == null || seen.isAfter(newest))) {
                    newest = seen;
                }
            }
            return newest;
        }

        /** How many members have shown no sign of life in the given number of days. */
        public long quietFor(int days) {
            Instant cutoff = Instant.now().minusSeconds(days * 86400L);
            return members.stream()
                    .filter(member -> member.lastSeen() == null || member.lastSeen().isBefore(cutoff))
                    .count();
        }
    }

    /**
     * Builds the report.
     *
     * @return the report, or {@code null} if Loka has no town by that name
     */
    public Report build(String townName) throws ApiException {
        LokaTown town = loka.findTownByName(townName);
        if (town == null) {
            return null;
        }

        List<String> ids = new ArrayList<>(town.memberIds());
        int skipped = Math.max(0, ids.size() - MAX_MEMBERS);
        if (skipped > 0) {
            ids = ids.subList(0, MAX_MEMBERS);
        }

        List<CompletableFuture<Member>> tasks = new ArrayList<>(ids.size());
        for (String identityId : ids) {
            tasks.add(CompletableFuture.supplyAsync(
                    () -> member(town, identityId), eldritch.bulkExecutor()));
        }

        List<Member> members = new ArrayList<>(tasks.size());
        for (CompletableFuture<Member> task : tasks) {
            try {
                Member member = task.join();
                if (member != null) {
                    members.add(member);
                }
            } catch (CompletionException e) {
                // One unreachable member should not lose the other thirty-nine.
                continue;
            }
        }

        // Owner first, then sub-owners, then the rest newest-seen first: the officers are who
        // decides whether a town survives, and the activity order says how likely that is.
        members.sort(Comparator
                .comparing(Member::owner).reversed()
                .thenComparing(Comparator.comparing(Member::subOwner).reversed())
                .thenComparing(Member::lastSeen,
                        Comparator.nullsLast(Comparator.reverseOrder())));

        return new Report(town, List.copyOf(members), skipped);
    }

    private Member member(LokaTown town, String identityId) {
        LokaPlayer player;
        try {
            player = loka.findPlayerByIdentity(identityId);
        } catch (ApiException e) {
            return null;
        }
        if (player == null || player.name() == null) {
            return null;
        }

        Instant listing = null;
        try {
            listing = loka.lastMarketListing(identityId);
        } catch (ApiException e) {
            // Optional: the fight date alone still makes a usable row.
        }

        return new Member(player.name(), player.rank(),
                identityId.equals(town.ownerId()), town.subOwnerIds().contains(identityId),
                player.firstSeen(), lastFight(player), listing);
    }

    /** EldritchBot files careers under whatever the player was called at their last fight. */
    private Instant lastFight(LokaPlayer player) {
        EldritchStats stats;
        try {
            stats = eldritch.fetchStats(player.name());
        } catch (ApiException byName) {
            if (!byName.notFound() || player.uuid() == null) {
                return null;
            }
            try {
                stats = eldritch.fetchStats(undashed(player.uuid()));
            } catch (ApiException byUuid) {
                return null;
            }
        }
        return parseFightDate(stats.lastFight());
    }

    private static String undashed(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    /** EldritchBot writes dates as {@code Wed Aug 12 2026}. */
    private static final DateTimeFormatter FIGHT_DATE =
            DateTimeFormatter.ofPattern("EEE MMM d yyyy", Locale.ENGLISH);

    static Instant parseFightDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(text.trim(), FIGHT_DATE);
            return date.atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
