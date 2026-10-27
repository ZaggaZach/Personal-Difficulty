# Personal Difficulty

A **server-side-only** Fabric mod for **Minecraft Java Edition 26.3** that lets every player choose their
own difficulty on a shared server, regardless of the world's difficulty, and lets operators restrict
which difficulties may be chosen.

| Difficulty | In one line |
|---|---|
| **Normal** | Plain vanilla (the default for everyone). |
| **Easy** | 60 % of the damage, half the hunger, enemy archers aim worse. |
| **Hard** | 125 % of the damage, 125 % hunger. |
| **Elite** | The hunted difficulty: weaker armour, double damage, double hunger, half-speed natural healing, sharper archers - and hostile mobs prefer you over everyone else and may leave you infected, venomed or slowed. |
| **Peaceful** | A personal safe mode: no damage (except the void and `/kill`), mobs ignore you, no hunger, no PvP either way. |

The world's actual difficulty is **never changed** by this mod. Different players can use different
difficulties at the same time. Nobody installs anything on the client.

> **Upgrading from 1.0.0:** the `inherit` difficulty no longer exists (it always behaved like Normal);
> Normal is now the default. Saved choices are migrated automatically, see [Migration](#migration-from-100).

## Requirements

| | |
|---|---|
| Minecraft | 26.3 (Java Edition). Other versions are untested and not claimed. |
| Fabric Loader | 0.20.1 or newer |
| Fabric API | `0.165.0+26.3` (the version this was built and tested with) |
| Java | 25 |
| Side | **dedicated server only** (`"environment": "server"`); clients need nothing |

## Installation (Modrinth App / manual)

1. Create or open a **Fabric 26.3** server instance.
2. Put **Fabric API 0.165.0+26.3** and `personal-difficulty-1.3.0.jar` in the server's `mods` folder.
3. Start the server. The log shows
   `Personal Difficulty ready: N non-default player selection(s) loaded from ... selectable: [...]`.

> The mod does not load in single-player or LAN worlds (declared server-only on purpose).

## Commands

### Everyone (no operator permission; only ever affects *yourself*)

| Command | Effect |
|---|---|
| `/personaldifficulty` or `/personaldifficulty status` | Your difficulty, the effective numbers, the world difficulty, what you can select |
| `/personaldifficulty normal` / `easy` / `hard` / `elite` / `peaceful` | Choose that difficulty (if the server allows it) |

Tab-completion offers only the difficulties the server currently allows. Choosing a disabled one gives a clear
error and changes nothing. Choosing the one you already have says "nothing changed".

### Operators (permission level 2) and the console

| Command | Effect |
|---|---|
| `/personaldifficulty admin difficulties` | Show which difficulties are enabled and how many players still use each disabled one |
| `/personaldifficulty admin disable <difficulty>` | Stop players from newly selecting it (refused if it is the last enabled one) |
| `/personaldifficulty admin enable <difficulty\|all>` | Allow it again; `all` is the recovery command |
| `/personaldifficulty admin status <player>` | A player's stored difficulty, lock, allow-list and who last changed it |
| `/personaldifficulty admin assign <player> <difficulty> [lock]` | Put a player on any difficulty, **overriding** server policy, allow-list and lock; `lock` also locks them |
| `/personaldifficulty admin lock <player>` / `unlock <player>` | Stop / allow the player changing their own difficulty |
| `/personaldifficulty admin restrict <player> <d1>[,<d2>...] [switch\|keep]` | Limit the player to an allow-list of difficulties (see below) |
| `/personaldifficulty admin unrestrict <player>` | Remove the player's allow-list (a lock is left alone) |
| `/personaldifficulty admin reset <player>` | Mode **Normal**, lock cleared **and** allow-list cleared. On a player whose stored record is invalid it is the explicit repair (verified backup first) |
| `/personaldifficulty admin gui` | Open the **admin menu** (Player Management and Difficulty Permissions; players only, see "The admin menu" below) |
| `/personaldifficulty admin config [prefix]` | The server overview, then every effective setting with its **source** (default, properties, override); `config elite.` shows only keys starting with `elite.` |
| `/personaldifficulty admin get <key>` | One setting: value and source, the value without the override, default, allowed range and when a change applies |
| `/personaldifficulty admin set <key> <value>` | Change a setting at runtime without editing files (strictly validated; stored as an override) |
| `/personaldifficulty admin unset <key>` | Remove the override of a setting; it falls back to the properties file or the default |
| `/personaldifficulty admin reload` | Re-read the properties file, the **overrides file** and the availability file; applies immediately. Also re-reads `players.json` if (and only if) it is currently refused as corrupt, unsupported or changed behind the mod's back |

Permission is enforced by the command parser (not just hidden), so it holds for players, command blocks,
`/execute` chains and the console. A command block or the console cannot *choose* a difficulty (they are not
players); an operator can deliberately act as a player with `/execute as <player> run personaldifficulty <mode>`.
`inherit` is not accepted any more. The vanilla `/difficulty` command is never touched.

## The five difficulties in detail

### Normal
No modification. This is what you get until you choose something else.

### Easy and Hard
Defaults (configurable): Easy damage ×0.60, hunger ×0.50, ranged attackers aim with **at least** uncertainty 14;
Hard damage ×1.25, hunger ×1.25 (no aim change).

### Elite
| Setting | Default | Meaning |
|---|---|---|
| `elite.armor-effectiveness` | **0.60** | Armour removes only 60 % of what it normally would (Protection and Resistance are unaffected). |
| `elite.damage-multiplier` | **2.0** | Applied once, after armour, Resistance and Protection, before absorption hearts. |
| `elite.hunger-multiplier` | **2.0** | Every exhaustion amount you accrue is doubled. |
| `elite.ranged-max-uncertainty` | **1.0** | Supported ranged attackers aim at you with **at most** this spread (vanilla: Easy 10, Normal 6, Hard 2). |

**Why not just ×2?** A bare multiplier is cancelled by endgame armour: a zombie hit on fully enchanted netherite is
0.24 HP in vanilla, so ×2 would still be only 0.48 HP. With the armour mechanic Elite takes **1.15 HP** (about 4.8×
vanilla); unarmoured players take 6 HP per hit. Measured per-tier numbers are in `docs/VERIFICATION.md`.
Better armour still always helps: nothing is an instant kill and nothing bypasses armour entirely.

Order of operations for an Elite player's incoming hit (each step applies exactly once):
world difficulty scaling (vanilla) → shield → **armour at 60 % effectiveness** → Resistance → Protection enchantments →
**×2.0** → absorption hearts → health. Invulnerability frames compare the *raw* damage, exactly as in vanilla.

#### Slower natural healing (1.2.0)
| Setting | Default | Meaning |
|---|---|---|
| `elite.natural-regeneration-multiplier` | **0.50** | Natural regeneration runs at this fraction of vanilla speed (0.1 to 1.0; 1.0 = vanilla). |

Vanilla heals from a tick counter inside `FoodData`: every **10 ticks** while you are saturated (food 20 with some
saturation) and every **80 ticks** when merely well fed (food 18+). Elite stretches that interval to `round(interval / m)`,
so the default gives 20 and 160 ticks: **exactly half the rate** in both cases, measured at 1-2.4-5.0 saturation and at
food 18, 19 and 20. The amount per heal and the exhaustion charged per heal are unchanged, so the same food buys the same
number of heals, only twice as slowly (with the Elite hunger multiplier on top, each of those heals costs 2x exhaustion as
before). Other multipliers are rounded to whole ticks (0.25 gives 40/320).
Only *natural* regeneration is affected. Not touched: the Regeneration and Instant Health effects, golden apples,
potions, absorption, beacons, `/effect`, `/damage` and the `naturalRegeneration` game rule, which the mod never reads or
changes (if you switch it off nobody regenerates, as before). The starvation damage timer is untouched. The rate is
read from the player's *current* mode every tick, so it changes the moment they switch mode, survives death, respawn and
reconnect, and there is nothing to bank by toggling (tested at switch periods of 1, 2, 3 and 7 ticks).

#### Smarter hostile mobs (1.2.0)
While an **Elite** player is the one being hunted, supported hostile mobs behave a little more like a determined hunter.
Nothing is changed for other players, nothing is a global mob buff, and **no attribute is ever modified** - every effect is
computed from the mob's current target, so it disappears the moment the target changes, the player leaves Elite (including
for Peaceful) or the mob dies. Nothing sees through walls and nothing is given extra damage.

