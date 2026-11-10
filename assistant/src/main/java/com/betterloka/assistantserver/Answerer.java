package com.betterloka.assistantserver;

import java.util.List;

/** Turns a player's question and the knowledge base into an answer. */
interface Answerer {
    /** What the model made of the question. */
    enum Status {
        /** Answered from the knowledge base (or plain Minecraft). */
        ANSWERED,
        /** About Loka, but the knowledge base does not say: worth a player's answer. */
        UNKNOWN,
        /** Not about Loka or Minecraft. */
        OFF_TOPIC,
        /** The model declined to answer. */
        REFUSED
    }

    record Turn(String question, String answer) {
    }

    /**
     * @param language the game's language, such as {@code pl_pl}, for when the question itself does
     *                 not show one
     * @param userId   an opaque id for the player, never their name
     */
    record Question(String knowledge, List<Turn> history, String text, String language, String userId) {
    }

    record Reply(String text, Status status, String model, Pricing.Usage usage, double costUsd) {
    }

    /** Thrown when no answer could be had: the API is down, busy, or said something unreadable. */
    final class Failure extends Exception {
        private final boolean busy;

        Failure(String message, boolean busy, Throwable cause) {
            super(message, cause);
            this.busy = busy;
        }

        boolean busy() {
            return busy;
        }
    }

    Reply answer(Question question) throws Failure;
}
