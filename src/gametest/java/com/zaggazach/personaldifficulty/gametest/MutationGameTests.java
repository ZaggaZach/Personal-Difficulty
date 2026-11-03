package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.gametest.EliteAiGameTests.Arena;
import com.zaggazach.personaldifficulty.logic.EndermanBlindside;
import com.zaggazach.personaldifficulty.mixin.EndermanAccess;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 1.4.0, Phase 9: the plan's mutation check (section 8, gate item 3) for every risky feature. Each test runs the same scenario as
 * the feature's own test with the feature switched OFF and asserts that the Elite player then gets vanilla behaviour, i.e. that
 * the feature's own test would fail without its hook. Features that ship on are switched off by the {@code elite_features_off}
 * environment; features that ship off are tested in the default environment, where they are off.
 */
public class MutationGameTests {
	private static final String FEATURES_OFF = "personaldifficulty-gametest:elite_features_off";

	private static Vec3 mean(List<Vec3> dirs) {
		Vec3 m = Vec3.ZERO;
		for (Vec3 d : dirs) {
			m = m.add(d);
		}
		return m.scale(1.0 / dirs.size());
	}

	// ------------------------------------------------------------------ features that ship ON, switched off here

	@GameTest(environment = FEATURES_OFF, maxTicks = 100)
	public void mutation_eliteSkeletonsOff_theEliteShotIsVanilla_noSpeedNoLead(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		check(!rt().config().eliteSkeleton().enabled(), "the environment switched Elite skeletons off");
		AbstractSkeleton skeleton = h.spawnWithNoFreeWill(EntityTypes.SKELETON, 2, 1, 2);
		skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		ServerPlayer elite = player(h, "pd_mu_sk", PersonalDifficultyMode.ELITE);
		elite.snapTo(skeleton.getX() + 15, skeleton.getY(), skeleton.getZ());
		elite.setKnownMovement(new Vec3(0, 0, 0.25));
		Sample s = sample(level, skeleton, 200, () -> skeleton.performRangedAttack(elite, 1.0F));
		Vec3 dir = mean(s.directions());
		String report = String.format(Locale.ROOT, "MUTATION skeleton off: Elite speed %.3f, mean sideways aim %.4f", s.meanSpeed(), dir.z);
		System.out.println(report);
		nearRel(1.6, s.meanSpeed(), 0.02, "vanilla launch speed: " + report);
		check(Math.abs(dir.z) < 0.02, "no lead: " + report);
		h.succeed();
	}

	@GameTest(environment = FEATURES_OFF, maxTicks = 100)
	public void mutation_blindsideOff_anEndermanNeverLandsBehindAnElitePlayer(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		check(!rt().config().eliteEnderman().enabled(), "the environment switched Blindside off");
		Arena a = new Arena(h, 126);
		ServerPlayer p = a.player(h, "pd_mu_bs", PersonalDifficultyMode.ELITE, 60.5, 30.5);
		Enderman e = (Enderman) a.spawn(EntityTypes.ENDERMAN, 60.5, 60.5);
		e.setNoAi(true);
		int behind = 0;
		for (int t = 0; t < 40; t++) {
			for (int kind = 0; kind < 2; kind++) {
				p.snapTo(a.x0 + 60.5, a.y + 1, a.z0 + 30.5, 0f, 0f);
				e.snapTo(a.x0 + 60.5, a.y + 1, a.z0 + 60.5);
				if (kind == 0) {
					((EndermanAccess) e).personaldifficulty$teleportTowards(p);
				} else {
					Arrow arrow = new Arrow(level, p, new ItemStack(Items.ARROW), null);
					e.hurtServer(level, level.damageSources().arrow(arrow, p), 1.0F);
					arrow.discard();
				}
				double dx = e.getX() - p.getX();
				double dz = e.getZ() - p.getZ();
				if (Math.sqrt(dx * dx + dz * dz) <= 5.0 && EndermanBlindside.isBehind(p.getX(), p.getZ(), p.getYRot(), e.getX(), e.getZ())) {
					behind++;
				}
			}
		}
		System.out.println("MUTATION blindside off: Elite player had the Enderman behind them " + behind + " of 80 times");
		check(behind <= 2, "vanilla: practically never (" + behind + " of 80)");
		a.close();
		h.succeed();
	}

