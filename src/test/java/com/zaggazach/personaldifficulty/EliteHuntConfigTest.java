package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import org.junit.jupiter.api.Test;

class EliteHuntConfigTest {
	@Test
	void defaultsMatchTheDocumentedValues() {
		PdConfig c = PdConfig.defaults();
		assertTrue(c.eliteHunt().priorityEnabled());
		assertEquals(10, c.eliteHunt().scanIntervalTicks());
		assertEquals(8, c.eliteHunt().reacquireHiddenSeconds());
		PdConfig.EliteEffects e = c.eliteEffects();
		assertTrue(e.enabled());
		assertEquals(30, e.maxDurationSeconds());
		assertEquals(0.35, e.zombie().chance());
		assertEquals(8, e.zombie().seconds());
		assertEquals(0.35, e.spider().chance());
		assertEquals(5, e.spider().seconds());
		assertEquals(0.50, e.caveSpider().chance());
		assertEquals(6, e.caveSpider().seconds());
		assertEquals(0.25, e.skeleton().chance());
		assertEquals(4, e.skeleton().seconds());
		assertEquals(0.30, e.drowned().chance());
		assertEquals(3, e.drowned().seconds());
	}

	@Test
	void anAbsentConfigurationFileYieldsExactlyTheDefaults() {
		PdConfigLoader.Result r = PdConfigLoader.parse("");
		assertEquals(PdConfig.defaults().eliteHunt(), r.config().eliteHunt());
		assertEquals(PdConfig.defaults().eliteEffects(), r.config().eliteEffects());
		assertTrue(r.warnings().isEmpty());
	}

	@Test
	void validValuesAreReadAndReloadedExactly() {
		PdConfig c = PdConfigLoader.parse(String.join("\n",
			"elite.priority.enabled=false",
			"elite.priority.scan-interval-ticks=20",
			"elite.priority.reacquire-hidden-seconds=0",
			"elite.effects.enabled=false",
			"elite.effects.max-duration-seconds=12",
			"elite.effects.zombie-infection-chance=1.0",
			"elite.effects.zombie-infection-seconds=15",
			"elite.effects.cave-spider-venom-chance=0",
			"elite.effects.skeleton-shot-seconds=2")).config();
		assertFalse(c.eliteHunt().priorityEnabled());
		assertEquals(20, c.eliteHunt().scanIntervalTicks());
		assertEquals(0, c.eliteHunt().reacquireHiddenSeconds());
		assertFalse(c.eliteEffects().enabled());
		assertEquals(12, c.eliteEffects().maxDurationSeconds());
		assertEquals(1.0, c.eliteEffects().zombie().chance());
		assertEquals(15, c.eliteEffects().zombie().seconds());
		assertEquals(0.0, c.eliteEffects().caveSpider().chance());
		assertEquals(2, c.eliteEffects().skeleton().seconds());
		assertEquals(0.35, c.eliteEffects().spider().chance(), "untouched keys keep their defaults");
	}

	@Test
	void malformedAndOutOfRangeValuesAreHandledSafely() {
		PdConfigLoader.Result bad = PdConfigLoader.parse(String.join("\n",
			"elite.priority.enabled=perhaps",
			"elite.priority.scan-interval-ticks=1",
			"elite.priority.reacquire-hidden-seconds=9999",
			"elite.effects.enabled=2",
			"elite.effects.max-duration-seconds=NaN",
			"elite.effects.zombie-infection-chance=lots",
			"elite.effects.zombie-infection-seconds=-4",
			"elite.effects.spider-venom-chance=7",
			"elite.effects.spider-venom-seconds=100000",
			"elite.effects.cave-spider-venom-chance=-1",
			"elite.effects.skeleton-shot-chance=Infinity",
			"elite.effects.drowned-chill-seconds=0"));
		PdConfig c = bad.config();
		assertTrue(c.eliteHunt().priorityEnabled(), "bad boolean -> default");
		assertEquals(PdConfig.MIN_SCAN_INTERVAL_TICKS, c.eliteHunt().scanIntervalTicks(), "clamped up: no per-tick scanning");
		assertEquals(PdConfig.MAX_REACQUIRE_SECONDS, c.eliteHunt().reacquireHiddenSeconds(), "clamped: finite memory");
		assertTrue(c.eliteEffects().enabled(), "bad boolean -> default");
		assertEquals(30, c.eliteEffects().maxDurationSeconds(), "NaN -> default");
		assertEquals(0.35, c.eliteEffects().zombie().chance(), "text -> default");
		assertEquals(1, c.eliteEffects().zombie().seconds(), "negative duration clamped to the 1 s minimum");
		assertEquals(1.0, c.eliteEffects().spider().chance(), "chance above 1 clamped to certain");
		assertEquals(PdConfig.MAX_EFFECT_SECONDS, c.eliteEffects().spider().seconds(), "huge duration clamped");
		assertEquals(0.0, c.eliteEffects().caveSpider().chance(), "negative chance clamped to never");
		assertEquals(0.25, c.eliteEffects().skeleton().chance(), "Infinity -> default");
		assertEquals(1, c.eliteEffects().drowned().seconds());
		assertEquals(12, bad.warnings().size(), bad.warnings().toString());
		assertFalse(bad.readFailed());
	}

	@Test
	void everyEffectKeyAppearsInTheShippedDefaultFile() {
		String file = PdConfigLoader.defaultFileContents();
		for (String key : new String[] {
			"elite.priority.enabled", "elite.priority.scan-interval-ticks", "elite.priority.reacquire-hidden-seconds",
			"elite.effects.enabled", "elite.effects.max-duration-seconds",
			"elite.effects.zombie-infection-chance", "elite.effects.zombie-infection-seconds",
			"elite.effects.spider-venom-chance", "elite.effects.spider-venom-seconds",
			"elite.effects.cave-spider-venom-chance", "elite.effects.cave-spider-venom-seconds",
			"elite.effects.skeleton-shot-chance", "elite.effects.skeleton-shot-seconds",
			"elite.effects.drowned-chill-chance", "elite.effects.drowned-chill-seconds"}) {
			assertTrue(file.contains("\n" + key + "="), key + " must be documented with its default in the shipped file");
		}
		PdConfigLoader.Result r = PdConfigLoader.parse(file);
		assertTrue(r.warnings().isEmpty(), "the shipped file parses without warnings: " + r.warnings());
		assertEquals(PdConfig.defaults().eliteHunt(), r.config().eliteHunt());
		assertEquals(PdConfig.defaults().eliteEffects(), r.config().eliteEffects());
	}
}