| Setting | Default | Effect | Verified by a live-AI test? |
|---|---|---|---|
| `elite.ai.acquisition-range-multiplier` | **1.25** | An idle mob may notice an Elite player out to this multiple of its own follow range (1.0 = off, max 2.0). Line of sight, sneaking/head-gear/invisibility rules, selectors and `canAttack` still apply; Peaceful players are never candidates. | yes: zombie, husk, skeleton, stray, creeper, vindicator, pillager |
| `elite.ai.follow-range-multiplier` | **1.25** | A mob keeps hunting an Elite player, and its pathfinder searches for a route, out to this multiple (1.0 = off, max 2.0; the larger of this and the acquisition multiplier is used so that a target is never acquired and dropped on the next tick). | yes: zombie (target held, complete route found, closes in) |
| `elite.ai.improved-pursuit` | **true** | Master switch for the next setting and for a shorter melee "start chasing" re-check (5 instead of 20 ticks). | memory: yes (zombie); re-check: **hook-level only** |
| `elite.ai.target-memory-multiplier` | **2.0** | A mob forgets an Elite target it cannot see after 2x as long (vanilla 60 ticks; max 4.0, never above 400 ticks). | yes: zombie, 61 vs 122 ticks |
| `elite.ai.advanced-combat` | **true** | Skeleton-family bow cadence against Elite players is the vanilla *Hard* cadence (20 ticks between shots instead of 40) even in an Easy/Normal world. | yes: skeleton, 6 vs 9 arrows in 350 ticks |

