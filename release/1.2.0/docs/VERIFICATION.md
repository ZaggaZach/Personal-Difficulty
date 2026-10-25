# Verification report: Personal Difficulty 1.2.0

Status keys: **PASS** = executed here and passed. **NOT RUN** = not executed here. **NOT IMPLEMENTED** = deliberately absent.
Nothing is claimed beyond what was run. 1.2.0 changes only Elite (natural regeneration and hostile-mob behaviour); everything
else is the 1.1.0 behaviour, re-run by the same regression suites. The 1.1.0 report is kept below as Appendix.

## 1. Toolchain
Unchanged from 1.1.0: Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.165.0+26.3, Loom 1.19.3, Gradle 9.8.2, Temurin
25.0.5+8.

## 2. Final results (branch `feature/1.2.0-elite-survival`, from `737652a`)

### 2.1 Build and unit tests: **PASS 89 / 89** (0 failed, 0 skipped)
`./gradlew clean build` -> BUILD SUCCESSFUL. AvailabilityPolicy 13, DamageAdjuster 17, **EliteSurvival 11 (new)**, HungerAndAim 13,
Migration 10, ModeAndConfig 11, PeacefulRules 6, PlayerSettings 8. New: interval stretching maths and rounding (0.5, 0.25, 0.1,
1.0, invalid inputs), range cap (never shrinks, 64-block cap, NaN/infinity), pursuit factor, memory cap, bow cadence,
defaults, and validation/clamping of every new config key.

### 2.2 In-server tests: **PASS 86 / 86** (85 ours + the framework's `always_pass`), 0 failed
General/1.0.0 regression 19, Elite 12, restrictions 9, Peaceful 13, Peaceful AI 7 (the 60 from 1.1.0, all still passing),
**Elite regeneration 11 (new)**, **Elite AI 14 (new)**. The complete suite was run at the final state **3 times in a row with
no failure** after the last code change (many more full runs happened during development, several with failures that were test bugs, see 2.6).

### 2.3 What the new tests prove

Test types: `ai_*` = real, unmodified vanilla goal AI of live mobs running for many ticks, only starting positions, a wall
or a mode switch injected. `direct_*` = hook called directly on an un-ticked mob (wiring only). Regeneration tests call the
real `FoodData.tick` (directly and through the real per-tick `ServerPlayer` path) with food/saturation held by the test.

**Natural regeneration (HP gained from 1 HP; Normal | Elite):**

| Case | Normal | Elite | Ratio |
|---|---|---|---|
| food 20, saturation 5.0, 160 ticks | 13.333 | 6.667 | 0.500 |
| food 20, saturation 2.4 | 6.400 | 3.200 | 0.500 |
| food 20, saturation 0.6 | 1.600 | 0.800 | 0.500 |
| food 20 / 19 / 18, no (or low) saturation, 480 ticks | 6.000 | 3.000 | 0.500 |
| food 17, 10, 0 | 0 | 0 | no natural regeneration in vanilla either |

* Easy, Hard and Peaceful heal exactly like Normal. Through the real per-tick path Elite is also exactly half.
* Same food buys the same number of heals (2 and 2 from food 19), the last one at tick 160 vs 320 (twice as slowly); with the
  Elite hunger multiplier on, each Elite heal charges exactly 2x exhaustion (the pre-existing 1.1.0 setting, shown separately).
* `LivingEntity.heal`, Instant Health I and II, the Regeneration effect (150 ticks) and Absorption are identical for Elite and Normal.
* Game rule `naturalRegeneration` off: nobody regenerates, mode switches while it is off neither enable regeneration nor change
  the rule; the rule is never modified by the mod (checked before and after).
* Mode switches take effect at once in both directions (Normal -> Elite -> Normal -> Peaceful). Toggling Elite/Normal every 1, 2, 3
  or 7 ticks for 150 ticks stays between the pure-Normal and pure-Elite result (no exploit). Death + respawn (new `FoodData`)
  and disconnect + reconnect keep the Elite pace; leaving Elite restores vanilla immediately.
* Custom multipliers 0.25, 0.2, 0.1, 0.5 are honoured within whole-tick rounding (6 %) after a live reload; 1.0 = vanilla; `NaN`
  falls back to 0.5. Peaceful after Elite: still no damage, vanilla regeneration with no hunger cost, and Elite damage x2 returns at once.

**Hostile-mob behaviour (live AI; control lane = Normal player with the same mob type at the same relative distance):**

