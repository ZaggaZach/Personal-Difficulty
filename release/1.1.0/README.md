# Personal Difficulty

A **server-side-only** Fabric mod for **Minecraft Java Edition 26.3** that lets every player choose their
own difficulty on a shared server, regardless of the world's difficulty, and lets operators restrict
which difficulties may be chosen.

| Difficulty | In one line |
|---|---|
| **Normal** | Plain vanilla (the default for everyone). |
| **Easy** | 60 % of the damage, half the hunger, enemy archers aim worse. |
| **Hard** | 125 % of the damage, 125 % hunger. |
| **Elite** | A serious survival challenge: weaker armour, double damage, double hunger, sharper enemy archers. |
| **Peaceful** | A personal safe mode: no damage (except the void and `/kill`), mobs ignore you, no hunger, no PvP either way. |

The world's actual difficulty is **never changed** by this mod. Different players can use different
difficulties at the same time. Nobody installs anything on the client.

> **Upgrading from 1.0.0:** the `inherit` difficulty no longer exists (it always behaved like Normal);
> Normal is now the default. Saved choices are migrated automatically, see [Migration](#migration-from-100).

## Requirements

| | |
|---|---|
| Minecraft | 26.3 (Java Edition). Other versions are untested and not claimed. |
| Fabric Loader | 0.20.1 or newer |
| Fabric API | `0.165.0+26.3` (the version this was built and tested with) |
| Java | 25 |
| Side | **dedicated server only** (`"environment": "server"`); clients need nothing |

## Installation (Modrinth App / manual)

1. Create or open a **Fabric 26.3** server instance.
2. Put **Fabric API 0.165.0+26.3** and `personal-difficulty-1.1.0.jar` in the server's `mods` folder.
3. Start the server. The log shows
   `Personal Difficulty ready: N non-default player selection(s) loaded from ... selectable: [...]`.

> The mod does not load in single-player or LAN worlds (declared server-only on purpose).

## Commands

### Everyone (no operator permission; only ever affects *yourself*)

| Command | Effect |
|---|---|
| `/personaldifficulty` or `/personaldifficulty status` | Your difficulty, the effective numbers, the world difficulty, what you can select |
| `/personaldifficulty normal` / `easy` / `hard` / `elite` / `peaceful` | Choose that difficulty (if the server allows it) |

Tab-completion offers only the difficulties the server currently allows. Choosing a disabled one gives a clear
error and changes nothing. Choosing the one you already have says "nothing changed".

### Operators (permission level 2) and the console

| Command | Effect |
|---|---|
| `/personaldifficulty admin difficulties` | Show which difficulties are enabled and how many players still use each disabled one |
| `/personaldifficulty admin disable <difficulty>` | Stop players from newly selecting it (refused if it is the last enabled one) |
| `/personaldifficulty admin enable <difficulty\|all>` | Allow it again; `all` is the recovery command |
| `/personaldifficulty admin status <player>` | A player's stored difficulty |
| `/personaldifficulty admin reset <player>` | Put a player back on Normal |
| `/personaldifficulty admin config` | Show the effective server configuration |
| `/personaldifficulty admin reload` | Re-read the config file **and** the availability file; applies immediately |

Permission is enforced by the command parser (not just hidden), so it holds for players, command blocks,
`/execute` chains and the console. A command block or the console cannot *choose* a difficulty (they are not
players); an operator can deliberately act as a player with `/execute as <player> run personaldifficulty <mode>`.
`inherit` is not accepted any more. The vanilla `/difficulty` command is never touched.

## The five difficulties in detail

### Normal
No modification. This is what you get until you choose something else.

### Easy and Hard
Defaults (configurable): Easy damage ×0.60, hunger ×0.50, ranged attackers aim with **at least** uncertainty 14;
Hard damage ×1.25, hunger ×1.25 (no aim change).

### Elite
| Setting | Default | Meaning |
|---|---|---|
| `elite.armor-effectiveness` | **0.60** | Armour removes only 60 % of what it normally would (Protection and Resistance are unaffected). |
| `elite.damage-multiplier` | **2.0** | Applied once, after armour, Resistance and Protection, before absorption hearts. |
| `elite.hunger-multiplier` | **2.0** | Every exhaustion amount you accrue is doubled. |
| `elite.ranged-max-uncertainty` | **1.0** | Supported ranged attackers aim at you with **at most** this spread (vanilla: Easy 10, Normal 6, Hard 2). |

**Why not just ×2?** A bare multiplier is cancelled by endgame armour: a zombie hit on fully enchanted netherite is
0.24 HP in vanilla, so ×2 would still be only 0.48 HP. With the armour mechanic Elite takes **1.15 HP** (about 4.8×
vanilla); unarmoured players take 6 HP per hit. Measured per-tier numbers are in `docs/VERIFICATION.md`.
Better armour still always helps: nothing is an instant kill and nothing bypasses armour entirely.

Order of operations for an Elite player's incoming hit (each step applies exactly once):
world difficulty scaling (vanilla) → shield → **armour at 60 % effectiveness** → Resistance → Protection enchantments →
**×2.0** → absorption hearts → health. Invulnerability frames compare the *raw* damage, exactly as in vanilla.

Elite does not weaken attacks against other players, does not touch PvP, self-inflicted, void or `/kill` damage
(same exceptions as Easy and Hard), and never changes any mob globally.

### Peaceful
A player-specific safe mode, similar in spirit to creative invulnerability but **without** changing game mode,
abilities, attributes or any game rule:

* **Damage:** every damage type is cancelled before vanilla processes it (no health loss, knockback, sound or on-hit
  effects), **except** the two types tagged `bypasses_invulnerability`: the **void** (`out_of_world`) and **`/kill`**
  (`generic_kill`). Peaceful is not an administrative immunity. `/damage ... minecraft:generic_kill` also works.
* **PvP, both directions:** a Peaceful player cannot be hurt by other players and cannot hurt other players, whatever
  the other player's difficulty. Attribution follows arrows, thrown potions, TNT, tamed animals and area clouds back to
  their owner. Ordinary PvP between non-Peaceful players is untouched.
* **Mobs:** hostile (and all other) mobs cannot acquire or keep a Peaceful player as a target, so melee and ranged
  attackers ignore you. Mobs behave normally toward everyone else; you are **not** a safe zone for nearby players.
* **Hunger:** no exhaustion accrues, so no hunger loss and no starvation damage. Your food and saturation are never
  refilled or drained; exhaustion banked before you switched is capped at vanilla's threshold. Natural regeneration works
  exactly as in vanilla and costs no hunger, so (because saturation is never spent) a Peaceful player keeps healing at the
  top rate longer than a vanilla player.
* **Effects:** harmful effects cannot be applied to you, and you cannot apply them to other players. On switching to
  Peaceful, existing harmful effects and fire are removed; beneficial effects stay. Leaving Peaceful restores
  everything immediately.
* Death, respawn and reconnect keep your choice.

Known limits are listed under [Limitations](#limitations).

## Server restrictions

Operators control which difficulties may be *newly selected* with `admin disable` / `admin enable`. It is a
server policy, completely separate from players' saved choices:

* Default: all five enabled. At least one always stays enabled (the last one cannot be disabled).
* Disabling a difficulty **never changes anyone's saved choice**. A player already on it keeps playing on it and keeps
  all its effects; they just cannot select it again after switching away. `status` and `admin difficulties` say so.
* Re-enabling is instant, no restart. Restrictions apply to operators' own selections too (they can simply re-enable).
* Stored in `config/personaldifficulty-availability.json` (`{"version":1,"disabled":["elite"]}`) and written
  atomically by the commands. A malformed file, or one that disables all five, is rejected: everything is enabled, a
  warning is logged and the original is kept as `*.backup-<time>`. `admin reload` re-reads it, and on a bad edit the
  previous policy stays in force.
* Recover from any mistake with `/personaldifficulty admin enable all`.

## Configuration

`<server>/config/personaldifficulty.properties` (created with comments on first start). Invalid values (not a number,
NaN, infinity) fall back to the default; out-of-range values are clamped (multipliers 0.0 to 10.0, uncertainty 0 to 60,
armour effectiveness 0 to 1); every problem is logged. Edit, then `/personaldifficulty admin reload`.

```properties
easy.damage-multiplier=0.60
easy.hunger-multiplier=0.50
easy.ranged-min-uncertainty=14.0
hard.damage-multiplier=1.25
hard.hunger-multiplier=1.25
elite.damage-multiplier=2.0
elite.hunger-multiplier=2.0
elite.armor-effectiveness=0.60
elite.ranged-max-uncertainty=1.0
pvp.damage-multiplier=1.0           # damage caused by another player (Easy/Hard/Elite)
self.damage-multiplier=1.0
environmental.apply-profile=true
void.apply-profile=false
admin.apply-profile=false
ranged.skeletons=true               # skeleton, stray (+ bogged/parched/wither skeleton by inheritance)
ranged.illusioners=true
ranged.drowned=true
ranged.crossbow-mobs=true           # pillagers
ranged.piglins=true
ranged.witches=true
#easy.damage-type.minecraft.fall=0.3   # optional per-damage-type override (easy|hard|elite)
```

Peaceful deliberately has no settings.

## Persistence

| What | Where |
|---|---|
| Server config | `<server>/config/personaldifficulty.properties` |
| Availability policy | `<server>/config/personaldifficulty-availability.json` |
| Player choices (UUID-keyed) | `<world>/personaldifficulty/players.json` |

Player choices are written only when someone changes their difficulty, atomically, and offline players' entries are
never removed. Only non-Normal choices are stored (Normal is the default).

### Migration from 1.0.0

The `players.json` format did not change, so there is **no rewrite on upgrade**:

* `EASY`, `HARD` (and `NORMAL`) are read as before; a stored `INHERIT` is read as `NORMAL`. This happens on every load, so
  it is idempotent, survives restarts and does not require anyone to join.
* The file is first rewritten the next time any selection changes; that write omits the retired `INHERIT`/`NORMAL` values.
* Entries this version cannot understand (an unknown mode, a malformed UUID key) are **kept verbatim** and written back,
  never dropped. A completely unreadable or newer-format file is copied to `players.json.backup-<time>` before anything
  else happens. Downgrading to 1.0.0 stays possible (it reads the same format).
* `/personaldifficulty admin reset` now means "back to Normal".

## Limitations

* **Ranged aim** is adjusted for: skeleton and stray (tested; bogged, parched and wither skeletons inherit the same
  vanilla method but are untested), illusioner, drowned (tridents), pillager (crossbow), piglin (crossbow, brain-driven;
  needed a dedicated hook) and witch (potions; vanilla witches always use uncertainty 8). **Not** adjusted: wither,
  ghast, blaze, breeze, snow golem, llama, and all modded shooters.
* **Elite aim is not a guaranteed hit.** In the Normal-difficulty test world a standing player was hit by the spread
  alone 100 % at 12 blocks, 93 % at 24, 77 % at 32 (vanilla there: 13 % at 24 blocks), so the benefit is mostly at range
  and against a moving target gravity and travel time still dominate. In a Hard world (vanilla spread 2) Elite is 2× tighter.
* **Peaceful attribution is best-effort.** Harm that cannot be traced to a player (dispensers, lava or fire they placed
  earlier, unowned TNT, lightning, falling blocks, pistons) is ordinary damage to a *non-Peaceful* victim. A Peaceful
  *victim* is protected from everything, whatever the source. Harmful effects cannot be applied to a Peaceful player by
  anything, including the `/effect` command. Explosion knockback is not damage and is not suppressed (not tested).
* "Mobs ignore you" covers vanilla mobs through their normal targeting. Live AI was verified for zombies and skeletons;
  for the other 36 vanilla mob types tested (including piglins, hoglins, the Warden and the Ender Dragon, which have their own hooks
  or brains) only the targeting gate was checked directly, not their live behaviour. Modded mobs with custom targeting may
  still chase you but cannot hurt you.
* Other mods that bypass `Player.causeFoodExhaustion`/`FoodData` or replace vanilla damage handling may not be covered.
* On an offline-mode server an unknown player name given to `admin status/reset` cannot be told apart from a real one;
  the reply says "has no stored selection" instead of claiming a change.

## Building from source

```
./gradlew build            # compiles and runs the 78 unit tests -> build/libs/personal-difficulty-<version>.jar
./gradlew runGameTest      # 61 in-server integration tests (real ServerPlayer / mobs / mixins)
./gradlew runServer        # local dev server in run/ (accept the EULA in run/eula.txt first)
```

Needs JDK 25. `docs/DESIGN.md` explains the damage pipeline and every hook; `docs/VERIFICATION.md` states exactly what
was and was not verified.

## Licence

MIT, see `LICENSE`.
