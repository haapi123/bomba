package com.betterloka.assistantserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A site's {@code robots.txt}: which paths a crawler may fetch. The group naming our agent wins,
 * else the {@code *} group; within it the longest matching rule decides, Allow winning a tie, and
 * {@code *} and a closing {@code $} work as Google reads them.
 */
final class Robots {
    private record Rule(boolean allow, String pattern) {
    }

    private final List<Rule> rules;

    private Robots(List<Rule> rules) {
        this.rules = rules;
    }

    static Robots allowAll() {
        return new Robots(List.of());
    }

    static Robots disallowAll() {
        return new Robots(List.of(new Rule(false, "/")));
    }

    static Robots parse(String text, String agentToken) {
        // The product token alone: "BetterLoka-Assistant-KB/1.0 (...)" is "betterloka-assistant-kb".
        String agent = agentToken.toLowerCase(Locale.ROOT).split("[/\\s]", 2)[0];
        List<Rule> star = new ArrayList<>();
        List<Rule> ours = new ArrayList<>();
        boolean sawOurs = false;
        List<String> groupAgents = new ArrayList<>();
        boolean inRules = false;
        List<List<Rule>> targets = new ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            String line = raw.replaceAll("#.*", "").strip();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (key.equals("user-agent")) {
                if (inRules) {
                    groupAgents.clear();
                    inRules = false;
                }
                groupAgents.add(value.toLowerCase(Locale.ROOT));
                continue;
            }
            if (!key.equals("allow") && !key.equals("disallow")) {
                continue;
            }
            if (!inRules) {
                inRules = true;
                targets = new ArrayList<>();
                for (String name : groupAgents) {
                    if (name.equals("*")) {
                        targets.add(star);
                    } else if (!name.isEmpty() && agent.startsWith(name)) {
                        targets.add(ours);
                        sawOurs = true;
                    }
                }
            }
            if (value.isEmpty()) {
                // "Disallow:" with nothing after it allows everything.
                continue;
            }
            Rule rule = new Rule(key.equals("allow"), value);
            for (List<Rule> target : targets) {
                target.add(rule);
            }
        }
        return new Robots(sawOurs ? ours : star);
    }

    boolean allows(String pathAndQuery) {
        Rule best = null;
        for (Rule rule : rules) {
            if (matches(rule.pattern(), pathAndQuery)) {
                if (best == null || rule.pattern().length() > best.pattern().length()
                        || (rule.pattern().length() == best.pattern().length() && rule.allow())) {
                    best = rule;
                }
            }
        }
        return best == null || best.allow();
    }

    static boolean matches(String pattern, String path) {
        boolean anchored = pattern.endsWith("$");
        String body = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
        StringBuilder regex = new StringBuilder();
        for (char c : body.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append(anchored ? "" : ".*");
        return path.matches(regex.toString());
    }
}
