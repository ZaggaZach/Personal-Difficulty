# Verification report: Personal Difficulty 1.3.0

Status keys: **PASS** = executed here and passed. **NOT RUN** = not executed here. **NOT IMPLEMENTED** = deliberately absent.
Nothing is claimed beyond what was run. 1.3.0 changes only Elite (target priority and combat effects). The 1.2.0 report is kept
below as Appendix A, the 1.1.0 report inside it as Appendix B.

## 1. Toolchain
Unchanged: Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.165.0+26.3, Loom 1.19.3, Gradle 9.8.2, Temurin 25.0.5+8.
Fabric API's `ServerLivingEntityEvents.AFTER_DAMAGE` signature `(LivingEntity, DamageSource, float, float, boolean)` was confirmed by
decompiling the shipped `fabric-entity-events-v1-6.0.4` jar; vanilla internals were read from the decompiled 26.3 sources.

## 2. Final results (branch `feature/1.3.0-elite-hunted`, from `c230e54`)

### 2.1 Build and unit tests: **PASS 94 / 94**
`./gradlew clean build` -> BUILD SUCCESSFUL. New: `EliteHuntConfigTest` (5): defaults, absent file = defaults, valid values, 12
malformed/out-of-range values (each warned, clamped or defaulted), every new key present and documented in the shipped default file.

### 2.2 In-server tests: **PASS 116 / 116** (115 ours + the framework's `always_pass`)
The 85 tests of 1.2.0 (all still passing) plus **30 new**: `EliteHuntGameTests` 19 and `EliteEffectsGameTests` 11. The complete suite
passed **3 times in a row** on the final build (and 3 + 2 times on the two builds before it).
`ai_*` = live, unmodified vanilla goal AI over many ticks; `direct_*` / `sync_*` = a real mob's real attack, or a real damage source,
inside one server tick (needed because the config differs from the default and is global while tests run in parallel).

### 2.3 What the new tests prove

**Priority per mob family** (live AI; lane A control: two Normal players, lane B: a Normal player at 0.35x the mob's follow range and an
Elite player at 0.7x, both in line of sight; one run shown):

| Mob | Follow range | Control: nearer Normal hunted (ticks of 120) | Elite lane: Elite hunted / nearer Normal hunted after tick 25 |
|---|---|---|---|
| zombie | 37.9 | 109 | 116 / 0 |
| husk | 35.9 | 95 | 118 / 0 |
| skeleton | 17.4 | 102 | 119 / 0 |
| stray | 15.9 | 120 | 117 / 0 |
| creeper | 16.4 | 117 | 119 / 0 (exploded at the end, as designed) |
| spider (in the dark) | 14.6 | 110 | 119 / 0 |
| cave spider (in the dark) | 16.0 | 107 | 119 / 0 |
| vindicator | 12.4 | 114 | 120 / 0 |
| pillager | 31.5 | 117 | 118 / 0 |

* **Switching:** a zombie hunting a Normal player (Normal lane) or an Easy player (Easy lane) takes a newcomer who *becomes* Elite within
  20 ticks and stays on them; drops them within 3 ticks when they turn Peaceful and never targets them again; recovers to the
  ordinary player; takes the newcomer again within 20 ticks after they turn Elite once more. No restart.
* **Several Elite players:** 0 flip-flops in 120 ticks; after the hunted one's position was changed so that the *other* Elite player
  became nearer (still in range, still visible), the zombie stayed on its target; when the hunted one left Elite it moved on to the
  other Elite player.
* **Limits:** an Elite player behind a solid wall is never selected (ordinary player hunted instead); an Elite player at 1.7x the follow
  range (beyond the 1.25x acquisition range) is never selected.
* **Hidden targets:** after losing its target a zombie re-acquires a hidden Elite player it recently saw when a doorway makes a
  complete route; a zombie never re-acquires a player in a sealed stone room (memory injected into that mob because it never had
  line of sight); a hidden Elite target is kept for its extended memory (not swapped for a visible ordinary player) and forgotten
  after it (122-130 ticks vs 61 for an ordinary target); a held Elite target cannot be replaced by the nearest-player goal.
