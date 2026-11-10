package com.betterloka.assistantserver;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

/**
 * Money and per-player counts, kept in {@code data/ledger.json} so a restart forgets nothing.
 *
 * <p>The month's budget is a hard ceiling. Without a hard daily cap, a day may spend up to
 * {@code dailyBurst} times its even share of what was left of the month when it began, so one
 * busy evening cannot empty the month and leave the rest of it silent.
 */
final class Ledger {
    enum Verdict { OK, PLAYER_LIMIT, BUDGET_DAY, BUDGET_MONTH }

    static final class State {
        String month = "";
        double monthUsd;
        String day = "";
        double dayUsd;
        double totalUsd;
        long totalQuestions;
        Map<String, PlayerDay> players = new HashMap<>();
    }

    static final class PlayerDay {
        String day = "";
        int questions;
        int proposals;
    }

    private final Path file;
    private final ServerConfig config;
    private final Clock clock;
    private final State state;

    Ledger(Path file, ServerConfig config, Clock clock) {
        this.file = file;
        this.config = config;
        this.clock = clock;
        State loaded = Json.read(file, State.class, State::new);
        if (loaded.players == null) {
            loaded.players = new HashMap<>();
        }
        this.state = loaded;
    }

    synchronized Verdict canAsk(String uuid, boolean admin) {
        roll();
        if (state.monthUsd >= config.monthlyBudgetUsd) {
            return Verdict.BUDGET_MONTH;
        }
        if (state.dayUsd >= allowedToday()) {
            return Verdict.BUDGET_DAY;
        }
        if (!admin && player(uuid).questions >= config.questionsPerDay) {
            return Verdict.PLAYER_LIMIT;
        }
        return Verdict.OK;
    }

    /** Counts a question that reached the model, and what it cost. */
    synchronized void recordQuestion(String uuid, double usd) {
        roll();
        player(uuid).questions++;
        state.totalQuestions++;
        spend(usd);
    }

    /** Spending that is nobody's question, such as building the knowledge base. */
    synchronized void spend(double usd) {
        roll();
        state.monthUsd += usd;
        state.dayUsd += usd;
        state.totalUsd += usd;
        save();
    }

    synchronized boolean tryProposal(String uuid, boolean admin) {
        roll();
        PlayerDay day = player(uuid);
        if (!admin && day.proposals >= config.proposalsPerDay) {
            return false;
        }
        day.proposals++;
        save();
        return true;
    }

    synchronized int questionsLeft(String uuid, boolean admin) {
        roll();
        return admin ? Integer.MAX_VALUE : Math.max(0, config.questionsPerDay - player(uuid).questions);
    }

    /** The most today may spend. */
    synchronized double allowedToday() {
        roll();
        double share = Double.MAX_VALUE;
        if (config.dailyBurst > 0) {
            LocalDate today = LocalDate.now(clock);
            int daysLeft = today.lengthOfMonth() - today.getDayOfMonth() + 1;
            double leftAtDawn = config.monthlyBudgetUsd - (state.monthUsd - state.dayUsd);
            share = Math.max(0, leftAtDawn) / daysLeft * config.dailyBurst;
        }
        if (config.dailyBudgetUsd > 0) {
            share = Math.min(share, config.dailyBudgetUsd);
        }
        return share;
    }

    synchronized double monthUsd() {
        roll();
        return state.monthUsd;
    }

    synchronized double dayUsd() {
        roll();
        return state.dayUsd;
    }

    synchronized long totalQuestions() {
        return state.totalQuestions;
    }

    private PlayerDay player(String uuid) {
        String today = LocalDate.now(clock).toString();
        PlayerDay day = state.players.computeIfAbsent(uuid, key -> new PlayerDay());
        if (!today.equals(day.day)) {
            day.day = today;
            day.questions = 0;
            day.proposals = 0;
        }
        return day;
    }

    private void roll() {
        LocalDate today = LocalDate.now(clock);
        String month = YearMonth.from(today).toString();
        if (!month.equals(state.month)) {
            state.month = month;
            state.monthUsd = 0;
        }
        if (!today.toString().equals(state.day)) {
            state.day = today.toString();
            state.dayUsd = 0;
            state.players.values().removeIf(day -> !state.day.equals(day.day));
        }
    }

    private void save() {
        Json.write(file, state);
    }
}
