package com.betterloka.assistantserver;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** One line per event on the console, which is what a panel or {@code journalctl} shows. */
final class Log {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Log() {
    }

    static void info(String message) {
        System.out.println(LocalDateTime.now().format(TIME) + " " + message);
    }

    static void warn(String message) {
        System.out.println(LocalDateTime.now().format(TIME) + " WARN " + message);
    }
}
