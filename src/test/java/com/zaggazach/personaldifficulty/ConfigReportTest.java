package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.OverridesStoreTest.FaultyIo;
import com.zaggazach.personaldifficulty.config.ConfigReport;
import com.zaggazach.personaldifficulty.config.ConfigState;
import com.zaggazach.personaldifficulty.config.FamilyKey;
import com.zaggazach.personaldifficulty.config.OverridesStore;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import com.zaggazach.personaldifficulty.config.SettingRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The text of {@code admin config} and {@code admin get}: complete, sourced, filterable and chat-sized. */
class ConfigReportTest {
	private static OverridesStore store(Path dir, String content) throws IOException {
		Path f = dir.resolve(OverridesStore.FILE_NAME);
		if (content != null) {
			Files.writeString(f, content);
		}
		AtomicLong clock = new AtomicLong(1);
		OverridesStore s = new OverridesStore(f, new FaultyIo(), clock::incrementAndGet, id -> !id.equals("mod:gone"));
		s.load();
		return s;
	}

	private static ConfigState state(String props, OverridesStore s) {
		return ConfigState.compose(PdConfigLoader.parseLayer(props).layer(), s.layer());
	}

	@Test
	void theUnfilteredTableListsEveryRegistrySettingExactlyOnceWithItsSource_andStaysChatSized(@TempDir Path dir) throws IOException {
		OverridesStore s = store(dir, "{\"version\":1,\"values\":{\"pvp.damage-multiplier\":2.5,\"easy.damage-type.minecraft.fall\":0.4}}");
		ConfigState st = state("easy.damage-multiplier=0.5\neasy.damage-type.minecraft.explosion=0.2\n", s);
		List<String> messages = ConfigReport.table(st, s, "");
		String all = String.join("\n", messages);
		for (var d : SettingRegistry.scalars()) {
			assertEquals(1, all.lines().filter(l -> l.startsWith(d.key() + " = ")).count(), d.key() + " appears exactly once");
		}
		assertTrue(all.contains("pvp.damage-multiplier = 2.5  [override]*"), all);
		assertTrue(all.contains("easy.damage-multiplier = 0.5  [properties]"), all);
		assertTrue(all.contains("hard.damage-multiplier = 1.25  [default]"), all);
		assertTrue(all.contains("easy.damage-type.minecraft.fall = 0.4  [override]*"), all);
		assertTrue(all.contains("easy.damage-type.minecraft.explosion = 0.2  [properties]"), all);
		for (String m : messages) {
			assertTrue(m.length() < 2500, "each chat message stays readable: " + m.length());
			assertTrue(m.lines().count() <= ConfigReportLimit.LINES + 1);
		}
		assertTrue(messages.size() >= 4, "the 41+ rows are split over several messages: " + messages.size());
	}

	private static final class ConfigReportLimit {
		static final int LINES = 12;
	}

	@Test
	void thePrefixFilterNarrowsTheTableAndSaysSoWhenNothingMatches(@TempDir Path dir) throws IOException {
		OverridesStore s = store(dir, null);
		ConfigState st = state("elite.ai.improved-pursuit=false\n", s);
		String elite = String.join("\n", ConfigReport.table(st, s, "elite."));
		assertTrue(elite.contains("elite.ai.improved-pursuit = false  [properties]"));
		assertFalse(elite.contains("easy.damage-multiplier"), "other keys are filtered out");
		assertTrue(elite.contains("starting with 'elite.'"));
		String ai = String.join("\n", ConfigReport.table(st, s, "ELITE.AI."));
		assertEquals(5, ai.lines().filter(l -> l.startsWith("elite.ai.")).count(), "case-insensitive; exactly the five AI keys");
		String none = String.join("\n", ConfigReport.table(st, s, "nothing"));
		assertTrue(none.contains("No setting key starts with 'nothing'"), none);
	}

