package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.config.SettingDef;
import com.zaggazach.personaldifficulty.config.SettingRegistry;
import com.zaggazach.personaldifficulty.config.SettingStep;
import com.zaggazach.personaldifficulty.config.SettingStep.AtBound;
import com.zaggazach.personaldifficulty.config.SettingStep.Next;
import com.zaggazach.personaldifficulty.ui.SettingsPages;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Phase 4: the pure parts of the Settings menu (which setting is on which page, and what a -/+ press asks for). */
class SettingsMenuModelTest {
	private static SettingDef def(String key) {
		return SettingRegistry.find(key).orElseThrow();
	}

	@Test
	void everyScalarSettingIsOnExactlyOnePage() {
		for (SettingDef d : SettingRegistry.scalars()) {
			List<SettingsPages> on = Arrays.stream(SettingsPages.values()).filter(p -> p.settings().contains(d)).toList();
			assertEquals(1, on.size(), d.key() + " is on " + on);
		}
	}

	@Test
	void subPagesHoldAtMostEightAndTogetherHoldEverySettingOnce() {
		for (SettingsPages p : SettingsPages.values()) {
			List<SettingDef> joined = new java.util.ArrayList<>();
			for (int i = 0; i < p.pageCount(); i++) {
				List<SettingDef> on = p.settingsOn(i);
				assertTrue(on.size() <= SettingsPages.PER_PAGE && !on.isEmpty(), p + " page " + i);
				joined.addAll(on);
			}
			assertEquals(p.settings(), joined, p.name());
			assertTrue(p.settingsOn(p.pageCount()).isEmpty(), "past the last page is empty");
		}
	}

	@Test
	void labelsDropThePagePrefix() {
		assertEquals("Damage taken multiplier", SettingsPages.EASY.label(def("easy.damage-multiplier")));
		assertEquals("Armour effectiveness", SettingsPages.ELITE.label(def("elite.armor-effectiveness")));
		assertEquals("Elite AI: improved pursuit", SettingsPages.ELITE_ADVANCED.label(def("elite.ai.improved-pursuit")));
	}

	@Test
	void decimalStepsAreExact() {
		SettingDef d = def("easy.damage-multiplier");
		assertEquals(new Next("0.65"), SettingStep.step(d, 0.6, 1, false));
		assertEquals(new Next("0.6"), SettingStep.step(d, 0.65, -1, false));
		assertEquals(new Next("1.1"), SettingStep.step(d, 0.6, 1, true), "shift-click: ten steps");
		assertEquals("0.05", SettingStep.stepText(d, false));
		assertEquals("0.5", SettingStep.stepText(d, true));
	}

	@Test
	void aStepPastABoundLandsOnIt_andAStepFromTheBoundIsRefused() {
		SettingDef d = def("easy.damage-multiplier");
		assertEquals(new Next("0"), SettingStep.step(d, 0.02, -1, false));
		assertInstanceOf(AtBound.class, SettingStep.step(d, 0.0, -1, false));
		assertInstanceOf(AtBound.class, SettingStep.step(d, d.max(), 1, true));
		assertTrue(((AtBound) SettingStep.step(d, 0.0, -1, false)).message().contains("minimum"));
		SettingDef ticks = def("elite.priority.scan-interval-ticks");
		assertEquals(new Next(String.valueOf((long) ticks.min())), SettingStep.step(ticks, 10, -1, true), "clamped to the minimum");
		assertInstanceOf(AtBound.class, SettingStep.step(ticks, (int) ticks.min(), -1, false));
	}

	@Test
	void wholeNumberSettingsStepByOneOrTen() {
		SettingDef d = def("elite.effects.max-duration-seconds");
		assertEquals(new Next("31"), SettingStep.step(d, 30, 1, false));
		assertEquals(new Next("20"), SettingStep.step(d, 30, -1, true));
	}

	@Test
	void onOffSettingsToggle() {
		SettingDef d = def("void.apply-profile");
		assertEquals(new Next("true"), SettingStep.step(d, false, 1, false));
		assertEquals(new Next("false"), SettingStep.step(d, true, -1, true));
	}

	@Test
	void minusThenPlusReturnsTheOriginalWheneverNeitherStepWasClamped() {
		Random r = new Random(42);
		int checked = 0;
		for (SettingDef d : SettingRegistry.scalars()) {
			if (d.type() != SettingDef.Type.DOUBLE) {
				continue;
			}
			for (int i = 0; i < 300; i++) {
				double v = i == 0 ? ((Number) d.defaultValue()).doubleValue() : Math.round((d.min() + r.nextDouble() * (d.max() - d.min())) * 100) / 100.0;
				if (!(SettingStep.step(d, v, -1, false) instanceof Next down)) {
					continue;
				}
				double dv = Double.parseDouble(down.text());
				if (Math.abs((v - dv) - d.step()) > 1e-9) {
					continue; // clamped at the minimum
				}
				SettingStep.Outcome up = SettingStep.step(d, dv, 1, false);
				assertTrue(up instanceof Next n && Double.compare(Double.parseDouble(n.text()), v) == 0, d.key() + ": " + v + " -> " + dv + " -> " + up);
				checked++;
			}
		}
		assertTrue(checked > 1000, "checked " + checked);
	}
}
