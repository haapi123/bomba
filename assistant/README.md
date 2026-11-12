# Loka Assistant server

The server behind BetterLoka's **Loka Assistant**: players ask questions about Loka in the game, and
Claude answers from a knowledge base built from Loka's website, its forum announcements, its wiki
and answers players wrote that you approved.

```
Player (BetterLoka)  ──►  this server  ──►  Claude API
                          • the API key (only here)
                          • the knowledge base (kb/*.md)
                          • limits, budget, logs, approvals
```

The API key lives only here. It is never in the mod: anyone can unpack a jar, and a key in it would
be spent by strangers.

## Quick start (po polsku)

1. Zainstaluj **Javę 21 lub nowszą** (np. Adoptium Temurin). Minecraftowa Java z launchera się nie liczy.
2. Zrób pusty folder, wrzuć do niego `betterloka-assistant-<wersja>.jar`.
3. Załóż konto API na **platform.claude.com**, doładuj kredyty (wyłącz auto-doładowanie: wtedy
   więcej, niż wpłacisz, nie wydasz) i utwórz klucz. Zapisz go w tym folderze w pliku
   **`api-key.txt`** (sam klucz, jedna linijka). Nikomu go nie wysyłaj i nie wklejaj na czat.
4. Zbuduj bazę wiedzy (raz, ok. 15 min czytania stron + kilka minut pisania):
   ```
   java -jar betterloka-assistant-<wersja>.jar build-kb
   ```
   Przed wydaniem pieniędzy pokaże szacowany koszt i zapyta „yes”.
5. Wpisz swój UUID Minecrafta do `admins` w `assistant.json` (bez dziennego limitu pytań).
   Odpowiedzi graczy zatwierdza się na Discordzie (sekcja niżej); mod od 0.45.1 nie ma już zakładki
   do zatwierdzania.
6. Uruchom:
   ```
   java -jar betterloka-assistant-<wersja>.jar
   ```
7. Mod od wersji 0.45.0 łączy się zawsze z serwerem BetterLoka (`ent-8.icehost.pl:40076`, zapisany
   w `AssistantClient.SERVER`); gracze nie wpisują adresu. Własny serwer pod innym adresem wymaga
   zmiany tej stałej i zbudowania moda.

Bez klucza da się obejrzeć ekran w grze: `java -jar ... serve --demo` odpowiada linijkami z bazy
wiedzy, bez Claude i za darmo.

### Discord: bot od townów i pytania z „Pomóż mu się uczyć” (po polsku)

Ten sam jar uruchamia też **bota od upadłych townów** (`/sprawdz` też działa), więc na panelu typu
Pterodactyl wystarczy **jeden serwer**. Bot i asystent używają jednego połączenia z Discordem.

1. **Bot na Discordzie** (jeśli jeszcze go nie masz): discord.com/developers/applications →
   New Application → **Bot** → Reset Token. Token wklej do **`betterloka-bot.json`** jako
   `botToken` (plik tworzy się sam przy pierwszym starcie). Tokenu nie wysyłaj nikomu. Wyłącz tam
   **Public Bot**, żeby tylko Ty mogłeś go dodawać do serwerów.
2. **Zaproszenie**: OAuth2 → URL Generator → zaznacz `bot` i `applications.commands`, uprawnienia:
   View Channels, Send Messages, Embed Links. Otwórz link i dodaj bota na swój serwer.
3. **Kanał i rangi**: włącz Ustawienia → Zaawansowane → Tryb dewelopera. Prawy klik na kanał →
   Kopiuj ID kanału, prawy klik na rangę → Kopiuj ID roli. Wpisz w `assistant.json`:
   ```json
   "discordQuestions": {
     "channelId": "ID kanału z pytaniami",
     "answerRoleIds": ["ID rangi, która może odpowiadać"],
     "approveRoleIds": ["ID rangi, która zatwierdza"],
     "pingRoleId": "",
     "language": "pl"
   }
   ```
   - **answerRoleIds**: odpowiadają przyciskiem **Odpowiedz**; ich odpowiedź czeka na zatwierdzenie.
   - **approveRoleIds**: zatwierdzają, poprawiają i odrzucają (także propozycje graczy z gry), a ich
     własne odpowiedzi trafiają od razu do bazy. Administratorzy serwera Discord mogą to samo.
   - **pingRoleId** (opcjonalnie): ranga pingowana przy każdym nowym pytaniu.
