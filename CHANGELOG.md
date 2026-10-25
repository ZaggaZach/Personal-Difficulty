# Changelog

## 1.3.0

Minecraft 26.3, Fabric Loader 0.20.1+, Fabric API 0.165.0+26.3, Java 25. Server-side only. Drop-in upgrade from 1.2.0 (all
existing files keep working; new config keys take their defaults, nothing is rewritten). Only **Elite** changes; the world's
vanilla difficulty is never touched.

### Added
* **Elite target priority.** Hostile mobs prefer an eligible Elite player over any other player, including one they are
  already fighting. Throttled, staggered per-mob re-evaluation (`elite.priority.scan-interval-ticks`) plus a preference inside
  the vanilla nearest-player goal. Stable (a mob never swaps between Elite targets while its target is valid), line of sight and
  all vanilla targeting rules respected, finite ranges, Peaceful never eligible, immediate response to mode switches, death,
  respawn and reconnect.
* **Hidden-target re-acquisition** (`elite.priority.reacquire-hidden-seconds`, default 8, 0 = off): a mob that lost sight of an
  Elite target may re-acquire that player without line of sight, only if its pathfinder finds a complete route.
* **Elite combat effects** (`elite.effects.*`): zombies and zombie villagers infect (Weakness + Hunger, deepening once), spiders
  and cave spiders slow, skeleton arrows slow, drowned slow. Applied only when the mob (or its arrow) genuinely damages an Elite
  player; bounded, never stacked without limit, never weakening a stronger existing effect.
* 15 new config keys (validated and clamped like the others), 2 new mixins (`MobHuntMixin`, `PathNavigationAccess`; 21 in total),
  `/personaldifficulty admin config` describes the new behaviour.