No Elite adjustment ever reaches beyond **64 blocks** (unless the mob's own vanilla range is larger). Ranges are never
shrunk. If the world is already on Hard, the bow setting changes nothing (the cadence is the same). The existing
`elite.ranged-max-uncertainty` still governs *aim*.

Which mobs are covered: the acquisition, pursuit and memory hooks sit in the vanilla goals every "nearest player"
hostile uses (`NearestAttackableTargetGoal`, `TargetGoal`, `PathNavigation`), so any mob built on them is covered by
construction, but only the mobs in the table were actually run through the live AI and **only those are claimed**. Not
claimed (not tested, or known not to apply): spiders and cave spiders (their own night-only goal), witches (a live test
found no extended acquisition - not supported), endermen (provoked by gaze), phantoms, drowned, guardians, piglin-family
and hoglins, the Warden, the Ender Dragon, blazes, ghasts, breezes, the Wither, illagers other than the vindicator and
pillager, zombified piglins, and all modded mobs.

#### Hunted: hostile mobs prefer Elite players (1.3.0)
Hostile mobs now prefer an eligible Elite player over any other player - **including one they are already fighting**.

* **How.** Vanilla only picks a target when a mob has none, so a mob busy with a Normal player would never notice an Elite
  one. Every `elite.priority.scan-interval-ticks` (default 10, staggered per mob) a mob that is not already hunting an Elite
  player takes the nearest eligible one, and the vanilla "nearest player" goal prefers Elite candidates when it does run and
  will not replace a valid Elite target with an ordinary player.
* **Eligible** means: an Elite player (never Peaceful), alive, not a spectator, within the mob's acquisition range
  (`elite.ai.acquisition-range-multiplier` x its own follow range, capped at 64) **and** passing the mob's own targeting rules:
  line of sight, invisibility, head gear, `canAttack`. No wallhacks, no teleporting, no global awareness.
* **Stable.** A mob that already hunts a valid Elite player keeps that target; it never swaps between Elite players, so groups
  of Elite players cannot make it flip-flop. It switches only when its Elite target is gone (dead, Peaceful, out of range,
  forgotten, disconnected) and then takes the nearest remaining candidate.
* **Hidden targets.** A mob that lost sight of an Elite player keeps hunting (memory x2, see 1.2.0) and, for
  `elite.priority.reacquire-hidden-seconds` (default 8 s) after it last SAW them, may re-acquire that same player without line of
  sight - but only if its pathfinder finds a **complete route** to them within range (around obstacles, never through them; a
  sealed room is never entered).
* **Recovery.** When the Elite target becomes invalid the mob goes back to vanilla targeting and hunts ordinary players again
  within a few ticks. Switching to Peaceful drops the target within 3 ticks; switching to Elite makes a player eligible within one
  scan interval - no restart. Death, respawn, disconnect and reconnect (same UUID) are handled.
* **Who takes part.** Only hostile mobs that own a player-hunting goal. Passive and neutral mobs (cows, villagers, iron golems,
  wolves, **endermen, zombified piglins**) are never drawn in. Spiders obey vanilla's rule and stay neutral in daylight.
* **Retaliation.** If an ordinary player hits a mob that is hunting an Elite player, vanilla retaliation may briefly turn the mob
  toward them; the next scan turns it back to the Elite player.
* `elite.priority.enabled=false` restores the 1.2.0 behaviour (wider range only when vanilla found nobody).

Verified with live AI for: zombie, husk, skeleton, stray, creeper, spider and cave spider (in the dark), vindicator and
pillager. **Not verified, so not claimed:** every other mob (see `docs/VERIFICATION.md`).

#### Elite combat effects (1.3.0)
When one of these mobs **genuinely damages** an Elite player it may inflict a temporary effect. "Genuinely" means: the hit
was not cancelled (Peaceful, invulnerability frames), not blocked by a shield, and the player's health plus absorption actually
went down (so a hit that Resistance reduced to zero counts for nothing); and the cause is the mob's own melee attack or its own
arrow. Fire, poison, explosions, thorns, falls, PvP and every other source never trigger anything.

| Attacker | Effect | Chance | Duration | Notes |
|---|---|---|---|---|
| Zombie, zombie villager | **Infection**: Weakness I + Hunger I | 35 % | 8 s | Hit again while still infected: deepened once to Weakness II + Hunger II + Nausea (about 4 s); never beyond. |
| Spider | Slowness I | 35 % | 5 s | Melee. |
| Cave spider | Slowness II | 50 % | 6 s | On top of vanilla's own poison (Normal/Hard worlds). |
| Skeleton | Slowness I | 25 % | 4 s | **Arrow hits only**, once per damaging hit. |
| Drowned | Slowness I | 30 % | 3 s | Melee only (not thrown tridents). |
| Husk, stray, bogged, witch, others | nothing added | - | - | Husks already inflict vanilla Hunger; strays and bogged shoot arrows that already carry their own effect; witches already throw potions. |

Bounds: a refresh sets the duration to `max(remaining, base)` capped at `elite.effects.max-duration-seconds` (30 s), amplifiers
never exceed the values above, and a stronger effect from another source (a potion, say) is never weakened. Only **Elite**
victims are affected; other difficulties and Peaceful players never receive any of this. `elite.effects.enabled=false` turns
the whole feature off; normal attacks are unaffected.

Elite does not weaken attacks against other players, does not touch PvP, self-inflicted, void or `/kill` damage
(same exceptions as Easy and Hard), and never changes any mob globally.

### Peaceful
A player-specific safe mode, similar in spirit to creative invulnerability but **without** changing game mode,
abilities, attributes or any game rule:

* **Damage:** every damage type is cancelled before vanilla processes it (no health loss, knockback, sound or on-hit
  effects), **except** the two types tagged `bypasses_invulnerability`: the **void** (`out_of_world`) and **`/kill`**
  (`generic_kill`). Peaceful is not an administrative immunity. `/damage ... minecraft:generic_kill` also works.
* **PvP, both directions:** a Peaceful player cannot be hurt by other players and cannot hurt other players, whatever
  the other player's difficulty. Attribution follows arrows, thrown potions, TNT, tamed animals and area clouds back to
  their owner. Ordinary PvP between non-Peaceful players is untouched.
* **Mobs:** hostile (and all other) mobs cannot acquire or keep a Peaceful player as a target, so melee and ranged
  attackers ignore you. Mobs behave normally toward everyone else; you are **not** a safe zone for nearby players.
* **Hunger:** no exhaustion accrues, so no hunger loss and no starvation damage. Your food and saturation are never
  refilled or drained; exhaustion banked before you switched is capped at vanilla's threshold. Natural regeneration works
  exactly as in vanilla and costs no hunger, so (because saturation is never spent) a Peaceful player keeps healing at the
  top rate longer than a vanilla player.
* **Effects:** harmful effects cannot be applied to you, and you cannot apply them to other players. On switching to
  Peaceful, existing harmful effects and fire are removed; beneficial effects stay. Leaving Peaceful restores
  everything immediately.
* Death, respawn and reconnect keep your choice.

Known limits are listed under [Limitations](#limitations).

## The admin menu (1.4.0)

`/personaldifficulty admin gui` opens a chest-style menu for operators (permission level 2). It is a server-side screen that uses the
vanilla chest menu type, so **nothing needs to be installed on the client**. It does the same things as the `admin` commands above and
nothing else: every change goes through the same code as the matching command, so the rules, the refusals, the wording of the result, the
server-console audit line and the broadcast to other operators are identical (and sent once). The menu shows the last result on its status
card.

* **Player Management:** a paged list of everyone the server knows: players with a stored record, players online now, and every entry of
  `usercache.json`, each once, sorted by name. Pick a player to see their status and to assign any difficulty (or assign and lock), lock or
  unlock, edit or remove their allow-list, or reset them. Listing or opening a player never creates a stored record; a player without one
  simply shows the defaults. If `usercache.json` is missing or damaged, the list is built from the other sources.
* **Difficulty Permissions:** enable or disable each difficulty server-wide (never the last one), "enable all", and the per-player
  allow-list editor. Applying an allow-list that excludes the player's current difficulty asks **Switch**, **Keep** or **Cancel**, with the
  same meaning as `restrict ... switch|keep`; the player's record and your authority are re-checked when you confirm.
* **Invalid stored records** are shown as invalid. Only the explicit **Repair invalid record** button is offered (it is `admin reset`: a
  verified backup is made first, unknown fields are kept); nothing else can touch the record.
* **Security.** Permission is checked when the menu opens, continuously while it is open (an operator who is demoted while a menu is open
  has the screen closed by the server on its next tick), at the start of every click, and again inside the service that performs the
  change. The client only ever sends a slot number and a click type; the page, the selected player and any pending choice live on the server.
  Only a plain left or right click on a menu slot does anything: shift-click, number keys, drag, double-click, drop, creative clone, clicks
  outside the menu and clicks for a stale or unknown container are ignored without side effects. Hidden buttons are never relied on.
* Not in this release: the settings pages (they arrive later). Use `admin get/set/unset` for settings.

## Server restrictions

Operators control which difficulties may be *newly selected* with `admin disable` / `admin enable`. It is a
server policy, completely separate from players' saved choices:

* Default: all five enabled. At least one always stays enabled (the last one cannot be disabled).
* Disabling a difficulty **never changes anyone's saved choice**. A player already on it keeps playing on it and keeps
  all its effects; they just cannot select it again after switching away. `status` and `admin difficulties` say so.
* Re-enabling is instant, no restart. Restrictions apply to operators' own selections too (they can simply re-enable).
* Stored in `config/personaldifficulty-availability.json` (`{"version":1,"disabled":["elite"]}`) and written
  atomically by the commands. A malformed file, or one that disables all five, is rejected: everything is enabled, a
  warning is logged and the original is kept as `*.backup-<time>`. `admin reload` re-reads it, and on a bad edit the
  previous policy stays in force.
* Recover from any mistake with `/personaldifficulty admin enable all`.

## Per-player control: assign, lock and allow-lists (1.4.0)

On top of the server-wide policy, operators can control individual players. The complete rules:

1. **Self-service** (`/personaldifficulty <difficulty>`) is allowed only if the player is **not locked**, the difficulty is
   **globally enabled**, and it is **in the player's allow-list** (when they have one). Otherwise it is refused with the specific
   reason and nothing changes. When several reasons apply at once they are reported in this fixed order: unreadable stored
   record, then lock, then server policy, then the player's allow-list. (The plan only asks for "a specific reason", so the order is
   a choice: the most fundamental blocker is named first, so a locked player is never told their pick is "not in your list".)
2. **`admin assign`** may give any player (operators themselves included) any difficulty, whatever the global policy,
   their allow-list or their lock. The result can be a difficulty the player could not choose themselves. Without `lock` an
   existing lock is left as it is; with `lock` the player is locked on it.
3. A **locked** player cannot change their own difficulty by any path, including `/execute as` them. Only an administrator
   (`assign`, `unlock`, `reset`) changes it.
4. A lock and an allow-list **survive** death, respawn, disconnect, restart and later changes to the server policy. Disabling a
   difficulty never changes anyone's saved difficulty.
5. An allow-list is **never empty**. If `restrict` would remove the player's *current* difficulty from the list you must say
   what happens: `switch` moves them to NORMAL if it is in the list, otherwise to the first listed difficulty in the order
   normal, easy, hard, elite, peaceful (regardless of how you typed the list); `keep` leaves them where they are as a recorded
   exception outside the list (once they switch away they cannot switch back). With **neither** flag the command is refused,
   changes nothing and tells you the options. If the current difficulty is in the list the flag is ignored.
6. `restrict` and `lock` are **independent**: a locked player with an allow-list stays locked, and the list applies if
   they are unlocked later. `unlock` and `unrestrict` each undo only their own part.
7. `admin reset <player>` sets Normal, clears the lock and clears the allow-list (previously: Normal only).
8. An operator using the ordinary self-service command is an **ordinary player**: they obey their own lock, allow-list
   and the server policy. Only `admin assign` overrides.

Changing the difficulty of a player who is online uses the same transition handling as choosing it themselves (for example,
entering Peaceful clears harmful effects). Offline players can be managed too; it applies when they next join. Tab-completion
offers a player exactly the difficulties they can choose right now (none while locked).

Every `admin` command needs gamemaster (permission level 2) or the console / a command block; a level-1 moderator or an ordinary
player is refused by the command parser. There is a single permission check for all of them (`PdPermissions.canAdminister`).

## Configuration

`<server>/config/personaldifficulty.properties` (created with comments on first start). Invalid values (not a number,
NaN, infinity) fall back to the default; out-of-range values are clamped (multipliers 0.0 to 10.0, uncertainty 0 to 60,
armour effectiveness 0 to 1, regeneration 0.1 to 1, AI ranges 1 to 2, memory 1 to 4); every problem is logged. Edit, then `/personaldifficulty admin reload`.

```properties
easy.damage-multiplier=0.60
easy.hunger-multiplier=0.50
easy.ranged-min-uncertainty=14.0
hard.damage-multiplier=1.25
hard.hunger-multiplier=1.25
elite.damage-multiplier=2.0
elite.hunger-multiplier=2.0
elite.armor-effectiveness=0.60
elite.ranged-max-uncertainty=1.0
pvp.damage-multiplier=1.0           # damage caused by another player (Easy/Hard/Elite)
self.damage-multiplier=1.0
environmental.apply-profile=true
void.apply-profile=false
admin.apply-profile=false
ranged.skeletons=true               # skeleton, stray (+ bogged/parched/wither skeleton by inheritance)
ranged.illusioners=true
ranged.drowned=true
ranged.crossbow-mobs=true           # pillagers
ranged.piglins=true
ranged.witches=true
elite.natural-regeneration-multiplier=0.50   # 0.1 .. 1.0, fraction of vanilla natural healing speed
elite.ai.acquisition-range-multiplier=1.25   # 1.0 .. 2.0
elite.ai.follow-range-multiplier=1.25        # 1.0 .. 2.0
elite.ai.improved-pursuit=true
elite.ai.target-memory-multiplier=2.0        # 1.0 .. 4.0
elite.ai.advanced-combat=true
elite.priority.enabled=true                   # hostile mobs prefer Elite players
elite.priority.scan-interval-ticks=10         # 5 .. 40
elite.priority.reacquire-hidden-seconds=8     # 0 .. 30, 0 = off
elite.effects.enabled=true
elite.effects.max-duration-seconds=30         # 1 .. 60
elite.effects.zombie-infection-chance=0.35    # per damaging hit, 0 .. 1
elite.effects.zombie-infection-seconds=8      # 1 .. 60
elite.effects.spider-venom-chance=0.35
elite.effects.spider-venom-seconds=5
elite.effects.cave-spider-venom-chance=0.50
elite.effects.cave-spider-venom-seconds=6
elite.effects.skeleton-shot-chance=0.25
elite.effects.skeleton-shot-seconds=4
elite.effects.drowned-chill-chance=0.30
elite.effects.drowned-chill-seconds=3
#easy.damage-type.minecraft.fall=0.3   # optional per-damage-type override (easy|hard|elite)
```

Peaceful deliberately has no settings.

### Changing settings with commands: the overrides layer (1.4.0)

Every setting above can also be read and changed with `admin get`, `admin set` and `admin unset` (permission level 2, console allowed),
with tab-completion for keys and values. There are three layers, lowest to highest:

1. **built-in defaults**;
2. **`config/personaldifficulty.properties`**, hand-edited; the mod only creates it from the default template when it is missing, and never changes an existing one;
3. **`config/personaldifficulty-overrides.json`**, written **only** by `admin set`/`unset`: `{"version":1,"values":{"elite.damage-multiplier":2.5}}`.

An override beats the properties file; `admin config` and `admin get` always say where a value comes from. Note that the
generated default properties file lists every key, so until you delete a line from it its values show `properties`, not `default`.

* **`set` is strict.** A value must be exactly right for the setting (a finite number inside its range, a whole number for
  whole-number settings, `true`/`false`); otherwise it is refused with the allowed range, and nothing changes. (The hand-edited properties file
  keeps its old lenient rules: bad values fall back to the default, out-of-range values are clamped, every problem is logged.)
* **A value equal to the one below the override layer is never stored.** If you `set` a setting to the value it already has from the
  properties file (or the default), any existing override of that setting is **removed** and nothing is stored: the setting then follows
  the properties file, so later edits of that file apply. An override cannot be used to "pin" a value against future properties edits.
  (If the properties file later changes to equal an existing override, the override stays and is still reported as an override until you
  `unset` it.)
* **Damage-type multipliers** (`<mode>.damage-type.<namespace>.<path>`, for example `easy.damage-type.minecraft.fall`) work with
  `get`/`set`/`unset` too. `<mode>` must be `easy`, `hard` or `elite`; the damage type must exist on the server (otherwise `set` is refused with
  close matches); namespace and path may contain lower-case letters, digits, `_` and `-` (paths also `.`). The first dot after
  `damage-type.` separates namespace from path. **Damage types whose path contains `/` cannot be typed in a command and must be configured in the
  properties file.**
* **Applies at once.** A change takes effect immediately for everyone (no restart); `elite.priority.*` settings are picked up at each mob's
  next scan. `get` shows the timing.

**Safety of the overrides file** (the same principles as `players.json`):

* Every rewrite is atomic (temporary file, flushed, renamed) and preceded by a **byte-verified** copy of the previous file,
  `personaldifficulty-overrides.json.backup-<millis>`. **Only the five newest ordinary backups are kept.** A failed write changes nothing:
  the file and the running configuration stay exactly as they were.
* **A corrupt, unsupported or unreadable file is never overwritten**, and it never stops the server: the overrides are simply not applied
  (settings are then those of the properties file and the defaults, which is **not** what was configured with `set`). This is reported loudly: an `ERROR`
  line at startup, a warning in `admin config`, and every `set`/`unset` is refused with the reason. The file is judged in this order: not valid JSON, not an object, no
  `version`, **duplicate JSON keys** (ambiguous), or no `values` object = corrupt; a `version` that is not a whole number of at least 1 or is above 1 =
  unsupported (the version is judged before the structure). A verified **evidence copy**,
  `personaldifficulty-overrides.json.evidence-<millis>`, is kept (an identical earlier copy is reused). **Evidence copies are never deleted by the
  five-backup pruning or by the mod at all; delete them by hand when you no longer need them.**
* **Recovery:** fix or replace the file (or copy a `.backup-`/`.evidence-` file back by hand), then run `admin reload`. Reload **always re-reads the
  overrides file**: a usable file is adopted; an unusable one leaves the last good overrides in force and keeps changes refused.
* **Edited while the server runs?** The mod notices a change it did not make just before its next write, refuses that write, keeps an evidence copy and
  waits for `admin reload`, which loads your edit on purpose. The properties file is hand-edited by design and is not guarded this way; `reload` reads it.
* **Nothing is silently deleted.** Unknown keys, entries with invalid values, damage types that do not exist on the server and unknown top-level
  fields are kept verbatim every time the file is rewritten, ignored (with a warning, once per load or reload) and shown in `admin config`. Only an
  explicit `set` or `unset` of that very key replaces or removes its entry (the previous file is then in a verified backup).
* Downgrading to 1.3.0 or earlier simply ignores the overrides file (those versions know nothing about it): the settings fall back to the properties file.

## Persistence

| What | Where |
|---|---|
| Server config (hand-edited) | `<server>/config/personaldifficulty.properties` |
| Setting overrides (written by `admin set`/`unset`) | `<server>/config/personaldifficulty-overrides.json` |
| Availability policy | `<server>/config/personaldifficulty-availability.json` |
| Player choices (UUID-keyed) | `<world>/personaldifficulty/players.json` |

Player records are written only when something changes, atomically (temporary file, flushed to disk, then renamed over the
old file), and offline players' entries are never removed. A player with nothing to remember (Normal, not locked, no
allow-list) has no entry. If a write fails the change is refused and reported, never shown as active.

