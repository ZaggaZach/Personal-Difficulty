# Personal Difficulty

A **server-side-only** Fabric mod for **Minecraft Java Edition 26.3** that lets every player choose their
own difficulty on a shared server, regardless of the world's difficulty, and lets operators restrict
which difficulties may be chosen.

| Difficulty | In one line |
|---|---|
| **Normal** | Plain vanilla (the default for everyone). |
| **Easy** | 60 % of the damage, half the hunger, enemy archers aim worse. |
| **Hard** | 125 % of the damage, 125 % hunger. |
| **Elite** | The hunted difficulty: weaker armour, double damage, double hunger, half-speed natural healing, sharper archers - and hostile mobs prefer you over everyone else and may leave you infected, venomed or slowed. |
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
2. Put **Fabric API 0.165.0+26.3** and `personal-difficulty-1.3.0.jar` in the server's `mods` folder.
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

#### Slower natural healing (1.2.0)
| Setting | Default | Meaning |
|---|---|---|
| `elite.natural-regeneration-multiplier` | **0.50** | Natural regeneration runs at this fraction of vanilla speed (0.1 to 1.0; 1.0 = vanilla). |

Vanilla heals from a tick counter inside `FoodData`: every **10 ticks** while you are saturated (food 20 with some
saturation) and every **80 ticks** when merely well fed (food 18+). Elite stretches that interval to `round(interval / m)`,
so the default gives 20 and 160 ticks: **exactly half the rate** in both cases, measured at 1-2.4-5.0 saturation and at
food 18, 19 and 20. The amount per heal and the exhaustion charged per heal are unchanged, so the same food buys the same
number of heals, only twice as slowly (with the Elite hunger multiplier on top, each of those heals costs 2x exhaustion as
before). Other multipliers are rounded to whole ticks (0.25 gives 40/320).
Only *natural* regeneration is affected. Not touched: the Regeneration and Instant Health effects, golden apples,
potions, absorption, beacons, `/effect`, `/damage` and the `naturalRegeneration` game rule, which the mod never reads or
changes (if you switch it off nobody regenerates, as before). The starvation damage timer is untouched. The rate is
read from the player's *current* mode every tick, so it changes the moment they switch mode, survives death, respawn and
reconnect, and there is nothing to bank by toggling (tested at switch periods of 1, 2, 3 and 7 ticks).

#### Smarter hostile mobs (1.2.0)
While an **Elite** player is the one being hunted, supported hostile mobs behave a little more like a determined hunter.
Nothing is changed for other players, nothing is a global mob buff, and **no attribute is ever modified** - every effect is
computed from the mob's current target, so it disappears the moment the target changes, the player leaves Elite (including
for Peaceful) or the mob dies. Nothing sees through walls and nothing is given extra damage.

| Setting | Default | Effect | Verified by a live-AI test? |
|---|---|---|---|
| `elite.ai.acquisition-range-multiplier` | **1.25** | An idle mob may notice an Elite player out to this multiple of its own follow range (1.0 = off, max 2.0). Line of sight, sneaking/head-gear/invisibility rules, selectors and `canAttack` still apply; Peaceful players are never candidates. | yes: zombie, husk, skeleton, stray, creeper, vindicator, pillager |
| `elite.ai.follow-range-multiplier` | **1.25** | A mob keeps hunting an Elite player, and its pathfinder searches for a route, out to this multiple (1.0 = off, max 2.0; the larger of this and the acquisition multiplier is used so that a target is never acquired and dropped on the next tick). | yes: zombie (target held, complete route found, closes in) |
| `elite.ai.improved-pursuit` | **true** | Master switch for the next setting and for a shorter melee "start chasing" re-check (5 instead of 20 ticks). | memory: yes (zombie); re-check: **hook-level only** |
| `elite.ai.target-memory-multiplier` | **2.0** | A mob forgets an Elite target it cannot see after 2x as long (vanilla 60 ticks; max 4.0, never above 400 ticks). | yes: zombie, 61 vs 122 ticks |
| `elite.ai.advanced-combat` | **true** | Skeleton-family bow cadence against Elite players is the vanilla *Hard* cadence (20 ticks between shots instead of 40) even in an Easy/Normal world. | yes: skeleton, 6 vs 9 arrows in 350 ticks |