| Mob | Vanilla follow range* | Player at | Normal lane acquisitions beyond range | Elite lane: acquired beyond range (at most 1.25x) |
|---|---|---|---|---|
| zombie | 35.7 | 39.9 | 0 | yes (1; max distance 40.0) |
| husk | 32.8 | 36.7 | 0 | yes |
| skeleton | 15.9 | 17.8 | 0 | yes |
| stray | 15.6 | 17.5 | 0 | yes |
| creeper | 17.3 | 19.4 | 0 | yes |
| vindicator | 11.7 | 13.2 | 0 | yes |
| pillager | 34.3 | 38.4 | 0 | yes |

\* vanilla gives every spawned mob its own random follow-range bonus, so each lane uses its own mob's range; values shown are one run.
In every test neither mob had any Personal Difficulty attribute modifier (FOLLOW_RANGE, MOVEMENT_SPEED, ATTACK_DAMAGE).

* **No wallhack:** a zombie with a solid wall between it and an Elite player inside the extended range never acquires them
  (0 ticks); an Elite player at 1.3x vanilla range (beyond the 1.25x acquisition range) is never acquired (0 ticks).
* **Pursuit (zombie):** both players start at 0.6x range and are acquired autonomously, then are moved to 1.12x range from where
  their hunter stands. The Normal zombie drops the target within 3 ticks; the Elite zombie keeps it, its pathfinder returns a
  *complete* route (vanilla: partial), and over 150 ticks it closed from 38.0 to 20.5 blocks while the Normal one stayed at 37.6.
* **Memory (zombie):** after a wall hides the player, the Normal zombie forgets after **61-62 ticks**, the Elite zombie after
  **121-122**; a player who leaves Elite at the moment of hiding is forgotten after 62 (no stale state).
* **Bow cadence (skeleton, 350 ticks, same distance):** 6 arrows at a Normal player, 9 at an Elite player (1.5x, because the
  draw time is unchanged; below the 2x of the interval alone).
* **Peaceful compatibility:** a Peaceful player 0.5x range away is never targeted by a live zombie; an Elite player whose zombie
  acquired them at extended range is dropped within 3 ticks of switching to Peaceful and never re-targeted.
* **Performance (informational, noisy test server):** 100 idle zombies with a player 62 blocks away (outside even the extended
  range): mean server tick 17.7 ms while Normal vs 14.5 ms while Elite in 100-tick alternating phases (JIT warm-up dominates; no
  measurable Elite cost); none of the zombies hunted. The test asserts only that ticks stay far below the 50 ms budget.
* **Direct (wiring only, un-ticked mob):** the hooks return vanilla values for Normal and Peaceful targets and the Elite values
  for an Elite target (`direct_hooks_returnVanillaForEveryoneButElite`).

### 2.4 Mutation checks: **PASS**
With one mixin removed at a time, the suite fails exactly where expected: without `PathNavigationMixin` the pursuit test
("route to the same distance is a complete one") fails; without `RangedBowAttackGoalMixin` the bow-cadence test fails; without
`TargetGoalMixin` the pursuit, memory and Peaceful-drop tests fail; without `FoodDataMixin` every regeneration test and the
1.1.0 hunger tests fail. A first version of the pursuit test passed even without the path hook (a zombie in open ground
still walks toward a partial path), so the route-completeness assertion was added: it is the evidence for that hook.
**Not covered by any mutation or live-AI measurement:** `MeleeAttackGoalMixin` (the shorter melee re-check).

### 2.5 Standalone dedicated-server smoke test of the final JAR: **PASS**
`personal-difficulty-1.2.0.jar` (+ Fabric API) in `mods/` of a genuine Fabric server (launcher, Loader 0.20.1, MC 26.3, Java 25),
loopback, offline mode, disposable world on Hard, driven from the console (three sessions):
* the mod loads, all 19 mixins apply (`defaultRequire` would abort startup otherwise), no exception in any log, clean shutdown;
* an existing 1.1.0-style config (no new keys) loads with defaults; a missing config is created including the six new keys;
* invalid values are rejected with logged warnings: on the final JAR `abc` -> default and `99` -> clamped to 4.0; `9` -> clamped to 2.0, `0.2` -> clamped to 1.0, `NaN` and `maybe` -> default were observed in an earlier session on the previous build (the config loader was not changed afterwards; the unit tests cover all of them);
* `admin config` describes the new Elite behaviour from the live values; `admin reload`, `disable elite`, `enable all`,
  `difficulties` work (checked on the final JAR); `/difficulty` stays **Hard** throughout (never changed);
