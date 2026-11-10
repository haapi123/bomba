package com.betterloka.assistantserver;

import java.util.Map;

/**
 * What a call cost, in US dollars, from the tokens the API reports. Prices are per million tokens;
 * writing to the cache costs 1.25x the input price for five minutes and 2x for an hour.
 */
public final class Pricing {
    /** Per million tokens. {@code longAbove}: from that many prompt tokens on, the long prices apply. */
    public record Price(double input, double output, double cacheRead, long longAbove, double longInput,
                        double longOutput, double longCacheRead) {
        public Price(double input, double output, double cacheRead) {
            this(input, output, cacheRead, 0, 0, 0, 0);
        }
    }

    /** Prices as published for the models the server is likely to use (November 2026). */
    static final Map<String, Price> KNOWN = Map.of(
            "claude-opus-5-5", new Price(4.00, 20.00, 0.20),
            "claude-sonnet-5-5", new Price(2.00, 10.00, 0.10),
            "claude-haiku-5-5", new Price(0.10, 0.50, 0.01, 100_000, 0.50, 2.50, 0.05),
            "claude-opus-5", new Price(5.00, 25.00, 0.50),
            "claude-opus-4-8", new Price(5.00, 25.00, 0.50),
            "claude-sonnet-5", new Price(2.00, 10.00, 0.20));

    /** For a model the table does not know: priced high, so the budget errs on the safe side. */
    static final Price UNKNOWN = new Price(10.00, 50.00, 1.00);

    /** Tokens of one call, as the API reports them. */
    public record Usage(long input, long output, long cacheRead, long cacheWrite5m, long cacheWrite1h) {
        public long prompt() {
            return input + cacheRead + cacheWrite5m + cacheWrite1h;
        }

        public Usage plus(Usage other) {
            return new Usage(input + other.input, output + other.output, cacheRead + other.cacheRead,
                    cacheWrite5m + other.cacheWrite5m, cacheWrite1h + other.cacheWrite1h);
        }
    }

    private Pricing() {
    }

    public static Price price(String model, Map<String, Price> overrides) {
        if (overrides != null && model != null && overrides.containsKey(model)) {
            return overrides.get(model);
        }
        if (model != null) {
            Price known = KNOWN.get(model);
            if (known != null) {
                return known;
            }
            // A dated or suffixed id of a known model.
            for (Map.Entry<String, Price> entry : KNOWN.entrySet()) {
                if (model.startsWith(entry.getKey() + "-") || model.startsWith(entry.getKey() + "@")) {
                    return entry.getValue();
                }
            }
        }
        return UNKNOWN;
    }

    public static double cost(String model, Usage usage, Map<String, Price> overrides) {
        Price price = price(model, overrides);
        boolean longPrompt = price.longAbove() > 0 && usage.prompt() > price.longAbove();
        double input = longPrompt ? price.longInput() : price.input();
        double output = longPrompt ? price.longOutput() : price.output();
        double read = longPrompt ? price.longCacheRead() : price.cacheRead();
        double dollars = usage.input() * input
                + usage.cacheWrite5m() * input * 1.25
                + usage.cacheWrite1h() * input * 2.0
                + usage.cacheRead() * read
                + usage.output() * output;
        return dollars / 1_000_000.0;
    }
}
