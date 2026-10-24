# Verification report

Status keys: **PASS** = executed and passed in this environment. **NOT RUN** = not executed here.
**NOT IMPLEMENTED** = feature deliberately absent. Nothing below is claimed beyond what was run.

## 1. Toolchain (verified, not assumed)

| Item | Value | Evidence |
|---|---|---|
| Minecraft | 26.3 (release, 2026-09-15) | Mojang version manifest `latest.release` = 26.3; Fabric meta lists `26.3` stable |
| Required Java | 25 | 26.3 `javaVersion.majorVersion` = 25; built with Temurin 25.0.5+8 |
| Fabric Loader | 0.20.1 | Fabric meta: newest stable (the brief's 0.19.5 is older; 0.20.1 matches the official example mod) |
| Fabric API | 0.165.0+26.3 | Modrinth: newest release for 26.3 (2026-10-23); Maven has the artifact |
| Fabric Loom | 1.19.3 plugin `net.fabricmc.fabric-loom` | Maven `release`; official template uses the 1.19 line |
| Gradle | 9.8.2 | `services.gradle.org` current; official template wrapper |
| Mappings | none (26.x is unobfuscated; Mojang names) | source jar from `genSources` uses Mojang names |

## 2. Compilation and packaging

* **PASS** `./gradlew clean build` -> BUILD SUCCESSFUL.
* **PASS** JAR `build/libs/personal-difficulty-1.0.0.jar` (46,504 bytes, sha256
  `04f799e36cf8ae57a7d68c0523917fefcc483c93498caa889a075c0f6b19e32b`, also in `release/SHA256SUMS`). Inspected: `fabric.mod.json` (`environment: server`,
  `minecraft ~26.3`, `fabricloader >=0.20.1`, `java >=25`, `fabric-api *`), mixin config, 25 classes (all
  Java-25 bytecode, major 69), no `net.minecraft.client` references, no gametest classes, no bundled Loader
  or Fabric API.
* No `remapJar`: 26.x is unobfuscated, so the built jar is the production jar.

## 3. Automated unit tests (`./gradlew test`) - **PASS 37 / 37**, 0 failed, 0 skipped

ModeAndConfigTest 10, DamageAdjusterTest 10, HungerAndAimTest 9, PlayerSettingsTest 8. Pure logic only:
mode parsing, INHERIT != NORMAL, defaults, NaN/Infinity/negative/huge/unparsable values, malformed file,
missing file, reload policy, damage precedence and exceptions, exactly-once, no-heal/no-NaN, hunger maths
(fractions preserved), spread maths, two-UUID persistence, restart, corrupt-file backup, rollback on failed
save, per-world isolation.

## 4. In-server integration tests (`./gradlew runGameTest`) - **PASS 19 / 19** (+ the framework's own `always_pass`)

Run inside a real Minecraft 26.3 server with the mod's mixins applied, using real `ServerPlayer` instances
(placed, "client loaded", survival; mock players are otherwise invulnerable) and real mobs. Results:
`build/reports/gametest/junit.xml`.

* Damage: Easy x0.60 / Normal x1 / Inherit x1 / Hard x1.25 for fall, lava, fire, drowning, in-wall; applied
  exactly once; real zombie melee equals 0.6 x a Normal player's loss (world scaling not re-applied);
  diamond armour + Protection IV + Resistance II mitigate first, then x0.6; PvP / self / void / `/kill`
  unchanged by default and changeable by config; per-type override; environmental gate; i-frames identical
  to vanilla; lethal damage still kills; multiplier 0 never heals.
* Hunger: x0.5 / x1 / x1 / x1.25 on `causeFoodExhaustion`; 1000 tiny steps accumulate exactly half; jump and
  natural-regeneration exhaustion halved while regeneration speed is identical; mode switching leaves food,
  saturation and exhaustion untouched.
* Ranged (600 shots per sample; same shooter, same geometry, only the target differs): spread against an Easy
  player is ~14/vanilla times wider (within +-25 % of expectation), a Normal player is shot at exactly like a
  non-player target (within 12 %), projectile speed and mean arrow damage unchanged, switching target back and
  forth is honoured. Covered: skeleton, stray, illusioner, drowned (trident), pillager (crossbow). Disabling a
  family in config restores vanilla aim; a Hard player's aim is vanilla.
* Commands: a player can set only their own mode without permission; there is no syntax to target another
  player; non-ops cannot use `admin`; ops can inspect/reset; level-0 rejected; unknown player not reported as
  reset; console can inspect but not choose; subcommand visibility is right for players vs ops (the client's
  tab-complete tree); vanilla `/difficulty` still exists; `admin reload` applies immediately, reports NaN as a
  warning, and keeps the previous config when the file is unreadable; world difficulty unchanged.
* Lifecycle: mode survives death and `PlayerList.respawn`, and is written to the world's `players.json`.

Negative controls inside the suite (so a pass cannot be vacuous): the ranged test fails if the world
difficulty would make Easy indistinguishable from vanilla; "family disabled" must equal baseline while
"family enabled" must be >1.5x wider.

## 5. Dedicated server smoke test (`./gradlew runServer`, local, offline-mode, loopback, disposable `run/`)

World difficulty `hard`; seeded `players.json` with Alice=EASY, Bob=NORMAL; driven via the server console.

* All items in this section were re-run against the final jar above.
* **PASS** server starts with the mod; log line `Personal Difficulty ready: 2 explicit player selection(s)`;
  all 7 mixins applied (a failure would abort startup).
* **PASS** `/personaldifficulty`, `status`, `admin config|status|reset|reload` execute; `bogus` rejected;
  console `easy` refused with an explanation.
* **PASS** `/difficulty` reports **Hard** before and after (world difficulty unchanged).
* **PASS** after `admin reset Alice` + `stop`, `players.json` contained only Bob=NORMAL.
* **PASS** restart on the same world: `1 explicit player selection(s) loaded`; Bob=NORMAL, Alice=INHERIT.
* **PASS** no mod-related errors, warnings or exceptions in either server log.

## 6. NOT RUN (honest gaps)

* **NOT RUN: a real Minecraft client connecting.** No client is available here. Therefore *not directly
  verified*: that a vanilla/unmodded client joins, that `/personaldifficulty` tab-completion renders in a real
  client, or that selections survive a *real* client reconnect. Reasoning only (not a test): the mod registers
  nothing synced to clients and sends no custom packets; reconnect safety follows from selections being keyed
  by UUID and independent of the entity (death/respawn is tested).
* **NOT RUN: a single end-to-end "player picks mode -> restart -> still set" flow.** The two halves were
  verified separately (command writes `players.json` in the gametest; the dedicated server reads that format
  after a restart).
* **NOT RUN:** live-AI scenarios (a skeleton's own goal selecting/changing targets); the tests call
  `performRangedAttack` on real mobs directly. Bogged / parched / wither skeleton and piglins untested.
* **NOT RUN:** dimension changes, real armour-durability/shield-blocking interplay, other mods.
* **NOT RUN:** upload/publish to Modrinth, GitHub release, push (not authorised).

## 7. NOT IMPLEMENTED

* Hard-mode ranged accuracy adjustment (optional in the brief).
* Aim adjustment for piglins (brain-driven, no target passed to the shot), witches, withers, ghasts, blazes,
  snow golems, llamas and modded shooters.

## 8. Environment note

Maven Central returned intermittent 429/404 responses in this sandbox; a local caching mirror with retries
(outside the repository, never part of the build files) was used for dependency resolution. Nothing in the
project depends on it.
