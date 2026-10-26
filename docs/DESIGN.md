# Design notes (verified against the Minecraft 26.3 sources)

Source facts below were read from the decompiled 26.3 jar (Loom `genSources`), not from memory.

## Damage pipeline and where the hooks sit

```
ServerPlayer.hurtServer
  Fabric ALLOW_DAMAGE ........ PEACEFUL: cancel here (victim Peaceful, or responsible player Peaceful)   <- this mod
  Player.hurtServer .......... world difficulty scaling for scalesWithDifficulty() sources
                               (Easy: min(d/2+1, d); Hard: d*1.5; Peaceful world: 0)                  <- vanilla, untouched
    LivingEntity.hurtServer .. shield blocking, helmet, i-frame check on RAW damage                      <- vanilla, untouched
      Player.actuallyHurt
        getDamageAfterArmorAbsorb ...... ELITE: armour only 60 % as effective                           <- this mod (@WrapMethod)
        getDamageAfterMagicAbsorb ...... resistance effect, then protection enchantments (vanilla)
          └── RETURN hook: profile multiplier (Easy 0.6 / Hard 1.25 / Elite 2.0), exactly once          <- this mod
        absorption hearts, then health, food exhaustion from damage, death
```

* **One multiplier, one place.** The profile factor is applied to the result of the last vanilla mitigation step, so it is
  an exact fraction of what vanilla would deal, i-frames (compared on raw damage earlier) are untouched, shields/totems/
  death run outside the hooks, and a factor >= 0 can never heal or revive.
* **No double application of the world difficulty.** The world's scaling happens earlier (`Player.hurtServer`) and is part of
  the number the hooks receive. The mod never reads the world difficulty to compute damage. Verified in Easy, Normal and Hard
  test worlds: Hard player = exactly 1.25x what a Normal player loses *in that world*.
* **Why mixins:** Fabric API's `ServerLivingEntityEvents` can cancel (`ALLOW_DAMAGE`) or observe (`AFTER_DAMAGE`) damage but
  cannot change its amount, so amounts need `@ModifyReturnValue`/`@WrapMethod`. Peaceful's cancellation uses the public
  `ALLOW_DAMAGE` event, no mixin.
* **A bug worth recording:** the armour hook was first written as a `RETURN` injection reading the method's `damage`
  parameter as "the original". Vanilla reassigns that parameter, so at `RETURN` it already held the post-armour value and
  Elite silently got a bare x2. Pure-function unit tests passed; the in-server armour-tier test (expected 4.056, saw 2.76)
  caught it. The hook is now `@WrapMethod`, which receives the true arguments.

Precedence for choosing the factor: void -> administrative -> self-inflicted -> other player (PvP) -> per-damage-type
override -> environmental gate -> profile multiplier. Exceptions that leave damage untouched also skip Elite's armour weakening.

## Elite: why an armour mechanic and not a bigger number

