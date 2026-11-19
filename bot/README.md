# BetterLoka Discord bot

Pings a Discord role when a Loka town falls, so people can get to its territory while it is open.

A town that is deleted or collapses does not lose its claims straight away — the territories keep
pointing at a town that no longer exists until the server clears them. That gap is what this
announces, with the continent, the territory number and the beacon coordinates.

What lands in the channel:

> @Raiders **Brickstone** has fallen — its territory is open.
>
> **Brickstone**
> No longer exists, and still holds these 2 territories.
> **Kalros · #74** — `1963, 69, 1902` (icetaiga)
> **Kalros · #75** — `1571, 91, 1833` (icetaiga)

Several towns in one sweep are batched into a single message, ten to a post, which is Discord's cap.

## Running it

**Inside the assistant server.** The [Loka Assistant server](../assistant/README.md) runs this bot
in the same process, from the same `betterloka-bot.json` in its folder, so a hosting panel that runs
one program per server can host both. Everything below applies unchanged; the one difference is that
a config with nowhere to post turns the watch off instead of stopping the process. Run the bot in one
place only: two copies with the same token would take each other's command clicks and post every
fall twice.

**On its own:**

Runs on Java 21 or newer. Building it needs JDK 25, because the bot lives in the same Gradle build
as the mod and the mod's build tooling will not start on anything older:

```bash
./gradlew :bot:botJar        # lands in bot/build/libs/betterloka-bot-<version>.jar
```

First run writes a config template and stops:

```bash
java -jar betterloka-bot-<version>.jar
```

Fill in `betterloka-bot.json`:

```json
{
  "webhookUrl": "https://discord.com/api/webhooks/…",
  "roleId": "123456789012345678",
  "checkIntervalSeconds": 30,
  "fullSweepIntervalMinutes": 60,
  "announceBacklogOnFirstRun": false
}
```

- **`webhookUrl`** — Discord → **the channel you want the messages in** → Edit Channel →
  Integrations → Webhooks → New Webhook → Copy Webhook URL. Nothing else to set up, and no
  permissions to grant: a webhook *is* permission to post, scoped to one channel.

  A webhook is bound to the channel it was created on and carries it in the URL. `channelId` does
  not override that — it applies to the bot-token setup only. To post somewhere else, make the
  webhook on that channel.
- **`roleId`** — turn on Settings → Advanced → Developer Mode, then right-click the role → Copy Role
  ID. Leave it empty to post without pinging.

Then start it and leave it running:

```bash
java -jar betterloka-bot-<version>.jar
```

Setting `"testOnStart": true` in the config does the same thing at startup and then carries on
watching, which is easier on a hosting panel where the startup command is awkward to edit.

`--check <town>` prints the `/check` report on the console and exits, with no Discord involved.
`--test` posts one clearly-labelled test message, so you can confirm the webhook works and the role
actually gets notified without waiting for a town to fall. `--list` prints the towns currently
standing as fallen and exits. `--once` runs a single check.

If the role appears as plain text rather than a notification, make it mentionable:
Server Settings → Roles → the role → **Allow anyone to @mention this role**.

### More than one channel, or more than one server

`alsoPostTo` lists more places for the alerts, each with the role to ping there — a role belongs to
one server, so the main `roleId` cannot be pinged anywhere else:

```json
{
  "botToken": "…",
  "channelId": "111111111111111111",
  "roleId": "222222222222222222",
  "guildId": "333333333333333333, 444444444444444444",
  "alsoPostTo": [
    { "channelId": "555555555555555555", "roleId": "666666666666666666" },
    { "webhookUrl": "https://discord.com/api/webhooks/…", "roleId": "" }
  ]
}
```

A channel needs `botToken` and the bot invited to that server; a webhook needs nothing else. Each
place is posted to on its own, so one the bot cannot reach does not cost the others, and `/test`
reports each of them.

`guildId` takes several servers, separated by commas: the commands are registered in each of them
and appear at once. A server the bot has not been invited to yet is skipped with a line in the log.
With servers listed, any global registration left from before is cleared, so no command shows up
twice.

### As a bot user instead of a webhook

If you would rather it posted under its own bot account, set `botToken` and `channelId` instead of
`webhookUrl`. The application needs to be in the server with permission to post in that channel.
Everything else is the same.

## `/check <town>`

Answers with **when a town was founded and how recently each of its members has been seen**, so you
can guess which towns are close to being deleted.