* **Lifecycle:** death (stops being the target), respawn, disconnect and reconnect with the same UUID: hunted again each time.
* **Passive / neutral:** cow, villager, iron golem, wolf, zombified piglin and enderman near an Elite player never target them.
* **Config:** with `elite.priority.enabled=false` (and no widened range) a zombie hunts the nearer ordinary player like vanilla;
  with it on, the Elite player (single-tick test, manual mob ticks).

**Combat effects** (real mob attacks through `doHurtTarget`; chance forced to 1.0 where stated):
* zombie hit infects an Elite player: Weakness I + Hunger I for at most 8 s, exactly one application for one hit; Normal, Easy, Hard
  and Peaceful players never receive anything (Peaceful takes no damage either).
* a shield-blocked hit and a hit reduced to zero by Resistance V apply nothing; the same zombie without Resistance does.
* 12 unrelated sources (fall, fire, magic, explosion, thorns, indirect magic, PvP, other mobs' attacks, cactus, drowning, a zombie's
  projectile damage) never trigger an attacker effect.
* spider: Slowness I <= 5 s; cave spider: Slowness II <= 6 s (plus vanilla poison); drowned melee: Slowness I <= 3 s; zombie villager:
  infection; husk: nothing added.
* chance 0 never applies (100 hits); the default 0.35 landed 203 / 213 / 199 of 600 hits across runs (expected 210 +/- 36); duration
  3 s is honoured; a second infection while ill deepens to Weakness II + Hunger II + Nausea, forty more hits never exceed amplifier II
  or the configured 20 s cap; a Weakness III potion is never weakened; `elite.effects.enabled=false` leaves attacks landing and hurting
  with no effect; malformed values fall back safely.
* **live skeletons** (12) shot an Elite player: 19-42 arrow hits per run (five runs), 6-10 applications, **never more than one application per hit**
  (measured per damage event), not every arrow; **8-16 strays** landed 3-40 arrows (across runs) in the same period and added **no** effect.

**Performance (informational, parallel noisy test server):** 120 engaged zombies with one Elite player far away: 16.8 ms mean tick
without any Elite player online vs 16.3 ms with one online (no measurable cost). Dedicated server: 40 idle zombies, 0.4 ms average per tick.

### 2.4 Mutation checks: **PASS**
One critical hook neutralised at a time; the unmodified source was restored byte-for-byte after each (verified with `cmp`):

| Mutation | Result |
|---|---|
| `huntTick` disabled | 4 tests fail (switching, oscillation/recovery, hidden re-acquisition, lifecycle) |
| nearest-player goal guard removed | 1 test fails (held Elite target replaced by a visible ordinary player) |
| effects listener disabled | 8 effect tests fail |
| "health actually went down" check removed | the blocked / fully-prevented test fails |
| stability rule (no swapping between Elite targets) removed | the oscillation test fails |
| owned-target validation removed | the memory test fails (a hidden target was never forgotten) |

Two mutations initially **survived** (the goal guard and the stability rule): the tests were too weak and were strengthened until
they failed under the mutation (documented in 2.6).

### 2.5 Standalone dedicated-server smoke test of the final JAR: **PASS**
`personal-difficulty-1.3.0.jar` + Fabric API in a genuine Fabric server (Loader 0.20.1, MC 26.3, Java 25), offline mode, loopback,
disposable Hard world, driven from the console (three sessions):
* mod loads, all 21 mixins apply (`defaultRequire` would abort startup otherwise), no exception, clean shutdown;
* a fresh server writes the 15 new keys; a 1.2.0-style config (no new keys) loads with defaults; invalid values warn and are clamped or
  defaulted (`perhaps`, `1`, `99999`, `NaN`, `lots`, `-5`, `7`); `admin config`/`admin reload` work and describe the new behaviour;
