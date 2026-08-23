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

Requires Java 21. Build the jar:

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

`--check <town>` prints the `/sprawdz` report on the console and exits, with no Discord involved.
`--test` posts one clearly-labelled test message, so you can confirm the webhook works and the role
actually gets notified without waiting for a town to fall. `--list` prints the towns currently
standing as fallen and exits. `--once` runs a single check.

If the role appears as plain text rather than a notification, make it mentionable:
Server Settings → Roles → the role → **Allow anyone to @mention this role**.

### As a bot user instead of a webhook

If you would rather it posted under its own bot account, set `botToken` and `channelId` instead of
`webhookUrl`. The application needs to be in the server with permission to post in that channel.
Everything else is the same.

## `/sprawdz <town>`

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

The cap exists because each member is three requests, one of them a 30 KB page — four when the
player has renamed and the career has to be fetched again by UUID.

Time matters more than bandwidth here, because **Discord only allows a command fifteen minutes to
answer**; a report that runs past it cannot be delivered at all. Two measured points, against
Concord:

| Members checked | Wall time |
|---|---|
| 25 | 6 seconds |
| 1217 (no limit) | **did not finish in 15 minutes** |

It is not a straight line — the small run is several times faster per member than the large one — so
size the limit from the table rather than from a rate. The bot gives up at twelve minutes and says
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

Pick a **Java 21** docker image and set the startup command to `java -jar bot.jar`. Give the jar a
name that will not change between versions — the startup command points at it by name, so uploading
`betterloka-bot-0.9.0.jar` over `betterloka-bot-0.8.3.jar` would break it.

The first start writes the config and exits, which the panel reports as the server stopping. That is
expected: fill the config in and start it again.

## What it does on the first run

**It does not announce the backlog.** There are usually a dozen or more towns standing as fallen at
any moment, most of them long dead, and pinging a role with all of them the first time somebody
starts the bot is a good way to get the bot muted. The first sweep records them quietly and
everything after that is news. Set `announceBacklogOnFirstRun` to `true` if you want them anyway.

What has been announced is kept in `betterloka-bot-state.json`, so a restart does not repeat itself.
A town that later loses *more* ground is announced again for the new territory.

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
