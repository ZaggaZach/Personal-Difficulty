# Changelog

## Unreleased (1.4.0 in progress, Phase 1: per-player control)

Not released; the mod version is unchanged. Elite behaviour is unchanged.

### Added
* **Per-player lock, allow-list and administrator assignment** with eight explicit rules (README, "Per-player control"):
  `admin assign <player> <difficulty> [lock]`, `admin lock`, `admin unlock`, `admin restrict <player> <d1>[,<d2>...] [switch|keep]`,
  `admin unrestrict`; `admin status` shows lock, allow-list, exception and who last changed it. All decisions are made by one
  pure `SelectionPolicy`; all admin permission checks go through one `PdPermissions.canAdminister`.
* **`players.json` format v2** (object records: `mode`, `locked`, `allowed`, `by`, `at`) with safe migration: v1 loads
  unchanged and is not rewritten on load; a timestamped byte-for-byte backup is made before the first v2 write; unknown
  entries and fields are preserved; writes are atomic and flushed; failed writes roll back; a corrupt file is backed up
  and a newer-format file is never overwritten.

### Changed
* `admin reset <player>` now also clears the lock and the allow-list (it still sets Normal).
* Administrative mode changes to an online player (assign, reset, `restrict ... switch`) now run the same transition hook as
  self-selection (entering Peaceful clears harmful effects). Offline players need no hook.
* Tab-completion offers a player exactly what they can choose now (none while locked).

### Fixed (found by the Phase 1 risk audit)
* **Invalid stored records fail closed.** An entry with an invalid known field used to be ignored, so the player silently became
  an unrestricted Normal player (dropping a lock, an allow-list and their stored mode). Such entries are now quarantined: kept
  verbatim, treated as locked, every change refused (even for operators) until `admin reset <player>` repairs them after a verified
  backup; `admin status` and `admin config` show them.
* **No more false success.** A change for a player whose entry was kept verbatim used to be reported as saved while the file kept the
  old entry, so it vanished on restart. Such changes are now refused.
* **The format version is judged before the structure.** A newer-format file with a different layout (or an uninterpretable version) used to be
  treated as merely corrupt and could be overwritten. Corrupt and unsupported files are now never overwritten: every change is refused with
  the reason, until the file is fixed and `admin reload` is run. Blank files are simply empty. A directory at the path is reported as such.
* A file changed behind the mod's back while the server runs is no longer overwritten by the next save.
* Identical backups are reused instead of piling up on every restart; every backup is verified byte for byte.

### Compatibility / downgrade
* **1.3.0 and earlier do not understand v2 object records.** Every player in a v2 `players.json` is an object, so they read **every
  listed player as Normal and unlocked**, including players whose stored mode was Hard or any other non-Normal mode. A later 1.3.0 write can
  rewrite the file with a `"version":1` header while keeping the v2 objects, producing a mixed file (1.4.0 reads it correctly).
  Downgrading is not safe without restoring a suitable backup: stop the server and restore the `players.json.backup-<millis>` made
  before the first v2 write (changes since then are lost).

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