* `/difficulty` stays **Hard** throughout (never changed); 40 summoned zombies idle at 0.4 ms average tick.
**NOT done there:** a real player joining (no client available), so priority targeting and effects were *not* observed on the standalone
server; they were verified in the GameTest server (same game code, real `ServerPlayer`s without a network client).

### 2.6 Honesty log of what failed during development
* Mobs placed on a void island (to keep them still) walked off it and died, silently invalidating measurements; replaced by vanilla's
  home restriction. A test lane that let a hunter drift into range of another lane's player was fixed with knockback immunity.
* `PathNavigation.createPath(...).canReach()` is **not** side-effect free: a repeated identical request returns the mob's *active* path
  (a stale stroll path reported `canReach() == true` for a sealed room). Found as a ~25 % flaky test; fixed with a fresh pathfinder
  search through an accessor mixin.
* A target set by the scan has no vanilla goal watching it: a hidden player stayed targeted forever and a player who left Elite was
  never forgotten. Fixed with owned-target validation (and adoption of Elite targets set by anyone).
* Fabric's `AFTER_DAMAGE` also fires for hits that Resistance reduced to zero, with a figure from the start of the pipeline.
  The first effects test applied an infection through Resistance V; fixed by checking that health + absorption really went down.
* The "who is Elite" cache was initially keyed by game tick only and could be stale within a tick; it is now also keyed by the
  mode-change counter and player count.
* Witches: not supported / not claimed (1.2.0 finding retained).

### 2.7 JAR inspection: **PASS**
`build/libs/personal-difficulty-1.3.0.jar`, **105,213 bytes**, sha256 `7d97ae9e6a733f0178fc12a99ed5d25d400159a5a9a0c59d123aa6647cdd8919`.
55 classes (21 mixins), `fabric.mod.json` (`environment: server`, version 1.3.0), Java 25 class files (major 69), no
`net.minecraft.client` references, no gametest classes, no bundled Loader/Fabric API/Gson, no call that sets the world difficulty.
`release/1.0.0` (top level), `release/1.1.0` and `release/1.2.0` are untouched.

## 3. NOT RUN
* **A real Minecraft client or any real player.** Nothing is claimed about real-client compatibility, how infected/slowed players look,
  or multiplayer feel. All "live" behaviour was observed with server-side mock players.
* Live AI for mobs not in the table: witches (not supported), phantoms, bogged, parched, wither skeletons, zombie villagers, blazes, ghasts,
  breezes, evokers/illusioners, ravagers, silverfish, endermites, guardians, the Wither; modded mobs.
* Effects: drowned only by direct attack (no live drowned in water); spider/cave spider effects only by direct attack (their priority is
  live-tested); no live zombie melee run on the effects path other than the autonomous skeleton test.
* Infection spreading between players (not implemented by design); long soak tests; large crowds (hundreds of mobs); nether/end;
  interaction with other mods that touch targeting or damage events.
* Publishing: no push, pull request, tag, release or Modrinth upload.

## 4. NOT IMPLEMENTED
Player-to-player infection; effects for husks, strays, bogged, phantoms, pillagers, witches (vanilla already gives most of them their
own); making passive or neutral mobs attack Elite players; any omniscient or wall-piercing detection.

## 5. Known risks and recommended practical tests
1. Real-client behaviour is unverified. 2. Elite is now very hard (double damage, half healing, hunted by everything with effects on
top); numbers are a design estimate, tune `elite.*`. 3. Mobs go for Elite players already engaged with others; in a crowd an Elite player
draws the whole mob. 4. Slowness II + poison from cave spiders in low light is dangerous. 5. Mods that replace targeting goals,
or fire damage in unusual orders, may bypass or confuse the hooks. See README "Limitations" and the playtest plan in the final report.

# Appendix A: Verification report for 1.2.0 (retained unchanged)


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

# Appendix B: Verification report for 1.1.0 (retained unchanged)


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
5. A corrupt `players.json` leaves players shown as Normal and (since 1.4.0) refuses every change until the file is fixed and `admin reload` is run; the original and a backup are kept (see README "Format v2").

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
