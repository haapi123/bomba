# BetterLoka

A client-side Fabric mod for **[Loka](https://lokamc.com)** (`play.lokamc.com`), Minecraft **1.21.11**.

BetterLoka adds an in-game menu, opened with a key you bind yourself, that pulls live data from
Loka's public API and from [EldritchBot](https://eldritchbot.com). It is entirely client-side: it
never talks to the game server and sends nothing about you anywhere.

## Modules

![The BetterLoka menu](docs/menu.png)

| Module | Status |
| --- | --- |
| **Player Finder** | Working |
| **Translator** | Working |
| **Loka Market** | Working |
| **Town Finder** | Working |
| **Town Logger** | Working |
| Fight Manager | Planned |
| Loka Helper | Planned |

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

### Ranked 1v1

![Ranked 1v1](docs/player-finder-ranked.png)

Below the Conquest record, both of Loka's ranked 1v1 ladders — Potion and Barebones — with this
season's **duels, wins, losses, win rate, rank** and ladder position, and underneath it the **best
rank they have ever held and the season they held it in**.

Loka publishes ladders rather than players, so a lookup means fetching a ladder and indexing it: the
current standings are two requests and arrive with the rest of the profile. "Best rank ever" needs
every past season's final table — about forty requests — so it is built once in the background and
the line fills in when it lands. Both are shared by every player you then look up.

A season's weekly snapshots are cumulative, which is what makes its **last published week** that
season's result and keeps this to one table per season rather than one per week. Players are followed
by UUID, not by name, because names change between seasons.

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

The lookup uses the player's Mojang account name from the player list, not the text on the
nameplate: Loka decorates those with a rank ("Duelist Rezorie"), and searching a stats service for
that finds nothing.

The badge is added where the game builds the label — `EntityRenderer.getDisplayName` — rather than
by editing the render state afterwards, so it goes through vanilla's own nameplate placement,
scaling and occlusion.

## Translator

![Translator](docs/translator.png)

For playing on an English-speaking server without speaking English.

- **Reading**: turn *Auto-translate* on and every chat line is translated into your language as it
  arrives. The Translator shows the translation with the original underneath.
- **Writing**: type a reply in your own language, press **Translate**, and **Copy** puts the English
  version on your clipboard — paste it into chat.

Messages are listed newest first as `Name: message`. Only actual chat is kept — join notices,
territory announcements and command output are filtered out, so the conversation is not buried and
no requests are wasted translating them.

**Town and alliance chat are translated too**, and marked as such: a `[Town]` or `[Alliance]` badge
and a coloured spine down the left of the card. Loka does not label those channels in the text — it
colours them, green for town and light blue for alliance — so the mod reads the colour off the
message rather than its wording, and matches by hue so a hand-picked shade still counts. Team chat
is also parsed more loosely once identified, since it is often written `Name » message` rather than
`Name: message`.

Both languages are pickers, so this works for any pair the translation service supports, not just
Polish and English. The mod never sends anything to the server itself; you always paste it yourself.

## Loka Market

![Named items on the market](docs/market-special.png)

Three views over Loka's market:

- **Search** — type an item and get every offer, cheapest per unit first, with the seller, the price,
  how many they have left and the price each.
- **Special** — every *named* item on sale, dearest first. This is where the one-off swords live.
  Plain lore is not enough to qualify: on Loka every imbued item carries "Imbued in <town>" lore, so
  only a player-given name marks a piece of gear out.
- **Overview** — total value listed, how many listings, items and sellers.

Every card is three columns: **what it is and who is selling it** on the left, **its enchantments**
down the middle, **what it costs** on the right. Enchantments are read out of the listed item stack
itself, so they show on search results as well — a bare Sharpness V book is as enchanted as a named
sword. Past five enchantments the rest are counted rather than listed, so one fully kitted sword
cannot fill the screen.

![Market overview](docs/market-overview.png)

Loka's API publishes what is on sale but **not how much currency exists on the server**, so the
overview says what it is showing rather than implying a money supply figure.

## Town Finder

![Town Finder](docs/town-finder.png)

A town by name: continent, level, strength, members, whether it is recruiting, and every territory it
currently holds with the number and beacon coordinates. Territories come from the Town Logger's sweep,
so this costs one request.

A name with no living town behind it is usually a town that is gone rather than one that never
existed, so the search falls back to the deleted-town roster and says so.

## Town Logger

![Town Logger](docs/town-logger.png)

Which towns have fallen, and what they were holding.

The distinction the module exists for: a territory that moves from one living town to another was
**taken in a fight** and says nothing about either side. A territory that Loka still records to a
town it no longer has means that **town was deleted or collapsed** — and that is what the first tab
lists, with the continent, the territory number and the beacon coordinates.

That works because Loka does not clear a deleted town's claims: the territories keep pointing at an
id that no longer resolves. So the whole standing backlog appears on the **first sweep**, without
having to have been running when it happened. Naming the town takes the deleted-town roster, since a
deleted town 404s on a lookup by id.

Three tabs: **Fallen towns**, **All changes** (captures, releases and claims as they happen) and
**Unowned** (every territory nobody holds). The log is written to `config/betterloka/town-log.json`
and survives restarts, so the baseline is not lost between sessions.

### What it costs to leave running

A sweep is three requests — one per continent — and about 850 KB, and runs every **10 minutes** by
default. The deleted-town roster is forty small requests and is refreshed at most once an hour, since
it only changes when a town dies. That is roughly **6 MB an hour**; the interval button offers 2, 5,
10, 30 and 60 minutes, **Check now** forces a sweep, and the whole thing can be switched off.

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

**Loka's own API** supplies rank, account age, town rosters, territory ownership, the deleted-town
roster and the battles running right now — the things EldritchBot does not track.

**Loka's ranked ladders** at `webapi.lokamc.com` are what the leaderboard page on the site reads, so
the mod reads them directly rather than scraping the page around them.

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

**Total currency on the server** is not available either — Loka's market endpoints cover listings,
not balances.

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
not-found path, case-insensitive lookup, the market, translation in both directions, both ranked
ladders and the territory sweep — and asserts that a whole profile still costs only a handful of
requests.

Two of those live checks are load-bearing assumptions rather than parsing:

- the ranked history is only usable per season because a season's weekly snapshots are **cumulative**,
  so the test asserts a later week includes the earlier one;
- the Town Logger only works because Loka **leaves a deleted town's id on its territories**, so the
  test sweeps every continent and resolves each dangling holder against the deleted-town roster.

## License

MIT. Not affiliated with or endorsed by Loka or EldritchBot.