	@Test
	void storedEntriesThatAreNotAppliedAreShownWithTheReason_andTheBannerWarns(@TempDir Path dir) throws IOException {
		OverridesStore s = store(dir, "{\"version\":1,\"values\":{\"pvp.damage-multiplier\":\"banana\",\"mystery\":1,\"hard.damage-type.mod.gone\":0.5}}");
		ConfigState st = state("", s);
		String all = String.join("\n", ConfigReport.table(st, s, ""));
		assertTrue(all.contains("pvp.damage-multiplier = \"banana\"  [stored override: not applied, invalid value"), all);
		assertTrue(all.contains("mystery = 1  [stored override: not applied, unrecognised key]"), all);
		assertTrue(all.contains("hard.damage-type.mod.gone = 0.5  [stored override: not applied, the damage type mod:gone does not exist on this server]"), all);
		assertTrue(all.contains("pvp.damage-multiplier = 1.0  [default]"), "the effective value is still the lower layer's");
		String banner = String.join("\n", ConfigReport.banner(st, s));
		assertTrue(banner.contains("3 stored override entries are kept but NOT applied"), banner);
		// a filter still shows the not-applied entries that match, and the banner is independent of the filter
		assertTrue(String.join("\n", ConfigReport.table(st, s, "mystery")).contains("mystery = 1"));
	}

	@Test
	void theBannerShowsABlockedOverridesFileWhateverTheFilter(@TempDir Path dir) throws IOException {
		OverridesStore s = store(dir, "{ not json");
		ConfigState st = state("", s);
		String banner = String.join("\n", ConfigReport.banner(st, s));
		assertTrue(banner.contains("WARNING: setting changes cannot be saved and the overrides file is not in use"), banner);
		assertTrue(banner.contains("admin reload"), banner);
		assertTrue(banner.contains("state: CORRUPT"), banner);
	}

	@Test
	void describeShowsValueSourceLowerLayerRangeAndTiming(@TempDir Path dir) throws IOException {
		OverridesStore s = store(dir, "{\"version\":1,\"values\":{\"easy.damage-multiplier\":0.7,\"elite.priority.scan-interval-ticks\":20,\"elite.effects.enabled\":\"maybe\"}}");
		ConfigState st = state("easy.damage-multiplier=0.5\n", s);
		String easy = String.join("\n", ConfigReport.describe(SettingRegistry.find("easy.damage-multiplier").orElseThrow(), st, s));
		assertTrue(easy.contains("value: 0.7  (source: override)") && easy.contains("without the override: 0.5  (properties file)") && easy.contains("default: 0.6; allowed: a number from 0.0 to 10.0") && easy.contains("immediately (LIVE)"), easy);
		String scan = String.join("\n", ConfigReport.describe(SettingRegistry.find("elite.priority.scan-interval-ticks").orElseThrow(), st, s));
		assertTrue(scan.contains("NEXT_SCAN") && scan.contains("within 20 ticks") && scan.contains("a whole number from 5 to 40"), scan);
		String invalid = String.join("\n", ConfigReport.describe(SettingRegistry.find("elite.effects.enabled").orElseThrow(), st, s));
		assertTrue(invalid.contains("(source: default)") && invalid.contains("NOT applied") && invalid.contains("admin set elite.effects.enabled <value>"), invalid);
	}

	@Test
	void describeAFamilyEntry(@TempDir Path dir) throws IOException {
		OverridesStore s = store(dir, "{\"version\":1,\"values\":{\"easy.damage-type.minecraft.fall\":0.4}}");
		ConfigState st = state("easy.damage-type.minecraft.fall=0.3\n", s);
		FamilyKey fall = ((FamilyKey.Ok) FamilyKey.parse("easy.damage-type.minecraft.fall")).key();
		String text = String.join("\n", ConfigReport.describe(fall, st, s, true));
		assertTrue(text.contains("value: 0.4  (source: override)") && text.contains("without the override: 0.3") && text.contains("a number from 0.0 to 10.0"), text);
		FamilyKey fresh = ((FamilyKey.Ok) FamilyKey.parse("hard.damage-type.mod.x")).key();
		String none = String.join("\n", ConfigReport.describe(fresh, st, s, false));
		assertTrue(none.contains("no entry") && none.contains("hard.damage-multiplier (1.25)") && none.contains("does not exist on this server"), none);
	}
}
