package com.betterloka.assistantserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * For trying the in-game screen without an API key or spending anything ({@code --demo}): answers
 * with the knowledge base lines that share the most words with the question. Not clever, and it
 * says so in every answer.
 */
final class DemoAnswerer implements Answerer {
    private static final Set<String> SKIP = Set.of("the", "a", "an", "is", "are", "how", "what", "where", "when",
            "do", "i", "to", "of", "in", "on", "and", "or", "jak", "gdzie", "co", "czy", "jest", "sa", "na", "w",
            "z", "sie", "mam", "ile", "kiedy");

    @Override
    public Reply answer(Question question) {
        Set<String> words = words(question.text());
        // Units: a heading and the lines under it, so "## How much is a town?" finds its answer.
        List<String> best = List.of();
        int bestScore = 0;
        String heading = "";
        List<String> unit = new ArrayList<>();
        List<String> lines = new ArrayList<>(List.of(question.knowledge().split("\n")));
        lines.add("#");
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.startsWith("#") || stripped.startsWith("<")) {
                for (String each : unit) {
                    int score = overlap(words, heading) + overlap(words, each) * 2;
                    if (score > bestScore) {
                        bestScore = score;
                        best = List.of(each);
                    }
                }
                if (!unit.isEmpty() && overlap(words, heading) > 0) {
                    int score = overlap(words, heading) * 2 + 1;
                    if (score > bestScore) {
                        bestScore = score;
                        best = unit.subList(0, Math.min(3, unit.size()));
                    }
                }
                heading = stripped.replaceFirst("^#+", "");
                unit = new ArrayList<>();
            } else if (!stripped.isEmpty() && !stripped.startsWith("_")) {
                unit.add(stripped);
            }
        }
        Pricing.Usage none = new Pricing.Usage(0, 0, 0, 0, 0);
        if (bestScore == 0) {
            return new Reply("(demo) I don't know that yet.", Status.UNKNOWN, "demo", none, 0);
        }
        return new Reply("(demo) " + String.join("\n", best), Status.ANSWERED, "demo", none, 0);
    }

    private static int overlap(Set<String> words, String text) {
        int score = 0;
        for (String word : words(text)) {
            if (words.contains(word)) {
                score++;
            }
        }
        return score;
    }

    private static Set<String> words(String text) {
        Set<String> words = new java.util.HashSet<>();
        for (String word : Community.normalize(text).split(" ")) {
            if (word.length() > 2 && !SKIP.contains(word)) {
                words.add(word.toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }
}
