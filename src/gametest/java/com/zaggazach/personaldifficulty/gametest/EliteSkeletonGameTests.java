package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.gametest.EliteAiGameTests.Arena;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 1.4.0, Phase 6 (plan 6.4): Elite skeletons. Direct shots (the hook, with exact numbers), live AI (range and firing rate) and an
 * opt-in damage measurement (plan section 9: what faster arrows actually do to damage, measured, not assumed).
 */
public class EliteSkeletonGameTests {
	/** Its own batch: the damage measurement changes the arrow speed setting while it runs. */
	private static final String MEASURE = "personaldifficulty-gametest:elite_skeleton_measure";

	private static Vec3 mean(List<Vec3> dirs) {
		Vec3 m = Vec3.ZERO;
		for (Vec3 d : dirs) {
			m = m.add(d);
		}
		return m.scale(1.0 / dirs.size());
	}

	// ------------------------------------------------------------------ direct: speed, damage, lead

	@GameTest(maxTicks = 100)
	public void skeleton_eliteShot_isFasterAndLeadsAMovingPlayer_anyOtherTargetGetsTheVanillaShot(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		PdConfig.EliteSkeleton sk = rt().config().eliteSkeleton();
		check(sk.enabled() && sk.arrowSpeedMultiplier() == 1.5 && sk.predictionStrength() == 1.0, "the shipped defaults: " + sk);
		AbstractSkeleton skeleton = h.spawnWithNoFreeWill(EntityTypes.SKELETON, 2, 1, 2);
		skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		ServerPlayer elite = player(h, "pd_sk_e", PersonalDifficultyMode.ELITE);
		ServerPlayer normal = player(h, "pd_sk_n", PersonalDifficultyMode.NORMAL);
		// 15 blocks away along x, level with the skeleton
		Vec3 spot = new Vec3(skeleton.getX() + 15, skeleton.getY(), skeleton.getZ());
		elite.snapTo(spot.x, spot.y, spot.z);
		normal.snapTo(spot.x, spot.y, spot.z);
		int n = 200;
		elite.setKnownMovement(Vec3.ZERO);
		normal.setKnownMovement(new Vec3(0, 0, 0.25));
		Sample eliteStill = sample(level, skeleton, n, () -> skeleton.performRangedAttack(elite, 1.0F));
		Sample normalMoving = sample(level, skeleton, n, () -> skeleton.performRangedAttack(normal, 1.0F));
		elite.setKnownMovement(new Vec3(0, 0, 0.25)); // walking sideways, a little faster than walking speed
		Sample eliteMoving = sample(level, skeleton, n, () -> skeleton.performRangedAttack(elite, 1.0F));
		Vec3 still = mean(eliteStill.directions());
		Vec3 moving = mean(eliteMoving.directions());
		Vec3 vanilla = mean(normalMoving.directions());
		String report = String.format(Locale.ROOT,
			"SKELETON-SHOT speed: Elite %.3f, Normal %.3f (ratio %.3f) | base damage: Elite %.3f, Normal %.3f | mean direction z: Elite still %.4f, Elite moving %.4f, Normal moving %.4f | pitch y: Elite %.4f, Normal %.4f",
			eliteStill.meanSpeed(), normalMoving.meanSpeed(), eliteStill.meanSpeed() / normalMoving.meanSpeed(), eliteStill.baseDamage(), normalMoving.baseDamage(),
			still.z, moving.z, vanilla.z, still.y, vanilla.y);
		System.out.println(report);
		nearRel(1.6 * 1.5, eliteStill.meanSpeed(), 0.02, "Elite arrows leave at 1.5x vanilla's 1.6: " + report);
		nearRel(1.6, normalMoving.meanSpeed(), 0.02, "a Normal player gets vanilla's speed: " + report);
		check(Math.abs(eliteStill.baseDamage() - normalMoving.baseDamage()) < 0.08, "damage multiplier 1.0: the base damage is vanilla's: " + report);
		check(moving.z - still.z > 0.05, "a moving Elite player is led (aimed ahead, on the side they move to): " + report);
		check(Math.abs(vanilla.z) < 0.02, "a Normal player's movement is never led: " + report);
		check(still.y < vanilla.y, "the faster arrow needs less arc than vanilla's fixed allowance: " + report);
		h.succeed();
	}

	// ------------------------------------------------------------------ live AI: range and firing rate