	@GameTest(environment = FEATURES_OFF, maxTicks = 520)
	public void mutation_fastCrossbowOff_anElitePillagerFiresLikeANormalOne(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		check(!rt().config().eliteMobs().pillagerFastCrossbow(), "the environment switched it off");
		Arena[] arenas = {new Arena(h, 127), new Arena(h, 128)};
		PersonalDifficultyMode[] modes = {PersonalDifficultyMode.ELITE, PersonalDifficultyMode.NORMAL};
		Mob[] pillagers = new Mob[2];
		ServerPlayer[] players = new ServerPlayer[2];
		int[] shots = new int[2];
		Set<UUID> seen = new HashSet<>();
		for (int i = 0; i < 2; i++) {
			pillagers[i] = arenas[i].spawnOnIsland(EntityTypes.PILLAGER, 5, 50);
			players[i] = arenas[i].player(h, "pd_mu_pl" + i, modes[i], 15, 50);
			players[i].addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100000, 4, false, false));
		}
		long start = h.getTick();
		everyTick(h, () -> {
			for (int i = 0; i < 2; i++) {
				tickPlayer(players[i]);
				players[i].setHealth(players[i].getMaxHealth());
				if (h.getTick() - start == 5) {
					pillagers[i].setTarget(players[i]);
				}
				Arena a = arenas[i];
				Mob shooter = pillagers[i];
				for (AbstractArrow arrow : level.getEntitiesOfClass(AbstractArrow.class, new AABB(a.x0 - 4, a.y - 2, a.z0 - 4, a.x0 + 124, a.y + 30, a.z0 + 104),
					x -> x.getOwner() == shooter)) {
					if (seen.add(arrow.getUUID())) {
						shots[i]++;
					}
				}
			}
		});
		at(h, 500, () -> {
			String report = "MUTATION fast crossbow off: Elite " + shots[0] + " shots, Normal " + shots[1];
			System.out.println(report);
			check(shots[1] >= 3, "premise: the Normal pillager fires: " + report);
			check(shots[0] <= shots[1] * 1.2, "the Elite pillager no longer fires clearly more often: " + report);
			for (Arena a : arenas) {
				a.close();
			}
			h.succeed();
		});
	}

	// ------------------------------------------------------------------ features that ship OFF: the default environment

	@GameTest(maxTicks = 200)
	public void mutation_creeperFuseOffByDefault_anEliteFuseIsVanilla(GameTestHelper h) {
		check(!rt().config().eliteMobs().creeperShortFuse(), "off by default");
		Arena a = new Arena(h, 129);
		Creeper c = (Creeper) a.spawn(EntityTypes.CREEPER, 50, 50);
		ServerPlayer p = a.player(h, "pd_mu_cr", PersonalDifficultyMode.ELITE, 51.5, 50);
		p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100000, 4, false, false));
		long[] lit = {-1};
		long[] boom = {-1};
		long start = h.getTick();
		everyTick(h, () -> {
			tickPlayer(p);
			p.setHealth(p.getMaxHealth());
			long t = h.getTick() - start;
			if (t == 5) {
				c.setTarget(p);
			}
			if (lit[0] < 0 && c.getSwellDir() > 0) {
				lit[0] = t;
			}
			if (lit[0] >= 0 && boom[0] < 0 && !c.isAlive()) {
				boom[0] = t;
			}
		});
		at(h, 180, () -> {
			long fuse = boom[0] - lit[0];
			System.out.println("MUTATION creeper fuse off: Elite fuse " + fuse + " ticks");
			check(lit[0] >= 0 && boom[0] >= 0, "premise: it lit and exploded");
			check(fuse >= 28 && fuse <= 33, "vanilla's 30 ticks: " + fuse);
			a.close();
			h.succeed();
		});
	}

	@GameTest(maxTicks = 1000)
	public void mutation_doorBreakingOffByDefault_aZombieLeavesAnElitePlayersDoorAlone(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		check(!rt().config().eliteMobs().zombieBreakDoors(), "off by default");
		Arena a = new Arena(h, 130);
		for (int x = 57; x <= 63; x++) {
			for (int z = 47; z <= 53; z++) {
				boolean wall = x == 57 || x == 63 || z == 47 || z == 53;
				for (int dy = 1; dy <= 4; dy++) {
					if (wall || dy == 4) {
						a.level.setBlock(new BlockPos(a.x0 + x, a.y + dy, a.z0 + z), Blocks.STONE.defaultBlockState(), 18);
					}
				}
			}
		}
		BlockPos door = new BlockPos(a.x0 + 57, a.y + 1, a.z0 + 50);
		var lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
		a.level.setBlock(door, lower, 18);
		a.level.setBlock(door.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 18);
		Mob zombie = a.spawn(EntityTypes.ZOMBIE, 45, 50);
		ServerPlayer p = a.player(h, "pd_mu_zd", PersonalDifficultyMode.ELITE, 60.5, 50.5);
		p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100000, 4, false, false));
		long start = h.getTick();
		everyTick(h, () -> {
			tickPlayer(p);
			if ((h.getTick() - start) % 20 == 5) {
				zombie.setTarget(p);
			}
		});
		at(h, 960, () -> {
			boolean intact = level.getBlockState(door).getBlock() instanceof DoorBlock;
			System.out.println("MUTATION door-breaking off: Elite player's door intact " + intact + " (world " + level.getDifficulty() + ")");
			check(level.getDifficulty() != net.minecraft.world.Difficulty.HARD, "premise: not a Hard world");
			check(intact, "the door is intact");
			a.close();
			h.succeed();
		});
	}

	@GameTest(maxTicks = 60)
	public void mutation_wardenAngerOffByDefault_anElitePlayerGetsVanillaAnger(GameTestHelper h) {
		check(!rt().config().eliteMobs().wardenAnger(), "off by default");
		Arena a = new Arena(h, 131);
		Warden w = (Warden) a.spawn(EntityTypes.WARDEN, 20, 20);
		ServerPlayer p = a.player(h, "pd_mu_wd", PersonalDifficultyMode.ELITE, 25, 20);
		w.increaseAngerAt(p, 35, true);
		int anger = w.getAngerManagement().getActiveAnger(p);
		System.out.println("MUTATION warden off: Elite anger from 35 is " + anger);
		check(anger == 35, "vanilla anger: " + anger);
		a.close();
		h.succeed();
	}
}
