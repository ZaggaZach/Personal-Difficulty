# Changelog

## 1.3.1-rc.4: UNPUBLISHED development checkpoint (Phase 5 of the 1.4.0 line: Elite hunting core)

> **Status: a private checkpoint, NOT published.** Builds on `1.3.1-rc.3`.
>
> **Phase 5 build: PASSED (29 October 2026).** `./gradlew build -PpdPerf=true` green: unit tests and all 174 required
> GameTests, both performance measurements within target. **In-game test: PASSED (29 October 2026)** on the real
> server with the new values set: long-range detection, alerts to the remembered point, giving up at a pillar, server load.

### Added
* `elite.ai.absolute-range-cap` (default **128** blocks, 16 .. 256) replaces the fixed 64-block cap of 1.3.0.
* The Elite range multipliers may now go up to **3.0** (were 2.0) and the memory multiplier up to **5.0** (was 4.0).
* **Changed defaults** (plan C2, after the performance gate passed): `elite.ai.acquisition-range-multiplier` **3.0** (was 1.25),
  `elite.ai.target-memory-multiplier` **5.0** (was 2.0), `elite.alert.enabled` **true** (was false). A zombie now notices an Elite
  player up to 105 blocks away and remembers one it cannot see for 15 s. An existing `personaldifficulty.properties` that
  states the old values keeps them (the mod never edits that file); delete those lines, or use the settings page, to get the new
  defaults.
* **Alert network and last-known-position search** (`elite.alert.enabled`, on by default; `elite.alert.radius` 64,
  `elite.alert.hops` 2, `elite.alert.memory-seconds` 60): a mob that SEES an Elite player records where and when; hostile mobs
  near it learn of that sighting (passed on at most `hops` times) and, if they have no target, walk to that remembered point with
  ordinary pathfinding, re-planning from the end of a partial route for up to 30 seconds. Nobody is told where the player is
  now. Each mob searches a sighting once; after a search that did not reach the point it ignores that player's sightings for
  10 s. The board is bounded (64 players per level, 32 relay mobs per sighting).
* All new settings are on the "Elite hunting and effects" settings page.

### Tests
* Unit: the alert board (radius edge, hop limit, expiry, caps, staleness, eligibility, once-only search); the raised limits and
  the cap; the differential loader test leaves out only values above the old maximums of the three raised keys.
* GameTests: a live listener zombie walks to where a sentry saw an Elite player and not to where the player hid; a Normal
  player's sighting is never passed on; nothing happens with the setting off. These run in their own test environment
  (`personaldifficulty-gametest:elite_alerts`), which switches the feature on only for that batch.