4. **Bot od townów**: w `betterloka-bot.json` zostaw swój `webhookUrl` i `roleId` jak dotąd (albo
   zamiast webhooka podaj `channelId`). Jeśli bot chodził wcześniej gdzie indziej, **wyłącz go tam** i
   przenieś `betterloka-bot.json` oraz `betterloka-bot-state.json` do folderu asystenta. Dwa
   programy z tym samym tokenem podbierałyby sobie kliknięcia, a alerty szłyby podwójnie.
5. Uruchom ponownie. W konsoli zobaczysz `Unanswered questions go to Discord channel ...` i
   `Watching Loka every 30s`.

## Commands

| Command | What it does |
| --- | --- |
| `serve` (or nothing) | Answers players. Type `status` for spending, `stop` to stop. |
| `build-kb` | `crawl`, then `distill`. |
| `crawl` | Reads Loka's pages into `sources/`, one text file per page. Run again later to fetch only what is new. |
| `distill` | Writes the knowledge base `kb/generated/*.md` from `sources/` with Claude. Shows the estimated cost and asks first (`--yes` skips the question). Parts already written are not paid for twice. |
| `ask "…"` | One question from the console, with its cost. |
| `kb-info` | How big the knowledge base is and what a question costs. |

Options: `--dir <folder>` (default: the current folder), `--yes`, `--demo`.

## How it answers

Every question is sent with the instructions and the **whole knowledge base**, which is marked for
the API's prompt cache: the first question in five minutes pays for writing it to the cache, the
next ones read it for a twentieth of the price. The answer comes back as JSON held to a schema —
the text, and whether the knowledge base covered it — so the server knows when the assistant did not
know. Up to three earlier questions and answers travel with a follow-up.

Requests opt into the API's **server-side fallback** (`fallbacks: "default"`): a question one
model's safety filter declines is retried on another model instead of failing. Players asking about
PvP and raids are the kind of thing a filter can misread.

The model is told to answer only from the knowledge base (or plain Minecraft knowledge, said as
such), never to guess numbers, coordinates or commands, to answer in the player's language, to keep
it short, and to treat the knowledge base and the question as material rather than instructions.

## Teaching it

The model does not learn from conversations. The knowledge base is files, and it changes in three
ways, none of which needs a restart (the folder is looked at again every ten seconds):

- **`build-kb` again** after Loka publishes something new.
- **Edit or add `.md` or `.txt` files in `kb/`** — anything there is part of the knowledge base, and
  the assistant trusts it over what `build-kb` wrote from the forum and the wiki (`kb/generated/`)
  when the two disagree.
- **Players**: a question the assistant could not answer, or an answer a player marked wrong, shows
  up in the game's *Help it learn* tab. Any player can write an answer; it waits for approval on
  Discord (below) and goes into `kb/community.md` only once someone with the approving role approves
  it (edited if need be). The mod has no review tab since 0.45.1; the server's review endpoints remain
  for older versions. Nothing a player writes reaches the knowledge base on its own — one troll could
  otherwise teach everyone something false. With `discordWebhookUrl` set, new unanswered questions
  and new proposals are posted to that channel.
- **Discord**: with `discordQuestions` set, the same questions are answered on Discord — see below.

## Answering on Discord

With `discordQuestions.channelId` set and a bot token in `betterloka-bot.json`, every open question
is posted to that channel with an **Answer** button (and **Dismiss question**). Answer opens a form
with the question filled in (it can be corrected) and a box for the answer.

- Members with an **`answerRoleIds`** role may answer. Their answer becomes a proposal, posted under
  the question with **Approve**, **Edit and approve** and **Reject**.