* Tests: 5 new unit tests (94 in total) and 30 new in-server tests (115 of ours + the framework's `always_pass` = 116), including
  live-AI tests for nine mob types, switching/recovery/oscillation/lifecycle tests, a real-arrow test and six mutation checks (see
  docs/VERIFICATION.md).

### Fixed / changed
* The 1.2.0 `elite.ai.*` acquisition now also applies when vanilla did find an ordinary player (previously only when it found
  nobody) while `elite.priority.enabled` is on.
* Found while testing 1.3.0: asking vanilla's `PathNavigation.createPath` "is there a route?" is not side-effect free (a repeated
  identical request returns the mob's active path). The route check uses a fresh pathfinder search instead.

### Not in this release (see README "Limitations" and docs/VERIFICATION.md)
Live-AI verification for any mob not named there (including witches, phantoms, piglins, hoglins, the Warden, endermen, blazes,
ghasts); effects for husks, strays, bogged, phantoms, pillagers and witches (deliberately none: vanilla already gives them
their own); infection spreading between players; real-client testing.

## 1.2.0

Minecraft 26.3, Fabric Loader 0.20.1+, Fabric API 0.165.0+26.3, Java 25. Server-side only. Drop-in upgrade from 1.1.0:
existing `players.json`, availability file and `personaldifficulty.properties` keep working (missing keys take their
defaults; nothing is rewritten). Only **Elite** changes.

### Added
* **Elite natural regeneration at half speed** (`elite.natural-regeneration-multiplier`, default 0.50, 0.1 to 1.0): the vanilla
  regeneration intervals (10 / 80 ticks) are stretched; amount per heal and hunger cost per heal are unchanged. Regeneration
  and Instant Health effects, golden apples, absorption and the `naturalRegeneration` game rule are untouched. Immediate on
  mode switch; survives respawn and reconnect.
* **Elite-specific hostile mob behaviour** (`elite.ai.*`): wider notice range (1.25x, respecting line of sight and
  visibility), longer chase range and pathfinding (1.25x), target memory 2x, shorter melee re-check, skeleton-family bow
  cadence of Hard difficulty. Target-specific; no attribute is modified; Peaceful players are never affected.
* 6 new config keys, validated like the others (invalid -> default, out of range -> clamped, both logged).
* 6 new mixins (19 in total): `NearestAttackableTargetGoal`, `TargetGoal`, `PathNavigation`, `MeleeAttackGoal`,
  `RangedBowAttackGoal` and an accessor for the skeleton's Hard interval; `FoodDataMixin` gained the two regeneration hooks.
* `/personaldifficulty admin config` and the server overview describe the new Elite behaviour.
* Tests: 11 new unit tests (89 total) and 25 new in-server tests (85 + framework `always_pass`), including live-AI tests for
  seven mob types, line-of-sight and range-cap checks, memory, pursuit, bow cadence, Peaceful compatibility and a
  mutation check.

### Not in this release (see README "Limitations" and docs/VERIFICATION.md)
Live-AI verification for any mob not named there; a live-AI measurement of the shorter melee re-check; witches (the test
found no extended acquisition); flanking/kiting/door-breaking; real-client testing.

## 1.1.0

Minecraft 26.3, Fabric Loader 0.20.1+, Fabric API 0.165.0+26.3, Java 25. Server-side only.

### Breaking changes
* **`inherit` is removed.** Normal is the default for everyone. Stored `INHERIT` values are read as `NORMAL` (see README,
  "Migration from 1.0.0"); the `players.json` format is unchanged and is not rewritten on upgrade.
* The command tree changed shape: the difficulty is now a validated argument instead of one literal per mode. Typing is
  identical (`/personaldifficulty elite`), but tab-completion is now live and policy-aware.
* `/personaldifficulty admin reset` now resets to Normal. `admin reload` also re-reads the availability policy.

### Added
* **Elite** difficulty: armour at 60 % effectiveness, then damage ×2.0 (applied once, after Resistance and Protection,
  before absorption), exhaustion ×2.0, and tighter aim (max uncertainty 1.0) for supported ranged enemies. All
  configurable (`elite.*`). Rationale and measured numbers in `docs/DESIGN.md` / `docs/VERIFICATION.md`.
* **Peaceful** difficulty: damage cancelled for every damage type except the void and `/kill`; PvP blocked in both
  directions with owner attribution (arrows, potions, TNT, tamed animals, clouds); mobs cannot target the player; no
  hunger or starvation; harmful effects blocked and cleared on entry. No world, game-mode, ability or attribute changes.
* **Operator-controlled availability**: `admin difficulties`, `admin enable <difficulty|all>`, `admin disable <difficulty>`.
  Persistent (`config/personaldifficulty-availability.json`), separate from players' saved choices, never erases a saved
  choice, never allows disabling the last difficulty, malformed files fail safe and are backed up.
* Aim adjustment for **piglin** crossbows (previously unsupported; needed a scoped hook because piglins are brain-driven)
  and **witch** potions. New toggles `ranged.piglins`, `ranged.witches`.
* `status` reports a restricted selection and what the player can select.
* 41 new unit tests and 41 new in-server tests (totals: 78 unit; 60 in-server plus the framework's own `always_pass`), a mutation check of the Peaceful hooks, and a smoke test of
  the built JAR on a standalone Fabric server.

### Fixed / corrected
* `PlayerSettings` now preserves entries it cannot understand instead of dropping them on the next save.
* `admin reset` for a player with nothing stored no longer implies a change.
* **Test-coverage correction for 1.0.0:** the 1.0.0 test described as covering "diamond armour + Protection IV + Resistance II"
  only exercised Protection and Resistance, because armour attributes are applied on a player's next tick and the test never
  ticked. Armour points are now genuinely in effect (and asserted) in every armour test. The 1.0.0 mod behaviour itself was
  not affected.

### Not in this release
Hard-mode ranged accuracy; aim adjustment for wither, ghast, blaze, breeze, snow golem, llama and modded shooters;
real-client testing (see `docs/VERIFICATION.md`).

## 1.0.0

First release. Per-player INHERIT/NORMAL/EASY/HARD, damage scaled once after vanilla mitigation with PvP/self/void/admin
exceptions, hunger scaling, per-shot Easy aim for skeleton family / illusioner / drowned / pillager, validated config,
UUID-keyed persistence, operator-only admin commands.
