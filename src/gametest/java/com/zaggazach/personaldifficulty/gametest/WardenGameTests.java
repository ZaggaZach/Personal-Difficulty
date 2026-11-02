package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.gametest.EliteAiGameTests.Arena;
import java.util.Locale;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * 1.4.0, Phase 7 (plan 6.8): Wardens gain anger toward Elite players faster. Off by default, so these run in the
 * {@code elite_mobs_on} environment, which switches it on for that batch. Anger levels (vanilla): agitated 40, angry 80.
 */
public class WardenGameTests {
	private static final String MOBS_ON = "personaldifficulty-gametest:elite_mobs_on";

	@GameTest(environment = MOBS_ON, maxTicks = 60)
	public void warden_angerFromVanillasEntryPointIsDoubledForAnElitePlayerOnly(GameTestHelper h) {
		check(rt().config().eliteMobs().wardenAnger() && rt().config().eliteMobs().wardenAngerMultiplier() == 2.0, "the test environment switched it on");
		Arena a = new Arena(h, 123);
		Warden wE = (Warden) a.spawn(EntityTypes.WARDEN, 20, 20);
		Warden wN = (Warden) a.spawn(EntityTypes.WARDEN, 80, 80);
		ServerPlayer elite = a.player(h, "pd_wd_e", PersonalDifficultyMode.ELITE, 25, 20);
		ServerPlayer normal = a.player(h, "pd_wd_n", PersonalDifficultyMode.NORMAL, 85, 80);
		wE.increaseAngerAt(elite, 35, true);
		wN.increaseAngerAt(normal, 35, true);
		int angerE = wE.getAngerManagement().getActiveAnger(elite);
		int angerN = wN.getAngerManagement().getActiveAnger(normal);
		String report = String.format(Locale.ROOT, "WARDEN-DIRECT one vibration's worth (35): Elite anger %d, Normal anger %d", angerE, angerN);
		System.out.println(report);
		check(angerE == 70, "doubled toward the Elite player: " + report);
		check(angerN == 35, "vanilla toward the Normal player: " + report);
		a.close();
		h.succeed();
	}

	/**
	 * Live: footstep vibrations emitted by each player next to a Warden of their own, through vanilla's game-event system (the
	 * Warden's listener decides whether it hears them). One every 50 ticks (the listener's cooldown is 40).
	 */
	@GameTest(environment = MOBS_ON, maxTicks = 240)
	public void warden_isAngeredByFewerVibrationsFromAnElitePlayer(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		Arena[] arenas = {new Arena(h, 124), new Arena(h, 125)};
		PersonalDifficultyMode[] modes = {PersonalDifficultyMode.ELITE, PersonalDifficultyMode.NORMAL};
		Warden[] wardens = new Warden[2];
		ServerPlayer[] players = new ServerPlayer[2];
		int[][] angerAfter = new int[2][3];
		for (int i = 0; i < 2; i++) {
			wardens[i] = (Warden) arenas[i].spawn(EntityTypes.WARDEN, 50, 50);
			players[i] = arenas[i].player(h, "pd_wv_" + i, modes[i], 56, 50);
			players[i].addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100000, 4, false, false));
		}
		long start = h.getTick();
		everyTick(h, () -> {
			long t = h.getTick() - start;
			for (int i = 0; i < 2; i++) {
				tickPlayer(players[i]);
				players[i].setHealth(players[i].getMaxHealth());
				players[i].snapTo(arenas[i].x0 + 56.5, arenas[i].y + 1, arenas[i].z0 + 50.5); // stays put
				if (t % 50 == 10 && t / 50 < 3) {
					level.gameEvent(players[i], GameEvent.STEP, players[i].position());
				}
				if (t % 50 == 45 && t / 50 < 3) {
					angerAfter[i][(int) (t / 50)] = wardens[i].getAngerManagement().getActiveAnger(players[i]);
				}
			}
		});
		at(h, 200, () -> {
			String report = String.format(Locale.ROOT, "WARDEN-LIVE anger after vibrations 1/2/3: Elite %d/%d/%d, Normal %d/%d/%d (angry at 80)",
				angerAfter[0][0], angerAfter[0][1], angerAfter[0][2], angerAfter[1][0], angerAfter[1][1], angerAfter[1][2]);
			System.out.println(report);
			check(angerAfter[0][0] > 0 && angerAfter[1][0] > 0, "premise: both Wardens heard the first vibration: " + report);
			check(angerAfter[0][1] >= 80, "the Elite player has angered the Warden after two vibrations: " + report);
			check(angerAfter[1][1] < 80, "the Normal player has not (vanilla needs three): " + report);
			for (Arena a : arenas) {
				a.close();
			}
			h.succeed();
		});
	}
}
