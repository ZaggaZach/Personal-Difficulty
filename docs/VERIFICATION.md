# Verification report: Personal Difficulty 1.1.0

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
