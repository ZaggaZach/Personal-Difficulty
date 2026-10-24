package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.zaggazach.personaldifficulty.PdHooks;
import com.zaggazach.personaldifficulty.logic.RangedAccuracy;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Pillagers fire via {@code CrossbowItem.performShooting(..., targetOverride)}. The shooter and
 * target are both known at the {@code shoot} call, so the spread is widened per shot, only for a
 * {@link Mob} shooter aiming at an Easy player. Player-fired crossbows are never touched. Piglins
 * reach the same code with a null target (brain-driven; vanilla aims them by view vector), so they
 * are deliberately unaffected and documented as unsupported.
 */
@Mixin(CrossbowItem.class)
abstract class CrossbowAimMixin {
	@WrapOperation(
		method = "performShooting",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/item/CrossbowItem;shoot(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;Ljava/util/List;FFZLnet/minecraft/world/entity/LivingEntity;)V"
		)
	)
	private void personaldifficulty$aim(
		CrossbowItem self, ServerLevel level, LivingEntity shooter, InteractionHand hand, ItemStack weapon, List<ItemStack> projectiles,
		float power, float uncertainty, boolean isCrit, @Nullable LivingEntity target, Operation<Void> original
	) {
		if (shooter instanceof Mob mob && target != null) {
			uncertainty = PdHooks.rangedUncertainty(mob, target, RangedAccuracy.Family.CROSSBOW, uncertainty);
		}
		original.call(self, level, shooter, hand, weapon, projectiles, power, uncertainty, isCrit, target);
	}
}
