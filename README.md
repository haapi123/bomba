# BetterLoka

A client-side Fabric mod for **[Loka](https://lokamc.com)** (`play.lokamc.com`), Minecraft **1.21.11**.

BetterLoka adds an in-game menu, opened with a key you bind yourself, that pulls live data from
Loka's public API and from [EldritchBot](https://eldritchbot.com). It is entirely client-side: it
never talks to the game server and sends nothing about you anywhere.

## Modules

| Module | Status |
| --- | --- |
| **Player Finder** | Working |
| **Translator** | Working |
| Fight Manager | Planned |
| Loka Helper | Planned |
| Loka Market | Planned |

The planned modules are listed in the menu but open a placeholder screen for now.

## Player Finder

![Player Finder](docs/player-finder.png)

Type a Loka player's name, hit Search, and their whole Conquest record comes back in about a second:

- **Kills**, **deaths**, **assists** and **K/D**
- **Wins**, **losses**, total **fights** and **win rate**
- **Golems** and **lamps** taken
- **Potions**, **pearls**, **food** and **ancient ingots** used across their career
- Their **town** — level, continent, member count, whether it is recruiting
- **Who they currently fight for** — Loka lets players reinforce towns other than their own
- Their **nemesis**: whoever has killed them most
- **When they first appeared on Loka**, and whether they are **in a fight right now**
- Their **last 5 fights** — territory, side sizes, kills/deaths/assists, the towns, and the result

![Career statistics](docs/player-finder-stats.png)

Name lookup is case-insensitive.

### K/D above nameplates

The toggle at the top of the Player Finder puts each player's K/D beside their name tag in the
world, coloured the way Loka players already read it:

| K/D | Colour |
| --- | --- |
| below 1.0 | red |
| 1.0 and above | yellow |
| 3.0 and above | gold |

Ratios are fetched in the background as players come into view, cached for fifteen minutes, and
capped at a handful of requests in flight, so a full fight fills in over a few seconds rather than
all at once.

## Translator

![Translator](docs/translator.png)

For playing on an English-speaking server without speaking English.

- **Reading**: turn *Auto-translate* on and every chat line is translated into your language as it
  arrives. The Translator shows the translation with the original underneath.
- **Writing**: type a reply in your own language, press **Translate**, and **Copy** puts the English
  version on your clipboard — paste it into chat.

Both languages are pickers, so this works for any pair the translation service supports, not just
Polish and English. The mod never sends anything to the server itself; you always paste it yourself.

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/installer) 0.19.0+ for Minecraft 1.21.11.
2. Drop [Fabric API](https://modrinth.com/mod/fabric-api) into `mods/`.
3. Drop `betterloka-<version>.jar` into `mods/`.

Then open **Options → Controls → Key Binds**, find the **BetterLoka** category, and bind
**Open BetterLoka menu** to whatever key you like. It defaults to `L`.

Settings live in `config/betterloka/config.json` and are written as you change them in the GUI.

## Where the data comes from

**EldritchBot** parses Loka's Conquest fight logs and publishes per-player career totals. It has one
JSON endpoint (`/api/player/<name>` — kills and deaths only, and case sensitive), so everything else
is read from the server-rendered player page, and each fight's breakdown from the fight page it
links to.

**Loka's own API** supplies rank, account age, town rosters and the battles running right now — the
things EldritchBot does not track.

A full profile is about ten requests and lands in roughly a second. The card appears as soon as the
career totals arrive; the fight rows fill in behind it.

Two things are done deliberately in the HTTP layer, both measured against the live services:

- Requests are paced by a token bucket, one per host, and a 429 backs every thread off at once.
- The client speaks **HTTP/1.1 on purpose**. Over HTTP/2 the JDK client multiplexes every request
  onto one connection per host and parallel work serialises behind it — the same sweep measured 8x
  slower, with rate-limit backoff on top.

### What is not shown

**Charge success rate** is not available. EldritchBot publishes how many golems and lamps a player
has taken, but not how many they attempted, so a percentage cannot be computed without downloading
every fight that player has ever been in.

## Building

Requires JDK 21.

```bash
./gradlew build          # jar lands in build/libs/
```

## Testing

```bash
./gradlew test                            # offline: HTML/JSON parsing
./gradlew test -Pbetterloka.live=true     # also hits the live services end to end
```

The parsing tests run against fixtures shaped like the real markup; the live suite is what catches
those sites changing. It checks a real career, a real fight breakdown, the nameplate endpoint, the
not-found path, case-insensitive lookup, and translation in both directions — and asserts that a
whole profile still costs only a handful of requests.

## License

MIT. Not affiliated with or endorsed by Loka or EldritchBot.
