# 1.4.0 Phase 7: spikes (plan 6.8) - feasibility notes

Status of each item against the plan's go/no-go gate (section 8: verified APIs, a live GameTest passing three times in a row, a
mutation check, non-Elite behaviour unchanged, the performance budget). Facts below were read from the 26.3 game code (`javap`).

## 1. Goal-based Nether mobs

**[Fact]** The Elite hunting scan covers a mob when it is a `Monster` with a player-targeting `NearestAttackableTargetGoal`
(`PdHunt.participates`). That makes the coverage, by construction and since 1.3.0, but never claimed:

| Mob | Class | Covered by construction? | Spike |
|---|---|---|---|
| Blaze | `Monster`, `NearestAttackableTargetGoal<Player>` | yes | live test: Elite acquired beyond vanilla range, Normal never |
| Wither skeleton | `AbstractSkeleton` (melee) | yes | same live test; the Elite skeleton *shot* never applies (it does not shoot) |
| Magma cube | `AbstractCubeMob`, not a `Monster` | no | documented by a non-required test |
| Ghast | `Mob implements Enemy`, not a `Monster` | no | documented by a non-required test |

**Result (2 November 2026):** all four acquire an Elite player beyond their vanilla range and never a Normal one. The
prediction above was half wrong: ghasts and magma cubes are covered too, because the acquisition hook sits in vanilla's
`NearestAttackableTargetGoal` itself; only the hunting scan (`PdHunt.participates`) is limited to `Monster`s.

Original decision path: if blaze and wither skeleton pass, they are listed as supported (they are already affected today). Ghast and magma
cube stay unsupported unless you ask for them (it would mean widening `participates` beyond `Monster`).

## 2. Warden

**[Fact]** Brain-driven. Anger lives in `AngerManagement`; `Warden.increaseAngerAt(Entity, int, boolean)` is the single entry
point, called by its vibration listener (`Warden$VibrationUser`, 35 per vibration, 10 for a projectile from beyond 30 blocks),
by `Sniffing` and `Roar`, and when hurt (+20). Anger levels: calm 0, agitated 40, angry 80 (the Warden attacks). Follow range 24.
`canTargetEntity` is already hooked (Peaceful players are never targets).

**Proposal (not built):** `elite.warden.anger-multiplier` (1.0 .. 3.0, default 2.0), switch `elite.warden.enabled` OFF by default.
Anger the Warden gains toward an Elite player through vanilla's own `increaseAngerAt` is multiplied, so an Elite player making
vibrations is attacked after two of them instead of three. No new sensing, no extra range, no target forcing, nothing through
walls: only anger the Warden already gets from vanilla's senses is scaled. Gate test: vibrations emitted next to a Warden by an
Elite and a Normal player (`level.gameEvent`, vanilla's listener path) reach "angry" after fewer events for the Elite player only.

## 3. Piglins, piglin brutes, hoglins, zoglins

**[Fact]** Brain-driven (`Brain` memories and sensors, no target goals); hoglins and zoglins are `Animal`s. None of the goal-based
hunting code applies. Making them Elite-aware means hooking their sensors (`NEAREST_VISIBLE_...` memories) and their anger rules
(piglins ignore players wearing gold; brutes do not), each with its own risks to vanilla bartering and zombification behaviour.

**Recommendation: no-go for 1.4.0 (deferred).** The plan already marks them deferred by default; nothing is built, and the
changelog lists them as deferred.
