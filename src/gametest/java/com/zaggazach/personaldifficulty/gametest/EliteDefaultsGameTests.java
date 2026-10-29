package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.gametest.EliteAiGameTests.Arena;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;

/**
 * The 1.4.0 Elite hunting defaults (plan C2, switched on after the Phase 5 performance gate), live, in the default test environment
 * with nothing configured: 3x detection inside the 128-block cap, 5x memory and the alert network on. (The 1.3.0 numbers are still
 * tested exactly, in {@link PdTestSupport#LEGACY_AI}.)
 */
public class EliteDefaultsGameTests {
	@GameTest(maxTicks = 260)
	public void ai_defaults_aZombieNoticesAnElitePlayerAt2_5xItsVanillaRange_neverANormalOne(GameTestHelper h) {
		PdConfig.EliteAi ai = rt().config().eliteAi();
		check(ai.acquisitionRangeMultiplier() == 3.0 && ai.targetMemoryMultiplier() == 5.0 && ai.absoluteRangeCap() == 128,
			"the run starts from the shipped defaults: " + ai);
		check(rt().config().eliteAlert().enabled(), "the alert network is on by default");

		Arena eliteArena = new Arena(h, 95);
		Arena normalArena = new Arena(h, 96);
		Mob mE = eliteArena.spawnOnIsland(EntityTypes.ZOMBIE, 5, 5);
		Mob mN = normalArena.spawnOnIsland(EntityTypes.ZOMBIE, 5, 5);
		double rE = EliteAiGameTests.followRange(mE);
		double rN = EliteAiGameTests.followRange(mN);
		ServerPlayer pE = eliteArena.player(h, "pd_df_e", PersonalDifficultyMode.ELITE, 5 + rE * 2.5, 5);
		ServerPlayer pN = normalArena.player(h, "pd_df_n", PersonalDifficultyMode.NORMAL, 5 + rN * 2.5, 5);
		pE.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100000, 4, false, false));
		double[] eliteAcquiredAt = {-1};
		int[] normalBeyondVanilla = {0};
		boolean[] had = {false};
		everyTick(h, () -> {
			tickPlayer(pE);
			tickPlayer(pN);
			boolean has = mE.getTarget() == pE;
			if (has && !had[0] && eliteAcquiredAt[0] < 0) {
				eliteAcquiredAt[0] = mE.distanceTo(pE);
			}
			had[0] = has;
			if (mN.getTarget() == pN && mN.distanceTo(pN) > rN + 0.5) {
				normalBeyondVanilla[0]++;
			}
		});
		at(h, 240, () -> {
			String report = String.format(java.util.Locale.ROOT, "DEFAULTS zombie vanilla range %.1f, Elite player at %.1f, acquired at %s; Normal targeted beyond vanilla range %d ticks",
				rE, rE * 2.5, eliteAcquiredAt[0] < 0 ? "never" : String.format(java.util.Locale.ROOT, "%.1f", eliteAcquiredAt[0]), normalBeyondVanilla[0]);
			System.out.println(report);
			check(eliteAcquiredAt[0] > rE * 2.0, "the Elite player is noticed beyond 2x the vanilla range (1.3.0 stopped at 1.25x): " + report);
			check(normalBeyondVanilla[0] == 0, "a Normal player at the same distance never is: " + report);
			eliteArena.close();
			normalArena.close();
			h.succeed();
		});
	}

	// ------------------------------------------------------------------ plan 6.2: pursuit beyond the path-search budget

	/**
	 * MEASUREMENT for plan 6.2 (not required yet): does an Elite target at 2.8x the vanilla range get reached with the mod's
	 * existing hooks (longer path length, held target), or are routes cut short so that a waypoint goal is needed?
	 * Two arenas: open ground, and a chasm the zombie can see across but must walk around (about 160 blocks of route for 98
	 * blocks of straight line). The target is set directly: this measures pursuit, not acquisition.
	 */
	@GameTest(maxTicks = 620, required = false)
	public void ai_measure_longPursuit_openGroundAndAroundAChasm(GameTestHelper h) {
		Arena open = new Arena(h, 97);
		Arena chasm = new Arena(h, 98);
		for (int x = 40; x <= 44; x++) { // a 5-wide gap in the floor from the edge to z = 70: too wide to jump, nothing blocks sight
			for (int z = -4; z <= 70; z++) {
				chasm.level.setBlock(new net.minecraft.core.BlockPos(chasm.x0 + x, chasm.y, chasm.z0 + z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 18);
			}
		}
		Arena[] arenas = {open, chasm};
		Mob[] mobs = new Mob[2];
		ServerPlayer[] players = new ServerPlayer[2];
		double[] start = new double[2];
		double[] closest = {Double.MAX_VALUE, Double.MAX_VALUE};
		int[] heldTicks = new int[2];
		boolean[] reachesAtStart = new boolean[2];
		int[] partialPlans = new int[2];
		for (int i = 0; i < 2; i++) {
			mobs[i] = arenas[i].spawn(EntityTypes.ZOMBIE, 5, 5);
			double r = EliteAiGameTests.followRange(mobs[i]);
			players[i] = arenas[i].player(h, "pd_lp_" + i, PersonalDifficultyMode.ELITE, 5 + r * 2.8, 5);
			players[i].addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100000, 4, false, false));
		}
		everyTick(h, () -> {
			for (int i = 0; i < 2; i++) {
				tickPlayer(players[i]);
				Mob m = mobs[i];
				if (h.getTick() == 5) {
					m.setTarget(players[i]);
					start[i] = m.distanceTo(players[i]);
					var path = m.getNavigation().createPath(players[i], 0);
					reachesAtStart[i] = path != null && path.canReach();
				}
				if (h.getTick() > 5) {
					heldTicks[i] += m.getTarget() == players[i] ? 1 : 0;
					closest[i] = Math.min(closest[i], m.distanceTo(players[i]));
					var current = m.getNavigation().getPath();
					if (current != null && !current.canReach() && h.getTick() % 20 == 0) {
						partialPlans[i]++;
					}
				}
			}
		});
		at(h, 600, () -> {
			for (int i = 0; i < 2; i++) {
				System.out.printf(java.util.Locale.ROOT, "PURSUIT-MEASURE %-6s start %.1f blocks, closest %.1f, target held %d/594 ticks, complete route at start %b, partial-route samples %d%n",
					i == 0 ? "open" : "chasm", start[i], closest[i], heldTicks[i], reachesAtStart[i], partialPlans[i]);
			}
			open.close();
			chasm.close();
			h.succeed();
		});
	}
}