* the existing `players.json` (with an unknown-mode entry) loads exactly as in 1.1.0.
NOT done in this test: a real player joining (see 3).

### 2.6 Notes on test development (honesty log)
* Development runs before the final state included failures that were test bugs, fixed and re-run: the Instant Health effect
  needs a tick to apply; the vanilla random follow-range bonus differs per mob, so lanes must use each mob's own range; mobs
  wander, so acquisition is judged at the moment of acquisition and mobs are kept on small islands; acquisition has a random
  delay, so premise checks wait longer.
* A first witch acquisition test found **no** extended acquisition; witches were removed from the claim rather than patched around.
* GameTest `@GameTest` has no batch option: each AI test builds its own platform 100 blocks up and 200 blocks apart.

### 2.7 JAR inspection: **PASS**
`build/libs/personal-difficulty-1.2.0.jar`, **84,942 bytes**, sha256 `d34f8a9875e56270abb81e25ace5e9923338ef7c99e96f883483d76ae8a6a4a5`
45 classes (19 mixins), `fabric.mod.json` (`environment: server`, `minecraft ~26.3`, `fabricloader >=0.20.1`, `java >=25`,
`fabric-api *`, version 1.2.0), mixin config (`defaultRequire: 1`, 19 mixins), default config, MIT licence. Class files are Java 25
(major 69). No `net.minecraft.client` references, no gametest classes, no bundled Loader/Fabric API/Gson. The source contains no
call that sets the world difficulty. The 1.0.0 and 1.1.0 release folders are unmodified.

## 3. NOT RUN
* **A real Minecraft client connecting.** No client is available here; nothing is claimed about real-client behaviour
  (animation of slower healing, how hunting mobs look, command suggestions).
* **Live AI for any mob not in the table in 2.3**, in particular: witches (not supported), spiders, drowned, bogged, parched,
  wither skeletons, piglins, hoglins, the Warden, the Ender Dragon, blazes, ghasts, breezes, endermen, phantoms, guardians, zombified
  piglins, zombie villagers and modded mobs. Bow cadence was tested for the skeleton only (the stray and the other skeleton
  variants use the same code; not run).
* The shorter **melee re-check** (`MeleeAttackGoalMixin`): wired and applied at startup, hook returns the expected value
  (direct test), but no live-AI measurement shows an effect.
* Multi-player crowds, nether/end behaviour, interaction with other mods that change mob targeting, long-running soak tests,
  multi-hour play-testing of the balance.
* Publishing: no push, pull request, tag, release or Modrinth upload was made.

## 4. NOT IMPLEMENTED
Flanking, group coordination, door breaking, dodging/kiting, per-mob tuning, any Elite change for mobs that do not use the
vanilla `NearestAttackableTargetGoal`/`TargetGoal`/`PathNavigation`, raising Elite mob damage or speed.

## 5. Remaining risks and recommended practical tests
1. Real-client behaviour is unverified; join with an unmodified client and watch healing (hearts should refill about twice as slowly).
2. Elite is now much harder; the numbers are a design estimate. Try a normal play session and adjust `elite.natural-regeneration-multiplier`
   (0.5 -> 0.7) and `elite.ai.*` if it is too much.
3. Open-field test: stand 40 blocks from a zombie, then a skeleton at 18 blocks, in daylight with line of sight, as Elite and as Normal.
4. Cave test: confirm mobs behind walls do not pick you out through terrain.
5. Mob farms/spawners next to an Elite player may produce more hunters at once; watch server tick time with a farm running.
6. Mods that replace targeting goals may bypass or conflict with the hooks.

# Appendix: Verification report for 1.1.0 (retained unchanged)


Status keys: **PASS** = executed here and passed. **NOT RUN** = not executed here. **NOT IMPLEMENTED** = deliberately absent.
Nothing below is claimed beyond what was run. Sections 8 and 9 are the ones to read before relying on the mod.

## 1. Toolchain

| Item | Value |
|---|---|
| Minecraft | 26.3 (the only version tested or claimed) |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.165.0+26.3 |
| Fabric Loom | 1.19.3 (`net.fabricmc.fabric-loom`) |
| Gradle | 9.8.2 |
| Java | Temurin 25.0.5+8 (class files are Java 25, major 69) |
| Mappings | none (26.x is unobfuscated); sources read via Loom `genSources` |

## 2. Baseline before any change (branch `feature/1.1.0-elite-peaceful`, from commit `b13ef8c`)

