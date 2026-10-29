package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.PdHooks;
import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Capture;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * Elite hostile-mob behaviour (1.2.0). Tests named {@code ai_*} run the real, unmodified vanilla goal AI of live mobs for
 * many ticks: nothing is injected except the starting positions (and, where stated, a mode switch or a wall). Tests named
 * {@code direct_*} call a hook directly and are NOT evidence about live mob behaviour.
 *
 * <p>Every test builds its own stone platform high above the 8x8 test arena, at a slot of its own 200 blocks apart (tests may run at the same time) with the chunks force-loaded, because the interesting distances (35+ blocks) do not fit the arena.
 * Each scenario has two lanes, 80 blocks apart: a Normal-player control lane and an Elite lane.
 */
public class EliteAiGameTests {
	private static final PersonalDifficultyMode NORMAL = PersonalDifficultyMode.NORMAL;
	private static final PersonalDifficultyMode ELITE = PersonalDifficultyMode.ELITE;
	private static final int LENGTH = 120;
	private static final int WIDTH = 100;
	private static final int LANE_B = 80;

	static final class Arena {
		final ServerLevel level;
		final int x0;
		final int y;
		final int z0;
		final List<Entity> entities = new ArrayList<>();
		final List<ServerPlayer> players = new ArrayList<>();
		final List<ChunkPos> forced = new ArrayList<>();

		Arena(GameTestHelper h, int slot) {
			this.level = h.getLevel();
			BlockPos origin = h.absolutePos(BlockPos.ZERO);
			this.x0 = origin.getX() + 200 * slot;
			this.z0 = origin.getZ();
			this.y = Math.min(origin.getY() + 100, level.getMaxY() - 10);
			for (int cx = (x0 >> 4) - 1; cx <= ((x0 + LENGTH) >> 4) + 1; cx++) {
				for (int cz = (z0 >> 4) - 1; cz <= ((z0 + WIDTH) >> 4) + 1; cz++) {
					level.setChunkForced(cx, cz, true);
					forced.add(new ChunkPos(cx, cz));
				}
			}
			for (int x = -4; x <= LENGTH; x++) {
				for (int z = -4; z <= WIDTH; z++) {
					for (int dy = 1; dy <= 12; dy++) {
						level.setBlock(new BlockPos(x0 + x, y + dy, z0 + z), Blocks.AIR.defaultBlockState(), 18);
					}
					level.setBlock(new BlockPos(x0 + x, y, z0 + z), Blocks.STONE.defaultBlockState(), 18);
				}
			}
		}

		/**
		 * A mob that idles near its start: vanilla's "home" restriction keeps its random strolling within 4 blocks (it does not
		 * hold it back once it hunts a target). An earlier version isolated mobs on a void island; strafing and jumping mobs
		 * walked off it and died, which silently invalidated those measurements.
		 */
		Mob spawnOnIsland(EntityType<? extends Mob> type, double x, double z) {
			Mob m = spawn(type, x, z);
			m.setHomeTo(BlockPos.containing(x0 + x, y + 1, z0 + z), 4);
			return m;
		}

		/** A mob standing at platform coordinates (x, z). */
		Mob spawn(EntityType<? extends Mob> type, double x, double z) {
			Mob m = type.spawn(level, BlockPos.containing(x0 + x, y + 1, z0 + z), EntitySpawnReason.COMMAND);
			check(m != null, "mob " + type + " must spawn");
			m.snapTo(x0 + x, y + 1, z0 + z);
			m.setPersistenceRequired();
			if (m instanceof net.minecraft.world.entity.monster.zombie.Zombie zm) {
				zm.setBaby(false);
			}
			if (!(m instanceof AbstractSkeleton)) {
				m.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); // no daylight burning mid-test
			}
			entities.add(m);
			return m;
		}

