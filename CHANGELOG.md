# Changelog

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