This one needs a **bot token**. A webhook can only speak; a slash command has to be listened for, and
that means a connection to Discord's gateway. With `botToken` set the bot opens one on startup and
registers the command itself; without it, the fallen-town watch runs exactly as before and the log
says so.

1. https://discord.com/developers/applications → **New Application** → **Bot** → **Reset Token** →
   copy it into `botToken`. No privileged intents are needed — the bot asks for none.
2. **OAuth2 → URL Generator** → scopes `bot` and `applications.commands` → open the URL and add it to
   your server.
3. Optionally set `guildId` to that server's id (Developer Mode → right-click the server → Copy
   Server ID). With it the command appears immediately; without it, it is registered globally and
   Discord can take up to an hour to publish it.

Set `"enableCommands": false` to keep the watch and skip the gateway connection entirely.

### What "last seen" means, and what it does not

**Loka publishes no login times.** There is no `lastSeen` field on a player record, and no
online-players endpoint. So the report uses the newest of the two things that only happen while
somebody is actually playing:

- their most recent **Conquest fight**, from EldritchBot;
- the newest **market listing** they have up, whose ObjectID says when it was posted.

That is a **lower bound**, not a login time, and the reply says so under every report. Somebody who
logs in daily but neither fights nor trades shows as "no record" — which means "nothing published",
not "has not played".

### How much of the roster it checks

`maxMembersChecked` in the config, **100** by default. Set it to `0` for no limit.

The cap exists because each member is three requests, one of them a 30 KB page. EldritchBot is asked
by UUID rather than by name: it answers to either and returns the same page, but a name misses both
for somebody who renamed and for somebody who never fought, so name-with-a-UUID-fallback paid two
requests for every member of that second group — most of a large roster. (This cut request count, not
measured time; see the table.)

Time matters more than bandwidth here, because **Discord only allows a command fifteen minutes to
answer**; a report that runs past it cannot be delivered at all. Two measured points, against
Concord:

| Members checked | Wall time |
|---|---|
| 25 | 6 seconds |
| 200 | 3m24s, and 4m15s on a second run |
| 1217 (no limit) | **did not finish in 15 minutes** |

It is not a straight line, and the two 200-member runs are 25% apart, so it is dominated by what the
two remote services feel like on the day rather than by anything countable here. Size the limit from
the table, conservatively. The bot gives up at twelve minutes and says
the town is too big, rather than leaving a "thinking..." that never resolves, but the useful fix is a
lower `maxMembersChecked`.

For scale: Loka's median town has **92** members and the largest has **1217**.

Two things the cap never does:

- **The owner and every sub-owner are always checked**, on top of the limit. They are the people who
  decide whether a town survives, so losing them to an arbitrary cut would remove the point of the
  command.
- **The rest are an even spread across the roster, not the first N.** Loka stores members in the
  order they joined, so "the first hundred" means the hundred oldest accounts — the group most
  likely to be inactive — and reporting their activity as the town's would make every large town
  look dead.

The reply says how many of the roster it checked, and every activity count is stated as a fraction
of that, never of the roster. Discord caps an embed at 4096 characters, so a large check is
summarised rather than listed line by line however high you set the limit.

### Trying it without Discord

The same report runs on the console, which needs no bot application and no config:

```bash
java -jar betterloka-bot-<version>.jar --check "Hilo"
```

### Environment variables

Every setting can be given as an environment variable instead, which is the better way to hand a
host a token: `BETTERLOKA_WEBHOOK_URL`, `BETTERLOKA_BOT_TOKEN`, `BETTERLOKA_CHANNEL_ID`,
`BETTERLOKA_ROLE_ID`, `BETTERLOKA_GUILD_ID`, `BETTERLOKA_CHECK_SECONDS`,
`BETTERLOKA_FULL_SWEEP_MINUTES`, `BETTERLOKA_ANNOUNCE_BACKLOG`, `BETTERLOKA_TEST_ON_START`,
`BETTERLOKA_ENABLE_COMMANDS`, `BETTERLOKA_MAX_MEMBERS`, `BETTERLOKA_STATE_FILE`. They win over the
file.

### On a hosting panel (Pterodactyl and friends)

Pick a **Java 21** (or newer) docker image and set the startup command to `java -jar bot.jar`. Give the jar a
name that will not change between versions — the startup command points at it by name, so uploading
`betterloka-bot-0.9.0.jar` over `betterloka-bot-0.8.3.jar` would break it.

The first start writes the config and exits, which the panel reports as the server stopping. That is
expected: fill the config in and start it again.

