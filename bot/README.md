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

- **`webhookUrl`** — Discord → the channel → Edit Channel → Integrations → Webhooks → New Webhook →
  Copy Webhook URL. Nothing else to set up.
- **`roleId`** — turn on Settings → Advanced → Developer Mode, then right-click the role → Copy Role
  ID. Leave it empty to post without pinging.

Then start it and leave it running:

```bash
java -jar betterloka-bot-<version>.jar
```

`--list` prints the towns currently standing as fallen and exits. `--once` runs a single sweep.

### As a bot user instead of a webhook

If you would rather it posted under its own bot account, set `botToken` and `channelId` instead of
`webhookUrl`. The application needs to be in the server with permission to post in that channel.
Everything else is the same.

### Environment variables

Every setting can be given as an environment variable instead, which is the better way to hand a
host a token: `BETTERLOKA_WEBHOOK_URL`, `BETTERLOKA_BOT_TOKEN`, `BETTERLOKA_CHANNEL_ID`,
`BETTERLOKA_ROLE_ID`, `BETTERLOKA_CHECK_SECONDS`, `BETTERLOKA_FULL_SWEEP_MINUTES`,
`BETTERLOKA_ANNOUNCE_BACKLOG`, `BETTERLOKA_STATE_FILE`. They win over the file.

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
