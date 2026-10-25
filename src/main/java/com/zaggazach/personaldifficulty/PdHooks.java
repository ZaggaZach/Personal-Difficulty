package com.zaggazach.personaldifficulty;

import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.logic.DamageAdjuster;
import com.zaggazach.personaldifficulty.logic.DamageContext;
import com.zaggazach.personaldifficulty.logic.EliteSurvival;
import com.zaggazach.personaldifficulty.logic.HungerAdjuster;
import com.zaggazach.personaldifficulty.logic.PeacefulRules;
import com.zaggazach.personaldifficulty.logic.RangedAccuracy;
import com.zaggazach.personaldifficulty.mixin.AbstractSkeletonAccess;
import java.util.ArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import org.jspecify.annotations.Nullable;

/**
 * Thin bridge from mixins/events to the Minecraft-free logic. Every method returns its input unchanged
 * unless a personal profile genuinely applies, so non-participants see pure vanilla behaviour.
 */
public final class PdHooks {
	private static final ThreadLocal<LivingEntity> AIM_TARGET = new ThreadLocal<>();

	private PdHooks() {
	}

	private static PersonalDifficultyMode modeOf(PdRuntime rt, @Nullable Entity e) {
		return e instanceof ServerPlayer p ? rt.modeOf(p.getUUID()) : PersonalDifficultyMode.NORMAL;
	}

	// ------------------------------------------------------------------ damage scaling

	/** Elite: weakens armour's own mitigation. Called with the damage before and after vanilla armour. */
	public static float armorMitigated(ServerPlayer victim, DamageSource source, float original, float afterArmor) {
		PdRuntime rt = PdRuntime.get();
		if (rt == null) {
			return afterArmor;
		}
		PersonalDifficultyMode mode = rt.modeOf(victim.getUUID());
		if (mode != PersonalDifficultyMode.ELITE) {
			return afterArmor;
		}
		double effectiveness = DamageAdjuster.armorEffectiveness(rt.config(), mode, describe(victim, source));
		return DamageAdjuster.applyArmor(original, afterArmor, effectiveness);
	}

	/** Called with damage that has already been through armour, resistance and enchantments. */
	public static float incomingDamage(ServerPlayer victim, DamageSource source, float vanillaDamage) {
		PdRuntime rt = PdRuntime.get();
		if (rt == null) {
			return vanillaDamage;
		}
		PersonalDifficultyMode mode = rt.modeOf(victim.getUUID());
		if (!mode.hasProfile()) {
			return vanillaDamage;
		}
		double factor = DamageAdjuster.multiplier(rt.config(), mode, describe(victim, source));
		return DamageAdjuster.apply(vanillaDamage, factor);
	}

