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
}