**PASS** 37/37 unit tests and 19/19 mod GameTests (+ the framework's `always_pass`): the 1.0.0 baseline, run untouched.

## 3. Final results

### 3.1 Build: **PASS**
`./gradlew clean build runGameTest` -> BUILD SUCCESSFUL.

### 3.2 Unit tests: **PASS 78 / 78** (0 failed, 0 skipped)
AvailabilityPolicyTest 13, DamageAdjusterTest 17, HungerAndAimTest 13, MigrationTest 10, ModeAndConfigTest 11,
PeacefulRulesTest 6, PlayerSettingsTest 8. Pure logic: modes (Inherit gone, legacy parse), config validation (NaN, infinity,
negative, huge, malformed, Elite keys), migration (every legacy value, restart stability, no rewrite on load, unknown/partly
corrupt/truncated/newer files, multiple UUIDs), availability policy (every mode on/off, last-one refusal, recovery, restart,
reload, rollback, malformed/all-disabled files), damage precedence, Elite maths against replicated vanilla formulas (design
table), hunger maths, aim maths, Peaceful decision matrix.

### 3.3 In-server integration tests (`./gradlew runGameTest`): **PASS 60 / 60** (+ framework `always_pass` = 61)
Real `ServerPlayer`s (survival, client-loaded, server-simulated), real mobs, projectiles, TNT, tamed wolves and live vanilla
AI inside a Minecraft 26.3 server with all 13 mixins applied. Suites: general/1.0.0 regression 19, Elite 12, restrictions 9,
Peaceful 13, Peaceful AI/targeting 7. Report: `docs/gametest-report.junit.xml`. The complete suite passed in **4 complete runs**: three at the pre-final state and once more at the final build (the only
difference between them is a corrected comment in the default config template); during development one live-AI test failed once because
its counter included a *neighbouring* arena's arrows (all arenas share one world) and was rewritten to track only that skeleton's
own arrows and target; it has passed in every run since.

Selected measured evidence (Normal-difficulty test world unless stated):

* **Damage, exactly once, in every world difficulty:** in Easy, Normal and Hard worlds a Hard player loses exactly 1.25x, an
  Easy player 0.6x and an Elite player the formula value of *that world's* vanilla result (Hard world: Normal 9.0, Hard 11.25).
* **Elite vs armour** (zombie hit, raw 3.0): see `docs/DESIGN.md`; netherite + Protection IV: Normal 0.242, Hard 0.302,
  **Elite 1.154** (4.8x vanilla, 17 hits to die). Every tier matched an independent computation from vanilla `CombatRules`
  within 0.2 %, and better armour strictly reduces Elite damage at each tier. Resistance II, Resistance V (immune), absorption
  hearts, shield blocking, invulnerability frames and the PvP/self/void/`/kill` exceptions behave as specified.
* **Elite hunger:** jump, sprint-jump, melee hit, block break and damage taken each cost exactly 2.0x (Hard 1.25x);
  100 sprint-jumps from 20 food / 0 saturation lose Normal 5, Hard 6, Elite 10 food points.
* **Aim** (800 shots per sample; spread = RMS direction deviation): Normal 0.059-0.062 (uncertainty 6), Easy 0.13-0.14,
  **Elite 0.0096-0.0100** for skeleton, stray, illusioner, drowned, pillager and piglin, witch (uncertainty 8: Normal 0.078,
  Elite 0.0099). A Normal player is shot exactly like a non-player target; projectile speed and mean arrow damage unchanged;
  switching target on the same shooter follows immediately; config toggles restore vanilla. In a Hard world: Elite 0.0099 vs
  vanilla 0.0197.
* **Elite is not a guaranteed hit:** spread-only hit fraction on a standing player: Elite 100 % at 12 blocks, 93 % at 24,
  77 % at 32; vanilla (Normal world) 13 % at 24, 8 % at 32. (In a Hard world vanilla is already tight, so the gain there is 2x.)
* **Peaceful damage:** all 51 registered damage types: 49 fully blocked, exactly `out_of_world` and `generic_kill` bypass.
* **Peaceful real hazards over real ticks:** lava, burning, cactus, magma block, sweet berry bush, suffocation, drowning,
  powder snow and falling each hurt a Normal control (10.5, 1.0, 1.5, 2.7, 1.5, 1.5, 2.0, 3.2, 3.2 HP) and never the Peaceful
  player.
* **Peaceful PvP:** all 20 ordered pairs of real melee attacks: any pair involving Peaceful dealt 0.00, every other pair 1.00.
  Real arrows, TNT, tamed wolves, thrown/indirect-magic/explosion damage sources follow the responsible player.
* **Peaceful vs mobs:** a real zombie spent up to 175 ticks within 2 blocks of a Peaceful player without ever targeting or
  hurting it; stayed hostile to a Normal player beside the Peaceful one; dropped its target within 3 ticks of a mid-fight
  switch and resumed on switching back; a live skeleton never targeted it nor shot near it (control shoots a Normal player).
* **Hunger, effects, lifecycle:** no exhaustion or food loss from heavy activity; banked exhaustion capped, not converted;
  starving player takes no damage while Peaceful and again after leaving; harmful effects refused and cleared on entry,
  beneficial kept; wither/poison/hunger-inflicting mob attacks inflict nothing; all 25 mode transitions take effect on the next
  hit with no attribute/ability/game-rule/world-difficulty change; `/kill`, void and `/damage ... generic_kill` still work;
  mode survives death, respawn and a disconnect/reconnect with the same UUID.
* **Restrictions/commands:** every mode selectable; disable blocks selection with a clear error and leaves the saved choice
  untouched; re-enable is live; a disabled mode a player already uses keeps being applied; suggestions never offer disabled
  modes (players and ops); permission matrix: console, command block and op (levels 2 and 3 tested) allowed, level 0/1 and
  no-permission sources denied for all seven admin subcommands, `/execute` needs level 2 itself and keeps the caller's
  permission; last-difficulty refusal and `enable all` recovery; file edit + reload applied live; broken/all-disabled
  edits rejected with the previous policy kept.

### 3.4 Mutation check: **PASS**
With the Peaceful hooks (`allowDamage`, `blocksTargeting`, `blocksEffect`) temporarily neutralised, **17 tests failed**
(including all four live-AI tests, the real-hazard, PvP, TNT, wolf, effect, hunger and lifecycle tests). Source restored
byte-for-byte. This shows those tests are sensitive to the feature, not vacuous.

### 3.5 Standalone dedicated-server test of the **built JAR**: **PASS** (run on the pre-final JAR, then repeated on the final JAR with identical results)
`personal-difficulty-1.1.0.jar` (+ Fabric API 0.165.0+26.3) in `mods/` of a genuine Fabric server (Fabric server launcher,
Loader 0.20.1, Minecraft 26.3, Java 25), local loopback, offline mode, disposable world on **Hard**, driven from the console:

* mod `personaldifficulty 1.1.0` listed, all 13 mixins applied, no mod errors/exceptions in any log;
* a seeded **1.0.0-format** `players.json` (INHERIT, NORMAL, EASY, HARD and an unknown `NIGHTMARE` entry) loaded:
  Alice(INHERIT)->NORMAL, Bob->NORMAL, Carol->EASY, Dave->HARD, the unknown entry kept with a warning, and the file was
  **byte-identical** after the session (no needless rewrite);
* `admin difficulties/disable/enable/reload/status/reset/config` work from the console; disabling the last enabled difficulty
  is refused; `enable all` recovers; console `easy` and `inherit` are refused with clear messages; `/difficulty` reported
  **Hard** before and after (never changed);
* restart: the availability policy survived, and the first rewrite kept Dave=HARD and the `NIGHTMARE` entry while dropping
  only the retired values and the reset player;
* corrupt data: a malformed availability file -> all enabled + warning + `*.backup-*`; a truncated `players.json` -> empty
  state + warning + `*.backup-*` (original recoverable); invalid config values defaulted/clamped with warnings; server started
  normally.

### 3.6 Built JAR inspection: **PASS**
`build/libs/personal-difficulty-1.1.0.jar`, 73,129 bytes, sha256
`f27a2bae6aa1d0a178bd4c9960120f89c7383a68d688ebff80ccdde5614305c1`. 37 classes (13 mixins), `fabric.mod.json`
(`environment: server`, `minecraft ~26.3`, `fabricloader >=0.20.1`, `java >=25`, `fabric-api *`), mixin config
(`defaultRequire: 1`), default config template, MIT licence. No `net.minecraft.client` references, no gametest classes,
no bundled Loader/Fabric API/Gson. The mod source contains no call that sets the world difficulty.

## 4. Corrections to earlier claims (1.0.0 documentation)

1. The 1.0.0 test described as "diamond armour + Protection IV + Resistance II mitigate first, then x0.6" did not apply armour
   *points*: armour attributes are only applied on the player's next tick and that test never ticked, so only Protection and
   Resistance were in effect. The 1.0.0 mod was unaffected (the hook point is correct), but the armour part of that claim was
   unproven until now. Armour points are now asserted to be in effect in every armour test.
2. The 1.0.0 "dedicated server smoke test against the final JAR" used Gradle's dev launcher (`runServer`), which runs the project's
   compiled classes, not the packaged JAR. The 1.1.0 smoke test above uses the packaged JAR on a standalone Fabric server.
3. The 1.0.0 "Easy/Hard damage-scaling exactly once" evidence was gathered in a Normal-difficulty test world, where vanilla's own
   scaling is x1. 1.1.0 verifies it in Easy, Normal and Hard worlds.

## 5. NOT RUN

* **A real Minecraft client connecting.** No client is available here. Not verified: that an unmodified client joins and plays;
  how the command suggestions render and whether the live "ask server" suggestion round-trip behaves in a real client;
  hurt animation/sound for cancelled Peaceful damage; behaviour over a real reconnect (simulated here by removing and re-adding a
  server-side player with the same UUID).
* **Live AI for mobs other than zombies and skeletons.** For 36 other vanilla mob types (including piglins, hoglins, the Warden,
  the Ender Dragon, creepers, spiders, phantoms, witches, pillagers) only `canAttack`/`setTarget` were checked directly on
  un-ticked mobs. That proves the gate is wired; it is not evidence of their live behaviour. Aim for piglins, witches, pillagers
  etc. was verified by invoking their ranged attack directly, not by letting their AI choose and fire.
* Bogged, parched and wither skeleton aim (same inherited method as the tested skeleton/stray); Hard-world Peaceful tests;
  dimension changes; explosion knockback on Peaceful players; interaction with other mods, totems, elytra, vehicles, lightning,
  end crystals and other attribution chains not listed in section 3.3.
* Play-testing of Elite balance. The defaults are a computed, measured design estimate, not tuned by play.
* Publishing: no push, pull request, release or Modrinth upload was made.

## 6. NOT IMPLEMENTED

* Hard-mode ranged accuracy change. Aim adjustment for wither, ghast, blaze, breeze, snow golem, llama and modded shooters.

## 7. Method notes

* The GameTest server defaults new players to creative and does not tick mock players: the tests set survival, make the server
  simulate movement (`isClientAuthoritative() = false`), and advance a player with `tick()` + `doTick()`; armour needs one such
  tick to take effect. Each of these initially produced a vacuous or failing test that was found by reading the failure, not
  assumed.
* Suffocation: vanilla puts a player whose head cell fills into the crawling pose, so the test forces the standing pose each tick
  to reach the real `in_wall` code path (models the first tick after a block lands on a standing player).
* AI tests share one world with neighbouring arenas, so they assert on *this* mob's target and *its own* arrows only.
* Maven Central returned intermittent 429/404 responses in this sandbox; a local caching mirror with retries (outside the
  repository) was used to resolve dependencies. Nothing in the project depends on it.

## 8. Remaining risks before practical use

1. Everything was exercised on a server side only; real-client behaviour is unverified (section 5).
2. Elite's difficulty is a design estimate; expect to tune `elite.*` after play.
3. Peaceful attribution is best-effort (README "Limitations"): unattributable harm to *non-Peaceful* victims is not blocked.
4. Peaceful forbids harmful effects from any source, including `/effect` by operators.
5. A corrupt `players.json` leaves players on Normal until an operator restores the `*.backup-*` copy.

## 9. Checklist for your own real-client test (Modrinth-managed Fabric 26.3 server)

1. Install Fabric API 0.165.0+26.3 and `personal-difficulty-1.1.0.jar`; start; confirm the log line `Personal Difficulty ready: ...`.
2. Join with an **unmodified** client. `/personaldifficulty status` should say NORMAL; type `/personaldifficulty ` and check the
   suggestions list `normal easy hard elite peaceful` (and `status`; `admin` only for ops).
3. With two accounts: set one to `peaceful`, the other to `elite`; confirm neither can hurt the other, mobs ignore the Peaceful
   player but attack the Elite one, and that the Peaceful player loses no hunger.
4. As an op: `/personaldifficulty admin disable elite`; the Elite player keeps playing on Elite; after they switch to normal they
   get a clear error trying to return; `/personaldifficulty admin enable all` restores it without a restart.
5. Restart the server and confirm choices and the availability policy persisted; `/difficulty` must still show your world difficulty.
6. Upgrading a 1.0.0 world: keep `world/personaldifficulty/players.json`; former `inherit` players appear as Normal.
