# Proposed Modrinth listing (DRAFT - nothing has been created or uploaded)

## Project
| Field | Proposal |
|---|---|
| Project type | Mod |
| Title | Personal Difficulty |
| Slug | `personal-difficulty` (3-64 chars from `[\w!@$()`.+,"\-']`; availability unchecked - needs a Modrinth account) |
| Summary (short description) | Let every player on a server choose their own difficulty - Normal, Easy, Hard, Elite or Peaceful - without changing the world's difficulty. |
| Licence | MIT (SPDX `MIT`; `LICENSE` file in the repo confirms it) - licence URL: https://github.com/ZaggaZach/Personal-Difficulty/blob/main/LICENSE (adjust to the branch/tag that will exist) |
| Source | https://github.com/ZaggaZach/Personal-Difficulty |
| Issues | https://github.com/ZaggaZach/Personal-Difficulty/issues (enable Issues on the repo first) |
| Categories (verified to exist for project type `mod` in `api.modrinth.com/v2/tag/category`) | featured: `game-mechanics`, `mobs`; additional: `utility`, `management` (`adventure`, `multiplayer`... only if you prefer; `multiplayer` is listed for modpacks only) |
| Environment | `dedicated_server_only` (a value listed by the current create-version API; the website's wording for it was not verifiable without logging in - if the form lacks it, use `server_only`) |
| Icon / gallery | none exist yet - needs a decision |

## Version (to be created later)
| Field | Value |
|---|---|
| Version number | `1.3.0` |
| Name | Personal Difficulty 1.3.0 - Elite is hunted |
| Release channel (`version_type`) | **`beta`** (owner's decision for the first Modrinth upload) |
| Loaders | `fabric` only |
| Game versions | `26.3` only. **Verified:** Modrinth's game-version tag list contains `26.3` (type release, 2026-09-15). |
| Dependencies | Fabric API - `required`. **Verified:** project id `P7dR8mSH`, slug `fabric-api`, type mod. Built/tested with 0.165.0+26.3. |
| File | `release/1.3.0/personal-difficulty-1.3.0.jar` (SHA-256 `7d97ae9e6a733f0178fc12a99ed5d25d400159a5a9a0c59d123aa6647cdd8919`, 105,213 bytes). The sources jar may be attached as type `sources-jar`. |
| Changelog | `docs/release/RELEASE_NOTES_1.3.0.md` |

## Description body (Markdown)
**Personal Difficulty** gives every player on a shared Fabric server their own difficulty, whatever the world is set to. Pick it with
`/personaldifficulty <mode>`. Nobody installs anything on their client.

### Difficulty modes
- **Normal** - plain vanilla, the default.
- **Easy** - 60% damage, half hunger, enemy archers aim worse.
- **Hard** - 125% damage and hunger.
- **Elite** - weaker armour, double damage, double hunger, half-speed natural healing, sharper archers, and the monsters hunt you.
- **Peaceful** - a personal safe mode: no damage (except the void and `/kill`), mobs ignore you, no hunger, no PvP either way.

Operators can disable modes (`/personaldifficulty admin disable elite`) and reload the config live.

### Elite: hunted
Hostile mobs prefer an Elite player over anyone else - even one they are already fighting - re-checking every half second. They
see only what they could normally see: no wallhacks. If they lose you they keep searching briefly and follow a real route around
obstacles. Successful hits can inflict temporary effects: zombie infection (Weakness + Hunger), spider and cave-spider slowing,
occasional slowing skeleton arrows, chilling drowned. Everything is bounded and configurable.

### Install (server owners)
Fabric Loader 0.20.1+, Java 25, Minecraft 26.3, **Fabric API**. Drop the jar in `mods/`. Config:
`config/personaldifficulty.properties`; reload with `/personaldifficulty admin reload`.

### Known limitations
Minecraft 26.3 only. Dedicated servers only (does not load in single-player/LAN). Only hostile mobs with a normal player-hunting
behaviour take part (live-tested: zombie, husk, skeleton, stray, creeper, spider, cave spider, vindicator, pillager; witches not
supported; endermen, zombified piglins, piglins, hoglins, Warden and modded mobs unaffected). Automated tests used simulated players.

### Troubleshooting
- Mod not loading: check Java 25, Loader >= 0.20.1, Fabric API present, Minecraft exactly 26.3.
- Elite too hard: lower `elite.effects.*-chance`, set `elite.priority.enabled=false` or `elite.effects.enabled=false`, then reload.
- Invalid config values are clamped/defaulted and logged as `[config]` warnings.

## Verified against the live Modrinth API on preparation day (read-only)
- Game version `26.3` exists. Fabric API = `P7dR8mSH`. Categories above exist for mods. `https://api.modrinth.com/v2/project/personal-difficulty` returned 404, i.e. the slug appears unclaimed (a 404 cannot distinguish a private/draft project, so confirm in the UI).
- Nothing was created or uploaded.

## Decisions needed from you
1. **Environment value.** The mod declares `"environment": "server"` and does not load in single-player. Closest Modrinth value:
   `dedicated_server_only` (or `server_only`). Pick one.
2. **Release channel:** release vs beta (no real-client test record in the repo).
3. **Slug/ownership:** Modrinth account, team/organisation, slug availability.
4. **Icon and gallery images** (none exist); **Issues** tracker enabled on GitHub.
5. **Tag/branch to link:** the GitHub release/tag does not exist yet; links must point at something that will exist. Decide whether
   to merge to the default branch first.
6. **Game-version tag:** confirm Modrinth offers `26.3` (cannot be verified without logging in).
7. **Categories:** confirm against the current tag list.

Modrinth submissions go through moderation review; plan for that delay. (API fields checked: create-project/create-version docs,
`version_type` = release|beta|alpha, `dependency_type` = required|optional|incompatible|embedded, `environment` values include
`server_only` and `dedicated_server_only`.)