	/** The arrows {@code shooter} has fired so far (by UUID), and the shooter-to-target distance when each one appeared. */
	private static void collectShots(ServerLevel level, Arena a, Mob shooter, Mob target, Set<UUID> seen, List<Double> distances) {
		AABB box = new AABB(a.x0 - 4, a.y - 2, a.z0 - 4, a.x0 + 124, a.y + 30, a.z0 + 104);
		for (AbstractArrow arrow : level.getEntitiesOfClass(AbstractArrow.class, box, x -> x.getOwner() == shooter)) {
			if (seen.add(arrow.getUUID())) {
				distances.add((double) shooter.distanceTo(target));
			}
		}
	}

	/**
	 * Three arenas, each a skeleton and one player who cannot be hurt: an Elite player 20 blocks away (beyond vanilla's 15-block
	 * shooting range, inside the Elite 24), an Elite player 10 blocks away and a Normal player 10 blocks away. The Elite skeleton
	 * at 20 must shoot from where it stands (vanilla would walk in to 15 first); at 10 blocks the Elite one must shoot more often
	 * than the Normal one (the firing countdown is 10 ticks instead of vanilla's 40, or 20 in a Hard world).
	 */
	@GameTest(maxTicks = 520)
	public void ai_skeleton_shootsAnElitePlayerFromFurtherAway_andMoreOften(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		Arena far = new Arena(h, 103);
		Arena nearE = new Arena(h, 104);
		Arena nearN = new Arena(h, 105);
		Arena[] arenas = {far, nearE, nearN};
		double[] distance = {20, 10, 10};
		PersonalDifficultyMode[] modes = {PersonalDifficultyMode.ELITE, PersonalDifficultyMode.ELITE, PersonalDifficultyMode.NORMAL};
		Mob[] skeletons = new Mob[3];
		ServerPlayer[] players = new ServerPlayer[3];
		List<Set<UUID>> seen = new ArrayList<>();
		List<List<Double>> shotAt = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			skeletons[i] = arenas[i].spawnOnIsland(EntityTypes.SKELETON, 5, 50);
			skeletons[i].setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); // no burning mid-test
			players[i] = arenas[i].player(h, "pd_skl_" + i, modes[i], 5 + distance[i], 50);
			players[i].addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100000, 4, false, false));
			seen.add(new HashSet<>());
			shotAt.add(new ArrayList<>());
		}
		long start = h.getTick();
		everyTick(h, () -> {
			for (int i = 0; i < 3; i++) {
				tickPlayer(players[i]);
				players[i].setHealth(players[i].getMaxHealth());
				if (h.getTick() - start == 5) {
					skeletons[i].setTarget(players[i]); // the targeting itself is tested elsewhere; this measures the shooting
				}
				collectShots(level, arenas[i], skeletons[i], players[i], seen.get(i), shotAt.get(i));
			}
		});
		at(h, 500, () -> {
			int farShots = shotAt.get(0).size();
			double farMin = shotAt.get(0).stream().mapToDouble(Double::doubleValue).min().orElse(-1);
			int eliteShots = shotAt.get(1).size();
			int normalShots = shotAt.get(2).size();
			String report = String.format(Locale.ROOT,
				"SKELETON-LIVE world %s | Elite at 20 blocks: %d shots, closest shot from %.1f blocks | at 10 blocks over 495 ticks: Elite %d shots (every %.0f ticks), Normal %d shots (every %.0f ticks)",
				level.getDifficulty(), farShots, farMin, eliteShots, eliteShots == 0 ? 0.0 : 495.0 / eliteShots, normalShots, normalShots == 0 ? 0.0 : 495.0 / normalShots);
			System.out.println(report);
			check(farShots >= 3, "an Elite player 20 blocks away is shot at: " + report);
			check(farMin > 16.0, "from beyond vanilla's 15-block range (the skeleton did not have to walk in): " + report);
			check(normalShots >= 3, "premise: the Normal skeleton shoots at 10 blocks: " + report);
			check(eliteShots >= normalShots * 1.2, "the Elite skeleton shoots clearly more often (cycle about 30 ticks against 60, or 40 in a Hard world): " + report);
			for (Arena a : arenas) {
				a.close();
			}
			h.succeed();
		});
	}

	// ------------------------------------------------------------------ measurement: what speed does to damage

	/**
	 * Plan section 9: the damage that Elite skeleton arrows actually deal, at fixed distances, for arrow speeds 1.0x, 1.5x and 2.0x
	 * (damage multiplier 1.0). Stationary Elite players (Elite takes 2x, no armour), one skeleton each at 8, 16 and 24 blocks, a
	 * shot every 20 ticks (more than the 10 invulnerability ticks), health restored after each hit. Opt-in ({@code -PpdPerf=true})
	 * and not required: it reports
	 * numbers for the README and for any decision on {@code arrow-damage-multiplier}.
	 */
	@GameTest(environment = MEASURE, maxTicks = 3 * 21 * 20 + 200, required = false)
	public void measure_skeletonArrowDamageAtDistance_forSeveralArrowSpeeds(GameTestHelper h) {
		if (!Boolean.getBoolean("pd.perf")) {
			h.succeed(); // opt-in, with the performance measurements: ./gradlew build -PpdPerf=true
			return;
		}
		ServerLevel level = h.getLevel();
		Arena a = new Arena(h, 106);
		double[] distances = {8, 16, 24};
		double[] speeds = {1.0, 1.5, 2.0};
		int shots = 20;
		AbstractSkeleton[] skeletons = new AbstractSkeleton[3];
		ServerPlayer[] players = new ServerPlayer[3];
		for (int i = 0; i < 3; i++) {
			skeletons[i] = (AbstractSkeleton) a.spawn(EntityTypes.SKELETON, 5, 10 + i * 30);
			skeletons[i].setNoAi(true);
			skeletons[i].setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
			skeletons[i].setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
			players[i] = a.player(h, "pd_skm_" + i, PersonalDifficultyMode.ELITE, 5 + distances[i], 10 + i * 30);
		}
		// [speed][distance] -> damage per hit; hits and misses
		List<List<List<Double>>> damage = new ArrayList<>();
		int[][] fired = new int[3][3];
		for (int s = 0; s < 3; s++) {
			List<List<Double>> row = new ArrayList<>();
			for (int d = 0; d < 3; d++) {
				row.add(new ArrayList<>());
			}
			damage.add(row);
		}
		long start = h.getTick();
		everyTick(h, () -> {
			long t = h.getTick() - start;
			int s = (int) (t / (21 * 20));
			if (s >= 3) {
				return;
			}
			long inSpeed = t % (21 * 20);
			if (inSpeed == 0) {
				rt().setValue("elite.skeleton.arrow-speed-multiplier", Double.toString(speeds[s]));
			}
			for (int d = 0; d < 3; d++) {
				ServerPlayer p = players[d];
				tickPlayer(p);
				p.snapTo(a.x0 + 5 + distances[d], a.y + 1, a.z0 + 10 + d * 30);
				if (inSpeed % 20 == 19) {
					// the shot fired 19 ticks ago has landed or missed by now
					float lost = p.getMaxHealth() - p.getHealth();
					if (lost > 0) {
						damage.get(s).get(d).add((double) lost);
					}
					p.setHealth(p.getMaxHealth());
				}
				if (inSpeed % 20 == 0 && inSpeed / 20 < shots) {
					skeletons[d].performRangedAttack(p, 1.0F);
					fired[s][d]++;
				}
			}
		});
		at(h, 3L * 21 * 20 + 20, () -> {
			try {
				StringBuilder out = new StringBuilder("SKELETON-DAMAGE stationary Elite player (2x damage taken, no armour), world " + level.getDifficulty() + ", damage per hit in health points:");
				for (int s = 0; s < 3; s++) {
					for (int d = 0; d < 3; d++) {
						List<Double> v = damage.get(s).get(d);
						double mean = v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
						double sd = Math.sqrt(v.stream().mapToDouble(x -> (x - mean) * (x - mean)).average().orElse(0));
						double max = v.stream().mapToDouble(Double::doubleValue).max().orElse(0);
						out.append(String.format(Locale.ROOT, "%nSKELETON-DAMAGE speed %.1fx distance %2.0f: hits %d/%d, mean %.2f, sd %.2f, max %.1f",
							speeds[s], distances[d], v.size(), fired[s][d], mean, sd, max));
					}
				}
				System.out.println(out);
			} finally {
				ConfigCommandGameTests.cleanOverrides();
				a.close();
			}
			h.succeed();
		});
	}
}
