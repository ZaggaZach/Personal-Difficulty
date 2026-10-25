package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.zaggazach.personaldifficulty.PdHooks;
import com.zaggazach.personaldifficulty.logic.RangedAccuracy;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Witch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Target-specific aim for a witch's thrown potions (per shot, only when the target is an Easy/Elite player). */
@Mixin(Witch.class)
abstract class WitchAimMixin {
	@ModifyExpressionValue(
		method = "performRangedAttack",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/Witch;rangedAttackUncertainty(Lnet/minecraft/world/level/Level;)F")
	)
	private float personaldifficulty$aim(float vanilla, @Local(argsOnly = true) LivingEntity target) {
		return PdHooks.rangedUncertainty((Witch) (Object) this, target, RangedAccuracy.Family.WITCH, vanilla);
	}
}
