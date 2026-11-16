package com.betterloka.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The server's own commands, as opposed to Loka's:
 *
 * <ul>
 *   <li>{@code /test} — is the bot alive, can it reach Loka, is the fallen-town watch running, and a
 *       sample alert posted where the real ones go.</li>
 *   <li>{@code /weryfikacja} — posts a message with a green button; whoever clicks it gets a role.</li>
 *   <li>{@code /propozycje} — posts a panel with a green button; it opens a form for a title and a
 *       suggestion, which the bot posts as its own message with a thread for discussing it, and the
 *       panel moves back to the bottom of the channel.</li>
 * </ul>
 *
 * <p>Everything a button needs is in its id — the role it gives, the panel a form came from — so a
 * restart loses nothing, and nothing but the suggestion count is stored.
 */
public final class ServerCommands {
    private static final Logger LOG = LoggerFactory.getLogger("BetterLokaBot");

    static final String PREFIX = "blb:";
    static final String VERIFY = PREFIX + "verify:";
    static final String SUGGEST = PREFIX + "suggest";
    static final String SUGGEST_FORM = PREFIX + "suggest-form:";

    static final String DEFAULT_PANEL_TITLE = "💡 Propozycje do moda";
    static final String DEFAULT_PANEL_TEXT =
            "Chciałbyś dodać coś do moda? Naciśnij w zielony przycisk i napisz propozycję!";
    static final String DEFAULT_VERIFY_TITLE = "✅ Weryfikacja";
    static final String DEFAULT_VERIFY_TEXT = "Kliknij przycisk poniżej, żeby się zweryfikować.";
    static final String DEFAULT_VERIFY_BUTTON = "Zweryfikuj się";

    static final int TITLE_MIN = 3;
    static final int TITLE_MAX = 80;
    static final int BODY_MIN = 10;
    static final int BODY_MAX = 1500;
    /** One suggestion a minute per person: enough for anyone with something to say. */
    static final long SUGGEST_COOLDOWN_MILLIS = 60_000;

    private static final int GREEN = 0x5FD37A;
    private static final int GOLD = 0xF2BE4E;
    private static final int ORANGE = 0xF0A050;
    private static final int RED = 0xE0605C;

    private static final BigInteger ADMINISTRATOR = BigInteger.ONE.shiftLeft(3);
    private static final BigInteger MANAGE_ROLES = BigInteger.ONE.shiftLeft(28);

    /** What {@code /test} needs from the fallen-town watch. */
    public interface Alerts {
        /** Why the watch is off, or {@code null} when it is running. */
        String offReason();

        /** Where alerts go, as Discord shows it: a channel mention, or "webhook". */
        String destination();

        String roleId();

        int intervalSeconds();

        /** One cheap request to Loka; the number of deleted towns it reports. */
        int probeLoka() throws Exception;

        /** Posts a sample alert where the real ones go. */
        void postSample(String requestedBy, boolean ping) throws Exception;
    }

    private final DiscordActions discord;
    private final BotStatus status;
    private final CommunityStore store;
    private final Alerts alerts;
    private final Supplier<String> botUserId;
    private final Executor work;
    private final LongSupplier clock;

    private final Map<String, Long> lastSuggestion = new ConcurrentHashMap<>();
    /** The panel at the bottom of each suggestions channel, as far as this run knows. */
    private final Map<String, String> panels = new HashMap<>();

    public ServerCommands(DiscordActions discord, BotStatus status, CommunityStore store, Alerts alerts,
                          Supplier<String> botUserId, Executor work, LongSupplier clock) {
        this.discord = discord;
        this.status = status;
        this.store = store;
        this.alerts = alerts;
        this.botUserId = botUserId;
        this.work = work;
        this.clock = clock;
    }

