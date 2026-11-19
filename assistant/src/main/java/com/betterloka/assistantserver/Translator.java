package com.betterloka.assistantserver;

import java.util.List;

/**
 * Translates lines of chat for the mod's Translator. Behind an interface, like {@link Answerer}, so
 * the tests can stand in for Claude.
 */
interface Translator {
    /** One line to translate; {@code source} is a language code, or {@code auto} to detect it. */
    record Item(String text, String source) {
    }

    /** One line translated, and the language it was written in (an ISO 639-1 code, or {@code und}). */
    record Result(String text, String source) {
    }

    /** A batch translated, in the order asked, with what it cost. */
    record Batch(List<Result> results, String model, Pricing.Usage usage, double costUsd) {
    }

    /**
     * @param target the language to translate into, as a code ({@code pl}, {@code en}, {@code zh-CN})
     * @param userId an opaque id for the player, never their name
     */
    Batch translate(List<Item> items, String target, String userId) throws Answerer.Failure;
}
