# End-to-end tests (real dedicated server + headless protocol client)

Added in the 1.4.0 development line (Phase 0). Ported from the Gift Mailbox project's test harness and trimmed to what Personal
Difficulty needs: `ServerRunner`, `Bot`, `E2e` and the smoke test, plus a test-only fixture mod.

## Tests
* `SmokeE2eTest`: join, run the mod command, open and use the fixture menu, hostile clicks (Phase 0).
* `PlayerPolicyE2eTest` (Phase 1): lock, allow-list and assigned mode survive a graceful restart and a `ServerRunner.kill()`
  (SIGKILL); a rapid write burst killed mid-stream never leaves a corrupt `players.json` (3 rounds); a v1 file loads without
  being rewritten, then migrates with a byte-identical backup on the first change; a newer-format file is read but never overwritten.
  A corrupt file at startup is visible to administrators, never overwritten, and `admin reload` recovers it without a restart; an invalid
  stored record is quarantined across restarts until `admin reset` repairs it with a verified backup.
* `ConfigE2eTest` (Phase 2): an override survives a graceful restart and a SIGKILL, a burst of `set`/`unset` commands killed mid-stream never leaves a
  corrupt overrides file or loses an unrelated override, `personaldifficulty.properties` stays byte-identical, a corrupt overrides file at startup is
  loud (ERROR line, logged once), never overwritten and recoverable with `admin reload`, and invalid or unknown entries are preserved, reported once and
  replaceable by an explicit valid `set` after a verified backup.
* `AdminGuiE2eTest` (Phase 3): a non-operator cannot open `admin gui`; an operator gets an ordinary vanilla 9x6 chest screen and can assign and
  lock a player through it with exactly one audit line per change; a demoted operator's screen is closed by the server and stale clicks for it are
  ignored; the changes survive a graceful restart and, after an abrupt client drop with the menu open, a `kill -9`; more than 2,000 hostile and
  malformed packets per screen (every click type on every slot including out-of-range ones, stale and nonsense container ids, junk changed-slot maps,
  menu-button and creative-slot packets) change nothing, do not drop the client and do not flood the log; offline players from `players.json` and
  `usercache.json` are listed, a missing or non-JSON user cache is harmless, and viewing a player creates no record; an invalid stored record is shown as
  invalid and only the explicit repair (with a verified backup) changes it.

## What it is
`./gradlew e2eTest` starts a REAL Fabric dedicated server process (installed with the official Fabric installer) with three mods in
`mods/`: Fabric API, **the built Personal Difficulty jar** (`build/libs`), and a tiny test-only fixture mod
(`build/e2e/libs/personaldifficulty-e2e-fixture-0.jar`, built from `src/test/java/.../e2e/fixture` and `src/e2e-fixture`). A headless
client (`Bot`) logs in over the real network protocol, runs commands, opens the fixture menu and sends clicks, including hostile ones.

## What it proves and what it does not
It proves protocol-level behaviour: the mod loads on a real server, its commands answer, the UI base classes send an ordinary vanilla chest
menu (`minecraft:generic_9x3`) that a client needs no mod for, button presses are handled and hostile click types are ignored.
It does **not** prove how a graphical client renders or feels. No graphical client is involved.

## Running
```
./gradlew e2eTest
```
`build`, `check` and `test` never run it and never download anything: `test` excludes tests tagged `e2e`, and `e2eTest` is not wired into `check`.

## Network and cache requirements
First use downloads, into `build/e2e/cache`:
- the Fabric installer jar from `maven.fabricmc.net`
- the Fabric API jar from `maven.fabricmc.net`
- the Minecraft server jar and libraries, fetched by the installer from Mojang's download servers

Later runs reuse the cache and need no network. To run offline, keep `build/e2e/cache` (or point at another copy with
`-DpdE2eCache=/path/to/cache`). Versions come from `gradle.properties` (`minecraft_version`, `loader_version`, `fabric_api_version`).
Per-test server directories are created under `build/e2e/work`. `-De2eDebug=true` and `-De2eServerDebug=true` add client and server debug output.