## The server's own commands

With a bot token the bot also runs four commands for the Discord server itself. They are registered
together with `/check` and, until an admin changes it under **Server Settings → Integrations**,
only people with **Manage Server** (`/test`, `/suggestions`) or **Manage Roles** (`/verification`) see
them. Every reply to whoever typed a command is visible to them alone.

### `/test [ping]`

Is everything working, without waiting for a town to fall. It answers with:

- **Discord** — that it is connected (the answer itself is the proof);
- **Loka API** — one kilobyte-sized request, with how long it took and Loka's deleted-town count;
- **the fallen-town watch** — on or off (and why), how often it checks, when it last did and whether
  that worked, when it last swept every territory;
- **the last alert** since the bot started, and the last error posting one, if any;
- a **sample alert**, posted to every place the real ones go — the same webhooks or channels, the
  same embed — marked as a test, with how each one went. It pings the roles only with `ping: True`.

### `/verification role [channel] [title] [description] [button]`

Posts a message with a green **Verify** button. Whoever clicks it gets `role`; somebody who already
has it is told so, and nothing else happens. The role is carried in the button itself, so the
message keeps working across restarts and there is nothing to configure.

Discord only lets a bot give a role that sits **below its own highest role**, and only with **Manage
Roles**. The command checks both when the message is posted and says what to fix, rather than leaving
the first person to click to find out. `@everyone` and roles that belong to a bot or an integration
are refused.

A button rather than a reaction: a reaction needs the bot to read every reaction in the server (a
gateway intent this bot does not ask for), and a click can be answered — the person sees "Verified!"
at once, or why not.

### `/suggestions post_to [channel] [title] [text]`

Posts a panel — *"Want to add something to the mod? Press the green button and write your
suggestion!"* by default — with a green **Write a suggestion** button, in `channel` (the one the
command is typed in, if left out). The button opens a form with a **title** (3–80 characters) and the
**suggestion** (10–1500). Sent, it becomes the bot's own message **in `post_to`**: the suggestion with
its author's name and picture, numbered (`Suggestion #12`, counted in `betterloka-community.json` next
to the state file), with 👍 and 👎 to vote. A **thread** opens on it, named after the title, with the
author called in so they follow the discussion. The channel suggestions go to travels in the button,
so the panel keeps working across restarts.

The panel sits in a channel of its own and stays put. If `post_to` is the panel's own channel, the
panel moves back to the bottom after each suggestion instead, so the button is always where people
look. (Panels posted by 0.49, which had no `post_to`, keep working that way.)

One suggestion a minute per person.

For a tidy suggestions channel, take **Send Messages** away from everyone there and leave **Send
Messages in Threads**: the channel holds only suggestions, and the talking happens in their threads.

### `/stats setup [category]` and `/stats remove`

Live stat tiles at the top of the server: a category (**📊 Loka Live** unless named) of five voice
channels nobody can join, whose names are Loka's numbers —

```
🟢 Online: 49
⚔️ Fights now: 2 · 3 declared
🗡️ Most kills (Nov): ccarb · 163
💥 Most damage (Nov): Quinten7 · 41.4k
🏆 #1 Potion: drakelover6000 · Diamond III
```

- **Online** — the Minecraft server list ping to `statsServerAddress` (`play.lokamc.com`), the same
  question the game's server list asks; nothing logs in. If the server cannot be reached from the
  bot's host, the public status service `api.mcsrvstat.us` is asked instead (`statsStatusFallback`).
- **Fights now** — Loka's declared battles: those under way, and those still to come.
- **Most kills / most damage** this month — added up from every conquest battle that ended this
  month in Loka's battle archive: each record has every player's kills and `damageToPlayers`.
  Rivina's battles are left out, as in the mod (`statsCountRivina` to count them). The first read
  of a month goes back to its first day (about ten seconds); after that only new battles are read.
  The month is counted in `statsTimeZone` (`Europe/Warsaw`). Battle records name nobody, so the
  leader's name is one more request, made once.
- **#1 Potion** — the top of Loka's current ranked potion ladder, with their rank.

They are updated every **10 minutes** (`statsIntervalMinutes`, never less): Discord lets a channel be
renamed only **twice in ten minutes**, so a faster loop would just be refused. A channel is renamed
only when its text has changed, and one Discord holds back is tried again at the next update. A
number that cannot be read keeps the last one shown. Loka is asked only while some server has the
tiles. They are made with the numbers of the moment, so they are right from the start; drag the
category wherever you like. A tile deleted by hand is forgotten; `/stats remove` deletes the rest
and the category. Which channels are tiles is kept in `betterloka-community.json`.

