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
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Pillagers fire via {@code CrossbowItem.performShooting(..., targetOverride)}. The shooter and
 * target are both known at the {@code shoot} call, so the spread is adjusted per shot, only for a
 * {@link Mob} shooter aiming at an Easy (wider) or Elite (tighter) player. Player-fired crossbows are
 * never touched. Piglins reach the same code with a null target (brain-driven; vanilla aims them by
 * view vector); {@link PiglinAimMixin} exposes their brain target for the duration of the call.
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
		// Pillagers pass their target; brain-driven piglins pass null and expose theirs for this call only.
		LivingEntity aimed = target != null ? target : PdHooks.scopedAimTarget();
		if (shooter instanceof Mob mob && aimed != null) {
			RangedAccuracy.Family family = mob instanceof Piglin ? RangedAccuracy.Family.PIGLIN : RangedAccuracy.Family.CROSSBOW;
			uncertainty = PdHooks.rangedUncertainty(mob, aimed, family, uncertainty);
		}
		original.call(self, level, shooter, hand, weapon, projectiles, power, uncertainty, isCrit, target);
	}
}
