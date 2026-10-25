package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Sample;
import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Tier;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.illager.Illusioner;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** In-server tests for the Elite difficulty: real players, real armour, real mobs, real mixins. */
public class EliteGameTests {
	private static final double ELITE_ARMOUR = 0.60;

	/** What Player.hurtServer does to a difficulty-scaling source before anything else sees it. */
	private static float worldScaled(ServerLevel level, DamageSource source, float amount) {
		if (!source.scalesWithDifficulty()) {
			return amount;
		}
		Difficulty d = level.getDifficulty();
		if (d == Difficulty.PEACEFUL) {
			return 0.0F;
		}
		if (d == Difficulty.EASY) {
			return Math.min(amount / 2.0F + 1.0F, amount);
		}
		return d == Difficulty.HARD ? amount * 3.0F / 2.0F : amount;
	}

	/** Independent expectation built from vanilla's own CombatRules, with an optional armour weakening and Resistance. */
	private static float expected(ServerLevel level, ServerPlayer p, DamageSource source, float amount, double armourEffectiveness, int resistanceAmplifier, double finalMultiplier) {
		float raw = worldScaled(level, source, amount);
		float afterArmour = CombatRules.getDamageAfterAbsorb(p, raw, source, (float) p.getArmorValue(), (float) p.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
		float weakened = (float) (raw - (raw - afterArmour) * armourEffectiveness);
		float dmg = weakened;
		if (resistanceAmplifier >= 0) {
			int absorb = 25 - (resistanceAmplifier + 1) * 5;
			dmg = Math.max(dmg * absorb / 25.0F, 0.0F);
		}
		float epf = EnchantmentHelper.getDamageProtection(level, p, source);
		if (dmg > 0.0F && epf > 0.0F) {
			dmg = CombatRules.getDamageAfterMagicAbsorb(dmg, epf);
		}
		return (float) (dmg * finalMultiplier);
	}

	@GameTest
	public void elite_unarmoured_isTwiceNormalAndMoreThanHard_andOthersAreUnchanged(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		var difficultyBefore = level.getDifficulty();
		ServerPlayer elite = player(h, "pd_e_elite", PersonalDifficultyMode.ELITE);
		ServerPlayer hard = player(h, "pd_e_hard", PersonalDifficultyMode.HARD);
		ServerPlayer normal = player(h, "pd_e_normal", PersonalDifficultyMode.NORMAL);
		ServerPlayer easy = player(h, "pd_e_easy", PersonalDifficultyMode.EASY);
		for (DamageSource s : List.of(level.damageSources().fall(), level.damageSources().lava(), level.damageSources().inFire(), level.damageSources().drown(), level.damageSources().cactus())) {
			near(8.0F, lost(elite, s, 4.0F), "elite x2 " + s.getMsgId() + " (applied once, not x4)");
			near(5.0F, lost(hard, s, 4.0F), "hard x1.25 " + s.getMsgId());
			near(4.0F, lost(normal, s, 4.0F), "normal " + s.getMsgId());
			near(2.4F, lost(easy, s, 4.0F), "easy x0.6 " + s.getMsgId());
			check(lost(elite, s, 4.0F) > lost(hard, s, 4.0F), "elite is more dangerous than hard under equal conditions");
		}
		check(level.getDifficulty() == difficultyBefore, "world difficulty must not change");
		h.succeed();
	}

	@GameTest
	public void elite_armourTiers_matchVanillaFormulasAndKeepPreparationRewarding(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 2, 1, 2);
		DamageSource hit = level.damageSources().mobAttack(zombie);
		float amount = 3.0F; // a zombie's base attack; the world's own difficulty scaling is added by Player.hurtServer
		StringBuilder table = new StringBuilder("ELITE-TABLE world=" + level.getDifficulty() + " raw=" + worldScaled(level, hit, amount));
		float prevElite = Float.MAX_VALUE;
		for (Tier tier : Tier.values()) {
			ServerPlayer normal = player(h, "pd_t_n_" + tier, PersonalDifficultyMode.NORMAL);
			ServerPlayer hard = player(h, "pd_t_h_" + tier, PersonalDifficultyMode.HARD);
			ServerPlayer elite = player(h, "pd_t_e_" + tier, PersonalDifficultyMode.ELITE);
			ServerPlayer easy = player(h, "pd_t_z_" + tier, PersonalDifficultyMode.EASY);
			for (ServerPlayer p : List.of(normal, hard, elite, easy)) {
				equip(level, p, tier);
			}
			float lNormal = lost(normal, hit, amount);
			float lHard = lost(hard, hit, amount);
			float lElite = lost(elite, hit, amount);
			float lEasy = lost(easy, hit, amount);
			float eNormal = expected(level, normal, hit, amount, 1.0, -1, 1.0);
			nearRel(eNormal, lNormal, 0.002, tier + " normal vs vanilla CombatRules");
			nearRel(eNormal * 1.25, lHard, 0.002, tier + " hard = 1.25 x vanilla");
			nearRel(eNormal * 0.60, lEasy, 0.002, tier + " easy = 0.60 x vanilla");
			nearRel(expected(level, elite, hit, amount, ELITE_ARMOUR, -1, 2.0), lElite, 0.002, tier + " elite = 2.0 x (armour at 60% effectiveness, then enchantments)");
			check(lElite > lHard * 1.4F, tier + ": elite (" + lElite + ") must be clearly more dangerous than hard (" + lHard + ")");
			check(lElite < prevElite, tier + ": better armour must still reduce Elite damage (" + lElite + " vs previous tier " + prevElite + ")");
			prevElite = lElite;
			table.append(String.format("%n  %-17s normal %6.3f hard %6.3f elite %6.3f  -> hits to die: normal %5.1f hard %5.1f elite %5.1f",
				tier, lNormal, lHard, lElite, 20 / lNormal, 20 / lHard, 20 / lElite));
		}
		System.out.println(table);
		// the point of the armour mechanic: top-tier armour must not neutralise Elite the way a bare x2 would
		ServerPlayer bestElite = player(h, "pd_t_best", PersonalDifficultyMode.ELITE);
		ServerPlayer bestNormal = player(h, "pd_t_bestn", PersonalDifficultyMode.NORMAL);
		equip(level, bestElite, Tier.NETHERITE_PROT4);
		equip(level, bestNormal, Tier.NETHERITE_PROT4);
		float eliteBest = lost(bestElite, hit, amount);
		float normalBest = lost(bestNormal, hit, amount);
		check(eliteBest > normalBest * 3.5F, "netherite+Prot IV Elite must take well over 3.5x vanilla (a bare x2 would be 2x): " + eliteBest + " vs " + normalBest);
		h.succeed();
	}