	public static DamageContext describe(Player victim, DamageSource source) {
		Entity attacker = source.getEntity();
		boolean isVoid = source.is(DamageTypes.FELL_OUT_OF_WORLD);
		boolean admin = !isVoid && (source.is(DamageTypes.GENERIC_KILL) || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY));
		boolean self = attacker == victim;
		boolean otherPlayer = attacker instanceof Player && !self;
		boolean environmental = attacker == null && source.getDirectEntity() == null;
		String typeId = source.typeHolder().unwrapKey().map(k -> k.identifier().toString()).orElse("unknown:unknown");
		return new DamageContext(typeId, isVoid, admin, self, otherPlayer, environmental);
	}

	// ------------------------------------------------------------------ peaceful

	/**
	 * The player a damage source or effect source is attributable to: the entity itself if a player, the
	 * owner of a projectile / area-effect cloud / tamed or otherwise ownable entity. Chains are followed a
	 * few levels. Null if nobody can be attributed (dispensers, lava or fire placed earlier, ...).
	 */
	public static @Nullable ServerPlayer responsiblePlayer(@Nullable Entity e) {
		for (int depth = 0; e != null && depth < 4; depth++) {
			if (e instanceof ServerPlayer p) {
				return p;
			}
			if (e instanceof Projectile projectile) {
				e = projectile.getOwner();
			} else if (e instanceof AreaEffectCloud cloud) {
				e = cloud.getOwner();
			} else if (e instanceof OwnableEntity ownable) {
				e = ownable.getOwner();
			} else {
				return null;
			}
		}
		return null;
	}

	private static @Nullable ServerPlayer responsiblePlayer(DamageSource source) {
		ServerPlayer p = responsiblePlayer(source.getEntity());
		return p != null ? p : responsiblePlayer(source.getDirectEntity());
	}

	/** Fabric ALLOW_DAMAGE: returns false to cancel the hit before any vanilla processing. */
	public static boolean allowDamage(LivingEntity victim, DamageSource source) {
		if (!(victim instanceof Player)) {
			return true;
		}
		PdRuntime rt = PdRuntime.get();
		if (rt == null) {
			return true;
		}
		PdMobEffects.noteBeforeHit(victim);
		ServerPlayer responsible = responsiblePlayer(source);
		boolean blocked = PeacefulRules.blocksDamage(
			true,
			modeOf(rt, victim),
			responsible == null ? null : rt.modeOf(responsible.getUUID()),
			responsible == victim,
			source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
		);
		return !blocked;
	}

	/** True if {@code attacker} (a mob) may not target {@code target} because it is a Peaceful player. */
	public static boolean blocksTargeting(@Nullable Entity attacker, @Nullable Entity target) {
		if (!(attacker instanceof Mob) || !(target instanceof ServerPlayer player)) {
			return false;
		}
		PdRuntime rt = PdRuntime.get();
		return rt != null && PeacefulRules.blocksTargeting(rt.modeOf(player.getUUID()));
	}

	/** True if {@code effect} must not be applied to {@code victim} (Peaceful protection, both directions). */
	public static boolean blocksEffect(LivingEntity victim, MobEffectInstance effect, @Nullable Entity source) {
		if (!(victim instanceof Player)) {
			return false;
		}
		PdRuntime rt = PdRuntime.get();
		if (rt == null) {
			return false;
		}
		boolean harmful = effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL;
		if (!harmful) {
			return false;
		}
		ServerPlayer responsible = responsiblePlayer(source);
		return PeacefulRules.blocksEffect(
			true,
			true,
			modeOf(rt, victim),
			responsible == null ? null : rt.modeOf(responsible.getUUID()),
			responsible == victim
		);
	}

	/**
	 * Side effects of switching mode. Entering Peaceful clears harmful effects (beneficial ones stay) and
	 * extinguishes the player; nothing is done on leaving, so ordinary behaviour simply resumes.
	 */
	public static void onModeChanged(ServerPlayer player, PersonalDifficultyMode previous, PersonalDifficultyMode next) {
		if (next == PersonalDifficultyMode.PEACEFUL && previous != next) {
			for (MobEffectInstance active : new ArrayList<>(player.getActiveEffects())) {
				if (active.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
					player.removeEffect(active.getEffect());
				}
			}
			player.clearFire();
			player.setTicksFrozen(0);
		}
	}

	// ------------------------------------------------------------------ hunger

	public static float exhaustion(ServerPlayer player, float amount) {
		PdRuntime rt = PdRuntime.get();
		if (rt == null) {
			return amount;
		}
		return HungerAdjuster.adjustExhaustion(rt.config(), rt.modeOf(player.getUUID()), amount);
	}

	public static boolean isPeaceful(ServerPlayer player) {
		PdRuntime rt = PdRuntime.get();
		return rt != null && rt.modeOf(player.getUUID()) == PersonalDifficultyMode.PEACEFUL;
	}

	// ------------------------------------------------------------------ ranged aim

	/** Only a player target with a profile that changes aim ever changes the spread; any other target is vanilla. */
	public static float rangedUncertainty(Mob shooter, @Nullable LivingEntity target, RangedAccuracy.Family family, float vanilla) {
		PdRuntime rt = PdRuntime.get();
		if (rt == null || !(target instanceof ServerPlayer player)) {
			return vanilla;
		}
		return RangedAccuracy.adjust(rt.config(), family, rt.modeOf(player.getUUID()), vanilla);
	}

	/** Runs a piglin shot with its brain target visible to the crossbow hook (scoped; never kept). */
	public static void withAimTarget(LivingEntity target, Runnable shot) {
		LivingEntity previous = AIM_TARGET.get();
		AIM_TARGET.set(target);
		try {
			shot.run();
		} finally {
			if (previous == null) {
				AIM_TARGET.remove();
			} else {
				AIM_TARGET.set(previous);
			}
		}
	}

	public static @Nullable LivingEntity scopedAimTarget() {
		return AIM_TARGET.get();
	}

	// ------------------------------------------------------------------ elite: natural regeneration

	/**
	 * The tick count at which a natural-regeneration cycle fires. Vanilla passes 10 (saturated) or 80 (well fed); Elite
	 * players get the stretched value, everyone else gets the vanilla value back unchanged.
	 */
	public static int regenInterval(ServerPlayer player, int vanillaInterval) {
		PdRuntime rt = PdRuntime.get();
		if (rt == null || rt.modeOf(player.getUUID()) != PersonalDifficultyMode.ELITE) {
			return vanillaInterval;
		}
		return EliteSurvival.regenInterval(vanillaInterval, rt.config().eliteRegenMultiplier());
	}

	// ------------------------------------------------------------------ elite: enemy behaviour (target-specific)

	/** The Elite config if {@code target} is an Elite player, else null. Every AI hook starts here, so non-Elite targets cost one map lookup. */
	private static PdConfig.@Nullable EliteAi eliteAiFor(@Nullable Entity target) {
		if (!(target instanceof ServerPlayer player)) {
			return null;
		}
		PdRuntime rt = PdRuntime.get();
		if (rt == null || rt.modeOf(player.getUUID()) != PersonalDifficultyMode.ELITE) {
			return null;
		}
		return rt.config().eliteAi();
	}

	/** Distance beyond which a mob gives up hunting {@code target}. */
	public static double pursuitDistance(@Nullable LivingEntity target, double vanilla) {
		PdConfig.EliteAi ai = eliteAiFor(target);
		return ai == null ? vanilla : EliteSurvival.cappedRange(vanilla, EliteSurvival.pursuitFactor(ai));
	}

	/** How long (ticks) a mob remembers {@code target} while it cannot see it. */
	public static int targetMemoryTicks(@Nullable LivingEntity target, int vanilla) {
		PdConfig.EliteAi ai = eliteAiFor(target);
		return ai == null || !ai.improvedPursuit() ? vanilla : EliteSurvival.memoryTicks(vanilla, ai.targetMemoryMultiplier());
	}

	/** Path-search length for a mob that is currently hunting an Elite player (its follow-range attribute is untouched). */
	public static float maxPathLength(Mob mob, float vanilla) {
		PdConfig.EliteAi ai = eliteAiFor(mob.getTarget());
		return ai == null ? vanilla : (float) EliteSurvival.cappedRange(vanilla, EliteSurvival.pursuitFactor(ai));
	}

	/** Melee mobs' "should I start chasing?" re-check delay. */
	public static long meleeRecheckTicks(Mob mob, long vanilla) {
		PdConfig.EliteAi ai = eliteAiFor(mob.getTarget());
		return ai == null || !ai.improvedPursuit() ? vanilla : Math.min(vanilla, EliteSurvival.ELITE_MELEE_RECHECK_TICKS);
	}

	/** Skeleton-family bow cadence against an Elite target: the vanilla Hard interval. */
	public static int bowInterval(Mob mob, int vanilla) {
		PdConfig.EliteAi ai = eliteAiFor(mob.getTarget());
		if (ai == null || !ai.advancedCombat() || !(mob instanceof AbstractSkeleton skeleton)) {
			return vanilla;
		}
		return EliteSurvival.bowInterval(vanilla, ((AbstractSkeletonAccess) skeleton).personaldifficulty$hardAttackInterval());
	}

	/**
	 * Second-chance acquisition for an idle mob whose vanilla search found nothing: the nearest ELITE player within the
	 * extended range that passes the very same {@link TargetingConditions} (line of sight, visibility incl. sneaking and
	 * heads, invisibility, selectors, {@code canAttack}) evaluated at the larger radius. Only Elite players are candidates,
	 * so Normal, Easy, Hard and Peaceful players are never affected.
	 */
	public static @Nullable ServerPlayer extendedAcquisition(Mob mob, TargetingConditions vanillaConditions, double vanillaRange) {
		PdRuntime rt = PdRuntime.get();
		if (rt == null || !(mob.level() instanceof ServerLevel level)) {
			return null;
		}
		PdConfig.EliteAi ai = rt.config().eliteAi();
		double extended = EliteSurvival.cappedRange(vanillaRange, ai.acquisitionRangeMultiplier());
		if (extended <= vanillaRange) {
			return null;
		}
		TargetingConditions conditions = vanillaConditions.copy().range(extended);
		double limit = extended * extended;
		ServerPlayer best = null;
		double bestDistance = Double.MAX_VALUE;
		for (ServerPlayer candidate : level.players()) {
			if (rt.modeOf(candidate.getUUID()) != PersonalDifficultyMode.ELITE) {
				continue;
			}
			double distance = mob.distanceToSqr(candidate.getX(), candidate.getEyeY(), candidate.getZ());
			if (distance > limit || distance >= bestDistance) {
				continue;
			}
			if (conditions.test(level, mob, candidate)) {
				best = candidate;
				bestDistance = distance;
			}
		}
		return best;
	}
}