* **Witches: root cause found, now covered by a live test.** A witch's player goal runs only while its `canAttack` flag is on,
  and outside a raid it is on all the time (it is off only during the witch's raider-healing cooldown). The 1.2.0 finding no
  longer reproduces: since 1.3.0 the Elite hunting scan sets the target directly, and a live GameTest shows a witch acquiring an
  Elite player beyond its vanilla range (238 of 240 ticks in the first run) and never a Normal player at that distance.
* **Performance gate: PASSED.** 150 zombies, 50 skeletons and 5 players (16-thread desktop, Java 25), server tick time with
  nobody on Elite against all five on Elite with every new feature: +0.24, -0.05 and -0.05 ms over three runs (target: at most
  +1.00 ms). Run-to-run noise is about 0.3 ms; the detection range is the costliest part (+0.28 to +0.90 ms on its own). In the
  final run the crowd was refilled at every window start (123 of the deaths were arrows: skeletons hitting zombies). Opt-in:
  `./gradlew runGameTest -PpdPerf=true`.
* **Long pursuit (plan 6.2): measured, limitation documented, no waypoint goal.** A new measurement (not required) puts a
  zombie 2.8x its vanilla range from an Elite player. On open ground it closes from about 96 to 28 blocks in 30 s with complete
  routes (it is simply slow). Across a 75-block chasm it walks to the edge and stops (about 59 blocks short): the route search
  returns partial routes that end at the edge. Enlarging the search's node budget was tried and removed: 3x and 9x left the
  chasm result unchanged, and at 9x 30 zombies hunting an Elite player on an unreachable pillar cost +7.73 ms per tick (p95
  25 ms; new opt-in measurement `perf_unreachablePlayer_30HuntersThatCanNeverReachThem`). The plan's waypoint hops would
  end at the same edge, so they were not built.
* **Worst case, final: PASSED.** 30 zombies around an Elite player on an unreachable pillar. Near (18-26 blocks, vanilla hunts
  the player too): the mod adds **+0.74 ms** per tick (target 1 ms; was +1.5 to +2.3 ms before the two fixes below). Far (44-58
  blocks, only Elite players are hunted from there): +0.67 ms for the extra ~13 hunters. Main measurement in the same run:
  +0.31 ms. Getting there took three findings, each confirmed with counters in the measurement: (1) hunters re-planning
  failing routes (fixed by giving up, below); (2) idle zombies starting a new failing last-known search after every fresh
  sighting (fixed: a failed search covers that player for 10 s); (3) hidden-reacquire route checks (rare here, but each failure
  now pauses that mob's checks for 3 s). A sampler records the server thread's stack whenever a tick runs over 300 ms; none did
  in the last three runs (two earlier runs each had one ~1.7 s tick in the same window; not reproduced, cause unknown).
* **Giving up on an unreachable Elite player** (new). Measured worst case: 30 zombies hunting an Elite player they could never
  reach (on top of a pillar) cost about +1.0 to +1.5 ms per tick, because every route search fails and is repeated. Now an Elite
  hunter whose routes keep failing without getting a block closer for 5 seconds gives up the Elite extensions for that player
  for 30 seconds: beyond its vanilla range it lets go of them; inside it, it keeps hunting exactly as a vanilla mob would. A
  complete route or real progress resets the watch; a reachable player is never given up. Pillaring up therefore still works
  as a defence against mobs that have to come from far away. Unit-tested (`PursuitStallTest`) and live (chasm, pillar and
  open-ground arenas).
* The live-AI tests written against the 1.3.0 numbers keep testing them exactly, in a test environment
  (`personaldifficulty-gametest:elite_13_ai`) that pins 1.25x, 2x and alerts off; a new test checks the new defaults live (a
  zombie notices an Elite player at 2.5x its vanilla range, never a Normal one). Every GameTest run now starts from the shipped
  defaults (the run directory's properties file is rewritten from the template and leftover overrides removed).
* The differential loader tests translate exactly the approved changes (raised maximums, two new defaults) in the frozen
  1.3.0 loader's results, and compare everything else unchanged.
* The unit tests also run outside Gradle in the development environment now, so most mistakes are caught before a build.

## 1.3.1-rc.3: UNPUBLISHED development checkpoint (Phase 4 of the 1.4.0 line: Settings in the admin menu)

> **Status: a private checkpoint, NOT published.** Builds on `1.3.1-rc.2`.
>
> **Phase 4 testing: PASSED (29 October 2026).** `./gradlew build` green (unit tests and all 166 GameTests). Tested in a real
> client on the dedicated test server by an operator: the Settings hub and pages, - and +, shift-click, the Reset brush, an on/off
> toggle, and an override surviving a server restart.

### Added
* **Settings** in the admin menu (main menu, middle button), so settings can be changed without editing files:
  * a settings page each for **Easy**, **Hard** and **Elite**, one for settings that apply to **all difficulties** (PvP and
    self-damage, which kinds of damage the profile applies to, which ranged mobs have their aim adjusted) and one for **Elite
    hunting and effects**; Normal and Peaceful have nothing to adjust, which the Settings page explains;
  * every number has **- and +** buttons (shift-click for ten steps); an on/off setting is a single toggle;
  * each setting shows its value in force, where it comes from (default, properties file or override), its default, its range,
    when a change applies and its key for the `admin set` command; an override glows and has a **Reset** brush next to it;
  * **Reset this page** removes every override on the page, after asking;
  * long pages (All difficulties, Elite hunting and effects) have page arrows.
* Every menu change goes through the same strict path as `admin set/unset`: validated, saved to
  `config/personaldifficulty-overrides.json`, then applied; the same message and operator broadcast; the properties file is never
  written. A value equal to the properties file or default removes the override instead of storing it, so - then + leaves no
  override behind. Clicking past a setting's limit is refused and changes nothing.
* Permission is re-checked on every click and again before every change; a demoted operator's settings page closes on the next tick.
* Shift-click is accepted only on the - and + buttons of a settings page; everywhere else it still does nothing.

### Not in the menu
* Per-damage-type multipliers (`<mode>.damage-type.<id>`) stay command-only (`admin set`), because their keys are open-ended.

### Tests
* Unit: every setting is on exactly one page; exact decimal steps; bounds; - then + round trip over thousands of values;
  `AdminService.setSetting/unsetSetting` authority, reporting and "the properties file is never written".
* GameTests: menu step, shift-step, reset, page reset with Cancel, bound refusal, on/off toggle, message parity with
  `admin set`, properties file unchanged, demotion.
* `AdminGuiE2eTest` updated for the rc.2 layout.

## 1.3.1-rc.2: UNPUBLISHED release candidate (admin menu redesign after the first graphical-client test)

> **Status: a private checkpoint, NOT published.** Built from `1.3.1-rc.1` (commit `5d40840`) after testing that RC's admin menu in a
> real client. `rc.1` and its tag are untouched.
>
> **Phase 3 manual testing: PASSED (29 October 2026).** Tested on a dedicated Fabric 26.3 server (Fabric Loader 0.20.1, Fabric API
> 0.165.0+26.3, Java 25) from an unmodified Minecraft 26.3 client, by an operator:
> * display: main menu, player list with real player-head skins and tooltips, player page, Server Difficulties, empty slots, the
>   navigation bar and the Peaceful-to-Elite order on every page;
> * controls: Set difficulty (including "already ...; nothing changed"), Lock on/off (a locked player's own `/personaldifficulty` is
>   refused; an administrator can still assign), allowed list Save with the Switch / Keep / Cancel choice, Allow all, Reset with its
>   confirmation and Cancel, and Server Difficulties ON/OFF;
> * persistence: lock, allowed list and difficulty survive a disconnect/reconnect and a server restart.
>
> Automated: unit tests pass; in-server GameTests pass apart from the two timing-sensitive mob-AI tests listed under Known gaps.

### Changed
* **Difficulty order is now Peaceful, Easy, Normal, Hard, Elite everywhere**: menus, command suggestions, "Known: ..." lists and
  status messages. Stored data uses names, so existing files are unaffected. `restrict ... switch` still prefers Normal, then Easy,
  Hard, Elite and Peaceful last (it no longer follows the declaration order, so the reorder cannot send anyone to Peaceful).
* **Admin menu redesigned** for clarity:
  * the main menu has two choices, **Players** and **Server Difficulties**;
  * players are shown as their **player heads**, with difficulty, online status, lock and allow-list in the tooltip;
  * one **player page** holds everything for that player: *Set difficulty* (the current one glows), *Allowed difficulties*
    (tick/untick, then *Save*, which glows when there are unsaved changes), a **Lock: ON/OFF** toggle, **Allow all**, and **Reset**
    (now asks to confirm). The separate "Assign ... and lock" row and the second route to the allow-list editor are gone;
  * **Server Difficulties** shows each difficulty as ON/OFF (glowing when on) plus *Turn all on*;
  * difficulty icons rise in toughness: poppy, wooden, iron, diamond and netherite sword (weapon stats hidden);
  * unused slots are **empty** instead of grey glass panes (all slots stay locked; shift-click and drags still do nothing);
  * the **same navigation bar on every page**: Back bottom-left, Close bottom-middle, page arrows bottom-right (shown only when
    there is another page).
* The empty allow-list message in the menu now points to the *Allow all* button instead of a command.

### Tests
* `AdminMenuGameTests` rewritten for the new layout (player heads, one player page, reset confirmation, Save/Allow all, Lock ON/OFF,
  Server Difficulties ON/OFF); the admin transcript golden file and `PlayerPolicyGameTests` updated for the new order (the only
  differences are list order; `switch` still moves players to NORMAL).
* Saving an unchanged stored allow-list from the menu now goes through the service like the command ("already restricted; nothing
  changed"), so menu and command messages stay identical.
* The transcript test and `FrozenLoaderIntegrityTest` ignore CRLF line endings, so they also pass on a Windows checkout.
* `AvailabilityPolicyTest` expects the disabled list in the new order; `SelectionPolicyTest` checks the pinned switch preference
  (NORMAL, EASY, HARD, ELITE, PEACEFUL) instead of the declaration order.

### Known gaps
* `AdminGuiE2eTest` (headless protocol client, `./gradlew e2eTest`, not part of `build`) still uses the rc.1 slot layout.
* Two mob-AI GameTests are timing-sensitive and have each failed once and passed on other runs, with no changed code involved:
  `EliteHuntGameTests.ai_priority_creeper` (control case, 56 of 60 ticks) and
  `EliteAiGameTests.ai_pursuit_eliteTarget_isHeldAndPathedBeyondVanillaFollowRange` (path completeness at tick 230).

## 1.3.1-rc.1: UNPUBLISHED release candidate (Phases 1-3 of the 1.4.0 line: per-player control, settings overrides, admin menu)

> **Status: a private, local checkpoint. NOT published, NOT a stable release.** The admin menu has **NOT been tested in a graphical
> client**; it is verified only by unit tests, in-server GameTests and a headless protocol client against a real server. The version
> `1.3.1-rc.1` is provisional and may be renamed before any release. The released `1.3.0` (tag `v1.3.0`) is untouched. Elite behaviour is unchanged.

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

### Added (Phase 3: the admin menu)
* **`/personaldifficulty admin gui`**: an operator-only chest menu with **Player Management** (browse online and offline players, assign, lock,
  unlock, restrict, unrestrict, reset, and the explicit repair of an invalid stored record) and **Difficulty Permissions** (server-wide
  enable/disable, the per-player allow-list editor with the Switch / Keep / Cancel confirmation). No client mod is needed.
* **Shared administrative service (`AdminService`)**: the logic of the `admin assign/lock/unlock/restrict/unrestrict/reset/status/enable/disable`
  commands moved out of `PdCommands` unchanged; the commands and the menu both call it, so they cannot drift apart. A golden transcript
  GameTest recorded from the previous code pins every command's wording, order and outcome.
* **`AdminActor`** (server-established identity and live authority; not related to `SelectionPolicy.Actor`). The service checks the live
  permission before it reads or changes anything, in addition to the command parser, the menu's `stillValid` and the click handler.
* **Click kinds**: the menu framework now classifies every click (`ClickKind`) and forwards only plain left/right presses on menu slots.
  Change: **shift-click on a menu button no longer acts as a press** (it used to in the Phase 0 base class); it is rejected like every other
  unsupported kind. Nothing shipped used it.
* **Offline player discovery (`PlayerDirectory`)** merges players with stored records, online players and `usercache.json`; read-only,
  deduplicated, sorted, and tolerant of a missing or damaged user cache.
* **Reserved permission-node ids** in `PdPermissions` (`personaldifficulty.admin.gui`, `.assign`, `.lock`, `.config`): constants only, nothing looks
  them up and the rule is still "gamemaster (level 2) or above".
* Menu hardening: `PdAdminMenu.stillValid` is final (no page can weaken the validity check), an `AdminActor` can only be built by the server-side
  factories (identity and authority cannot be injected), and `admin enable/disable` report failure for a refusal or a lost authority.
* `PlayerSettings.knownIds()` (read-only) lists the UUIDs of valid and invalid stored records.

### Added (Phase 2: settings you can change with commands)
* **`SettingRegistry`**: the single table of every configurable setting (key, type, bounds, default, step, display text, apply timing),
  and a registry-driven properties loader whose behaviour (clamping, fallbacks, warning texts) is pinned against a frozen copy of the
  previous loader by a differential test suite.
* **Settings overrides layer**: defaults, then `personaldifficulty.properties` (still hand-edited; the mod only creates it from the default template when it is missing and never changes an existing one), then
  `personaldifficulty-overrides.json` (written only by `admin set`/`unset`). New commands `admin get <key>`, `admin set <key> <value>`,
  `admin unset <key>`; `admin config [prefix]` now also lists every effective setting with its source (default, properties or override). Damage-type
  multipliers (`<mode>.damage-type.<ns>.<path>`) are supported.
* A value equal to the one below the override layer is not stored; it removes the override instead (documented in the README).
* The overrides file has the Phase 1 safety principles: atomic writes with byte-verified backups (newest five kept), never overwriting a corrupt,
  unsupported or externally modified file, `.evidence-` copies that are never pruned, duplicate-key detection, unknown and invalid entries preserved,
  and an explicit recovery path (`admin reload`). `admin reload` now re-reads the properties file, the overrides file and the availability file, each
  keeping its last good data when its file cannot be used.

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
