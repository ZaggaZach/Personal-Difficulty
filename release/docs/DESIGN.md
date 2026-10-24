# Design notes (verified against the Minecraft 26.3 sources)

Source facts below were read from the decompiled 26.3 jar (Loom `genSources`), not from memory.

## Where the damage multiplier sits

```
ServerPlayer.hurtServer
  Player.hurtServer           world difficulty scaling for scalesWithDifficulty() sources
                              (Easy: min(d/2+1, d); Hard: d*1.5; Peaceful: 0)       <- vanilla, untouched
    LivingEntity.hurtServer   shield blocking, helmet, i-frame check on RAW damage    <- vanilla, untouched
      Player.actuallyHurt
        getDamageAfterArmorAbsorb
        getDamageAfterMagicAbsorb   resistance effect, then protection enchantments
          └── RETURN hook: Personal Difficulty multiplier (exactly once)             <- this mod
        absorption hearts, then health, food exhaustion from damage, death
```

Why there:

* It is the last step of vanilla mitigation, so the result is an exact fraction of what vanilla would deal
  (testable: Easy loss == 0.6 x the loss of an identically equipped Normal player).
* The invulnerability-frame comparison (`damage <= lastHurt`) uses raw damage earlier in `hurtServer`, so
  i-frames are untouched.
* Shields, totems and death handling run outside the hook; a multiplier >= 0 cannot heal or revive.
* No public Fabric API event can change a damage amount (`ALLOW_DAMAGE` only cancels, `AFTER_DAMAGE` only
  observes), hence one narrow `@ModifyReturnValue` mixin on `LivingEntity.getDamageAfterMagicAbsorb`, applied
  only when `this` is a `ServerPlayer`.
* **No double application of vanilla Hard.** The world's x1.5 is part of the number the hook receives. The
  personal factor is one flat multiplier on top; the mod never reads or re-applies the world difficulty.

Precedence for choosing the factor: void -> administrative -> self-inflicted -> other player (PvP) ->
per-damage-type override -> environmental gate -> profile multiplier.

## Hunger

All player-driven exhaustion goes through `Player.causeFoodExhaustion(float)`; its argument is scaled at the
head (`@ModifyVariable`). Natural regeneration adds exhaustion inside `FoodData.tick` directly, so those two
`addExhaustion` calls are wrapped the same way (`@WrapOperation`, player taken from `tick`'s parameter).
Scaling the amount keeps vanilla's float remainder exact: no accumulator, no state to corrupt on mode
switches, logout or respawn.

## Ranged aim

Vanilla passes `rangedAttackUncertainty(level)` (14 - 4 x difficultyId) to `Projectile.shoot`, which adds
`triangle(0, 0.0172275 * uncertainty)` per axis to the unit aim vector. Spread is therefore linear in the
value, and a larger value is a wider spread. For a shot whose target is an Easy player the value becomes
`max(vanilla, easy.ranged-min-uncertainty)`; it never tightens. Hooks sit at the one call that has the target
in scope: `AbstractSkeleton.performRangedAttack`, `Illusioner.performRangedAttack`,
`Drowned.performRangedAttack` (`@ModifyExpressionValue` on the uncertainty call, target = the method
argument) and `CrossbowItem.performShooting` -> `shoot` (`@WrapOperation`, mob shooters with a non-null target).
Nothing is stored on the mob, there is no per-tick work and no extra RNG use.

## State

* `PdRuntime` is a single volatile reference installed in `SERVER_STARTING` and cleared in `SERVER_STOPPED`
  (one server per JVM). Before/after, every hook returns vanilla values.
* Config: immutable record, swapped atomically on reload. Selections: `ConcurrentHashMap<UUID, Mode>`, with
  INHERIT represented by absence; JSON written atomically only on change; a failed write rolls the change
  back so a mode is never reported active unless stored.
* `mixins.json` uses `defaultRequire: 1`: if any target changes in a future Minecraft version the server
  fails loudly at startup instead of silently losing a feature.
