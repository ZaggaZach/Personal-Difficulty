package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.gametest.EliteAiGameTests.Arena;
import java.util.HashSet;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

/**
 * 1.4.0, Phase 6 (plan 6.6): zombies breaking doors, the creeper fuse and the pillager crossbow, each against an Elite player and,
 * as the control, a Normal player in an arena of its own. Door-breaking and the creeper fuse ship OFF, so those two run in the
 * {@code elite_mobs_on} test environment, which switches them on for that batch only.
 */
public class EliteMobsGameTests {
	private static final String MOBS_ON = "personaldifficulty-gametest:elite_mobs_on";

	private static ServerPlayer toughPlayer(GameTestHelper h, Arena a, String name, PersonalDifficultyMode mode, double x, double z) {
		ServerPlayer p = a.player(h, name, mode, x, z);
		p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100000, 4, false, false));
		return p;
	}

	// ------------------------------------------------------------------ pillagers (on by default)

	@GameTest(maxTicks = 520)
	public void ai_pillager_firesSoonerAtAnElitePlayer(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		check(rt().config().eliteMobs().pillagerFastCrossbow() && rt().config().eliteMobs().pillagerAttackDelayTicks() == 5, "the shipped defaults");
		Arena[] arenas = {new Arena(h, 109), new Arena(h, 110)};
		PersonalDifficultyMode[] modes = {PersonalDifficultyMode.ELITE, PersonalDifficultyMode.NORMAL};
		Mob[] pillagers = new Mob[2];
		ServerPlayer[] players = new ServerPlayer[2];
		int[] shots = new int[2];
		Set<UUID> seen = new HashSet<>();
		for (int i = 0; i < 2; i++) {
			pillagers[i] = arenas[i].spawnOnIsland(EntityTypes.PILLAGER, 5, 50);
			players[i] = toughPlayer(h, arenas[i], "pd_pl_" + i, modes[i], 15, 50);
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
				AABB box = new AABB(a.x0 - 4, a.y - 2, a.z0 - 4, a.x0 + 124, a.y + 30, a.z0 + 104);
				for (AbstractArrow arrow : level.getEntitiesOfClass(AbstractArrow.class, box, x -> x.getOwner() == shooter)) {
					if (seen.add(arrow.getUUID())) {
						shots[i]++;
					}
				}
			}
		});
		at(h, 500, () -> {
			String report = String.format(Locale.ROOT, "PILLAGER-LIVE 10 blocks, 495 ticks: Elite %d shots (every %.0f ticks), Normal %d shots (every %.0f ticks)",
				shots[0], shots[0] == 0 ? 0.0 : 495.0 / shots[0], shots[1], shots[1] == 0 ? 0.0 : 495.0 / shots[1]);
			System.out.println(report);
			check(shots[1] >= 3, "premise: the Normal pillager fires: " + report);
			check(shots[0] >= shots[1] * 1.25, "the Elite pillager fires clearly more often (cycle about 30 ticks against about 55): " + report);
			for (Arena a : arenas) {
				a.close();
			}
			h.succeed();
		});
	}

	// ------------------------------------------------------------------ creepers (off by default; on in this environment)

	@GameTest(environment = MOBS_ON, maxTicks = 200)
	public void ai_creeper_shortFuseAgainstAnElitePlayerOnly(GameTestHelper h) {
		check(rt().config().eliteMobs().creeperShortFuse() && rt().config().eliteMobs().creeperFuseTicks() == 20, "the test environment switched it on");
		Arena[] arenas = {new Arena(h, 111), new Arena(h, 112)};
		PersonalDifficultyMode[] modes = {PersonalDifficultyMode.ELITE, PersonalDifficultyMode.NORMAL};
		Creeper[] creepers = new Creeper[2];
		ServerPlayer[] players = new ServerPlayer[2];
		long[] lit = {-1, -1};
		long[] boom = {-1, -1};
		for (int i = 0; i < 2; i++) {
			creepers[i] = (Creeper) arenas[i].spawn(EntityTypes.CREEPER, 50, 50);
			players[i] = toughPlayer(h, arenas[i], "pd_cr_" + i, modes[i], 51.5, 50);
		}
		long start = h.getTick();
		everyTick(h, () -> {
			for (int i = 0; i < 2; i++) {
				tickPlayer(players[i]);
				players[i].setHealth(players[i].getMaxHealth());
				long t = h.getTick() - start;
				if (t == 5) {
					creepers[i].setTarget(players[i]);
				}
				if (lit[i] < 0 && creepers[i].getSwellDir() > 0) {
					lit[i] = t;
				}
				if (lit[i] >= 0 && boom[i] < 0 && !creepers[i].isAlive()) {
					boom[i] = t;
				}
			}
		});
		at(h, 180, () -> {
			long fuseE = boom[0] - lit[0];
			long fuseN = boom[1] - lit[1];
			String report = String.format(Locale.ROOT, "CREEPER-FUSE lit to explosion: Elite %d ticks (lit at %d), Normal %d ticks (lit at %d)", fuseE, lit[0], fuseN, lit[1]);
			System.out.println(report);
			check(lit[0] >= 0 && boom[0] >= 0 && lit[1] >= 0 && boom[1] >= 0, "premise: both creepers lit and exploded: " + report);
			check(fuseE >= 18 && fuseE <= 23, "Elite: about 20 ticks: " + report);
			check(fuseN >= 28 && fuseN <= 33, "Normal: vanilla's 30 ticks: " + report);
			for (Arena a : arenas) {
				a.close();
			}
			h.succeed();
		});
	}

	// ------------------------------------------------------------------ zombies and doors (off by default; on in this environment)

	/** A closed stone room around (60, 50) with an oak door in its west wall at (57, 50); returns the door's lower block. */
	private static BlockPos room(Arena a) {
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
		return door;
	}

	/**
	 * A zombie hunting a player shut in a room with a wooden door, in a Normal world: against the Elite player it breaks the door
	 * (vanilla's own break: about 12 seconds of hitting it); against the Normal player the door stays. The test keeps each zombie
	 * on its target (it cannot see through the walls, and target memory is not what this measures).
	 */
	@GameTest(environment = MOBS_ON, maxTicks = 1000)
	public void ai_zombie_breaksADoorToReachAnElitePlayer_notANormalOne(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		check(rt().config().eliteMobs().zombieBreakDoors(), "the test environment switched it on");
		Arena[] arenas = {new Arena(h, 113), new Arena(h, 114)};
		PersonalDifficultyMode[] modes = {PersonalDifficultyMode.ELITE, PersonalDifficultyMode.NORMAL};
		Mob[] zombies = new Mob[2];
		ServerPlayer[] players = new ServerPlayer[2];
		BlockPos[] doors = new BlockPos[2];
		long[] brokenAt = {-1, -1};
		for (int i = 0; i < 2; i++) {
			doors[i] = room(arenas[i]);
			zombies[i] = arenas[i].spawn(EntityTypes.ZOMBIE, 45, 50);
			players[i] = toughPlayer(h, arenas[i], "pd_zd_" + i, modes[i], 60.5, 50.5);
		}
		long start = h.getTick();
		everyTick(h, () -> {
			for (int i = 0; i < 2; i++) {
				tickPlayer(players[i]);
				long t = h.getTick() - start;
				if (t % 20 == 5) {
					zombies[i].setTarget(players[i]);
				}
				if (brokenAt[i] < 0 && !(level.getBlockState(doors[i]).getBlock() instanceof DoorBlock)) {
					brokenAt[i] = t;
				}
			}
		});
		at(h, 960, () -> {
			String report = String.format(Locale.ROOT, "ZOMBIE-DOOR world %s: Elite room door broken at tick %d, Normal room door broken at tick %d (-1 = intact); zombies ended %.1f and %.1f blocks from the door",
				level.getDifficulty(), brokenAt[0], brokenAt[1], Math.sqrt(zombies[0].distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(doors[0]))), Math.sqrt(zombies[1].distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(doors[1]))));
			System.out.println(report);
			check(level.getDifficulty() != net.minecraft.world.Difficulty.HARD, "premise: not a Hard world (where vanilla zombies may break doors anyway): " + report);
			check(brokenAt[0] > 0, "the zombie broke the door to reach the Elite player: " + report);
			check(brokenAt[1] < 0, "the Normal player's door is intact: " + report);
			for (Arena a : arenas) {
				a.close();
			}
			h.succeed();
		});
	}
}
