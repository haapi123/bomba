package com.betterloka.assistantserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The duel statistics against a real MySQL or MariaDB, through the driver the server ships with.
 * Run with BETTERLOKA_DB_HOST (host:port), BETTERLOKA_DB_NAME, BETTERLOKA_DB_USER and
 * BETTERLOKA_DB_PASSWORD set; it removes the rows it wrote.
 */
@EnabledIfEnvironmentVariable(named = "BETTERLOKA_DB_HOST", matches = ".+")
class DuelDatabaseLiveTest {
    @Test
    void keepsAndReadsDuelsInARealDatabase() throws Exception {
        DuelDatabase.Settings settings = new DuelDatabase.Settings();
        settings.host = System.getenv("BETTERLOKA_DB_HOST");
        settings.name = System.getenv("BETTERLOKA_DB_NAME");
        settings.user = System.getenv("BETTERLOKA_DB_USER");
        settings.password = System.getenv().getOrDefault("BETTERLOKA_DB_PASSWORD", "");
        DuelStore.Connections connections = DuelDatabase.connections(settings);
        DuelStore store = new DuelStore(connections);
        store.createTables();
        store.createTables();
        assertTrue(store.reachable());

        String reporter = UUID.randomUUID().toString();
        String opponent = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Sessions.Session player = new Sessions.Session(reporter, "LiveTest");
        try {
            var body = DuelStatsTest.report(now - 5000, "WON", 6, 10, 3, 70);
            body.getAsJsonObject("opponent").addProperty("uuid", opponent);
            assertTrue(store.save(DuelService.parse(player, body, now)));
            assertFalse(store.save(DuelService.parse(player, body, now)), "a duplicate is told apart");
            DuelStore.Stats stats = store.stats(opponent);
            assertEquals(1, stats.observations());
            assertEquals(70, stats.pingAvg());
            assertEquals(60, stats.accuracy());
            assertEquals(50, stats.critShare());
            assertEquals(1, store.reportedSince(reporter, now - 60_000));
        } finally {
            try (Connection connection = connections.open();
                 PreparedStatement delete = connection.prepareStatement("DELETE FROM duel_sides WHERE reporter = ?")) {
                delete.setString(1, reporter);
                delete.executeUpdate();
            }
        }
    }
}