	@GameTest
	public void elite_resistanceAndAbsorptionAndZeroDamage(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 2, 1, 2);
		DamageSource hit = level.damageSources().mobAttack(zombie);
		ServerPlayer elite = player(h, "pd_r_elite", PersonalDifficultyMode.ELITE);
		ServerPlayer normal = player(h, "pd_r_normal", PersonalDifficultyMode.NORMAL);
		for (ServerPlayer p : List.of(elite, normal)) {
			equip(level, p, Tier.NETHERITE_PROT4);
			p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100000, 1)); // Resistance II
		}
		nearRel(expected(level, normal, hit, 12.0F, 1.0, 1, 1.0), lost(normal, hit, 12.0F), 0.002, "normal with Resistance II");
		nearRel(expected(level, elite, hit, 12.0F, ELITE_ARMOUR, 1, 2.0), lost(elite, hit, 12.0F), 0.002, "elite with Resistance II: resistance still fully effective, then x2");
		float withRes = lost(elite, hit, 12.0F);
		elite.removeEffect(MobEffects.RESISTANCE);
		check(lost(elite, hit, 12.0F) > withRes, "Resistance still helps Elite players");
		elite.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100000, 4)); // Resistance V = immune
		near(0.0F, lost(elite, hit, 12.0F), "Resistance V still fully protects (x2 of 0 is 0; Elite is not an unavoidable kill)");
		elite.removeEffect(MobEffects.RESISTANCE);

		// absorption hearts take the already-scaled damage first
		ServerPlayer abs = player(h, "pd_abs", PersonalDifficultyMode.ELITE);
		abs.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100000, 1)); // raises MAX_ABSORPTION; setAbsorptionAmount alone is clamped to it
		abs.setHealth(20.0F);
		abs.setAbsorptionAmount(6.0F);
		near(6.0F, abs.getAbsorptionAmount(), "premise: 6 absorption hearts in effect");
		abs.damageCooldownTime = 0;
		abs.hurtServer(level, level.damageSources().fall(), 4.0F); // scaled to 8
		near(18.0F, abs.getHealth(), "8 damage: 6 absorbed, 2 reaches health");
		near(0.0F, abs.getAbsorptionAmount(), "absorption consumed");
		h.succeed();
	}

	@GameTest
	public void elite_shieldStillBlocksBeforeAnyScaling(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer elite = player(h, "pd_s_elite", PersonalDifficultyMode.ELITE);
		ServerPlayer normal = player(h, "pd_s_normal", PersonalDifficultyMode.NORMAL);
		for (ServerPlayer p : List.of(elite, normal)) {
			p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
			p.setYRot(0.0F); // facing +Z
			p.startUsingItem(InteractionHand.OFF_HAND);
			tickPlayer(p, 8); // vanilla shield blocking delay is 5 ticks
			check(p.isBlocking(), p.getName().getString() + " must be blocking");
			Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 2, 1, 2);
			zombie.snapTo(p.getX(), p.getY(), p.getZ() + 1.5);
			p.setHealth(20.0F);
			p.damageCooldownTime = 0;
			p.hurtServer(level, level.damageSources().mobAttack(zombie), 3.0F);
			near(20.0F, p.getHealth(), p.getName().getString() + ": a fully blocked hit is never scaled up into damage");
		}
		h.succeed();
	}

	@GameTest
	public void elite_invulnerabilityFramesAreVanilla_andScaleOnlyTheRawDifference(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer elite = player(h, "pd_i_elite", PersonalDifficultyMode.ELITE);
		ServerPlayer normal = player(h, "pd_i_normal", PersonalDifficultyMode.NORMAL);
		for (ServerPlayer p : List.of(elite, normal)) {
			p.setHealth(20.0F);
			p.setAbsorptionAmount(0.0F);
			p.damageCooldownTime = 0;
			check(p.hurtServer(level, level.damageSources().fall(), 4.0F), "first hit lands");
			check(!p.hurtServer(level, level.damageSources().fall(), 3.0F), "weaker raw hit inside i-frames is ignored for both");
			check(p.hurtServer(level, level.damageSources().fall(), 6.0F), "stronger raw hit lands, only the 2.0 raw difference applies");
		}
		near(6.0F, 20.0F - normal.getHealth(), "normal: 4 + (6-4)");
		near(12.0F, 20.0F - elite.getHealth(), "elite: 2 x (4 + (6-4)); i-frames compare raw damage exactly as in vanilla");
		h.succeed();
	}

	@GameTest
	public void elite_exceptionsFollowTheExistingRules(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer elite = player(h, "pd_x_elite", PersonalDifficultyMode.ELITE);
		ServerPlayer normal = player(h, "pd_x_normal", PersonalDifficultyMode.NORMAL);
		ServerPlayer attacker = player(h, "pd_x_attacker", PersonalDifficultyMode.NORMAL);
		for (ServerPlayer p : List.of(elite, normal)) {
			equip(level, p, Tier.DIAMOND_PROT4);
		}
		DamageSource pvp = level.damageSources().playerAttack(attacker);
		near(lost(normal, pvp, 6.0F), lost(elite, pvp, 6.0F), "Normal attacks Elite: existing PvP rule (unchanged), armour not weakened");
		near(lost(normal, level.damageSources().playerAttack(normal), 4.0F), lost(elite, level.damageSources().playerAttack(elite), 4.0F), "self-inflicted unchanged");
		near(4.0F, lost(elite, level.damageSources().fellOutOfWorld(), 4.0F), "void unchanged by default");
		near(4.0F, lost(elite, level.damageSources().genericKill(), 4.0F), "/kill-type damage unchanged by default");
		h.succeed();
	}

	@GameTest
	public void elite_hunger_isScaledAtTheSourceForRealActivities(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer normal = player(h, "pd_h_normal", PersonalDifficultyMode.NORMAL);
		ServerPlayer hard = player(h, "pd_h_hard", PersonalDifficultyMode.HARD);
		ServerPlayer elite = player(h, "pd_h_elite", PersonalDifficultyMode.ELITE);
		Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 2, 1, 2);
		h.setBlock(1, 1, 1, Blocks.DIRT);
		java.util.Map<String, java.util.function.Consumer<ServerPlayer>> activities = new java.util.LinkedHashMap<>();
		activities.put("jump", p -> p.jumpFromGround());
		activities.put("sprint-jump", p -> {
			p.setSprinting(true);
			p.jumpFromGround();
			p.setSprinting(false);
		});
		activities.put("melee attack", p -> {
			// exhaustion is only added when the hit LANDS, so clear the zombie's own invulnerability frames
			zombie.damageCooldownTime = 0;
			zombie.setHealth(zombie.getMaxHealth());
			p.attack(zombie);
		});
		activities.put("break block", p -> {
			h.setBlock(1, 1, 1, Blocks.DIRT); // breakable by hand WITH drops; playerDestroy (and its exhaustion) needs the correct tool
			net.minecraft.core.BlockPos bp = h.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1));
			p.gameMode.destroyBlock(bp);
		});
		activities.put("take damage", p -> {
			p.damageCooldownTime = 0;
			p.hurtServer(level, level.damageSources().mobAttack(zombie), 1.0F);
		});
		for (var activity : activities.entrySet()) {
			float[] cost = new float[3];
			int i = 0;
			for (ServerPlayer p : List.of(normal, hard, elite)) {
				setExhaustion(p, 0.0F);
				p.setHealth(20.0F);
				activity.getValue().accept(p);
				cost[i++] = exhaustion(p);
			}
			check(cost[0] > 0.0F, activity.getKey() + " must cost exhaustion in vanilla (" + cost[0] + ")");
			near(cost[0] * 1.25F, cost[1], activity.getKey() + ": hard x1.25 exactly once");
			near(cost[0] * 2.0F, cost[2], activity.getKey() + ": elite x2.0 exactly once");
		}

		// Representative sustained effort: 100 sprint-jumps with the real FoodData.tick conversion of exhaustion to food.
		int[] foodLost = new int[3];
		int i = 0;
		for (ServerPlayer p : List.of(normal, hard, elite)) {
			p.setHealth(20.0F);
			p.getFoodData().setFoodLevel(20);
			p.getFoodData().setSaturation(0.0F);
			setExhaustion(p, 0.0F);
			for (int n = 0; n < 100; n++) {
				p.setSprinting(true);
				p.jumpFromGround();
				p.getFoodData().tick(p);
			}
			foodLost[i++] = 20 - p.getFoodData().getFoodLevel();
		}
		System.out.println("ELITE-HUNGER 100 sprint-jumps from 20 food, 0 saturation: normal lost " + foodLost[0] + ", hard " + foodLost[1] + ", elite " + foodLost[2]);
		check(foodLost[2] >= foodLost[0] * 2 - 1 && foodLost[2] <= foodLost[0] * 2 + 1, "elite loses ~2x the food of normal: " + foodLost[2] + " vs " + foodLost[0]);
		check(foodLost[1] > foodLost[0] && foodLost[1] < foodLost[2], "hard sits between");

		// saturation and food are never edited directly: only exhaustion is scaled
		ServerPlayer keep = player(h, "pd_h_keep", PersonalDifficultyMode.ELITE);
		keep.getFoodData().setFoodLevel(13);
		keep.getFoodData().setSaturation(2.5F);
		setExhaustion(keep, 1.0F);
		keep.causeFoodExhaustion(0.5F);
		check(keep.getFoodData().getFoodLevel() == 13, "food untouched");
		near(2.5F, keep.getFoodData().getSaturationLevel(), "saturation untouched");
		near(2.0F, exhaustion(keep), "1.0 + 0.5 x 2.0");
		h.succeed();
	}

	// ------------------------------------------------------------------ ranged

	@GameTest(maxTicks = 100)
	public void elite_ranged_skeletonAimIsTighterOnlyAgainstElitePlayers(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		AbstractSkeleton skeleton = h.spawnWithNoFreeWill(EntityTypes.SKELETON, 2, 1, 2);
		skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		ServerPlayer elite = player(h, "pd_ra_elite", PersonalDifficultyMode.ELITE);
		ServerPlayer normal = player(h, "pd_ra_normal", PersonalDifficultyMode.NORMAL);
		ServerPlayer hard = player(h, "pd_ra_hard", PersonalDifficultyMode.HARD);
		ServerPlayer easy = player(h, "pd_ra_easy", PersonalDifficultyMode.EASY);
		ServerPlayer peaceful = player(h, "pd_ra_peaceful", PersonalDifficultyMode.PEACEFUL);
		Zombie decoy = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 6, 1, 2);
		Vec3 spot = elite.position();
		for (var e : List.of(normal, hard, easy, peaceful)) {
			e.snapTo(spot.x, spot.y, spot.z);
		}
		decoy.snapTo(spot.x, spot.y, spot.z);
		int n = 800;
		double vanilla = vanillaUncertainty(level);
		Sample eliteS = sample(level, skeleton, n, () -> skeleton.performRangedAttack(elite, 1.0F));
		Sample normalS = sample(level, skeleton, n, () -> skeleton.performRangedAttack(normal, 1.0F));
		Sample mobS = sample(level, skeleton, n, () -> skeleton.performRangedAttack(decoy, 1.0F));
		expectTighter("skeleton", eliteS, normalS, mobS, vanilla, 1.0);
		// target-specific: every other personal difficulty is shot at exactly like a non-player (Easy is widened, tested elsewhere)
		for (var other : List.of(hard, peaceful)) {
			Sample s = sample(level, skeleton, n, () -> skeleton.performRangedAttack(other, 1.0F));
			nearRel(mobS.spread(), s.spread(), 0.12, other.getName().getString() + " keeps vanilla aim");
		}
		// switching target on the SAME skeleton follows immediately
		Sample back = sample(level, skeleton, n, () -> skeleton.performRangedAttack(elite, 1.0F));
		nearRel(eliteS.spread(), back.spread(), 0.15, "back to the Elite player: tight again");
		// "tighter" is not "guaranteed": the spread alone still misses a standing player at long range
		double hit12 = eliteS.hitFraction(12, 0.3, 0.9), hit24 = eliteS.hitFraction(24, 0.3, 0.9), hit32 = eliteS.hitFraction(32, 0.3, 0.9);
		double normal24 = normalS.hitFraction(24, 0.3, 0.9), normal32 = normalS.hitFraction(32, 0.3, 0.9);
		System.out.printf("ELITE-AIM world=%s vanilla-uncertainty=%.0f: spread-only hit fraction on a standing player: elite 12m %.3f 24m %.3f 32m %.3f | vanilla 24m %.3f 32m %.3f%n",
			level.getDifficulty(), vanilla, hit12, hit24, hit32, normal24, normal32);
		check(hit32 < 0.995, "not a mathematically guaranteed hit at long range: " + hit32);
		check(hit32 > normal32 + 0.02, "but clearly better than vanilla: " + hit32 + " vs " + normal32);
		h.succeed();
	}

	@GameTest(maxTicks = 100)
	public void elite_ranged_strayIllusionerDrownedPillagerPiglinWitch(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer elite = player(h, "pd_rb_elite", PersonalDifficultyMode.ELITE);
		ServerPlayer normal = player(h, "pd_rb_normal", PersonalDifficultyMode.NORMAL);
		Vec3 spot = elite.position();
		normal.snapTo(spot.x, spot.y, spot.z);
		Zombie decoy = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 6, 1, 2);
		decoy.snapTo(spot.x, spot.y, spot.z);
		int n = 500;
		double vanilla = vanillaUncertainty(level);

		AbstractSkeleton stray = h.spawnWithNoFreeWill(EntityTypes.STRAY, 2, 1, 2);
		stray.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		expectTighter("stray", sample(level, stray, n, () -> stray.performRangedAttack(elite, 1.0F)),
			sample(level, stray, n, () -> stray.performRangedAttack(normal, 1.0F)),
			sample(level, stray, n, () -> stray.performRangedAttack(decoy, 1.0F)), vanilla, 1.0);

		Illusioner illusioner = h.spawnWithNoFreeWill(EntityTypes.ILLUSIONER, 2, 1, 2);
		illusioner.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		expectTighter("illusioner", sample(level, illusioner, n, () -> illusioner.performRangedAttack(elite, 1.0F)),
			sample(level, illusioner, n, () -> illusioner.performRangedAttack(normal, 1.0F)),
			sample(level, illusioner, n, () -> illusioner.performRangedAttack(decoy, 1.0F)), vanilla, 1.0);

		Drowned drowned = h.spawnWithNoFreeWill(EntityTypes.DROWNED, 2, 1, 2);
		drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
		expectTighter("drowned", sample(level, drowned, n, () -> drowned.performRangedAttack(elite, 1.0F)),
			sample(level, drowned, n, () -> drowned.performRangedAttack(normal, 1.0F)),
			sample(level, drowned, n, () -> drowned.performRangedAttack(decoy, 1.0F)), vanilla, 1.0);

		Pillager pillager = h.spawnWithNoFreeWill(EntityTypes.PILLAGER, 2, 1, 2);
		java.util.function.Consumer<LivingEntity> pillagerShot = target -> {
			pillager.setTarget(target);
			ItemStack fresh = new ItemStack(Items.CROSSBOW);
			fresh.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStackTemplate(Items.ARROW)));
			pillager.setItemSlot(EquipmentSlot.MAINHAND, fresh);
			pillager.performRangedAttack(target, 1.0F);
		};
		expectTighter("pillager", sample(level, pillager, n, () -> pillagerShot.accept(elite)),
			sample(level, pillager, n, () -> pillagerShot.accept(normal)),
			sample(level, pillager, n, () -> pillagerShot.accept(decoy)), vanilla, 1.0);

		// Piglin: brain-driven. getTarget() is null, the target only exists as the performRangedAttack argument.
		Piglin piglin = h.spawnWithNoFreeWill(EntityTypes.PIGLIN, 2, 1, 2);
		check(piglin.getTarget() == null, "premise: a piglin's getTarget() is empty even while it shoots");
		java.util.function.Consumer<LivingEntity> piglinShot = target -> {
			ItemStack fresh = new ItemStack(Items.CROSSBOW);
			fresh.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStackTemplate(Items.ARROW)));
			piglin.setItemSlot(EquipmentSlot.MAINHAND, fresh);
			piglin.performRangedAttack(target, 1.0F);
		};
		expectTighter("piglin", sample(level, piglin, n, () -> piglinShot.accept(elite)),
			sample(level, piglin, n, () -> piglinShot.accept(normal)),
			sample(level, piglin, n, () -> piglinShot.accept(decoy)), vanilla, 1.0);

		Witch witch = h.spawnWithNoFreeWill(EntityTypes.WITCH, 2, 1, 2);
		Sample w1 = sample(level, witch, n, () -> witch.performRangedAttack(elite, 1.0F));
		Sample w2 = sample(level, witch, n, () -> witch.performRangedAttack(normal, 1.0F));
		Sample w3 = sample(level, witch, n, () -> witch.performRangedAttack(elite, 1.0F));
		Sample w4 = sample(level, witch, n, () -> witch.performRangedAttack(decoy, 1.0F));
		System.out.printf("WITCH-DIAG elite %.5f normal %.5f elite-again %.5f decoy %.5f%n", w1.spread(), w2.spread(), w3.spread(), w4.spread());
		expectTighter("witch", w1, w2, w4, 8.0, 1.0); // Witch.rangedAttackUncertainty is a vanilla constant 8, independent of world difficulty
		h.succeed();
	}

	@GameTest(maxTicks = 100)
	public void ranged_easyAlsoCoversPiglinAndWitch_andConfigTogglesApply(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer easy = player(h, "pd_rc_easy", PersonalDifficultyMode.EASY);
		ServerPlayer normal = player(h, "pd_rc_normal", PersonalDifficultyMode.NORMAL);
		Vec3 spot = easy.position();
		normal.snapTo(spot.x, spot.y, spot.z);
		Zombie decoy = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 6, 1, 2);
		decoy.snapTo(spot.x, spot.y, spot.z);
		int n = 500;
		double vanilla = vanillaUncertainty(level);
		Piglin piglin = h.spawnWithNoFreeWill(EntityTypes.PIGLIN, 2, 1, 2);
		java.util.function.Consumer<LivingEntity> piglinShot = target -> {
			ItemStack fresh = new ItemStack(Items.CROSSBOW);
			fresh.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStackTemplate(Items.ARROW)));
			piglin.setItemSlot(EquipmentSlot.MAINHAND, fresh);
			piglin.performRangedAttack(target, 1.0F);
		};
		expectWider("piglin", sample(level, piglin, n, () -> piglinShot.accept(easy)),
			sample(level, piglin, n, () -> piglinShot.accept(normal)), sample(level, piglin, n, () -> piglinShot.accept(decoy)), vanilla);
		Witch witch = h.spawnWithNoFreeWill(EntityTypes.WITCH, 2, 1, 2);
		expectWider("witch", sample(level, witch, n, () -> witch.performRangedAttack(easy, 1.0F)),
			sample(level, witch, n, () -> witch.performRangedAttack(normal, 1.0F)), sample(level, witch, n, () -> witch.performRangedAttack(decoy, 1.0F)), 8.0);
		withConfig("ranged.piglins=false\nranged.witches=false\n", () -> {
			Sample off = sample(level, piglin, n, () -> piglinShot.accept(easy));
			Sample base = sample(level, piglin, n, () -> piglinShot.accept(normal));
			nearRel(base.spread(), off.spread(), 0.12, "piglin toggle off -> vanilla aim");
			Sample woff = sample(level, witch, n, () -> witch.performRangedAttack(easy, 1.0F));
			Sample wbase = sample(level, witch, n, () -> witch.performRangedAttack(normal, 1.0F));
			nearRel(wbase.spread(), woff.spread(), 0.12, "witch toggle off -> vanilla aim");
		});
		h.succeed();
	}

	/**
	 * Runs {@code body} with the (disposable) GameTest world temporarily on {@code difficulty}. This is the TEST
	 * HARNESS changing its own world; the mod itself never changes the world difficulty (it contains no such call).
	 */
	private static void inWorldDifficulty(ServerLevel level, Difficulty difficulty, Runnable body) {
		Difficulty before = level.getDifficulty();
		level.getServer().setDifficulty(difficulty, true);
		try {
			check(level.getDifficulty() == difficulty, "test world switched to " + difficulty);
			body.run();
		} finally {
			level.getServer().setDifficulty(before, true);
		}
	}

	@GameTest
	public void worldDifficultyScaling_isIncludedExactlyOnce_inEasyNormalAndHardWorlds(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 2, 1, 2);
		DamageSource hit = level.damageSources().mobAttack(zombie);
		float amount = 6.0F;
		float[] normalLoss = new float[Difficulty.values().length];
		for (Difficulty world : List.of(Difficulty.EASY, Difficulty.NORMAL, Difficulty.HARD)) {
			inWorldDifficulty(level, world, () -> {
				for (Tier tier : List.of(Tier.NONE, Tier.NETHERITE_PROT4)) {
					ServerPlayer normal = player(h, "pd_w_n_" + world + tier, PersonalDifficultyMode.NORMAL);
					ServerPlayer easy = player(h, "pd_w_z_" + world + tier, PersonalDifficultyMode.EASY);
					ServerPlayer hard = player(h, "pd_w_h_" + world + tier, PersonalDifficultyMode.HARD);
					ServerPlayer elite = player(h, "pd_w_e_" + world + tier, PersonalDifficultyMode.ELITE);
					for (ServerPlayer p : List.of(normal, easy, hard, elite)) {
						equip(level, p, tier);
					}
					float lNormal = lost(normal, hit, amount);
					nearRel(expected(level, normal, hit, amount, 1.0, -1, 1.0), lNormal, 0.002, world + "/" + tier + " vanilla (world scaling " + worldScaled(level, hit, amount) + ")");
					nearRel(lNormal * 0.60, lost(easy, hit, amount), 0.002, world + "/" + tier + ": easy = 0.6 x the vanilla result of THIS world");
					nearRel(lNormal * 1.25, lost(hard, hit, amount), 0.002, world + "/" + tier + ": hard = 1.25 x the vanilla result of THIS world (world scaling not repeated)");
					nearRel(expected(level, elite, hit, amount, ELITE_ARMOUR, -1, 2.0), lost(elite, hit, amount), 0.002, world + "/" + tier + ": elite from the world-scaled raw damage");
					if (tier == Tier.NONE) {
						normalLoss[world.ordinal()] = lNormal;
					}
				}
			});
		}
		// premise: vanilla itself scales this hit by the world (Easy: min(6/2+1, 6) = 4, Normal: 6, Hard: 9)
		near(4.0F, normalLoss[Difficulty.EASY.ordinal()], "vanilla Easy world");
		near(6.0F, normalLoss[Difficulty.NORMAL.ordinal()], "vanilla Normal world");
		near(9.0F, normalLoss[Difficulty.HARD.ordinal()], "vanilla Hard world");
		h.succeed();
	}

	@GameTest(maxTicks = 100)
	public void worldDifficultyAim_eliteAndEasyAreRelativeToTheVanillaValueOfThatWorld(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		AbstractSkeleton skeleton = h.spawnWithNoFreeWill(EntityTypes.SKELETON, 2, 1, 2);
		skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		ServerPlayer elite = player(h, "pd_wa_elite", PersonalDifficultyMode.ELITE);
		ServerPlayer easy = player(h, "pd_wa_easy", PersonalDifficultyMode.EASY);
		ServerPlayer normal = player(h, "pd_wa_normal", PersonalDifficultyMode.NORMAL);
		Zombie decoy = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 6, 1, 2);
		Vec3 spot = elite.position();
		for (ServerPlayer e : List.of(easy, normal)) {
			e.snapTo(spot.x, spot.y, spot.z);
		}
		decoy.snapTo(spot.x, spot.y, spot.z);
		inWorldDifficulty(level, Difficulty.HARD, () -> {
			check(vanillaUncertainty(level) == 2.0, "Hard world: vanilla skeleton uncertainty is 2");
			Sample e = sample(level, skeleton, 800, () -> skeleton.performRangedAttack(elite, 1.0F));
			Sample n = sample(level, skeleton, 800, () -> skeleton.performRangedAttack(normal, 1.0F));
			Sample d = sample(level, skeleton, 800, () -> skeleton.performRangedAttack(decoy, 1.0F));
			expectTighter("skeleton in a HARD world", e, n, d, 2.0, 1.0); // 1 vs 2: still half the spread
			Sample z = sample(level, skeleton, 800, () -> skeleton.performRangedAttack(easy, 1.0F));
			expectWider("skeleton in a HARD world (easy player)", z, n, d, 2.0); // 14 vs 2
		});
		h.succeed();
	}
}