    /** The commands this registers, alongside {@code /sprawdz}. */
    public static JsonArray definitions() {
        JsonArray commands = new JsonArray();

        JsonObject test = command("test", "Sprawdza, czy bot działa i wysyła powiadomienia o upadłych townach", "32");
        test.add("options", options(
                option(5, "ping", "Oznaczyć rangę w wiadomości testowej? (domyślnie nie)", false)));
        commands.add(test);

        JsonObject verify = command("weryfikacja", "Wysyła wiadomość z przyciskiem, który nadaje rangę", "268435456");
        JsonObject channel = option(7, "kanal", "Kanał, na który wysłać (domyślnie ten)", false);
        JsonArray types = new JsonArray();
        types.add(0);
        types.add(5);
        channel.add("channel_types", types);
        verify.add("options", options(
                option(8, "ranga", "Ranga, którą dostanie klikający", true),
                channel,
                text("tytul", "Tytuł wiadomości", 256),
                text("opis", "Treść wiadomości", 2000),
                text("przycisk", "Napis na przycisku", 80)));
        commands.add(verify);

        JsonObject suggest = command("propozycje", "Wysyła panel propozycji z zielonym przyciskiem", "32");
        suggest.add("options", options(
                channel.deepCopy(),
                text("tytul", "Tytuł panelu", 256),
                text("tekst", "Treść panelu", 2000)));
        commands.add(suggest);
        return commands;
    }

    private static JsonObject command(String name, String description, String permissions) {
        JsonObject command = new JsonObject();
        command.addProperty("name", name);
        command.addProperty("description", description);
        command.addProperty("type", 1);
        // Who may use it until the server's admins say otherwise in Server Settings → Integrations.
        command.addProperty("default_member_permissions", permissions);
        JsonArray contexts = new JsonArray();
        contexts.add(0);
        command.add("contexts", contexts);
        return command;
    }

    private static JsonObject option(int type, String name, String description, boolean required) {
        JsonObject option = new JsonObject();
        option.addProperty("type", type);
        option.addProperty("name", name);
        option.addProperty("description", description);
        option.addProperty("required", required);
        return option;
    }

    private static JsonObject text(String name, String description, int max) {
        JsonObject option = option(3, name, description, false);
        option.addProperty("max_length", max);
        return option;
    }

    private static JsonArray options(JsonObject... options) {
        JsonArray array = new JsonArray();
        for (JsonObject option : options) {
            array.add(option);
        }
        return array;
    }

    // ---- routing ----

    /** @return true if the interaction is one of these, and is being answered */
    public boolean handle(JsonObject interaction) {
        int type = interaction.has("type") ? interaction.get("type").getAsInt() : 0;
        JsonObject data = object(interaction, "data");
        if (data == null) {
            return false;
        }
        if (type == 2) {
            String name = string(data, "name");
            Runnable task = switch (name == null ? "" : name) {
                case "test" -> () -> test(interaction);
                case "weryfikacja" -> () -> setUpVerification(interaction);
                case "propozycje" -> () -> setUpSuggestions(interaction);
                default -> null;
            };
            if (task == null) {
                return false;
            }
            run(task);
            return true;
        }
        String customId = string(data, "custom_id");
        if ((type != 3 && type != 5) || customId == null || !customId.startsWith(PREFIX)) {
            return false;
        }
        if (customId.startsWith(VERIFY)) {
            run(() -> verify(interaction, customId.substring(VERIFY.length())));
        } else if (customId.equals(SUGGEST)) {
            run(() -> openSuggestionForm(interaction));
        } else if (customId.startsWith(SUGGEST_FORM)) {
            run(() -> submitSuggestion(interaction, customId.substring(SUGGEST_FORM.length())));
        } else {
            return false;
        }
        return true;
    }

