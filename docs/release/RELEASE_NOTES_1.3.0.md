# Personal Difficulty 1.3.0 - "Elite is hunted"

**For:** Minecraft Java Edition 26.3 Fabric **servers**. Clients need nothing installed.

Personal Difficulty lets every player on a shared server pick their own difficulty (Normal, Easy, Hard, Elite, Peaceful) without
changing the world's difficulty. 1.3.0 makes **Elite** a different game: the monsters want you.

## What is new
- **Hostile mobs prefer Elite players.** A zombie busy chewing on a Normal player will turn to an Elite player who comes into view.
  Mobs that lose sight of an Elite player keep looking for a while, and may follow them around a corner or through a doorway - but
  never through walls, and never into a sealed room.
- **Mobs apply temporary effects to Elite players** when they genuinely hurt them:
  - Zombies and zombie villagers: infection (Weakness + Hunger; a second hit while ill makes it worse once, then no further).
  - Spiders: Slowness. Cave spiders: stronger Slowness (on top of their normal poison).
  - Skeleton arrows: occasional Slowness. Drowned: brief Slowness on melee hits.
  - Blocked hits (shield) and hits reduced to nothing do not count. Effects never stack without limit.
- **Everything is configurable** (`config/personaldifficulty.properties`): turn priority or effects off, change chances and
  durations, change how often mobs re-check their target. `/personaldifficulty admin reload` applies changes live.
- Peaceful protection and all existing modes are unchanged. Peaceful players are never targeted and never receive these effects.

(1.2.0 added: Elite heals at half speed, hostile mobs notice and chase Elite players from further away, remember them longer and
skeletons shoot at the Hard-difficulty rate.)

## Honest limits
- Only hostile mobs with a normal "hunt the nearest player" behaviour take part. Tested with live mobs: zombie, husk, skeleton,
  stray, creeper, spider and cave spider (in the dark), vindicator and pillager. Witches are not supported. Endermen, zombified piglins,
  piglins, hoglins, the Warden and modded mobs are not affected. Spiders stay neutral in daylight.
- In a group, any mob that can see an Elite player may go for them. Elite is meant to be punishing; tune the config if it is too much.
- Automated tests used simulated players. The release is approved for preparation on the basis of the owner's playtest; no test
  record of that playtest is included in this repository.

## Install / upgrade
1. Use a Fabric 26.3 server with Fabric Loader 0.20.1+ and Java 25.
2. Put **Fabric API 0.165.0+26.3** and `personal-difficulty-1.3.0.jar` in `mods/`. Remove the old Personal Difficulty jar.
3. Start the server. Existing player choices and config keep working; new settings use their defaults.

Source: https://github.com/ZaggaZach/Personal-Difficulty - MIT licence.
