package com.zaggazach.personaldifficulty.mixin;

import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the bow-cadence hook ask a skeleton for its own (per-species) Hard-difficulty attack interval. */
@Mixin(AbstractSkeleton.class)
public interface AbstractSkeletonAccess {
	@Invoker("getHardAttackInterval")
	int personaldifficulty$hardAttackInterval();
}
