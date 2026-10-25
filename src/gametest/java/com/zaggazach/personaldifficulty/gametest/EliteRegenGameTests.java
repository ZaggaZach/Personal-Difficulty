package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Capture;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Elite natural regeneration, measured on real players through the real vanilla {@code FoodData.tick} (directly, and through
 * the real per-tick player path). Food and saturation are held by the test where stated, so that only the regeneration
 * INTERVAL is being measured.
 */
public class EliteRegenGameTests {
	private static final PersonalDifficultyMode NORMAL = PersonalDifficultyMode.NORMAL;
	private static final PersonalDifficultyMode ELITE = PersonalDifficultyMode.ELITE;

	/** HP gained from 1 HP over {@code ticks} ticks with food and saturation re-asserted every tick (and exhaustion cleared). */
	private static float gain(ServerPlayer p, int ticks, int food, float saturation, boolean realPlayerTick) {
		p.setHealth(1.0F);
		for (int i = 0; i < ticks; i++) {
			FoodData fd = p.getFoodData();
			fd.setFoodLevel(food);
			fd.setSaturation(saturation);
			setExhaustion(p, 0.0F);
			if (realPlayerTick) {
				tickPlayer(p);
			} else {
				fd.tick(p);
			}
		}
		return p.getHealth() - 1.0F;
	}

	private record Case(String name, int food, float saturation, int ticks) {
	}