### Format v2 (1.4.0) and migration from 1.0.0 to 1.3.0

```json
{"version":2,"players":{"<uuid>":{"mode":"ELITE","locked":true,"allowed":["NORMAL","ELITE"],"by":"<admin uuid>","at":1700000000000}}}
```

Every stored player is an object; `locked`, `allowed`, `by` and `at` appear only when set.

* **Loading never writes.** A v1 file (`"<uuid>":"MODE"`) loads exactly as before and is not rewritten until something changes.
* **Backup first.** Before the first v2 write over a v1 file, the old file is copied byte for byte to
  `players.json.backup-<millis>`. If that copy cannot be made, the change is refused and the file is untouched. An
  already-v2 file needs no further backups.
* **Nothing unknown is lost.** Unknown fields inside a record, unknown top-level fields and entries whose key is not a UUID are kept
  verbatim and written back. A stored `INHERIT` (1.0.0) is still read as `NORMAL`.
* **How a file is judged, in this order:** missing or blank file = empty (a blank file needs no backup); a directory at the path is
  reported as a directory (nothing is claimed backed up); not valid JSON, not an object, or no `version` = **corrupt**; a
  **version that is not a whole number of at least 1, or is above 2 = unsupported**; only then is the structure
  (`players`) checked. The version is judged *before* the structure, so a newer file with a different layout is never mistaken
  for a corrupt one.