		ServerPlayer player(GameTestHelper h, String name, PersonalDifficultyMode mode, double x, double z) {
			ServerPlayer p = PdTestSupport.player(h, name, mode);
			// mobs' hits must not shove the stationary test players around (that made hunters drift into other lanes)
			p.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
			p.snapTo(x0 + x, y + 1, z0 + z);
			players.add(p);
			return p;
		}

		void wall(double x, int zFrom, int zTo) {
			for (int z = zFrom; z <= zTo; z++) {
				for (int dy = 1; dy <= 10; dy++) {
					level.setBlock(new BlockPos(x0 + (int) x, y + dy, z0 + z), Blocks.STONE.defaultBlockState(), 18);
				}
			}
		}

		void close() {
			for (Entity e : entities) {
				e.discard();
			}
			for (ServerPlayer p : players) {
				level.getServer().getPlayerList().remove(p);
			}
			for (ChunkPos c : forced) {
				level.setChunkForced(c.x(), c.z(), false);
			}
		}
	}

	static void finish(GameTestHelper h, Arena a) {
		a.close();
		h.succeed();
	}

	static double followRange(Mob m) {
		return m.getAttributeValue(Attributes.FOLLOW_RANGE);
	}

	private static void noModifiers(Mob m, String what) {
		// vanilla itself gives every spawned mob a random FOLLOW_RANGE bonus; what must be absent is anything of ours
		for (var modifier : m.getAttribute(Attributes.FOLLOW_RANGE).getModifiers()) {
			check(!modifier.id().getNamespace().equals("personaldifficulty"), what + ": no Personal Difficulty attribute modifier on FOLLOW_RANGE (" + modifier.id() + "); the effect is target-specific");
		}
		for (var attribute : List.of(Attributes.MOVEMENT_SPEED, Attributes.ATTACK_DAMAGE, Attributes.FOLLOW_RANGE)) {
			var instance = m.getAttribute(attribute);
			if (instance != null) {
				for (var modifier : instance.getModifiers()) {
					check(!modifier.id().getNamespace().equals("personaldifficulty"), what + ": no Personal Difficulty modifier on " + attribute);
				}
			}
		}
	}

	// ------------------------------------------------------------------ acquisition, per mob class

	/**
	 * A player 1.12x the mob's vanilla follow range away, in plain line of sight. Normal lane: the mob must never hunt
	 * them while farther than its vanilla range. Elite lane: it must (this is the extended acquisition).
	 */
	private static void acquisition(GameTestHelper h, EntityType<? extends Mob> type, int slot) {
		Arena a = new Arena(h, slot);
		Mob mN = a.spawnOnIsland(type, 5, 5);
		Mob mE = a.spawnOnIsland(type, 5, LANE_B + 5);
		// vanilla gives every spawned mob its own random follow-range bonus, so each lane uses its own mob's range
		double rN = followRange(mN);
		double r = followRange(mE);
		double d = r * 1.12;
		ServerPlayer pN = a.player(h, "pd_aq_n", NORMAL, 5 + rN * 1.12, 5);
		ServerPlayer pE = a.player(h, "pd_aq_e", ELITE, 5 + d, LANE_B + 5);
		// judged at the moment the live AI ACQUIRES the target (a skeleton that is already hunting strafes about afterwards)
		int[] normalExtended = {0}, eliteExtended = {0}, eliteBeyondCap = {0}, eliteTotal = {0};
		double[] maxEliteDistanceTargeted = {0};
		boolean[] hadN = {false}, hadE = {false};
		everyTick(h, () -> {
			tickPlayer(pN);
			tickPlayer(pE);
			boolean hasN = mN.getTarget() == pN;
			if (hasN && !hadN[0] && mN.distanceTo(pN) > rN + 0.5) {
				normalExtended[0]++;
			}
			hadN[0] = hasN;
			boolean hasE = mE.getTarget() == pE;
			if (hasE) {
				eliteTotal[0]++;
				double dist = mE.distanceTo(pE);
				maxEliteDistanceTargeted[0] = Math.max(maxEliteDistanceTargeted[0], dist);
				if (!hadE[0]) {
					if (dist > r + 0.5) {
						eliteExtended[0]++;
					}
					if (dist > r * 1.25 + 0.5) {
						eliteBeyondCap[0]++;
					}
				}
			}
			hadE[0] = hasE;
		});
		at(h, 150, () -> {
			System.out.printf("AI-ACQUISITION %-14s vanilla-range=%.1f player-at=%.1f  normal-extended-acquisitions=%d  elite-targeted-ticks=%d  elite-extended-acquisitions=%d  max-elite-target-distance=%.1f%n",
				type.toShortString(), r, d, normalExtended[0], eliteTotal[0], eliteExtended[0], maxEliteDistanceTargeted[0]);
			check(normalExtended[0] == 0, "a Normal player beyond vanilla range is never acquired (" + normalExtended[0] + ")");
			check(eliteExtended[0] > 0, "an Elite player beyond vanilla range but inside 1.25x IS hunted by the live AI (never targeted)");
			check(eliteBeyondCap[0] == 0, "...and never beyond 1.25x (" + eliteBeyondCap[0] + ")");
			noModifiers(mN, "Normal-lane mob");
			noModifiers(mE, "Elite-lane mob");
			finish(h, a);
		});
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 200)
	public void ai_acquisition_zombie(GameTestHelper h) {
		acquisition(h, EntityTypes.ZOMBIE, 20);
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 200)
	public void ai_acquisition_husk(GameTestHelper h) {
		acquisition(h, EntityTypes.HUSK, 21);
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 200)
	public void ai_acquisition_skeleton(GameTestHelper h) {
		acquisition(h, EntityTypes.SKELETON, 22);
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 200)
	public void ai_acquisition_stray(GameTestHelper h) {
		acquisition(h, EntityTypes.STRAY, 23);
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 200)
	public void ai_acquisition_creeper(GameTestHelper h) {
		acquisition(h, EntityTypes.CREEPER, 24);
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 200)
	public void ai_acquisition_vindicator(GameTestHelper h) {
		acquisition(h, EntityTypes.VINDICATOR, 26);
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 200)
	public void ai_acquisition_pillager(GameTestHelper h) {
		acquisition(h, EntityTypes.PILLAGER, 27);
	}

	// ------------------------------------------------------------------ no wallhacks, hard cap, Peaceful

	@GameTest(environment = LEGACY_AI, maxTicks = 250)
	public void ai_extendedAcquisition_respectsLineOfSight_andTheCap(GameTestHelper h) {
		Arena a = new Arena(h, 2);
		Mob behindWall = a.spawnOnIsland(EntityTypes.ZOMBIE, 5, 5);
		Mob tooFar = a.spawnOnIsland(EntityTypes.ZOMBIE, 5, LANE_B + 5);
		double r = followRange(behindWall);
		double rFar = followRange(tooFar);
		ServerPlayer hidden = a.player(h, "pd_wall_e", ELITE, 5 + r * 1.12, 5);
		ServerPlayer far = a.player(h, "pd_far_e", ELITE, 5 + rFar * 1.3, LANE_B + 5);
		a.wall(5 + r * 0.5, -4, 49);
		int[] hiddenTicks = {0}, farTicks = {0};
		everyTick(h, () -> {
			tickPlayer(hidden);
			tickPlayer(far);
			if (behindWall.getTarget() == hidden) {
				hiddenTicks[0]++;
			}
			if (tooFar.getTarget() == far) {
				farTicks[0]++;
			}
		});
		at(h, 200, () -> {
			check(hiddenTicks[0] == 0, "no wallhack: an Elite player inside the extended range but behind a solid wall is never acquired (" + hiddenTicks[0] + " ticks)");
			check(farTicks[0] == 0, "an Elite player at 1.3x vanilla range (beyond the 1.25x acquisition range) is never acquired (" + farTicks[0] + " ticks)");
			finish(h, a);
		});
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 320)
	public void ai_peacefulStaysUntargetable_andLeavingEliteDropsTheExtendedTarget(GameTestHelper h) {
		Arena a = new Arena(h, 3);
		Mob m = a.spawnOnIsland(EntityTypes.ZOMBIE, 5, 5);
		Mob mPeaceful = a.spawnOnIsland(EntityTypes.ZOMBIE, 5, LANE_B + 5);
		double r = followRange(m);
		double rP = followRange(mPeaceful);
		ServerPlayer p = a.player(h, "pd_pe_e", ELITE, 5 + r * 1.12, 5);
		ServerPlayer peaceful = a.player(h, "pd_pe_p", PersonalDifficultyMode.PEACEFUL, 5 + rP * 0.5, LANE_B + 5);
		int[] peacefulTicks = {0};
		int[] phase = {0};
		long[] switchedAt = {0};
		boolean[] targetedAtSwitch = {false};
		long[] droppedAt = {-1};
		int[] retargetedAfter = {0};
		everyTick(h, () -> {
			tickPlayer(p);
			tickPlayer(peaceful);
			if (mPeaceful.getTarget() == peaceful || mPeaceful.getTargetUnchecked() == peaceful) {
				peacefulTicks[0]++;
			}
			long t = h.getTick();
			if (phase[0] == 0 && t == 60) {
				targetedAtSwitch[0] = m.getTarget() == p;
				switchedAt[0] = t;
				setMode(p, PersonalDifficultyMode.PEACEFUL);
				phase[0] = 1;
			} else if (phase[0] == 1) {
				if (m.getTarget() != p && droppedAt[0] < 0) {
					droppedAt[0] = t;
				}
				if (droppedAt[0] >= 0 && m.getTarget() == p) {
					retargetedAfter[0]++;
				}
			}
		});
		at(h, 250, () -> {
			check(peacefulTicks[0] == 0, "a Peaceful player 0.5x range away is never targeted by a live zombie (" + peacefulTicks[0] + ")");
			check(targetedAtSwitch[0], "premise: the zombie had acquired the Elite player autonomously before the switch");
			check(droppedAt[0] >= 0 && droppedAt[0] - switchedAt[0] <= 3, "switching to Peaceful drops the target at once (dropped after " + (droppedAt[0] - switchedAt[0]) + " ticks)");
			check(retargetedAfter[0] == 0, "and the zombie never re-targets them (" + retargetedAfter[0] + ")");
			noModifiers(m, "mob");
			finish(h, a);
		});
	}

	// ------------------------------------------------------------------ pursuit, memory, stale state

	@GameTest(environment = LEGACY_AI, maxTicks = 320)
	public void ai_pursuit_eliteTargetIsHeldAndPathedBeyondVanillaFollowRange(GameTestHelper h) {
		Arena a = new Arena(h, 4);
		Mob mN = a.spawn(EntityTypes.ZOMBIE, 5, 5);
		Mob mE = a.spawn(EntityTypes.ZOMBIE, 5, LANE_B + 5);
		double r = followRange(mN);
		double rE = followRange(mE);
		ServerPlayer pN = a.player(h, "pd_pu_n", NORMAL, 5 + r * 0.6, 5);
		ServerPlayer pE = a.player(h, "pd_pu_e", ELITE, 5 + rE * 0.6, LANE_B + 5);
		boolean[] acquired = {false, false};
		boolean[] heldAt3 = {false, false};
		boolean[] heldAt10 = {false, false};
		boolean[] pathReaches = {false, false};
		boolean[] pathFound = {false, false};
		double[] distanceAtMove = {0, 0};
		double[] distanceAtEnd = {0, 0};
		long[] moved = {0};
		everyTick(h, () -> {
			tickPlayer(pN);
			tickPlayer(pE);
			long t = h.getTick();
			if (t == 70) {
				acquired[0] = mN.getTarget() == pN;
				acquired[1] = mE.getTarget() == pE;
				// the players walk away (teleport) to 1.12x the vanilla range from where their hunter now stands
				pN.snapTo(mN.getX() + r * 1.12, mN.getY(), mN.getZ());
				pE.snapTo(mE.getX() + rE * 1.12, mE.getY(), mE.getZ());
				distanceAtMove[0] = mN.distanceTo(pN);
				distanceAtMove[1] = mE.distanceTo(pE);
				moved[0] = t;
			} else if (t == 73) {
				heldAt3[0] = mN.getTarget() == pN;
				heldAt3[1] = mE.getTarget() == pE;
				// can the pathfinder find a COMPLETE route to a player 1.12x the vanilla range away? (vanilla only returns a partial one)
				var pathN = mN.getNavigation().createPath(pN, 0);
				var pathE = mE.getNavigation().createPath(pE, 0);
				pathFound[0] = pathN != null;
				pathFound[1] = pathE != null;
				pathReaches[0] = pathN != null && pathN.canReach();
				pathReaches[1] = pathE != null && pathE.canReach();
			} else if (t == 80) {
				heldAt10[0] = mN.getTarget() == pN;
				heldAt10[1] = mE.getTarget() == pE;
			} else if (t == 220) {
				distanceAtEnd[0] = mN.distanceTo(pN);
				distanceAtEnd[1] = mE.distanceTo(pE);
			}
		});
		at(h, 230, () -> {
			System.out.printf("AI-PURSUIT start=%.1f -> after 150 ticks: normal-lane=%.1f elite-lane=%.1f; held@3: normal=%b elite=%b; held@10: normal=%b elite=%b%n",
				distanceAtMove[1], distanceAtEnd[0], distanceAtEnd[1], heldAt3[0], heldAt3[1], heldAt10[0], heldAt10[1]);
			check(acquired[0] && acquired[1], "premise: both mobs had acquired their (nearer) players autonomously");
			check(!heldAt3[0] && !heldAt10[0], "Normal control: the zombie gives up on a player who got beyond the vanilla follow range");
			check(heldAt3[1] && heldAt10[1], "Elite: the same zombie keeps hunting a player at 1.12x the vanilla range");
			check(pathFound[0] && pathFound[1], "premise: both pathfinders return a path");
			check(!pathReaches[0], "Normal: the route to a player beyond the follow range is only partial (path length is capped at the follow range)");
			check(pathReaches[1], "Elite: the route to the same distance is a complete one (path length is extended for an Elite target)");
			check(distanceAtMove[1] - distanceAtEnd[1] > 12.0, "Elite: and its path search really reaches them - it closes in (" + distanceAtMove[1] + " -> " + distanceAtEnd[1] + ")");
			finish(h, a);
		});
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 320)
	public void ai_memory_eliteHuntersRememberAHiddenPlayerLonger_andOnlyWhileElite(GameTestHelper h) {
		Arena a = new Arena(h, 5);
		Mob mN = a.spawnOnIsland(EntityTypes.ZOMBIE, 5, 5);
		Mob mE = a.spawnOnIsland(EntityTypes.ZOMBIE, 5, LANE_B + 5);
		Mob mS = a.spawnOnIsland(EntityTypes.ZOMBIE, 65, 40);
		ServerPlayer pN = a.player(h, "pd_me_n", NORMAL, 5 + 30, 5);
		ServerPlayer pE = a.player(h, "pd_me_e", ELITE, 5 + 30, LANE_B + 5);
		ServerPlayer pS = a.player(h, "pd_me_s", ELITE, 65 + 30, 40);
		long[] hiddenAt = {0};
		long[] dropN = {-1}, dropE = {-1}, dropS = {-1};
		boolean[] acquired = {false, false, false};
		everyTick(h, () -> {
			tickPlayer(pN);
			tickPlayer(pE);
			tickPlayer(pS);
			long t = h.getTick();
			if (t == 60) {
				acquired[0] = mN.getTarget() == pN;
				acquired[1] = mE.getTarget() == pE;
				acquired[2] = mS.getTarget() == pS;
				// full-width walls: with the 1.2.0 islands gone (mobs fell off them) a short wall could be walked around
				a.wall(5 + 18, -4, WIDTH + 4);
				a.wall(65 + 18, -4, WIDTH + 4);
				hiddenAt[0] = t;
				setMode(pS, NORMAL); // this Elite player becomes Normal the moment they hide: no extended memory may remain
			} else if (t > 60) {
				if (dropN[0] < 0 && mN.getTarget() != pN) {
					dropN[0] = t - hiddenAt[0];
				}
				if (dropE[0] < 0 && mE.getTarget() != pE) {
					dropE[0] = t - hiddenAt[0];
				}
				if (dropS[0] < 0 && mS.getTarget() != pS) {
					dropS[0] = t - hiddenAt[0];
				}
			}
		});
		at(h, 240, () -> {
			System.out.printf("AI-MEMORY ticks until the hidden player is forgotten: normal=%d elite=%d elite-that-switched-to-normal=%d%n", dropN[0], dropE[0], dropS[0]);
			check(acquired[0] && acquired[1] && acquired[2], "premise: all three zombies were hunting before the wall went up");
			check(dropN[0] >= 55 && dropN[0] <= 80, "Normal: forgets after about 60 ticks (" + dropN[0] + ")");
			check(dropE[0] >= 110 && dropE[0] <= 145, "Elite: forgets after about 2x as long (" + dropE[0] + ")");
			check(dropS[0] >= 0 && dropS[0] <= 80, "no stale state: once the player is no longer Elite the extra memory is gone (" + dropS[0] + ")");
			finish(h, a);
		});
	}

	// ------------------------------------------------------------------ combat

	@GameTest(environment = LEGACY_AI, maxTicks = 400)
	public void ai_skeletonBowCadence_eliteShootsAtTheHardRateOnly(GameTestHelper h) {
		Arena a = new Arena(h, 6);
		AbstractSkeleton sN = (AbstractSkeleton) a.spawn(EntityTypes.SKELETON, 5, 5);
		AbstractSkeleton sE = (AbstractSkeleton) a.spawn(EntityTypes.SKELETON, 5, LANE_B + 5);
		for (AbstractSkeleton s : List.of(sN, sE)) {
			s.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
			s.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
			s.reassessWeaponGoal();
		}
		ServerPlayer pN = a.player(h, "pd_bw_n", NORMAL, 15, 5);
		ServerPlayer pE = a.player(h, "pd_bw_e", ELITE, 15, LANE_B + 5);
		Set<Integer> arrowsN = new HashSet<>(), arrowsE = new HashSet<>();
		everyTick(h, () -> {
			for (ServerPlayer p : List.of(pN, pE)) {
				p.setHealth(20.0F);
				p.getFoodData().setFoodLevel(20);
				tickPlayer(p);
			}
			for (AbstractArrow arrow : a.level.getEntitiesOfClass(AbstractArrow.class, new AABB(a.x0 - 10, a.y, a.z0 - 10, a.x0 + LENGTH, a.y + 20, a.z0 + WIDTH + 10))) {
				if (arrow.getOwner() == sN) {
					arrowsN.add(arrow.getId());
				} else if (arrow.getOwner() == sE) {
					arrowsE.add(arrow.getId());
				}
			}
		});
		at(h, 350, () -> {
			System.out.println("AI-BOW arrows in 350 ticks: normal-player-lane=" + arrowsN.size() + " elite-player-lane=" + arrowsE.size());
			check(arrowsN.size() >= 4, "premise: the control skeleton really shoots (" + arrowsN.size() + ")");
			check(arrowsE.size() >= arrowsN.size() * 1.3, "the skeleton shoots an Elite player clearly faster (" + arrowsE.size() + " vs " + arrowsN.size() + ")");
			check(arrowsE.size() <= arrowsN.size() * 2.1, "...but no faster than the Hard rate (draw time keeps it under 2x) (" + arrowsE.size() + " vs " + arrowsN.size() + ")");
			finish(h, a);
		});
	}

	@GameTest
	public void direct_hooks_returnVanillaForEveryoneButElite(GameTestHelper h) {
		// DIRECT invocation of the hooks, with a mob that is never ticked. Wiring evidence only.
		ServerPlayer elite = player(h, "pd_dh_e", ELITE);
		ServerPlayer normal = player(h, "pd_dh_n", NORMAL);
		ServerPlayer peaceful = player(h, "pd_dh_p", PersonalDifficultyMode.PEACEFUL);
		Mob z = h.spawn(EntityTypes.ZOMBIE, new net.minecraft.world.phys.Vec3(1.5, 1.0, 1.5));
		z.setTarget(elite);
		check(PdHooks.meleeRecheckTicks(z, 20L) == 5L, "melee re-check delay is shortened against an Elite target");
		check(PdHooks.maxPathLength(z, 35.0F) > 35.0F, "path length is longer against an Elite target");
		check(PdHooks.targetMemoryTicks(elite, 60) == 120, "target memory doubles for an Elite target");
		check(PdHooks.pursuitDistance(elite, 35.0) > 35.0, "pursuit distance grows for an Elite target");
		for (ServerPlayer other : List.of(normal, peaceful)) {
			z.setTarget(null);
			check(PdHooks.meleeRecheckTicks(z, 20L) == 20L, "vanilla re-check delay otherwise");
			check(PdHooks.maxPathLength(z, 35.0F) == 35.0F, "vanilla path length otherwise");
			check(PdHooks.targetMemoryTicks(other, 60) == 60, "vanilla memory for " + other.getName().getString());
			check(PdHooks.pursuitDistance(other, 35.0) == 35.0, "vanilla pursuit distance for " + other.getName().getString());
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ performance

	@GameTest(environment = LEGACY_AI, maxTicks = 700)
	public void ai_performance_idleMobsNearAnEliteVsNormalPlayer(GameTestHelper h) {
		Arena a = new Arena(h, 7);
		List<Mob> mobs = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			mobs.add(a.spawn(EntityTypes.ZOMBIE, 5 + (i % 10) * 3, 5 + (i / 10) * 3));
		}
		// 62 blocks from the nearest zombie (and well clear of wandering): outside even the extended range, so Elite pays only the second-chance search
		ServerPlayer p = a.player(h, "pd_pf", NORMAL, 5 + 27 + 62, 20);
		long[] last = {0};
		long[][] sums = new long[2][2]; // [mode][ns, ticks]
		everyTick(h, () -> {
			tickPlayer(p);
			long now = System.nanoTime();
			long t = h.getTick();
			if (t > 20 && last[0] != 0) {
				int phase = (int) ((t / 100) % 2); // 100-tick alternating phases
				sums[phase][0] += now - last[0];
				sums[phase][1]++;
			}
			if (t % 100 == 0) {
				setMode(p, (t / 100) % 2 == 0 ? NORMAL : ELITE);
			}
			last[0] = now;
		});
		at(h, 600, () -> {
			double normalMs = sums[0][0] / 1e6 / Math.max(1, sums[0][1]);
			double eliteMs = sums[1][0] / 1e6 / Math.max(1, sums[1][1]);
			int hunting = 0;
			for (Mob m : mobs) {
				if (m.getTarget() == p) {
					hunting++;
				}
			}
			System.out.printf("AI-PERF 100 idle zombies, player 62 blocks away: mean tick %.3f ms (Normal) vs %.3f ms (Elite); zombies hunting at end: %d%n", normalMs, eliteMs, hunting);
			check(hunting == 0, "premise: the player is outside the extended range, so nobody hunts them");
			check(eliteMs < 45.0, "an Elite player does not make ticks anywhere near the 50 ms budget (" + eliteMs + " ms)");
			finish(h, a);
		});
	}
}