	@GameTest
	public void regen_eliteIsHalfOfNormal_atEveryFoodAndSaturationLevel(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		boolean ruleBefore = level.getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION);
		List<Case> cases = List.of(
			new Case("saturated, 5.0 saturation (10-tick path)", 20, 5.0F, 160),
			new Case("saturated, 2.4 saturation (smaller heals)", 20, 2.4F, 160),
			new Case("saturated, 0.6 saturation", 20, 0.6F, 160),
			new Case("food 20, no saturation (80-tick path)", 20, 0.0F, 480),
			new Case("food 19 with saturation (80-tick path)", 19, 5.0F, 480),
			new Case("food 18, no saturation (80-tick path)", 18, 0.0F, 480),
			new Case("food 17 (no natural regeneration)", 17, 5.0F, 480),
			new Case("food 10 (no natural regeneration)", 10, 0.0F, 480),
			new Case("food 0 (no natural regeneration)", 0, 0.0F, 480));
		StringBuilder table = new StringBuilder("ELITE-REGEN HP gained from 1 HP (Normal | Elite | ratio)");
		for (Case c : cases) {
			ServerPlayer normal = player(h, "pd_rg_n", NORMAL);
			ServerPlayer elite = player(h, "pd_rg_e", ELITE);
			float n = gain(normal, c.ticks(), c.food(), c.saturation(), false);
			float e = gain(elite, c.ticks(), c.food(), c.saturation(), false);
			if (n == 0.0F) {
				check(e == 0.0F, c.name() + ": no natural regeneration in vanilla, so none for Elite either");
			} else {
				check(n < 19.0F, c.name() + ": the measurement must not hit the 20 HP cap (" + n + ")");
				nearRel(n * 0.5, e, 0.012, c.name() + ": Elite heals half of what a Normal player does");
			}
			table.append(String.format("%n  %-44s %7.3f | %7.3f | %s", c.name(), n, e, n == 0 ? "-" : String.format("%.3f", e / n)));
		}
		System.out.println(table);
		check(level.getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION) == ruleBefore, "the game rule is never changed by the mod");
		h.succeed();
	}

	@GameTest
	public void regen_realPlayerTickPath_matchesTheDirectMeasurement(GameTestHelper h) {
		floor(h);
		ServerPlayer normal = player(h, "pd_rt_n", NORMAL);
		ServerPlayer elite = player(h, "pd_rt_e", ELITE);
		float n = gain(normal, 160, 20, 5.0F, true);
		float e = gain(elite, 160, 20, 5.0F, true);
		check(n > 5.0F, "real ServerPlayer.doTick path regenerates (" + n + ")");
		nearRel(n * 0.5, e, 0.012, "through the real per-tick path Elite is also exactly half");
		h.succeed();
	}

	@GameTest
	public void regen_otherDifficultiesKeepVanillaHealing(GameTestHelper h) {
		ServerPlayer normal = player(h, "pd_ro_n", NORMAL);
		float vanillaA = gain(normal, 160, 20, 5.0F, false);
		float vanillaB = gain(normal, 480, 19, 0.0F, false);
		for (PersonalDifficultyMode m : List.of(PersonalDifficultyMode.EASY, PersonalDifficultyMode.HARD, PersonalDifficultyMode.PEACEFUL)) {
			ServerPlayer p = player(h, "pd_ro_" + m.commandName(), m);
			near(vanillaA, gain(p, 160, 20, 5.0F, false), m + ": saturated regeneration is exactly vanilla");
			near(vanillaB, gain(p, 480, 19, 0.0F, false), m + ": well-fed regeneration is exactly vanilla");
		}
		h.succeed();
	}

	@GameTest(maxTicks = 100)
	public void regen_costPerHealIsUnchanged_soTheSameNumberOfHealsAreAffordableJustSlower(GameTestHelper h) {
		// Unheld food: each heal charges exhaustion that eventually costs food, and regeneration stops below food 18.
		// With the Elite hunger multiplier neutralised, an Elite player must get exactly as many heals out of the same
		// food as a Normal player, only twice as slowly (the interval is stretched, the cost per heal is not).
		withConfig("elite.hunger-multiplier=1.0\n", () -> {
			int[] heals = new int[2];
			int[] lastHealTick = new int[2];
			int i = 0;
			for (PersonalDifficultyMode m : List.of(NORMAL, ELITE)) {
				ServerPlayer p = player(h, "pd_rc_" + m.commandName(), m);
				p.getFoodData().setFoodLevel(19);
				p.getFoodData().setSaturation(0.0F);
				setExhaustion(p, 0.0F);
				p.setHealth(1.0F);
				float last = 1.0F;
				for (int t = 1; t <= 1200; t++) {
					p.getFoodData().tick(p);
					if (p.getHealth() > last) {
						heals[i]++;
						lastHealTick[i] = t;
						last = p.getHealth();
					}
				}
				i++;
			}
			System.out.println("ELITE-REGEN-COST heals from food 19 / 0 saturation: normal " + heals[0] + " (last at tick " + lastHealTick[0] + "), elite " + heals[1] + " (last at tick " + lastHealTick[1] + ")");
			check(heals[0] >= 2, "premise: vanilla gets several heals out of that food (" + heals[0] + ")");
			check(heals[0] == heals[1], "same food buys the same number of heals: " + heals[0] + " vs " + heals[1]);
			check(Math.abs(lastHealTick[1] - 2 * lastHealTick[0]) <= 3, "...but takes twice as long: " + lastHealTick[1] + " vs 2 x " + lastHealTick[0]);
		});
		h.succeed();
	}

	@GameTest
	public void regen_theEliteHungerMultiplierStillAppliesOnTopOfTheSlowerHealing(GameTestHelper h) {
		// documents the interaction between two Elite features: each (slower) heal costs 2x exhaustion because of elite.hunger-multiplier
		ServerPlayer normal = player(h, "pd_rx_n", NORMAL);
		ServerPlayer elite = player(h, "pd_rx_e", ELITE);
		float[] exhaustionAfterOneHeal = new float[2];
		int i = 0;
		for (ServerPlayer p : List.of(normal, elite)) {
			p.getFoodData().setFoodLevel(20);
			p.getFoodData().setSaturation(5.0F);
			setExhaustion(p, 0.0F);
			p.setHealth(10.0F);
			int guard = 0;
			while (p.getHealth() <= 10.0F && guard++ < 100) {
				p.getFoodData().tick(p);
			}
			exhaustionAfterOneHeal[i++] = exhaustion(p);
		}
		near(exhaustionAfterOneHeal[0] * 2.0F, exhaustionAfterOneHeal[1], "one Elite heal charges 2x the exhaustion of one Normal heal (existing hunger multiplier)");
		h.succeed();
	}

	@GameTest
	public void regen_gameRuleOff_nobodyRegenerates_andTheRuleIsNeverTouched(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel level = h.getLevel();
		boolean before = level.getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION);
		ServerPlayer normal = player(h, "pd_rr_n", NORMAL);
		ServerPlayer elite = player(h, "pd_rr_e", ELITE);
		Capture cap = new Capture();
		try {
			level.getGameRules().set(GameRules.NATURAL_HEALTH_REGENERATION, false, server);
			near(0.0F, gain(normal, 200, 20, 5.0F, false), "rule off: Normal does not regenerate");
			near(0.0F, gain(elite, 200, 20, 5.0F, false), "rule off: Elite does not regenerate either");
			// switching modes while the rule is off must neither enable regeneration nor change the rule
			run(server, "personaldifficulty hard", as(elite, PermissionSet.NO_PERMISSIONS, cap), cap);
			run(server, "personaldifficulty elite", as(elite, PermissionSet.NO_PERMISSIONS, cap), cap);
			check(!level.getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION), "the rule is still exactly what the operator set (false)");
			near(0.0F, gain(elite, 200, 20, 5.0F, false), "rule off: still no regeneration after switching");
		} finally {
			level.getGameRules().set(GameRules.NATURAL_HEALTH_REGENERATION, before, server);
		}
		check(level.getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION) == before, "rule restored by the harness");
		check(gain(elite, 160, 20, 5.0F, false) > 0.0F, "rule on again: Elite regenerates (governed by the world's rule)");
		h.succeed();
	}

	@GameTest(maxTicks = 300)
	public void regen_explicitHealingAndAbsorptionAreNotSlowed(GameTestHelper h) {
		floor(h);
		ServerPlayer normal = player(h, "pd_re_n", NORMAL);
		ServerPlayer elite = player(h, "pd_re_e", ELITE);
		for (ServerPlayer p : List.of(normal, elite)) {
			p.getFoodData().setFoodLevel(6); // no natural regeneration, so only the explicit sources below can heal
			p.getFoodData().setSaturation(0.0F);
		}
		// 1) LivingEntity.heal (golden apples, potions of healing, commands and other mods all end up here)
		for (ServerPlayer p : List.of(normal, elite)) {
			p.setHealth(5.0F);
			p.heal(4.0F);
		}
		near(9.0F, normal.getHealth(), "Normal heal(4)");
		near(9.0F, elite.getHealth(), "Elite heal(4) is not slowed");
		// 2) Instant Health I and II
		for (int amp : new int[] {0, 1}) {
			float[] got = new float[2];
			int i = 0;
			for (ServerPlayer p : List.of(normal, elite)) {
				p.setHealth(2.0F);
				p.addEffect(new MobEffectInstance(MobEffects.INSTANT_HEALTH, 1, amp));
				p.getFoodData().setFoodLevel(6);
				tickPlayer(p, 3);
				p.removeAllEffects();
				got[i++] = p.getHealth() - 2.0F;
			}
			check(got[0] > 0.0F, "premise: Instant Health heals (" + got[0] + ")");
			near(got[0], got[1], "Instant Health " + (amp + 1) + " heals the same for Elite");
		}
		// 3) Regeneration effect over 150 real ticks
		float[] regenEffect = new float[2];
		int i = 0;
		for (ServerPlayer p : List.of(normal, elite)) {
			p.removeAllEffects();
			p.setHealth(5.0F);
			p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 1000, 0));
			for (int t = 0; t < 150; t++) {
				p.getFoodData().setFoodLevel(6);
				tickPlayer(p);
			}
			regenEffect[i++] = p.getHealth() - 5.0F;
			p.removeAllEffects();
		}
		check(regenEffect[0] >= 2.0F, "premise: the Regeneration effect heals (" + regenEffect[0] + ")");
		near(regenEffect[0], regenEffect[1], "Regeneration effect heals the same for Elite (it is not natural regeneration)");
		// 4) Absorption hearts
		float[] absorption = new float[2];
		i = 0;
		for (ServerPlayer p : List.of(normal, elite)) {
			p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 1000, 1));
			absorption[i++] = p.getAbsorptionAmount();
		}
		check(absorption[0] > 0.0F, "premise: absorption granted");
		near(absorption[0], absorption[1], "Absorption hearts are identical for Elite");
		h.succeed();
	}

	@GameTest(maxTicks = 100)
	public void regen_switching_takesEffectAtOnce_andTogglingCannotBeatVanilla(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_rs_switch", NORMAL);
		Capture cap = new Capture();
		var src = as(p, PermissionSet.NO_PERMISSIONS, cap);
		float vanilla = gain(p, 100, 20, 5.0F, false);
		run(server, "personaldifficulty elite", src, cap);
		float elite = gain(p, 100, 20, 5.0F, false);
		nearRel(vanilla * 0.5, elite, 0.012, "after switching INTO Elite the very next cycles are half-speed");
		run(server, "personaldifficulty normal", src, cap);
		near(vanilla, gain(p, 100, 20, 5.0F, false), "after switching OUT of Elite regeneration is vanilla again at once");
		run(server, "personaldifficulty peaceful", src, cap);
		near(vanilla, gain(p, 100, 20, 5.0F, false), "Peaceful regenerates at the vanilla rate (it is not Elite)");

		// Exploit attempt: flip between Elite and Normal every few ticks hoping to bank regeneration progress.
		// The counter only ever has to reach 10 (Normal) or 20 (Elite), so the result must lie between the two pure runs.
		for (int period : new int[] {1, 2, 3, 7}) {
			ServerPlayer q = player(h, "pd_rs_exploit" + period, NORMAL);
			q.setHealth(1.0F);
			for (int t = 0; t < 150; t++) {
				setMode(q, (t / period) % 2 == 0 ? ELITE : NORMAL);
				q.getFoodData().setFoodLevel(20);
				q.getFoodData().setSaturation(5.0F);
				setExhaustion(q, 0.0F);
				q.getFoodData().tick(q);
			}
			float toggled = q.getHealth() - 1.0F;
			ServerPlayer pureN = player(h, "pd_rs_pn" + period, NORMAL);
			ServerPlayer pureE = player(h, "pd_rs_pe" + period, ELITE);
			float maxGain = gain(pureN, 150, 20, 5.0F, false);
			float minGain = gain(pureE, 150, 20, 5.0F, false);
			check(toggled <= maxGain + 0.001F, "toggling every " + period + " ticks must not out-heal pure Normal: " + toggled + " vs " + maxGain);
			check(toggled >= minGain - 0.001F, "...and cannot do worse than pure Elite: " + toggled + " vs " + minGain);
		}
		h.succeed();
	}

	@GameTest
	public void regen_deathRespawnAndReconnect_keepTheElitePace(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel level = h.getLevel();
		ServerPlayer normal = player(h, "pd_rn_ref", NORMAL);
		float vanilla = gain(normal, 100, 20, 5.0F, false);
		ServerPlayer p = player(h, "pd_rn_elite", ELITE);
		java.util.UUID id = p.getUUID();
		nearRel(vanilla * 0.5, gain(p, 100, 20, 5.0F, false), 0.012, "before death");
		p.kill(level);
		ServerPlayer respawned = server.getPlayerList().respawn(p, false, Entity.RemovalReason.KILLED);
		respawned.setGameMode(GameType.SURVIVAL);
		check(respawned.getUUID().equals(id) && rt().modeOf(id) == ELITE, "still Elite after respawn");
		nearRel(vanilla * 0.5, gain(respawned, 100, 20, 5.0F, false), 0.012, "after respawn (a brand-new FoodData) regeneration is still half-speed");
		server.getPlayerList().remove(respawned);
		ServerPlayer rejoined = placeNewPlayer(h, id, "pd_rn_elite");
		nearRel(vanilla * 0.5, gain(rejoined, 100, 20, 5.0F, false), 0.012, "after disconnect + reconnect still half-speed");
		setMode(rejoined, NORMAL);
		near(vanilla, gain(rejoined, 100, 20, 5.0F, false), "and vanilla the moment they leave Elite");
		h.succeed();
	}

	@GameTest
	public void regen_customMultiplierIsHonouredWithinWholeTickRounding_andReloadsLive(GameTestHelper h) {
		ServerPlayer normal = player(h, "pd_rm_n", NORMAL);
		ServerPlayer elite = player(h, "pd_rm_e", ELITE);
		float vanilla = gain(normal, 200, 20, 5.0F, false);
		Map<String, Double> expectedRatio = Map.of("0.25", 0.25, "0.2", 0.2, "0.1", 0.1, "0.5", 0.5);
		for (var e : expectedRatio.entrySet()) {
			withConfig("elite.natural-regeneration-multiplier=" + e.getKey() + "\n", () -> {
				float got = gain(elite, 200, 20, 5.0F, false);
				nearRel(vanilla * e.getValue(), got, 0.06, "multiplier " + e.getKey() + " is honoured live after reload (200 ticks, whole-cycle rounding)");
			});
		}
		withConfig("elite.natural-regeneration-multiplier=1.0\n", () -> near(vanilla, gain(elite, 200, 20, 5.0F, false), "1.0 = vanilla"));
		withConfig("elite.natural-regeneration-multiplier=NaN\n", () -> nearRel(vanilla * 0.5, gain(elite, 200, 20, 5.0F, false), 0.012, "an invalid value falls back to the 0.5 default"));
		nearRel(vanilla * 0.5, gain(elite, 200, 20, 5.0F, false), 0.012, "default restored after the harness restores the file");
		h.succeed();
	}

	@GameTest
	public void regen_peacefulSafeModeIsIntact_whenSwitchingToAndFromElite(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel level = h.getLevel();
		ServerPlayer p = player(h, "pd_rp_pe", ELITE);
		Capture cap = new Capture();
		var src = as(p, PermissionSet.NO_PERMISSIONS, cap);
		run(server, "personaldifficulty peaceful", src, cap);
		near(0.0F, lost(p, level.damageSources().fall(), 6.0F), "Peaceful still takes no damage after coming from Elite");
		p.setHealth(10.0F);
		setExhaustion(p, 0.0F);
		p.getFoodData().setFoodLevel(20);
		p.getFoodData().setSaturation(5.0F);
		for (int i = 0; i < 40; i++) {
			p.getFoodData().tick(p);
		}
		check(p.getHealth() > 10.0F, "Peaceful regenerates (vanilla speed)");
		near(0.0F, exhaustion(p), "Peaceful still pays no hunger for it");
		run(server, "personaldifficulty elite", src, cap);
		near(8.0F, lost(p, level.damageSources().fall(), 4.0F), "and Elite damage (x2) is back at once");
		h.succeed();
	}
}