No Elite adjustment ever reaches beyond **64 blocks** (unless the mob's own vanilla range is larger). Ranges are never
shrunk. If the world is already on Hard, the bow setting changes nothing (the cadence is the same). The existing
`elite.ranged-max-uncertainty` still governs *aim*.

Which mobs are covered: the acquisition, pursuit and memory hooks sit in the vanilla goals every "nearest player"
hostile uses (`NearestAttackableTargetGoal`, `TargetGoal`, `PathNavigation`), so any mob built on them is covered by
construction, but only the mobs in the table were actually run through the live AI and **only those are claimed**. Not
claimed (not tested, or known not to apply): spiders and cave spiders (their own night-only goal), witches (a live test
found no extended acquisition - not supported), endermen (provoked by gaze), phantoms, drowned, guardians, piglin-family
and hoglins, the Warden, the Ender Dragon, blazes, ghasts, breezes, the Wither, illagers other than the vindicator and
pillager, zombified piglins, and all modded mobs.

#### Hunted: hostile mobs prefer Elite players (1.3.0)
Hostile mobs now prefer an eligible Elite player over any other player - **including one they are already fighting**.

* **How.** Vanilla only picks a target when a mob has none, so a mob busy with a Normal player would never notice an Elite
  one. Every `elite.priority.scan-interval-ticks` (default 10, staggered per mob) a mob that is not already hunting an Elite
  player takes the nearest eligible one, and the vanilla "nearest player" goal prefers Elite candidates when it does run and
  will not replace a valid Elite target with an ordinary player.
* **Eligible** means: an Elite player (never Peaceful), alive, not a spectator, within the mob's acquisition range
  (`elite.ai.acquisition-range-multiplier` x its own follow range, capped at 64) **and** passing the mob's own targeting rules:
  line of sight, invisibility, head gear, `canAttack`. No wallhacks, no teleporting, no global awareness.
* **Stable.** A mob that already hunts a valid Elite player keeps that target; it never swaps between Elite players, so groups
  of Elite players cannot make it flip-flop. It switches only when its Elite target is gone (dead, Peaceful, out of range,
  forgotten, disconnected) and then takes the nearest remaining candidate.
* **Hidden targets.** A mob that lost sight of an Elite player keeps hunting (memory x2, see 1.2.0) and, for
  `elite.priority.reacquire-hidden-seconds` (default 8 s) after it last SAW them, may re-acquire that same player without line of
  sight - but only if its pathfinder finds a **complete route** to them within range (around obstacles, never through them; a
  sealed room is never entered).
* **Recovery.** When the Elite target becomes invalid the mob goes back to vanilla targeting and hunts ordinary players again
  within a few ticks. Switching to Peaceful drops the target within 3 ticks; switching to Elite makes a player eligible within one
  scan interval - no restart. Death, respawn, disconnect and reconnect (same UUID) are handled.
* **Who takes part.** Only hostile mobs that own a player-hunting goal. Passive and neutral mobs (cows, villagers, iron golems,
  wolves, **endermen, zombified piglins**) are never drawn in. Spiders obey vanilla's rule and stay neutral in daylight.
* **Retaliation.** If an ordinary player hits a mob that is hunting an Elite player, vanilla retaliation may briefly turn the mob
  toward them; the next scan turns it back to the Elite player.
* `elite.priority.enabled=false` restores the 1.2.0 behaviour (wider range only when vanilla found nobody).

Verified with live AI for: zombie, husk, skeleton, stray, creeper, spider and cave spider (in the dark), vindicator and
pillager. **Not verified, so not claimed:** every other mob (see `docs/VERIFICATION.md`).

#### Elite combat effects (1.3.0)
When one of these mobs **genuinely damages** an Elite player it may inflict a temporary effect. "Genuinely" means: the hit
was not cancelled (Peaceful, invulnerability frames), not blocked by a shield, and the player's health plus absorption actually
went down (so a hit that Resistance reduced to zero counts for nothing); and the cause is the mob's own melee attack or its own
arrow. Fire, poison, explosions, thorns, falls, PvP and every other source never trigger anything.

| Attacker | Effect | Chance | Duration | Notes |
|---|---|---|---|---|
| Zombie, zombie villager | **Infection**: Weakness I + Hunger I | 35 % | 8 s | Hit again while still infected: deepened once to Weakness II + Hunger II + Nausea (about 4 s); never beyond. |
| Spider | Slowness I | 35 % | 5 s | Melee. |
| Cave spider | Slowness II | 50 % | 6 s | On top of vanilla's own poison (Normal/Hard worlds). |
| Skeleton | Slowness I | 25 % | 4 s | **Arrow hits only**, once per damaging hit. |
| Drowned | Slowness I | 30 % | 3 s | Melee only (not thrown tridents). |
| Husk, stray, bogged, witch, others | nothing added | - | - | Husks already inflict vanilla Hunger; strays and bogged shoot arrows that already carry their own effect; witches already throw potions. |

Bounds: a refresh sets the duration to `max(remaining, base)` capped at `elite.effects.max-duration-seconds` (30 s), amplifiers
never exceed the values above, and a stronger effect from another source (a potion, say) is never weakened. Only **Elite**
victims are affected; other difficulties and Peaceful players never receive any of this. `elite.effects.enabled=false` turns
the whole feature off; normal attacks are unaffected.

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
armour effectiveness 0 to 1, regeneration 0.1 to 1, AI ranges 1 to 2, memory 1 to 4); every problem is logged. Edit, then `/personaldifficulty admin reload`.

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
elite.natural-regeneration-multiplier=0.50   # 0.1 .. 1.0, fraction of vanilla natural healing speed
elite.ai.acquisition-range-multiplier=1.25   # 1.0 .. 2.0
elite.ai.follow-range-multiplier=1.25        # 1.0 .. 2.0
elite.ai.improved-pursuit=true
elite.ai.target-memory-multiplier=2.0        # 1.0 .. 4.0
elite.ai.advanced-combat=true
elite.priority.enabled=true                   # hostile mobs prefer Elite players
elite.priority.scan-interval-ticks=10         # 5 .. 40
elite.priority.reacquire-hidden-seconds=8     # 0 .. 30, 0 = off
elite.effects.enabled=true
elite.effects.max-duration-seconds=30         # 1 .. 60
elite.effects.zombie-infection-chance=0.35    # per damaging hit, 0 .. 1
elite.effects.zombie-infection-seconds=8      # 1 .. 60
elite.effects.spider-venom-chance=0.35
elite.effects.spider-venom-seconds=5
elite.effects.cave-spider-venom-chance=0.50
elite.effects.cave-spider-venom-seconds=6
elite.effects.skeleton-shot-chance=0.25
elite.effects.skeleton-shot-seconds=4
elite.effects.drowned-chill-chance=0.30
elite.effects.drowned-chill-seconds=3
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

