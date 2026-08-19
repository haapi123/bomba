package com.betterloka.gui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/** Date and "how long ago" rendering for the GUI. */
public final class TimeFormat {
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault());

    private TimeFormat() {
    }

    public static String date(Instant instant) {
        return instant == null ? "—" : DATE.format(instant);
    }

    /** Compact age of a timestamp, e.g. {@code 3d ago}. */
    public static String ago(long epochMillis) {
        if (epochMillis <= 0) {
            return "—";
        }
        long millis = System.currentTimeMillis() - epochMillis;
        if (millis < 0) {
            return "now";
        }
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis);
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        if (hours < 24) {
            return hours + "h ago";
        }
        long days = TimeUnit.MILLISECONDS.toDays(millis);
        if (days < 30) {
            return days + "d ago";
        }
        if (days < 365) {
            return (days / 30) + "mo ago";
        }
        return (days / 365) + "y ago";
    }
}