* **Corrupt or unsupported file: nothing can overwrite it.** It is left byte-identical, a copy is kept as
  `players.json.backup-<millis>` (an identical earlier copy is reused, so restarts do not pile up copies), and **every change is
  refused** with the reason (players are told their change was not saved; nothing is reported as applied). Everyone is shown as the default
  while a corrupt file is not loaded, which is *not* their saved state, so this is reported loudly: an `ERROR` line at startup,
  a warning in `admin config`, and in the refusal text. Records that can be understood in a *newer-format* file are still applied,
  read-only (so locks in it keep working). If the backup itself fails the file is still never overwritten.
* **Recovery without a restart:** fix or replace the file, then run `/personaldifficulty admin reload`. A healthy in-memory state is
  never replaced by a reload, and a reload that finds the file still bad keeps the current state and keeps refusing writes.
* **A file changed behind the mod's back** (edited or corrupted while the server runs) is never overwritten either: the next change
  is refused, a copy is kept, and `admin reload` loads the new content on purpose. A file that was deleted is recreated from memory.
* **Invalid records are quarantined.** If a stored entry has an *invalid known field* (`mode` not a mode name, `locked` not true/false,
  `allowed` not a non-empty list of known modes, an unknown mode string, two entries for one player), it is **not** treated as a
  default player. It is kept verbatim, the player is treated as **locked** (fail closed), the entry's own `mode` is used for
  gameplay only if that field alone is valid (otherwise Normal), and **no other field of it is trusted**. All changes for that player
  (self-service, `assign`, `lock`, `unlock`, `restrict`, `unrestrict`) are refused, with the way out named, even for operators.
  `admin status <player>` shows `INVALID STORED RECORD` with the reason and the raw entry, and `admin config` counts them.
  `by` and `at` are audit-only: invalid values there are kept but never quarantine a player.