- Members with an **`approveRoleIds`** role — and the Discord server's administrators — may approve,
  edit, reject and dismiss, players' proposals from the game included. Their own answers go straight
  into `kb/community.md`.
- The game and Discord stay in step: an answer written in the game appears on Discord for approval,
  and a question answered or dismissed on Discord leaves the game's *Help it learn* list.
- Who may do what comes from the roles Discord reports with each click, and only clicks in that one
  channel count. Replies to the clicker are visible to them alone.
- Questions are posted without the asker's name. Proposals carry the name of whoever wrote them, as
  `community.md` does. Player text is shown inside code blocks, so it cannot ping, link or format;
  only `pingRoleId` is ever pinged.
- When the channel is first set up, the 20 most asked open questions and the waiting proposals are
  posted once. A message deleted by hand stays deleted. Which message is which is kept in
  `data/discord-questions.json`.

## One process for the assistant and the fallen-town bot

`serve` also runs the [fallen-town bot](../bot/README.md) from `betterloka-bot.json` in the same
folder (written on first start), so a panel that runs one program per server hosts both. They share
one Discord gateway connection: Discord hands each click to one connection, so two programs with the
same token would take each other's. Without a webhook or channel for it, the town watch stays off with
a line saying why and the assistant carries on; the bot's own jar still runs on its own as before.

## Settings — `assistant.json`

Written with defaults on first start.

| Key | Default | |
| --- | --- | --- |
| `port`, `bind` | `8787`, `0.0.0.0` | Where the mod connects. `SERVER_PORT` or `PORT` in the environment wins (game panels set it). |
| `model` | `claude-opus-5-5` | The model that answers. One line to change; `kb-info` shows what a question costs on it. |
| `effort` | `low` | How hard it thinks: `low`, `medium`, `high`. Short Q&A rarely gains from more. |
| `maxOutputTokens` | `2048` | |
| `cacheTtl` | `5m` | `1h` keeps the knowledge base cached across longer gaps, at twice the price of writing it. |
| `monthlyBudgetUsd` | `50` | A hard ceiling: past it the assistant rests until next month. |
| `dailyBudgetUsd` | `0` | A hard cap per day. `0` means: a day may spend `dailyBurst` × its even share of what is left of the month. |
| `dailyBurst` | `2` | |
| `questionsPerDay` | `30` | Per player. Admins have no limit. |
| `proposalsPerDay` | `10` | Answers a player may propose per day. |
| `maxQuestionChars`, `maxProposalChars` | `300`, `1200` | |
| `historyTurns` | `3` | |
| `concurrentAnswers` | `4` | Claude calls at once; the rest wait. |
| `admins` | `[]` | Minecraft UUIDs (or names) with no daily question limit. (Mods before 0.45.1 also showed them an in-game Review tab.) |
| `discordWebhookUrl` | `""` | Optional. Treat it as a secret. |
| `discordQuestions` | | `channelId`, `answerRoleIds`, `approveRoleIds`, `pingRoleId`, `language` (`pl` or `en`): see *Answering on Discord*. Added to an older `assistant.json` on start. |
| `skipMojangCheck` | `false` | **Testing only**: trusts any name. Anyone could then pose as anyone. |
| `trustForwardedFor` | `false` | Behind Caddy/nginx on the same machine. |
| `crawl` | | What `crawl` reads and how politely: `userAgent`, `delayMillis` (1200), `site`, `forum`, `forumIds` (`[5]`, the announcements), `maxForumThreads`, `wiki`, `maxWikiPages`. |
| `distill` | | `model`, `effort` (`medium`), `chunkTokens` (60000). |
| `prices` | `{}` | Per-million-token prices for a model the server does not know. |

## What it costs

`kb-info` prints it for the knowledge base you have. As a guide, with a knowledge base of about
30,000 tokens (some sixty pages of text) and short answers, on the default model a question costs
about **$0.01–0.02** when the knowledge base is cached and **$0.15–0.20** when it is not (no
question in the last five minutes). Busy hours are mostly cached. Smaller models cost a fraction of
that; changing `model` is the biggest lever there is.