    private void run(Runnable task) {
        work.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                LOG.warn("A server command failed", e);
            }
        });
    }

    // ---- /test ----

    private void test(JsonObject interaction) {
        if (!defer(interaction)) {
            return;
        }
        boolean ping = Boolean.TRUE.equals(booleanOption(object(interaction, "data"), "ping"));
        String user = userId(interaction);
        boolean bad = false;
        boolean warn = false;

        String loka;
        long started = clock.getAsLong();
        try {
            int deleted = alerts.probeLoka();
            loka = "✅ odpowiada (" + (clock.getAsLong() - started) + " ms) · usuniętych townów: " + deleted;
        } catch (Exception e) {
            loka = "❌ nie odpowiada: " + describe(e);
            bad = true;
        }

        String watch;
        String sample;
        String off = alerts.offReason();
        if (off != null) {
            watch = "⏸ wyłączone: " + off;
            sample = "⏸ nie wysłano, bo pilnowanie jest wyłączone";
            warn = true;
        } else {
            StringBuilder text = new StringBuilder("✅ działa, sprawdza Lokę co " + alerts.intervalSeconds() + " s");
            if (status.lastCheckAt() == 0) {
                text.append("\nPierwsze sprawdzenie jeszcze trwa");
            } else {
                text.append("\nOstatnie sprawdzenie: ").append(ago(status.lastCheckAt()))
                        .append(status.lastCheckOk() ? " ✅" : " ❌ " + status.lastCheckError());
                if (!status.lastCheckOk()) {
                    warn = true;
                }
            }
            if (status.lastSweepAt() != 0) {
                text.append("\nPełny przegląd terytoriów: ").append(ago(status.lastSweepAt()));
            }
            watch = text.toString();
            try {
                alerts.postSample(user, ping);
                sample = "✅ wysłana " + ("webhook".equals(alerts.destination())
                        ? "przez webhook" : "na " + alerts.destination())
                        + (ping ? " z oznaczeniem rangi" : " (bez oznaczania rangi)");
            } catch (Exception e) {
                sample = "❌ " + describe(e);
                bad = true;
            }
        }

        String last = status.lastAnnouncedAt() == 0
                ? "jeszcze żadnego od uruchomienia bota (" + ago(status.startedAt()) + ")"
                : ago(status.lastAnnouncedAt()) + " — " + status.lastAnnounced();

        JsonObject embed = new JsonObject();
        embed.addProperty("title", bad ? "❌ Coś nie działa" : warn ? "⚠️ Bot działa, ale coś wymaga uwagi" : "✅ Bot działa");
        embed.addProperty("color", bad ? RED : warn ? ORANGE : GREEN);
        JsonArray fields = new JsonArray();
        fields.add(field("Discord", "✅ połączony — ta odpowiedź to dowód"));
        fields.add(field("Loka API", loka));
        fields.add(field("Pilnowanie upadłych townów", watch));
        fields.add(field("Ostatnie powiadomienie", last));
        if (status.lastPostErrorAt() != 0) {
            fields.add(field("Ostatni błąd wysyłania", ago(status.lastPostErrorAt()) + " — " + status.lastPostError()));
        }
        fields.add(field("Wiadomość testowa", sample));
        if (off == null) {
            String role = alerts.roleId() == null || alerts.roleId().isBlank()
                    ? "bez oznaczania rangi" : "oznacza <@&" + alerts.roleId() + ">";
            fields.add(field("Gdzie trafiają powiadomienia",
                    ("webhook".equals(alerts.destination()) ? "webhook" : alerts.destination()) + " · " + role));
        }
        embed.add("fields", fields);
        footer(embed, "BetterLoka · /test");
        editReply(interaction, null, embed);
    }

    // ---- verification ----

    private void setUpVerification(JsonObject interaction) {
        if (!defer(interaction)) {
            return;
        }
        JsonObject data = object(interaction, "data");
        String guild = string(interaction, "guild_id");
        String roleId = stringOption(data, "ranga");
        String channel = firstNonBlank(stringOption(data, "kanal"), string(interaction, "channel_id"));
        if (guild == null || roleId == null || channel == null) {
            editReply(interaction, "❌ Tej komendy można użyć tylko na serwerze.", null);
            return;
        }
        JsonObject role = resolved(data, "roles", roleId);
        if (roleId.equals(guild)) {
            editReply(interaction, "❌ Ranga @everyone ma ją każdy — wybierz inną.", null);
            return;
        }
        if (role != null && role.has("managed") && role.get("managed").getAsBoolean()) {
            editReply(interaction, "❌ Tej rangi nie da się nadać: należy do bota albo integracji.", null);
            return;
        }

        JsonObject message = verificationMessage(roleId, stringOption(data, "tytul"), stringOption(data, "opis"),
                stringOption(data, "przycisk"));
        try {
            discord.post(channel, message);
        } catch (Exception e) {
            editReply(interaction, cannotPost(channel, e), null);
            return;
        }
        StringBuilder reply = new StringBuilder("✅ Wiadomość weryfikacyjna wysłana na <#" + channel
                + ">. Kliknięcie przycisku nada rangę <@&" + roleId + ">.");
        for (String warning : roleWarnings(guild, roleId, role)) {
            reply.append("\n\n").append(warning);
        }
        editReply(interaction, reply.toString(), null);
    }

    static JsonObject verificationMessage(String roleId, String title, String text, String button) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", clip(firstNonBlank(title, DEFAULT_VERIFY_TITLE), 256));
        embed.addProperty("description", clip(firstNonBlank(text, DEFAULT_VERIFY_TEXT), 3800)
                + "\n\nPo kliknięciu dostaniesz rangę <@&" + roleId + ">.");
        embed.addProperty("color", GREEN);
        footer(embed, "BetterLoka");
        JsonObject message = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        message.add("embeds", embeds);
        message.add("components", row(button(VERIFY + roleId, clip(firstNonBlank(button, DEFAULT_VERIFY_BUTTON), 80), "✅")));
        message.add("allowed_mentions", noMentions());
        return message;
    }

    /**
     * Why the button might not work, checked once when it is set up rather than discovered by the
     * first person to click it: the bot needs Manage Roles, and its own highest role has to sit above
     * the one it hands out — Discord's rule, and the usual reason a verification button does nothing.
     */
    private java.util.List<String> roleWarnings(String guild, String roleId, JsonObject role) {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        String me = botUserId.get();
        if (me == null) {
            return warnings;
        }
        try {
            JsonObject member = discord.member(guild, me);
            JsonArray roles = discord.roles(guild);
            java.util.Set<String> mine = new java.util.HashSet<>();
            if (member.has("roles") && member.get("roles").isJsonArray()) {
                for (JsonElement each : member.getAsJsonArray("roles")) {
                    mine.add(each.getAsString());
                }
            }
            int top = 0;
            int target = role != null && role.has("position") ? role.get("position").getAsInt() : -1;
            BigInteger permissions = BigInteger.ZERO;
            for (JsonElement each : roles) {
                JsonObject r = each.getAsJsonObject();
                String id = string(r, "id");
                int position = r.has("position") ? r.get("position").getAsInt() : 0;
                if (id == null) {
                    continue;
                }
                if (id.equals(roleId)) {
                    target = position;
                }
                if (mine.contains(id) || id.equals(guild)) {
                    permissions = permissions.or(permissionsOf(r));
                    if (mine.contains(id)) {
                        top = Math.max(top, position);
                    }
                }
            }
            boolean administrator = permissions.and(ADMINISTRATOR).signum() != 0;
            if (!administrator && permissions.and(MANAGE_ROLES).signum() == 0) {
                warnings.add("⚠️ Bot nie ma uprawnienia **Zarządzanie rolami** — nadaj je jego randze, "
                        + "inaczej przycisk nie zadziała.");
            }
            if (target >= top) {
                warnings.add("⚠️ Ranga <@&" + roleId + "> jest wyżej niż najwyższa ranga bota. Przeciągnij rangę "
                        + "bota nad nią w Ustawienia serwera → Role, inaczej przycisk nie zadziała.");
            }
        } catch (Exception e) {
            LOG.info("Could not check the bot's roles: {}", e.getMessage());
        }
        return warnings;
    }

    private static BigInteger permissionsOf(JsonObject role) {
        try {
            return new BigInteger(string(role, "permissions"));
        } catch (RuntimeException e) {
            return BigInteger.ZERO;
        }
    }

    private void verify(JsonObject interaction, String roleId) {
        String guild = string(interaction, "guild_id");
        String user = userId(interaction);
        if (guild == null || user == null || roleId.isBlank()) {
            respond(interaction, ephemeral("❌ To działa tylko na serwerze."));
            return;
        }
        if (memberRoles(interaction).contains(roleId)) {
            respond(interaction, ephemeral("Masz już rangę <@&" + roleId + "> 🙂"));
            return;
        }
        if (!defer(interaction)) {
            return;
        }
        try {
            discord.addRole(guild, user, roleId, "Weryfikacja przyciskiem (BetterLoka)");
            editReply(interaction, "✅ Zweryfikowano! Masz teraz rangę <@&" + roleId + ">.", null);
        } catch (DiscordActions.Refused e) {
            LOG.warn("Could not give role {} to {}: {}", roleId, user, e.getMessage());
            editReply(interaction, e.status() == 404
                    ? "❌ Tej rangi już nie ma na serwerze — daj znać administracji."
                    : "❌ Nie mogę nadać tej rangi. Administracja musi dać botowi **Zarządzanie rolami** i "
                    + "przesunąć jego rangę nad <@&" + roleId + ">.", null);
        } catch (Exception e) {
            LOG.warn("Could not give role {} to {}: {}", roleId, user, e.getMessage());
            editReply(interaction, "❌ Coś poszło nie tak — spróbuj ponownie za chwilę.", null);
        }
    }

    // ---- suggestions ----

    private void setUpSuggestions(JsonObject interaction) {
        if (!defer(interaction)) {
            return;
        }
        JsonObject data = object(interaction, "data");
        String channel = firstNonBlank(stringOption(data, "kanal"), string(interaction, "channel_id"));
        if (channel == null) {
            editReply(interaction, "❌ Tej komendy można użyć tylko na serwerze.", null);
            return;
        }
        try {
            String id = discord.post(channel, suggestionPanel(stringOption(data, "tytul"), stringOption(data, "tekst")));
            synchronized (panels) {
                panels.put(channel, id);
            }
        } catch (Exception e) {
            editReply(interaction, cannotPost(channel, e), null);
            return;
        }
        editReply(interaction, "✅ Panel propozycji wysłany na <#" + channel + ">.\n\n"
                + "Bot potrzebuje tam: **Wysyłanie wiadomości**, **Osadzanie linków**, **Tworzenie wątków "
                + "publicznych**, **Wysyłanie wiadomości w wątkach**, **Dodawanie reakcji** i **Czytanie historii "
                + "wiadomości**.\n"
                + "Wskazówka: zabierz graczom **Wysyłanie wiadomości** na tym kanale, a zostaw **Wysyłanie "
                + "wiadomości w wątkach** — kanał zostanie czysty, a dyskusje pójdą do wątków.", null);
    }

    static JsonObject suggestionPanel(String title, String text) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", clip(firstNonBlank(title, DEFAULT_PANEL_TITLE), 256));
        embed.addProperty("description", clip(firstNonBlank(text, DEFAULT_PANEL_TEXT), 4000));
        embed.addProperty("color", GREEN);
        footer(embed, "Każda propozycja dostaje swój wątek do dyskusji · BetterLoka");
        JsonObject message = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        message.add("embeds", embeds);
        message.add("components", row(button(SUGGEST, "Napisz propozycję", "💡")));
        message.add("allowed_mentions", noMentions());
        return message;
    }

    private void openSuggestionForm(JsonObject interaction) {
        String wait = cooldown(userId(interaction));
        if (wait != null) {
            respond(interaction, ephemeral(wait));
            return;
        }
        JsonObject message = object(interaction, "message");
        String panel = message == null ? null : string(message, "id");
        respond(interaction, suggestionForm(panel == null ? "" : panel));
    }

    static JsonObject suggestionForm(String panelId) {
        JsonArray rows = new JsonArray();
        rows.add(inputRow(input("title", "Tytuł propozycji", 1, TITLE_MIN, TITLE_MAX,
                "np. Licznik do restartu serwera na ekranie")));
        rows.add(inputRow(input("body", "Twoja propozycja", 2, BODY_MIN, BODY_MAX,
                "Opisz, co chcesz dodać do moda i dlaczego by się przydało.")));
        JsonObject data = new JsonObject();
        data.addProperty("custom_id", SUGGEST_FORM + panelId);
        data.addProperty("title", "Nowa propozycja");
        data.add("components", rows);
        JsonObject response = new JsonObject();
        response.addProperty("type", 9);
        response.add("data", data);
        return response;
    }

    private void submitSuggestion(JsonObject interaction, String panelId) {
        Map<String, String> values = formValues(object(interaction, "data"));
        String title = tidy(values.get("title"), false);
        String body = tidy(values.get("body"), true);
        String user = userId(interaction);
        String channel = string(interaction, "channel_id");
        if (title.length() < TITLE_MIN || body.length() < BODY_MIN || user == null || channel == null) {
            respond(interaction, ephemeral("❌ Tytuł musi mieć co najmniej " + TITLE_MIN + " znaki, a propozycja "
                    + BODY_MIN + ". Spróbuj jeszcze raz."));
            return;
        }
        String wait = cooldown(user);
        if (wait != null) {
            respond(interaction, ephemeral(wait));
            return;
        }
        if (!defer(interaction)) {
            return;
        }
        lastSuggestion.put(user, clock.getAsLong());
        int number = store.nextSuggestion();

        String messageId;
        try {
            messageId = discord.post(channel, suggestionMessage(number, clip(title, TITLE_MAX), clip(body, BODY_MAX),
                    displayName(interaction), avatarUrl(interaction)));
        } catch (Exception e) {
            lastSuggestion.remove(user);
            LOG.warn("Could not post suggestion #{}: {}", number, e.getMessage());
            editReply(interaction, "❌ Nie udało się wysłać propozycji — bot nie może pisać na tym kanale. "
                    + "Daj znać administracji.", null);
            return;
        }
        for (String vote : new String[]{"👍", "👎"}) {
            try {
                discord.react(channel, messageId, vote);
            } catch (Exception e) {
                LOG.info("Could not add {} to suggestion #{}: {}", vote, number, e.getMessage());
            }
        }
        String thread = null;
        try {
            thread = discord.startThread(channel, messageId, threadName(number, title));
            JsonObject welcome = new JsonObject();
            welcome.addProperty("content", "<@" + user + "> — tutaj można dyskutować o tej propozycji. "
                    + "Głosujcie 👍 / 👎 pod wiadomością.");
            JsonObject allowed = noMentions();
            JsonArray users = new JsonArray();
            users.add(user);
            allowed.add("users", users);
            welcome.add("allowed_mentions", allowed);
            discord.post(thread, welcome);
        } catch (Exception e) {
            LOG.warn("Could not open a thread for suggestion #{}: {}", number, e.getMessage());
        }
        movePanel(channel, panelId);

        editReply(interaction, thread != null
                ? "✅ Dzięki! Twoja propozycja **#" + number + "** jest już na kanale — dyskusja: <#" + thread + ">"
                : "✅ Dzięki! Twoja propozycja **#" + number + "** jest już na kanale. (Wątku nie udało się "
                + "otworzyć — bot potrzebuje uprawnienia Tworzenie wątków publicznych.)", null);
    }

    static JsonObject suggestionMessage(int number, String title, String body, String author, String avatar) {
        JsonObject embed = new JsonObject();
        JsonObject authorObject = new JsonObject();
        authorObject.addProperty("name", clip(author, 256));
        if (avatar != null) {
            authorObject.addProperty("icon_url", avatar);
        }
        embed.add("author", authorObject);
        embed.addProperty("title", clip("💡 " + title, 256));
        embed.addProperty("description", body);
        embed.addProperty("color", GOLD);
        footer(embed, "Propozycja #" + number + " · głosuj 👍 / 👎 · dyskusja w wątku");
        embed.addProperty("timestamp", java.time.Instant.now().toString());
        JsonObject message = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        message.add("embeds", embeds);
        message.add("allowed_mentions", noMentions());
        return message;
    }

    static String threadName(int number, String title) {
        return clip("#" + number + " " + title.replace('\n', ' '), 100);
    }

    /**
     * Puts the panel back at the bottom of the channel, under the suggestion just posted, so the
     * button is always where somebody opening the channel looks.
     */
    private void movePanel(String channel, String fromForm) {
        synchronized (panels) {
            String current = firstNonBlank(panels.get(channel), fromForm);
            if (current == null || current.isBlank()) {
                return;
            }
            try {
                JsonObject old = discord.message(channel, current);
                JsonObject copy = new JsonObject();
                copy.add("embeds", old.has("embeds") ? old.get("embeds") : suggestionPanel(null, null).get("embeds"));
                copy.add("components", old.has("components") ? old.get("components")
                        : suggestionPanel(null, null).get("components"));
                copy.add("allowed_mentions", noMentions());
                String moved = discord.post(channel, copy);
                panels.put(channel, moved);
                discord.delete(channel, current);
            } catch (Exception e) {
                LOG.info("Could not move the suggestions panel: {}", e.getMessage());
            }
        }
    }

    private String cooldown(String user) {
        Long last = user == null ? null : lastSuggestion.get(user);
        if (last == null) {
            return null;
        }
        long left = SUGGEST_COOLDOWN_MILLIS - (clock.getAsLong() - last);
        return left <= 0 ? null : "⏳ Następną propozycję możesz wysłać za " + Math.max(1, (left + 999) / 1000) + " s.";
    }

    /** Trimmed, with runs of blank lines squeezed; a title stays on one line. */
    static String tidy(String text, boolean multiline) {
        if (text == null) {
            return "";
        }
        String out = text.strip();
        out = multiline ? out.replaceAll("\\n{3,}", "\n\n") : out.replaceAll("\\s+", " ");
        return out;
    }

    // ---- answering ----

    private boolean defer(JsonObject interaction) {
        JsonObject response = new JsonObject();
        response.addProperty("type", 5);
        JsonObject data = new JsonObject();
        data.addProperty("flags", 64);
        response.add("data", data);
        return respond(interaction, response);
    }

    private boolean respond(JsonObject interaction, JsonObject response) {
        try {
            discord.respond(string(interaction, "id"), string(interaction, "token"), response);
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Could not answer an interaction: {}", e.getMessage());
            return false;
        }
    }

    private void editReply(JsonObject interaction, String content, JsonObject embed) {
        JsonObject message = new JsonObject();
        message.addProperty("content", content == null ? "" : content);
        JsonArray embeds = new JsonArray();
        if (embed != null) {
            embeds.add(embed);
        }
        message.add("embeds", embeds);
        message.add("allowed_mentions", noMentions());
        try {
            discord.editReply(string(interaction, "application_id"), string(interaction, "token"), message);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Could not finish answering an interaction: {}", e.getMessage());
        }
    }

    static JsonObject ephemeral(String content) {
        JsonObject data = new JsonObject();
        data.addProperty("content", content);
        data.addProperty("flags", 64);
        data.add("allowed_mentions", noMentions());
        JsonObject response = new JsonObject();
        response.addProperty("type", 4);
        response.add("data", data);
        return response;
    }

    private static String cannotPost(String channel, Exception e) {
        LOG.warn("Could not post in channel {}: {}", channel, e.getMessage());
        return "❌ Nie mogę pisać na <#" + channel + ">. Daj botowi tam **Wyświetlanie kanału**, **Wysyłanie "
                + "wiadomości** i **Osadzanie linków**." + (e instanceof DiscordActions.Refused refused
                ? " (Discord: " + refused.status() + ")" : "");
    }

    // ---- small builders ----

    private static JsonArray row(JsonObject... components) {
        JsonObject row = new JsonObject();
        row.addProperty("type", 1);
        JsonArray inner = new JsonArray();
        for (JsonObject component : components) {
            inner.add(component);
        }
        row.add("components", inner);
        JsonArray rows = new JsonArray();
        rows.add(row);
        return rows;
    }

    /** A green button. */
    private static JsonObject button(String customId, String label, String emoji) {
        JsonObject button = new JsonObject();
        button.addProperty("type", 2);
        button.addProperty("style", 3);
        button.addProperty("label", label);
        button.addProperty("custom_id", customId);
        JsonObject emojiObject = new JsonObject();
        emojiObject.addProperty("name", emoji);
        button.add("emoji", emojiObject);
        return button;
    }

    private static JsonObject inputRow(JsonObject input) {
        JsonObject row = new JsonObject();
        row.addProperty("type", 1);
        JsonArray components = new JsonArray();
        components.add(input);
        row.add("components", components);
        return row;
    }

    /** Styles: 1 one line, 2 a paragraph. */
    private static JsonObject input(String id, String label, int style, int min, int max, String placeholder) {
        JsonObject input = new JsonObject();
        input.addProperty("type", 4);
        input.addProperty("custom_id", id);
        input.addProperty("label", label);
        input.addProperty("style", style);
        input.addProperty("min_length", min);
        input.addProperty("max_length", max);
        input.addProperty("required", true);
        input.addProperty("placeholder", clip(placeholder, 100));
        return input;
    }

    private static JsonObject field(String name, String value) {
        JsonObject field = new JsonObject();
        field.addProperty("name", name);
        field.addProperty("value", clip(value, 1024));
        field.addProperty("inline", false);
        return field;
    }

    private static void footer(JsonObject embed, String text) {
        JsonObject footer = new JsonObject();
        footer.addProperty("text", text);
        embed.add("footer", footer);
    }

    private static JsonObject noMentions() {
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        return allowed;
    }

    /** Discord's own relative time, shown in each reader's language and time zone. */
    private static String ago(long millis) {
        return "<t:" + (millis / 1000) + ":R>";
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
    }

    // ---- reading an interaction ----

    static Map<String, String> formValues(JsonObject data) {
        Map<String, String> values = new LinkedHashMap<>();
        collect(data == null ? null : data.get("components"), values);
        return values;
    }

    private static void collect(JsonElement element, Map<String, String> values) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement each : element.getAsJsonArray()) {
                collect(each, values);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        String id = string(object, "custom_id");
        String value = string(object, "value");
        if (id != null && value != null) {
            values.put(id, value);
        }
        collect(object.get("components"), values);
        collect(object.get("component"), values);
    }

    private static JsonObject user(JsonObject interaction) {
        JsonObject member = object(interaction, "member");
        JsonObject user = member == null ? null : object(member, "user");
        return user != null ? user : object(interaction, "user");
    }

    static String userId(JsonObject interaction) {
        JsonObject user = user(interaction);
        return user == null ? null : string(user, "id");
    }

    private static java.util.Set<String> memberRoles(JsonObject interaction) {
        java.util.Set<String> roles = new java.util.HashSet<>();
        JsonObject member = object(interaction, "member");
        if (member != null && member.has("roles") && member.get("roles").isJsonArray()) {
            for (JsonElement role : member.getAsJsonArray("roles")) {
                roles.add(role.getAsString());
            }
        }
        return roles;
    }

    static String displayName(JsonObject interaction) {
        JsonObject member = object(interaction, "member");
        JsonObject user = user(interaction);
        return firstNonBlank(member == null ? null : string(member, "nick"),
                user == null ? null : string(user, "global_name"),
                user == null ? null : string(user, "username"), "?");
    }

    static String avatarUrl(JsonObject interaction) {
        JsonObject user = user(interaction);
        String id = user == null ? null : string(user, "id");
        if (id == null) {
            return null;
        }
        String avatar = string(user, "avatar");
        if (avatar != null) {
            return "https://cdn.discordapp.com/avatars/" + id + "/" + avatar + ".png?size=128";
        }
        try {
            return "https://cdn.discordapp.com/embed/avatars/" + (Long.parseUnsignedLong(id) >>> 22) % 6 + ".png";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stringOption(JsonObject data, String name) {
        JsonObject option = findOption(data, name);
        return option == null ? null : string(option, "value");
    }

    private static Boolean booleanOption(JsonObject data, String name) {
        JsonObject option = findOption(data, name);
        return option == null || !option.has("value") ? null : option.get("value").getAsBoolean();
    }

    private static JsonObject findOption(JsonObject data, String name) {
        if (data == null || !data.has("options") || !data.get("options").isJsonArray()) {
            return null;
        }
        for (JsonElement element : data.getAsJsonArray("options")) {
            JsonObject option = element.getAsJsonObject();
            if (name.equals(string(option, "name"))) {
                return option;
            }
        }
        return null;
    }

    private static JsonObject resolved(JsonObject data, String kind, String id) {
        JsonObject resolved = object(data, "resolved");
        JsonObject all = resolved == null ? null : object(resolved, kind);
        return all == null ? null : object(all, id);
    }

    private static JsonObject object(JsonObject json, String key) {
        return json != null && json.has(key) && json.get(key).isJsonObject() ? json.getAsJsonObject(key) : null;
    }

    private static String string(JsonObject json, String key) {
        return json != null && json.has(key) && !json.get(key).isJsonNull() && json.get(key).isJsonPrimitive()
                ? json.get(key).getAsString() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