Only people with **Manage Channels** see `/stats`.

### Permissions

The bot's role needs, besides what the alerts need: **Manage Roles** (verification), **Create Public
Threads**, **Send Messages in Threads**, **Add Reactions** and **Read Message History** (in the
suggestions channel), and **Manage Channels** (the stat tiles). Re-opening the bot's invite link with
these ticked updates an existing bot — or tick the permission on the bot's role under **Server
Settings → Roles**.

## What it does on the first run

**It does not announce the backlog.** There are usually a dozen or more towns standing as fallen at
any moment, most of them long dead, and pinging a role with all of them the first time somebody
starts the bot is a good way to get the bot muted. The first sweep records them quietly and
everything after that is news. Set `announceBacklogOnFirstRun` to `true` if you want them anyway.

What has been announced is kept in `betterloka-bot-state.json`, so a restart does not repeat itself.
A town that later loses *more* ground is announced again for the new territory.

The same file keeps Loka's deleted-town listing as last read and its count, so a **restart is not a
burst**: it looks the territories over once (five requests, one per world, to find whatever fell
while the bot was off) and reads the listing's forty-odd pages (Loka serves twenty towns a page,
whatever size is asked for) only when the count has moved — or when the listing is incomplete or
twelve hours old.

**No alert is lost on the way.** A count only counts as seen once the sweep it set off has finished:
a sweep that fails part-way (Loka answering 429 on page thirty, say) leaves it unseen, so the next
check, thirty seconds on, sweeps again. Each request of a sweep is tried four times, waiting 15, 30
and 45 seconds, before the sweep gives up. And an alert goes into a queue in the state file, one per
place, and leaves it only once Discord has taken it: Discord down, a missing permission put right
later, or a restart in between — it is tried again (after 30 s, 1 min, 2 min … up to every 30 min)
for a day, and each place that took it is not sent it again. Before 0.59 a count was taken as seen
before its sweep had finished, and an alert Discord refused was dropped; with the 429s of 0.55–0.58,
falls could go unannounced.

**To see it work**, type `simulate` in the console (or run the bot with `--simulate`): it reads Loka
as a sweep does, lists every town standing fallen with whether it was announced, shows the alerts
waiting for Discord, and posts one test alert — built as a real one, marked TEST — to every place.
`simulate ping` (`--simulate-ping`) pings the role as well. It records nothing. Before 0.58.2 every start read all of it, which with the stat channels' month of battles on top
is what made Loka answer 429 for a minute after each restart. The stat channels keep their tally of
the month's battles in `betterloka-bot-month.json` for the same reason, and say a refused potion
ladder (HTTP 403) once rather than every refresh.

## How it watches every 30 seconds without downloading a megabyte

A town falling **changes nothing in the territory list** — its claims keep pointing at the same id.
What changes is that the town joins Loka's deleted list. So the thing worth watching is the *size of
that list*, and asking for it costs one request of about **1.2 KB**:

```
GET /towns/search/findDeleted?size=1&page=0   ->  page.totalElements
```

That is the 30-second check. Only when the count moves — or once an hour as a backstop — does the
bot do the full job: the three territory requests (~850 KB) and the whole deleted-town listing
(~650 KB) needed to say *which* town fell and *where* its ground is.

| | Requests | Bandwidth |
| --- | --- | --- |
| Check, every 30s | 1 | ~1.2 KB |
| Full sweep, on a change or hourly | 44 | ~1.5 MB |
| **Total, idle hour** | ~164 | **~1.6 MB** |

Polling the territory list itself every 30 seconds would have been about 100 MB an hour for the same
answer. Loka sends no `ETag` or `Last-Modified` on that endpoint and no compression, so there is no
cheaper way to ask it directly — hence asking a different question instead.

`checkIntervalSeconds` has a floor of 10, and `fullSweepIntervalMinutes` is what actually decides the
bandwidth if you want to lower it.

## Why it is not part of the mod

The mod runs only while somebody's game is open, and it runs on *every* player's machine — so the
channel would get one ping per player per town, and nothing at all overnight. The bot is one process
watching on everyone's behalf.

It shares the mod's data layer rather than reimplementing it: `FallenTowns`, `LokaApi` and the
models are compiled straight out of `../src/main/java`, so the bot and the in-game Town Logger
cannot disagree about what counts as a fallen town.