Building the knowledge base from about 700 pages costs a few dollars once; `distill` prints its
estimate and asks before spending.

The server never spends past `monthlyBudgetUsd`. Loading prepaid credits with automatic reload off in
the Claude Console is a second ceiling the server cannot get around.

## Who is asking

A player proves who they are the way they do joining any Minecraft server: the mod asks this server
for a one-time id, the game tells Mojang the player is "joining" it, and this server asks Mojang
(`hasJoined`) whether they did. The player's Minecraft token goes only to Mojang. The server then
hands out its own signed ticket, good for three days; nothing is stored per session. Per-player
limits hang on the verified UUID, and admins are recognised by it.

## Files

| Path | |
| --- | --- |
| `assistant.json` | Settings. |
| `api-key.txt` | The Claude API key (or set `ANTHROPIC_API_KEY`). Never commit or share it. |
| `kb/` | The knowledge base. `betterloka.md` (written on first start: the mod and the important places), `generated/` (from `distill`), `community.md` (approved answers), and anything you add. |
| `sources/` | What `crawl` read, one page per file. |
| `data/ledger.json` | Spending this month and today, and each player's counts today. |
| `data/questions.jsonl` | Every question: time, player, question, answer, status, cost. For improving the assistant; it holds names, so keep it private. |
| `data/community.json` | Open questions and proposals. |
| `data/secret.key` | Signs the tickets. Deleting it logs everyone out. |
| `data/distill/` | `distill`'s notes, so a rerun only pays for what changed. |
| `data/discord-questions.json` | Which Discord message shows which question and proposal. |
| `betterloka-bot.json` | The fallen-town bot's settings, and the bot token the Discord questions use. A secret. |
| `betterloka-bot-state.json` | Fallen towns already announced. |

## Where the knowledge comes from

`crawl` reads, at about one request a second, saying who it is (`BetterLoka-Assistant-KB/1.0`) and
keeping to each site's `robots.txt`:

- **lokamc.com** — every page in its sitemap except tools and account pages (map, accounts, link,
  cart, the lore editor, leaderboards).
- **forums.lokamc.com** — the first post of each thread in *News, Announcements & Server
  Information*; replies, members and search are not read.
- **loka.fandom.com** — through the wiki's own MediaWiki API (`api.php`), one page at a time. The
  wiki's text is CC BY-SA; the knowledge base keeps each fact's source.

Not read: Reddit (its robots.txt closes it to crawlers) and anything behind a login.

## Running it for real

Anything that runs Java 21 and has one open TCP port will do:

- **A VPS**: open the port in the firewall and run it as a service, e.g. systemd:
  ```ini
  [Service]
  WorkingDirectory=/opt/loka-assistant
  ExecStart=/usr/bin/java -jar betterloka-assistant.jar
  Restart=always
  ```
  For HTTPS, put Caddy in front (`reverse_proxy 127.0.0.1:8787`) and set `trustForwardedFor`.
- **A game-server panel** (Pterodactyl and the like) with a Java or "generic" image: upload the jar,
  its start command is `java -jar betterloka-assistant.jar`, and the port the panel assigns arrives in
  `SERVER_PORT`. Its console takes `status` and `stop`. Upload `api-key.txt` through the panel's file
  manager. Use its own server, not one that already runs your Minecraft world. The fallen-town bot
  runs in the same process, so it needs no server of its own.

## Building

```bash
./gradlew :assistant:assistantJar     # assistant/build/libs/betterloka-assistant-<version>.jar
./gradlew :assistant:test
```

The tests run the whole server over HTTP — log in, ask, rate, propose, approve, budget — and check
the request sent to the Claude API (cached knowledge base, fallback, structured output, effort)
against a stand-in for the API, and the Discord questions channel (posting, buttons, forms, roles,
approvals from either side) against a stand-in for Discord.