* **Explicit repair:** `/personaldifficulty admin reset <player>` on a quarantined player first makes a **new, byte-for-byte verified**
  backup of the file, then replaces the entry with a default record (Normal, unlocked, no allow-list) that keeps the entry's
  *unknown* fields. The invalid values themselves stay in the backup. If the backup or the write fails, nothing changes.
* **Downgrading to 1.3.0 or earlier is not safe once a v2 file has been written.** Those versions only understand
  `"<uuid>":"MODE"` strings, but **every** player in a v2 file is an object. Verified by running the real 1.3.0 reader on v2 files: it reads
  **every** player in the file as **Normal and unlocked**, including players whose stored mode was Hard, Easy, Elite or Peaceful (not only
  locked or restricted ones), because it does not recognise any object record. It logs a "newer format" warning and copies the file, but
  keeps running. The next time 1.3.0 saves anything it rewrites the file with a **`"version":1` header while keeping the v2 objects
  verbatim**, and stores new choices as plain strings, so the file ends up mixed. (1.4.0 reads such a mixed file correctly, with
  locks and allow-lists intact.) To go back safely, **stop the server and restore the `players.json.backup-<millis>` made before
  the first v2 write**; every change made since then is lost.

### Migration from 1.0.0

* `EASY`, `HARD` (and `NORMAL`) are read as before; a stored `INHERIT` is read as `NORMAL`. This happens on every load, so
  it is idempotent, survives restarts and does not require anyone to join.