* **Elite priority is "prefer", not "omniscient".** A mob only ever prefers an Elite player it could legitimately target: within a
  finite range, in line of sight (or recently seen and reachable by a complete path), visible under vanilla's rules, never
  Peaceful. A mob that is hunting an Elite player may be briefly turned by vanilla retaliation if an ordinary player hits it; the
  next scan (default 10 ticks) turns it back. Only mobs that own a vanilla player-hunting goal take part: modded mobs with custom
  targeting, the Warden, piglins, hoglins, endermen and zombified piglins (provocation-based) and the Ender Dragon do not.
  Spiders stay neutral in daylight. In a crowd, every mob that can see an Elite player may go for them.
* **Combat effects are attributed conservatively.** Only the exact families in the table fire; ranged effects only for plain
  skeleton arrows; a hit must actually reduce health or absorption. Other mods that change `LivingEntity.hurtServer` or fire damage
  events in an unusual order may defeat the attribution. Infection does not spread between players (deliberately).
* **Effects stack on top of vanilla's own.** Cave spiders also poison on Normal/Hard worlds, husks inflict Hunger, strays slow with
  their arrows. Slowness II plus poison from a cave spider on a low-health Elite player is genuinely dangerous; tune
  `elite.effects.*` if that is too much.

* **Elite hostile-mob behaviour is not a full AI rewrite.** It widens notice/chase radius, extends the pathfinder's search
  length and target memory, and speeds skeleton bow cadence, for the mobs listed above. It does not add flanking,
  coordinated groups, door-breaking, dodging or ranged-mob kiting, and the shorter melee re-check is not measured by a
  live-AI test. A mob that cannot see you still cannot notice you.
* **Elite healing is slower, not different.** Regeneration is slowed by stretching the vanilla timer; a player can
  still out-heal damage with golden apples, potions, Regeneration and beacons, which are deliberately untouched.

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
./gradlew build            # compiles and runs the 94 unit tests -> build/libs/personal-difficulty-<version>.jar
./gradlew runGameTest      # 116 in-server integration tests (115 of ours + the framework's always_pass) (real ServerPlayer / mobs / mixins)
./gradlew e2eTest          # real dedicated server + headless client smoke test (downloads once, see docs/E2E.md)
./gradlew runServer        # local dev server in run/ (accept the EULA in run/eula.txt first)
```

Needs JDK 25. `docs/DESIGN.md` explains the damage pipeline and every hook; `docs/VERIFICATION.md` states exactly what
was and was not verified.

## Licence

MIT, see `LICENSE`.