Computed from vanilla `CombatRules` (and re-checked in game by `EliteGameTests`). Damage to health from one hit, raw damage
into the armour step = 4.5 (a zombie's 3.0 on a Hard world); k = armour effectiveness, m = final multiplier:

| Armour | vanilla | Hard x1.25 | Elite m=2, k=1.0 (bare x2) | **Elite m=2, k=0.6 (shipped)** |
|---|---|---|---|---|
| none | 4.50 | 5.62 | 9.00 | **9.00** |
| iron | 2.21 | 2.76 | 4.41 | **6.25** |
| diamond | 1.10 | 1.38 | 2.21 | **4.92** |
| diamond + Prot IV | 0.40 | 0.50 | 0.79 | **1.77** |
| netherite + Prot IV | 0.38 | 0.48 | 0.76 | **1.75** |

With a bare multiplier (k = 1.0) fully enchanted netherite makes Elite trivial (0.76 HP per zombie hit, 1.3 per arrow).
Weakening only the armour step keeps Protection and Resistance fully effective (preparation still pays), cannot reduce
damage below what the unarmoured hit would be, never bypasses armour entirely, and never produces an unavoidable kill.
At k = 0.6 a fully enchanted netherite player takes ~4.8x vanilla and survives ~11-17 zombie hits but only 2-3 hits from a
heavy hitter (a Hard vindicator, raw 19.5, deals ~8.6); unarmoured and iron players die in 3-5 hits. These are design
estimates for the stated defaults, **not a play-tested balance**; every value is configurable.

In-game table (Normal-difficulty test world, raw 3.0, 20 HP):

| Armour | Normal | Hard | Elite | Elite hits to die |
|---|---|---|---|---|
| none | 3.000 | 3.750 | 6.000 | 3.3 |
| iron | 1.380 | 1.725 | 4.056 | 4.9 |
| diamond | 0.690 | 0.862 | 3.228 | 6.2 |
| netherite | 0.672 | 0.840 | 3.206 | 6.2 |
| diamond + Prot IV | 0.248 | 0.310 | 1.162 | 17.2 |
| netherite + Prot IV | 0.242 | 0.302 | 1.154 | 17.3 |

**Elite hunger:** exhaustion x2.0 at the source (jump, sprint-jump, melee, block break and damage taken measured exactly 2x;
100 sprint-jumps from full food with no saturation cost Normal 5, Hard 6, Elite 10 food points). Food and saturation are never
edited; vanilla regeneration rules are untouched.

**Elite aim:** `Projectile.shoot` adds `triangle(0, 0.0172275 * uncertainty)` per axis, so spread is linear in the uncertainty.
Elite uses `min(vanilla, 1.0)` (never loosens), Easy `max(vanilla, 14)` (never tightens). Hooks sit at the call that has the
target in scope; nothing is stored on the mob; projectile speed and damage are not touched (checked). Witch uses a
hard-coded vanilla uncertainty of 8. Piglins are brain-driven: `getTarget()` is empty during their crossbow shot, so
`PiglinAimMixin` exposes the brain target through a `ThreadLocal` for the duration of that one call only.

## Peaceful

* **Damage:** `ALLOW_DAMAGE` returns false before any vanilla processing when the victim is a Peaceful player, or when the
  responsible player (resolved through projectile owner, area-effect-cloud owner, TNT owner or tamed animal owner, a few
  levels deep) is Peaceful and the victim is a different player. Exempt: damage tagged `bypasses_invulnerability` (the void
  and `/kill`). All 51 registered damage types were enumerated in game: 49 blocked, exactly those 2 bypass.
* **Targeting:** `LivingEntity.canAttack` is the gate every goal (`TargetingConditions`) and brain sensor uses and is
  re-evaluated continuously, so a cleared target cannot restart. `Warden` and `EnderDragon` override it without `super`
  and get their own hooks; `Mob.setTarget` turns a direct request into `null` as a backstop. Only the Peaceful player is
  affected; others are targeted exactly as before.
* **Hunger:** `causeFoodExhaustion` and the regeneration cost return 0 for Peaceful; exhaustion banked earlier is capped at
  4.0 inside `FoodData.tick` so it can never turn into food loss. Starvation damage is cancelled by the damage rule.
  Regeneration rules are vanilla; because healing costs Peaceful no saturation, saturation is not spent, so a Peaceful
  player keeps healing at the top rate longer than a vanilla player (measured 13.3 vs 12.5 HP after 40 ticks from 10 HP).
* **Effects:** `addEffect(effect, source)` refuses harmful effects for a Peaceful victim and, by attribution, for any
  player victim when the source's player is Peaceful. On entering Peaceful, harmful effects and fire are removed.
* **Policy for exceptional cases:** void and `/kill` still work (no administrative immunity); `/damage` with an ordinary
  type is blocked like any damage; `/effect` of a harmful effect on a Peaceful player is refused.
* **Not attributable, therefore not blocked** (when the victim is not Peaceful): dispensers, lava/fire placed earlier,
  unowned TNT, lightning, falling blocks, pistons. A Peaceful *victim* is always safe.

## Availability policy

Separate file, separate class (`AvailabilityPolicy`), never touches `PlayerSettings`. It stores the *disabled* set, so an
absent file or a mode added later is enabled by default. Safeguards: the last enabled mode cannot be disabled; a file that
disables all five is invalid (all enabled, original backed up); a failed write rolls back; `admin enable all` always
recovers. The self-service difficulty is a validated Brigadier word argument with a live suggestion provider; the
permission-gated `admin` literal is enforced by the parser, so it cannot be bypassed through other command sources.

## Player data and migration

See README "Format v2 (1.4.0) and migration". Since 1.4.0 the format is v2 (object records); v1 files are read unchanged and migrated on
the first change after a verified backup. Unknown data is kept verbatim, unreadable entries are quarantined (fail closed), and corrupt,
unsupported or externally changed files are never overwritten (every change is refused until the file is fixed and `admin reload` is run).

## State and failure behaviour

* `PdRuntime` is one volatile reference installed in `SERVER_STARTING`, cleared in `SERVER_STOPPED`; hooks return vanilla
  values outside that window. The only other mutable static is the `ThreadLocal` used by the piglin hook, scoped to a call.
* Config: immutable record swapped on reload. Selections: `ConcurrentHashMap<UUID, Mode>`, written atomically only on change.
* `mixins.json` uses `defaultRequire: 1`: if a target changes in a future Minecraft version the server fails loudly at
  startup instead of silently losing a feature (13 mixins).

## Elite survival (1.2.0)

### Natural regeneration
`FoodData.tick(ServerPlayer)` regenerates on one shared counter `tickTimer`: with food 20, saturation > 0 and the player hurt,
every time the counter reaches **10** it heals `min(saturation, 6) / 6` HP and charges `min(saturation, 6)` exhaustion; with
food >= 18 (and not in that first branch) every time it reaches **80** it heals 1 HP and charges 6 exhaustion; at food 0 the
same counter reaches 80 for starvation damage. Both heal branches require the `naturalRegeneration` game rule to be on.
Options considered: (a) scale the healed amount - rejected, it changes how the exhaustion cost per HP relates to the heal and
creates fractional-health quirks; (b) cancel some ticks - rejected, needs per-player state and can be exploited by toggling;
(c) **stretch the two interval constants** - chosen. The two constants are replaced through `@ModifyExpressionValue` on the
`CONSTANT 10` / `CONSTANT 80` of the regeneration branches (ordinal 0; the starvation `80` is a different ordinal and is
untouched). The new interval is `max(vanilla, round(vanilla / m))` from the player's *current* mode, so it is stateless:
nothing to clear on a mode change, nothing to persist, nothing to exploit (a toggle between Elite and Normal every 1-7 ticks
lands between the pure-Normal and pure-Elite results). Amount per heal and exhaustion per heal are the vanilla code, so the
same food buys the same number of heals. The `naturalRegeneration` rule is checked by vanilla before this code is reached.
Healing that does not go through that branch (`LivingEntity.heal` callers such as golden apples, the Regeneration and
Instant Health effects, commands) is never intercepted.

### Hostile-mob architecture and what was hooked
Vanilla hostile-mob targeting is goal-based: `NearestAttackableTargetGoal<Player>` finds a target with a
`TargetingConditions` (range = `FOLLOW_RANGE` attribute, line of sight, visibility, selector, `canAttack`) and
`TargetGoal.canContinueToUse` keeps it while the target is alive, within the follow distance and not unseen for
`unseenMemoryTicks` (60). `PathNavigation.getMaxPathLength` is the `FOLLOW_RANGE` attribute, and `MeleeAttackGoal.canUse`
throttles with a 20-tick re-check. Skeleton-family archers use `RangedBowAttackGoal` with an interval set by difficulty.

| Concern | Hook | Evaluated from | Notes |
|---|---|---|---|
| Acquisition | `NearestAttackableTargetGoal.findTarget` (RETURN): if vanilla found nothing and the goal targets players, re-run the goal's own `TargetingConditions.copy().range(extended)` against **Elite** players only | the candidate's mode | line of sight, sneaking/head-gear, invisibility, selector and `canAttack` all still apply; cap 64 |
| Keep hunting | `TargetGoal.canContinueToUse`: the follow-distance read and `unseenMemoryTicks` read | the current target's mode | pursuit factor = max(follow, acquisition) |
| Route search | `PathNavigation.getMaxPathLength` | the mob's current target's mode | the attribute itself is untouched |
| Melee re-check | `MeleeAttackGoal.canUse` constant 20 | the mob's current target's mode | |
| Bow cadence | `RangedBowAttackGoal.tick` reads `attackIntervalMin` | the mob's current target's mode | `min(current, Hard interval)` |

Rejected: attribute modifiers on mobs (would be a global buff, linger after the target changes, and be visible to other
mods/commands); scanning every mob each tick (cost); wall-penetrating or radius-only detection (would be a wallhack). Because
everything is a function of the *current target's current mode*, there is no state to go stale: switch to Peaceful and the very
next goal check drops the target (Peaceful's existing targeting gate), switch to Normal and ranges, memory and cadence are vanilla
on the next tick.

### Cost
The only new per-mob work is the second-chance search, which runs only when the vanilla search already found nothing and only
if there is at least one Elite player in the level; it loops over the level's players with a squared-distance early-out before
any line-of-sight test. Idle mobs only do it at the goal's own search interval (every ~10 ticks). Measured: 100 idle zombies
with an Elite player just outside the extended range showed no measurable change in mean tick time (see VERIFICATION.md).

### Mob classification
Verified in live AI (autonomous goal selector, no injected targets): zombie, husk, skeleton, stray, creeper, vindicator,
pillager (acquisition); zombie (pursuit, route, memory); skeleton (bow cadence). Same code path but untested: drowned,
zombie villager, bogged, parched, wither skeleton, other illagers, zoglin-like melee mobs that use the same goals. Own targeting,
not covered or not claimed: spiders (night-only goal), witches (live test found no extended acquisition), endermen, piglins
and hoglins (brain-driven), the Warden (sensor-driven), the Ender Dragon, blazes, ghasts, breezes, phantoms, guardians,
the Wither, zombified piglins, modded mobs.

## Elite hunted (1.3.0)

### Why a new hook instead of only widening the existing goal
Vanilla re-runs target selection only when a mob has no target (`NearestAttackableTargetGoal.canUse`, throttled to ~1 in 10
ticks) or when `HurtByTargetGoal` fires. A zombie busy with a Normal player therefore never looks for anyone else. The 1.2.0
hook (second-chance search when vanilla found nobody) cannot change that. Options considered:

| Option | Verdict |
|---|---|
| Replace `Mob.targetSelector` goals with custom ones | rejected: breaks other mods, loses vanilla behaviour for every mob |
| Mixin into `NearestAttackableTargetGoal.canUse` only | insufficient: not evaluated while the mob already has a target |
| Tick every mob, scan all players every tick | rejected: cost |
| **Throttled per-mob check in `Mob.serverAiStep` + preference inside the vanilla goal** | chosen |

### The pieces
* `MobHuntMixin` (on `Mob`): one call per AI step into `PdHunt.huntTick`, which exits after a modulo test unless this is the
  mob's scan tick (`(tickCount + entityId) % scan-interval == 0`, so mobs are spread across ticks) and the mob owns a goal that
  picks players (cached tri-state computed once from `targetSelector`). Endermen and zombified piglins are excluded explicitly
  (their anger is provocation-based: forcing a target on them would turn a neutral mob hostile).
* `PdHunt.elites(level)`: the Elite players of a level, rebuilt at most once per game tick and only when some mob asks; an
  empty result is a shared constant, so levels without Elite players pay one `List.isEmpty()` per scan.
* Selection: the nearest Elite player that passes a `TargetingConditions.forCombat().range(r)` test (line of sight, invisibility,
  head gear, selector and `canAttack`, which already contains the Peaceful gate). `r = follow range x acquisition multiplier`,
  capped at 64 (never below the mob's own range). First in player-list order wins exact ties.
* **Stability rule:** a mob holding a live Elite target keeps it. Elite-to-Elite swaps therefore never happen while the target
  is valid. Distance changes cannot cause oscillation because distance is only consulted when choosing among candidates, i.e.
  when no Elite target is held.
* `NearestAttackableTargetGoalMixin`: (a) `canUse` returns false while the mob holds a live Elite target, otherwise the goal
  would start and replace it with the nearest ordinary player; (b) `findTarget` prefers an Elite candidate (extended range) over
  whatever vanilla found. (c) implements `PlayerTargetingGoal` so the mob can tell whether it hunts players.
* **Owned targets.** A target set by `huntTick` has no vanilla goal running over it (the goal is deliberately blocked), so
  nothing would ever drop it. `PdHunt.stillValid` enforces the rules a target goal would: alive; within the pursuit range
  (Elite: follow x pursuit factor, otherwise vanilla follow range); not unseen for longer than the memory (Elite: 60 x memory
  multiplier, otherwise 60 ticks). Counted at scan granularity. Found in testing: without this a hidden player stayed targeted
  forever, and a player who left Elite was never forgotten.
* **Hidden re-acquisition.** `HuntingMob` stores `(lastElite UUID, lastEliteSeen gameTime)` on the mob (a mixin field; it dies
  with the mob, so there is no registry to clean). It is refreshed only while the mob actually has line of sight. After the
  target is lost, within `reacquire-hidden-seconds` the same player is re-acquired iff they are still Elite, pass the targeting
  rules except line of sight, are within range and `PathNavigation.createPath(player, 0).canReach()` (one A* search per
  eligible mob per scan, bounded by the mob's follow range). A complete path never crosses solid blocks, so this follows
  doorways and detours and refuses sealed rooms.
* **Spiders.** `SpiderTargetGoal` and `SpiderAttackGoal` give up in daylight; forcing a target would make the mob drop it again
  every few ticks, so daylight spiders are skipped by the scan (vanilla rule retained).

### Combat effects
`ServerLivingEntityEvents.AFTER_DAMAGE` (Fabric API) fires once per applied hit and not for cancelled hits (so Peaceful and
invulnerability frames never reach it). It also fires for hits that Resistance or Protection reduce to zero, and the figure it
passes is the damage from the start of the pipeline, so "damage > 0" is not enough. `PdHooks.allowDamage` (already the first
listener for every hit) therefore notes the victim's `health + absorption` just before vanilla processes the hit, and
`PdMobEffects.afterDamage` requires that the total went down. A shield block is excluded by the event's `blocked` flag.
Attribution: melee requires damage type `mob_attack` whose *direct* entity is the attacker; arrows require damage type `arrow`
whose direct entity is an arrow *owned* by the attacker. Thorns, magic, fire, explosions, PvP and every other source fail one
of these. Per-family decisions use exact entity types (`ZOMBIE`, `ZOMBIE_VILLAGER`, `SPIDER`, `CAVE_SPIDER`, `SKELETON`,
`DROWNED`), so subclasses with their own mechanics (husk: vanilla Hunger; stray/bogged: effect arrows; cave spider: vanilla
poison) are not doubled up. Effects are refreshed, not stacked: duration `min(cap, max(remaining, base))`, amplifier fixed per
family, a stronger existing amplifier is left alone; the zombie infection deepens once (II + Nausea) if both Weakness and Hunger
are already present. Only Elite victims are considered, so Peaceful protection is untouched: a Peaceful hit never reaches
AFTER_DAMAGE, and `blocksEffect` additionally refuses harmful effects on Peaceful players.

### Cost
Per mob per AI step: one volatile read, a config read and one modulo. Per scan tick, mobs without a player-hunting goal and
levels without Elite players exit immediately. Otherwise: one pass over that level's Elite players (usually 1-3) with a squared
distance early-out before the line-of-sight test. No world scans, no per-tick allocation (the two `TargetingConditions` are
shared and the Elite list is reused within a tick).