* The file is first rewritten the next time any record changes; that write omits the retired `INHERIT`/`NORMAL` values.
* `/personaldifficulty admin reset` meant "back to Normal" since 1.1.0; since 1.4.0 it also clears the lock and allow-list.

The `players.json` format did not change, so there is **no rewrite on upgrade**:

* `EASY`, `HARD` (and `NORMAL`) are read as before; a stored `INHERIT` is read as `NORMAL`. This happens on every load, so
  it is idempotent, survives restarts and does not require anyone to join.
* The file is first rewritten the next time any selection changes; that write omits the retired `INHERIT`/`NORMAL` values.
* Entries this version cannot understand are never dropped: an entry with a malformed UUID key is kept verbatim, and an entry for a
  real player with an unknown mode or invalid field is quarantined (see above). Files written by 1.0.0 to 1.3.0 are v1 files:
  1.4.0 reads them unchanged and migrates them on the first change, after a backup. (Going back from v2 to those versions is
  covered above: restore the backup.)
* `/personaldifficulty admin reset` meant "back to Normal" since 1.1.0; since 1.4.0 it also clears the lock and allow-list.

## Limitations

* **Elite priority is "prefer", not "omniscient".** A mob only ever prefers an Elite player it could legitimately target: within a
  finite range, in line of sight (or recently seen and reachable by a complete path), visible under vanilla's rules, never
  Peaceful. A mob that is hunting an Elite player may be briefly turned by vanilla retaliation if an ordinary player hits it; the
  next scan (default 10 ticks) turns it back. Only mobs that own a vanilla player-hunting goal take part: modded mobs with custom
  targeting, the Warden, piglins, hoglins, endermen and zombified piglins (provocation-based) and the Ender Dragon do not.
  Spiders stay neutral in daylight. In a crowd, every mob that can see an Elite player may go for them.
