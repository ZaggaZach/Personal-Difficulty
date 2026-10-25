package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.zaggazach.personaldifficulty.PdHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The dragon's {@code canAttack} does not call super, so it needs its own Peaceful gate. */
@Mixin(EnderDragon.class)
abstract class EnderDragonTargetMixin {
	@ModifyReturnValue(method = "canAttack", at = @At("RETURN"))
	private boolean personaldifficulty$noTargetingPeaceful(boolean original, LivingEntity target) {
		return original && !PdHooks.blocksTargeting((Entity) (Object) this, target);
	}
}
