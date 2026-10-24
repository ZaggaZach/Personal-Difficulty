# Personal Difficulty

A **server-side-only** Fabric mod for **Minecraft Java Edition 26.3** that lets every player choose
their own difficulty on a shared server, regardless of the world's difficulty.

One player can pick **Easy** (less damage taken, shakier enemy archers, slower hunger) while everyone
else keeps playing on the server's Hard rules. Nobody installs anything on the client.

## Requirements

| | |
|---|---|
| Minecraft | 26.3 (Java Edition) |
| Fabric Loader | 0.20.1 or newer |
| Fabric API | `0.165.0+26.3` (the version this was built and tested against) |
| Java | 25 |
| Side | **dedicated server only** (`"environment": "server"`) |

Clients need nothing: the mod adds no blocks, items, registries, packets or resources.

## Installation (Modrinth App / manual)

1. Create or open a **Fabric 26.3** server instance (Modrinth App "Server" or a plain Fabric server).
2. Add **Fabric API** for 26.3 to the `mods` folder.
3. Drop `personal-difficulty-1.0.0.jar` into the same `mods` folder.
4. Start the server. The log should contain
   `Personal Difficulty ready: N explicit player selection(s) loaded from ...`.

> The mod does not load in single-player or LAN worlds (it is declared server-only on purpose).

## Commands

Everyone (no operator permission needed; only ever affects *yourself*):

| Command | Effect |
|---|---|
| `/personaldifficulty` or `/personaldifficulty status` | Show your mode, the effective numbers, and the world difficulty |
| `/personaldifficulty easy` | Easy profile |
| `/personaldifficulty normal` | Explicit vanilla: no personal changes |
| `/personaldifficulty hard` | Hard profile |
| `/personaldifficulty inherit` | Back to "never chose" (follow the world) |

Operators (permission level 2 "gamemaster") and the console:

| Command | Effect |
|---|---|
| `/personaldifficulty admin status <player>` | Inspect a player's stored mode |
| `/personaldifficulty admin reset <player>` | Reset a player to INHERIT |
| `/personaldifficulty admin config` | Show effective server configuration |
| `/personaldifficulty admin reload` | Re-read the config file; applies **immediately**, no restart or reconnect |

`INHERIT` (never chosen) and `NORMAL` (explicitly chose vanilla) both apply no changes today, but they
are stored as different states. The console cannot choose a mode for itself (it is not a player).
The vanilla `/difficulty` command and the world difficulty are never touched.

## What each mode does

| | INHERIT / NORMAL | EASY (defaults) | HARD (defaults) |
|---|---|---|---|
| Incoming damage | vanilla | x0.60 | x1.25 |
| Hunger (exhaustion) | vanilla | x0.50 | x1.25 |
| Ranged attackers' aim at you | vanilla | spread widened to at least uncertainty 14 | vanilla (not implemented) |

* **Damage** is scaled **once**, on the damage you would really lose in vanilla: after armour, resistance
  and protection enchantments, before absorption hearts. The world's own difficulty scaling (e.g. vanilla
  Hard's x1.5 mob damage) is already inside that number and is **not** applied again. So with the world on
  Hard, Personal Hard means "25 % more than this server's vanilla result". See `docs/DESIGN.md`.
* **Invulnerability frames, shields, totems and death** behave exactly as in vanilla. A multiplier can never
  heal or revive (negative values are impossible; 0 means "no damage").
* **Hunger** scales every exhaustion amount you accrue (sprinting, jumping, mining, fighting, taking damage,
  the Hunger effect, and the exhaustion cost of natural regeneration). Food level and saturation are never
  edited; regeneration speed is unchanged.
* **Ranged aim** changes only the projectile *spread* (vanilla's "uncertainty") for that one shot, only when
  the target is an Easy player. Arrow speed and damage are untouched, and nothing is changed on the mob, so
  it follows its target automatically.

### Exceptions (configurable, all default to "unchanged")

PvP damage, self-inflicted damage, void damage and administrative damage (`/kill`, anything tagged
`bypasses_invulnerability`) are not scaled by default. Each has its own option (see below).

## Configuration

File: `<server>/config/personaldifficulty.properties` (created with comments on first start).
Invalid values (non-numbers, NaN, infinity) fall back to the default; out-of-range values are clamped
(multipliers 0.0 - 10.0, uncertainty 0 - 60); every problem is logged as a warning.
Edit, then run `/personaldifficulty admin reload`.

```properties
easy.damage-multiplier=0.60
easy.hunger-multiplier=0.50
easy.ranged-min-uncertainty=14.0   # vanilla: Easy 10, Normal 6, Hard 2 (bigger = wider spread)
hard.damage-multiplier=1.25
hard.hunger-multiplier=1.25
pvp.damage-multiplier=1.0
self.damage-multiplier=1.0
environmental.apply-profile=true
void.apply-profile=false
admin.apply-profile=false
ranged.skeletons=true        # skeleton, stray (+ bogged/parched/wither skeleton via inheritance)
ranged.illusioners=true
ranged.drowned=true
ranged.crossbow-mobs=true    # pillagers
#easy.damage-type.minecraft.fall=0.3     # optional per-damage-type override
```

Player selections: `<world folder>/personaldifficulty/players.json`, keyed by UUID, written only when someone
changes their mode. Selections of offline players are never deleted. Separate worlds never share data.

## Limitations (please read)

* **Unsupported ranged attackers** (vanilla aim is unchanged against everyone): witches, withers, ghasts,
  blazes, snow golems, llamas, **piglin crossbows** (brain-driven, no target is passed to the shot),
  and every modded shooter. Skeleton-family mobs (skeleton and stray are tested; bogged, parched and wither skeletons inherit the same
  vanilla method and are covered by the same hook but untested), illusioners, drowned (tridents) and
  pillagers (crossbows) are supported.
* `/damage`, commands and other mods that deal damage via a normal damage type are scaled like any other
  damage of that type; only `/kill`-style and void damage are recognised as administrative.
* Other mods that bypass `Player.causeFoodExhaustion` / `FoodData` or replace vanilla damage handling may not
  be covered.
* Personal **Hard** has no ranged-accuracy component (not implemented): only damage and hunger.
* On an offline-mode server an unknown name given to `admin status/reset` cannot be told apart from a real
  one; the reply says "has no stored selection" rather than claiming a reset.

## Building from source

```
./gradlew build            # compiles, runs the 37 unit tests, builds build/libs/personal-difficulty-<v>.jar
./gradlew runGameTest      # 19 in-server integration tests (real ServerPlayer / mobs / mixins)
./gradlew runServer        # local dedicated server in run/ (accept the EULA in run/eula.txt first)
```

Needs JDK 25. See `docs/VERIFICATION.md` for exactly what was and was not verified, and `docs/DESIGN.md`
for the damage pipeline reasoning.

## Licence

MIT, see `LICENSE`.
