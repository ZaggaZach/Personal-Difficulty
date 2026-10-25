package com.zaggazach.personaldifficulty.mixin;

import com.zaggazach.personaldifficulty.PdHooks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Belt and braces for {@code canAttack}: a direct {@code setTarget(peacefulPlayer)} becomes {@code setTarget(null)}. */
@Mixin(Mob.class)
abstract class MobTargetMixin {
	@ModifyVariable(method = "setTarget", at = @At("HEAD"), argsOnly = true)
	private @Nullable LivingEntity personaldifficulty$noPeacefulTarget(@Nullable LivingEntity target) {
		return PdHooks.blocksTargeting((Mob) (Object) this, target) ? null : target;
	}
}