* **Combat effects are attributed conservatively.** Only the exact families in the table fire; ranged effects only for plain
  skeleton arrows; a hit must actually reduce health or absorption. Other mods that change `LivingEntity.hurtServer` or fire damage
  events in an unusual order may defeat the attribution. Infection does not spread between players (deliberately).
* **Effects stack on top of vanilla's own.** Cave spiders also poison on Normal/Hard worlds, husks inflict Hunger, strays slow with
  their arrows. Slowness II plus poison from a cave spider on a low-health Elite player is genuinely dangerous; tune
  `elite.effects.*` if that is too much.

* **Elite hostile-mob behaviour is not a full AI rewrite.** It widens notice/chase radius, extends the pathfinder's search
  length and target memory, and speeds skeleton bow cadence, for the mobs listed above. It does not add flanking,
  coordinated groups, door-breaking, dodging or ranged-mob kiting, and the shorter melee re-check is not measured by a
  live-AI test. A mob that cannot see you still cannot notice you.
* **Elite healing is slower, not different.** Regeneration is slowed by stretching the vanilla timer; a player can
  still out-heal damage with golden apples, potions, Regeneration and beacons, which are deliberately untouched.

* **Ranged aim** is adjusted for: skeleton and stray (tested; bogged, parched and wither skeletons inherit the same
  vanilla method but are untested), illusioner, drowned (tridents), pillager (crossbow), piglin (crossbow, brain-driven;
  needed a dedicated hook) and witch (potions; vanilla witches always use uncertainty 8). **Not** adjusted: wither,
  ghast, blaze, breeze, snow golem, llama, and all modded shooters.
* **Elite aim is not a guaranteed hit.** In the Normal-difficulty test world a standing player was hit by the spread
  alone 100 % at 12 blocks, 93 % at 24, 77 % at 32 (vanilla there: 13 % at 24 blocks), so the benefit is mostly at range
  and against a moving target gravity and travel time still dominate. In a Hard world (vanilla spread 2) Elite is 2× tighter.
* **Peaceful attribution is best-effort.** Harm that cannot be traced to a player (dispensers, lava or fire they placed
  earlier, unowned TNT, lightning, falling blocks, pistons) is ordinary damage to a *non-Peaceful* victim. A Peaceful
  *victim* is protected from everything, whatever the source. Harmful effects cannot be applied to a Peaceful player by
  anything, including the `/effect` command. Explosion knockback is not damage and is not suppressed (not tested).
* "Mobs ignore you" covers vanilla mobs through their normal targeting. Live AI was verified for zombies and skeletons;
  for the other 36 vanilla mob types tested (including piglins, hoglins, the Warden and the Ender Dragon, which have their own hooks
  or brains) only the targeting gate was checked directly, not their live behaviour. Modded mobs with custom targeting may
  still chase you but cannot hurt you.
* Other mods that bypass `Player.causeFoodExhaustion`/`FoodData` or replace vanilla damage handling may not be covered.
* On an offline-mode server an unknown player name given to `admin status/reset` cannot be told apart from a real one;
  the reply says "has no stored selection" instead of claiming a change.

## Building from source

```
./gradlew build            # compiles and runs the 94 unit tests -> build/libs/personal-difficulty-<version>.jar
./gradlew runGameTest      # 116 in-server integration tests (115 of ours + the framework's always_pass) (real ServerPlayer / mobs / mixins)
./gradlew e2eTest          # real dedicated server + headless client smoke test (downloads once, see docs/E2E.md)
./gradlew runServer        # local dev server in run/ (accept the EULA in run/eula.txt first)
```

Needs JDK 25. `docs/DESIGN.md` explains the damage pipeline and every hook; `docs/VERIFICATION.md` states exactly what
was and was not verified.

## Licence

MIT, see `LICENSE`.
