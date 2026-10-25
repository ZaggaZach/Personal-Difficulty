package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.zaggazach.personaldifficulty.PdHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Warden decides targets (and anger) in {@code canTargetEntity} and its {@code canAttack} does not call super. */
@Mixin(Warden.class)
abstract class WardenTargetMixin {
	@ModifyReturnValue(method = "canTargetEntity", at = @At("RETURN"))
	private boolean personaldifficulty$noTargetingPeaceful(boolean original, Entity entity) {
		return original && !PdHooks.blocksTargeting((Entity) (Object) this, entity);
	}
}
