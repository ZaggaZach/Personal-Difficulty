package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import com.zaggazach.personaldifficulty.legacy.LegacyConfigLoader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Differential test: the registry-driven properties loader must behave exactly like the frozen 1.3.0/Phase 1 loader
 * ({@link LegacyConfigLoader}) for the same input: same {@link PdConfig}, same warnings (text and order, except the damage-type
 * family whose legacy order depends on hash iteration and is compared sorted) and the same {@code readFailed}.
 *
 * <p>The expectations here are never adapted to the implementation. A mismatch is a finding: investigate it, then fix the loader
 * (or, for an intended behaviour change, ask for approval).
 *
 * <p><b>Approved changes (1.4.0 plan, decision 6):</b> the Elite AI range multipliers may go up to 3.0 (was 2.0) and the memory
 * multiplier up to 5.0 (was 4.0). For those three keys only, a value the 1.3.0 loader would have clamped (a finite number above
 * its old maximum) is left out of the comparison; every other value, including every invalid spelling, is still compared, and the
 * new limits are tested directly in {@code EliteSurvivalTest}. For values that both loaders clamp to the minimum, the only
 * difference is the range named in the warning ("outside [1.0, 2.0]" becomes "outside [1.0, 3.0]"); exactly that text is
 * translated in the 1.3.0 warnings before comparing. The new key {@code elite.ai.absolute-range-cap} is unknown to the 1.3.0
 * loader and is never part of the generated inputs.
 *
 * <p><b>Approved default changes (plan C2):</b> the acquisition and memory defaults are 3.0 and 5.0 (were 1.25 and 2.0). Every 1.3.0
 * result is passed through {@link ApprovedDefaults}, which changes exactly the values (and "using default" warning texts) where the
 * 1.3.0 loader fell back to its default for one of those two keys, and nothing else.
 */
class LoaderDifferentialTest {
	/** Every scalar key, written out by hand (NOT taken from the registry, so the test is independent of it). */
	private static final List<String> NUMERIC_KEYS = List.of(
		"easy.damage-multiplier", "easy.hunger-multiplier", "hard.damage-multiplier", "hard.hunger-multiplier",
		"elite.damage-multiplier", "elite.hunger-multiplier", "easy.ranged-min-uncertainty", "elite.ranged-max-uncertainty",
		"elite.armor-effectiveness", "elite.natural-regeneration-multiplier", "pvp.damage-multiplier", "self.damage-multiplier",
		"elite.ai.acquisition-range-multiplier", "elite.ai.follow-range-multiplier", "elite.ai.target-memory-multiplier",
		"elite.priority.scan-interval-ticks", "elite.priority.reacquire-hidden-seconds", "elite.effects.max-duration-seconds",
		"elite.effects.zombie-infection-chance", "elite.effects.zombie-infection-seconds",
		"elite.effects.spider-venom-chance", "elite.effects.spider-venom-seconds",
		"elite.effects.cave-spider-venom-chance", "elite.effects.cave-spider-venom-seconds",
		"elite.effects.skeleton-shot-chance", "elite.effects.skeleton-shot-seconds",
		"elite.effects.drowned-chill-chance", "elite.effects.drowned-chill-seconds"
	);
	private static final List<String> BOOLEAN_KEYS = List.of(
		"environmental.apply-profile", "void.apply-profile", "admin.apply-profile",
		"ranged.skeletons", "ranged.illusioners", "ranged.drowned", "ranged.crossbow-mobs", "ranged.piglins", "ranged.witches",
		"elite.ai.improved-pursuit", "elite.ai.advanced-combat", "elite.priority.enabled", "elite.effects.enabled"
	);
	private static final List<String> ALL_KEYS = join(NUMERIC_KEYS, BOOLEAN_KEYS);

	private static final List<String> NUMBER_VALUES = List.of(
		"", " ", "\t", "  1.5  ", "0", "0.0", "-0", "-0.0", "1", "1.0", "1.25", "2", "2.0", "2.5", "3", "4", "4.0", "5", "6", "8", "10", "10.0",
		"10.0000001", "11", "30", "31", "40", "41", "60", "60.5", "61", "-1", "-0.0001", "0.05", "0.1", "0.09999", "0.5", "0.6", "0.35", "0.999999",
		"1.0000001", "1e1", "1E1", "1e-1", "1e400", "-1e400", "NaN", "nan", "Infinity", "-Infinity", "+Infinity", "inf", "0x10", "0x1p3", "1.5f", "1.5d",
		"1.5F", "1,5", "1_0", "abc", "true", "false", "1.", ".5", "+1.5", "1 2", "9999999999999", "2147483648", "4.5", "29.5", "30.5", "7.5", "7.4999",
		"5.5", "10.5", "39.5", "40.4", "0.5e1", "٣", "1.5\u00a0", "--1", "1..5", "e5", "", "0.", "00.5"
	);
	private static final List<String> BOOLEAN_VALUES = List.of(
		"", " ", "true", "false", "TRUE", "False", " true ", "\tfalse\t", "yes", "no", "1", "0", "on", "off", "t", "f", "null", "tru e", "true;", "truee", "2.5", "NaN"
	);

	private static List<String> join(List<String> a, List<String> b) {
		List<String> out = new ArrayList<>(a);
		out.addAll(b);
		return out;
	}

	/** Splits warnings into the damage-type family (order-insensitive in the legacy loader) and everything else (order-sensitive). */
	private static boolean family(String warning) {
		return warning.contains(".damage-type.");
	}

	/** Key to the 1.3.0 maximum that 1.4.0 raised (approved, see the class comment). */
	private static final java.util.Map<String, Double> RAISED_MAXIMUM = java.util.Map.of(
		"elite.ai.acquisition-range-multiplier", 2.0, "elite.ai.follow-range-multiplier", 2.0, "elite.ai.target-memory-multiplier", 4.0);

	/** A copy of {@code p} without values that only the approved raised maximums treat differently. */
	private static Properties withoutApprovedChanges(Properties p) {
		Properties q = new Properties();
		for (String key : p.stringPropertyNames()) {
			String value = p.getProperty(key);
			Double oldMax = RAISED_MAXIMUM.get(key);
			if (oldMax != null) {
				double v;
				try {
					v = Double.parseDouble(value.trim());
				} catch (NumberFormatException e) {
					v = Double.NaN;
				}
				if (Double.isFinite(v) && v > oldMax) {
					continue;
				}
			}
			q.setProperty(key, value);
		}
		return q;
	}

	/** The 1.3.0 warning for one of the three raised keys, naming the 1.4.0 range instead of the old one (and nothing else). */
	private static String withRaisedRangeText(String warning) {
		for (var e : RAISED_MAXIMUM.entrySet()) {
			if (warning.startsWith(e.getKey() + "=")) {
				double newMax = e.getValue() + 1.0;
				return warning.replace("is outside [1.0, " + e.getValue() + "]", "is outside [1.0, " + newMax + "]");
			}
		}
		return warning;
	}

	private static void compare(Properties original, String label) {
		Properties p = withoutApprovedChanges(original);
		LegacyConfigLoader.Result legacy = ApprovedDefaults.fromProperties(p);
		LegacyConfigLoader.Result expected = new LegacyConfigLoader.Result(legacy.config(),
			legacy.warnings().stream().map(LoaderDifferentialTest::withRaisedRangeText).toList(), legacy.readFailed());
		PdConfigLoader.Result actual = PdConfigLoader.fromProperties(p);
		assertEquals(expected.config(), actual.config(), "config differs for " + label + " " + p);
		assertEquals(expected.readFailed(), actual.readFailed(), "readFailed differs for " + label);
		List<String> eFam = new ArrayList<>(expected.warnings().stream().filter(LoaderDifferentialTest::family).toList());
		List<String> aFam = new ArrayList<>(actual.warnings().stream().filter(LoaderDifferentialTest::family).toList());
		Collections.sort(eFam);
		Collections.sort(aFam);
		assertEquals(eFam, aFam, "damage-type warnings differ for " + label + " " + p);
		assertEquals(expected.warnings().stream().filter(w -> !family(w)).toList(), actual.warnings().stream().filter(w -> !family(w)).toList(),
			"warnings (text and order) differ for " + label + " " + p);
	}

	private static Properties props(String... keyValues) {
		Properties p = new Properties();
		for (int i = 0; i < keyValues.length; i += 2) {
			p.setProperty(keyValues[i], keyValues[i + 1]);
		}
		return p;
	}

	// ------------------------------------------------------------------ systematic: every key x every value, type-crossed

	@Test
	void everyKeyWithEveryNumericAndBooleanSpelling_aloneAndWithEveryOtherKeyAbsent() {
		int cases = 0;
		for (String key : ALL_KEYS) {
			for (String v : NUMBER_VALUES) {
				compare(props(key, v), key + "=" + v);
				cases++;
			}
			for (String v : BOOLEAN_VALUES) {
				compare(props(key, v), key + "=" + v);
				cases++;
			}
		}
		assertTrue(cases > 4000, "the corpus is large enough: " + cases);
	}

	@Test
	void theEmptyAndTheDefaultProperties() {
		compare(new Properties(), "empty");
		Properties defaults = new Properties();
		try {
			defaults.load(new StringReader(PdConfigLoader.defaultFileContents()));
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		compare(defaults, "shipped default file");
	}

	@Test
	void everyKeyAtItsBoundariesAndJustOutside() {
		String[][] bounds = {
			{"easy.damage-multiplier", "0", "10"}, {"hard.hunger-multiplier", "0", "10"}, {"elite.damage-multiplier", "0", "10"},
			{"easy.ranged-min-uncertainty", "0", "60"}, {"elite.ranged-max-uncertainty", "0", "60"}, {"elite.armor-effectiveness", "0", "1"},
			{"elite.natural-regeneration-multiplier", "0.1", "1"}, {"pvp.damage-multiplier", "0", "10"}, {"self.damage-multiplier", "0", "10"},
			{"elite.ai.acquisition-range-multiplier", "1", "2"}, {"elite.ai.follow-range-multiplier", "1", "2"}, {"elite.ai.target-memory-multiplier", "1", "4"},
			{"elite.priority.scan-interval-ticks", "5", "40"}, {"elite.priority.reacquire-hidden-seconds", "0", "30"}, {"elite.effects.max-duration-seconds", "1", "60"},
			{"elite.effects.zombie-infection-chance", "0", "1"}, {"elite.effects.zombie-infection-seconds", "1", "60"},
			{"elite.effects.drowned-chill-chance", "0", "1"}, {"elite.effects.skeleton-shot-seconds", "1", "60"}
		};
		for (String[] b : bounds) {
			double min = Double.parseDouble(b[1]);
			double max = Double.parseDouble(b[2]);
			for (double v : new double[] {min, Math.nextUp(min), Math.nextDown(min), min - 1, max, Math.nextDown(max), Math.nextUp(max), max + 1, (min + max) / 2}) {
				compare(props(b[0], Double.toString(v)), b[0] + "=" + v);
			}
		}
	}

	// ------------------------------------------------------------------ random whole files

	@Test
	void thousandsOfSeededRandomCombinationsAcrossAllKeysAtOnce() {
		Random rnd = new Random(0x50D1FFL);
		List<String> unknown = List.of("elite.unknown", "ELITE.damage-multiplier", "easy.damage-multiplier ", "pvp", "", "elite.ai", "foo.bar-baz");
		for (int i = 0; i < 6000; i++) {
			Properties p = new Properties();
			for (String key : NUMERIC_KEYS) {
				if (rnd.nextInt(3) != 0) {
					p.setProperty(key, NUMBER_VALUES.get(rnd.nextInt(NUMBER_VALUES.size())));
				}
			}
			for (String key : BOOLEAN_KEYS) {
				if (rnd.nextInt(3) != 0) {
					p.setProperty(key, rnd.nextInt(4) == 0 ? NUMBER_VALUES.get(rnd.nextInt(NUMBER_VALUES.size())) : BOOLEAN_VALUES.get(rnd.nextInt(BOOLEAN_VALUES.size())));
				}
			}
			if (rnd.nextInt(3) == 0) { // type confusion: a boolean spelling in a numeric key and vice versa
				p.setProperty(NUMERIC_KEYS.get(rnd.nextInt(NUMERIC_KEYS.size())), BOOLEAN_VALUES.get(rnd.nextInt(BOOLEAN_VALUES.size())));
			}
			if (rnd.nextInt(4) == 0) {
				p.setProperty(unknown.get(rnd.nextInt(unknown.size())), "1.0");
			}
			compare(p, "random #" + i);
		}
	}

	// ------------------------------------------------------------------ the damage-type family

	@Test
	void damageTypeOverrideFamilyIncludingMalformedKeysAndValues() {
		List<String> ids = List.of(
			"minecraft.fall", "minecraft.explosion", "minecraft.generic_kill", "ns.a.b", "ns.a.b.c", "x.y", "Minecraft.Fall", "mod.my_type", "mod.my-type",
			"mod.type/with/slash", ".x", "x.", "x", "", ".", "..", "a..b", "minecraft.", "ünï.cöde", "m.f all", "normal.fall", "peaceful.fall", "ns.UPPER"
		);
		List<String> values = List.of("0.3", "0", "10", "10.5", "-1", "NaN", "Infinity", "abc", "", " ", " 0.4 ", "1e1", "0x1", "1e400", "5");
		for (String profile : List.of("easy", "hard", "elite", "normal", "peaceful", "Easy")) {
			for (String id : ids) {
				for (String value : values) {
					compare(props(profile + ".damage-type." + id, value), profile + ".damage-type." + id + "=" + value);
				}
			}
		}
		// several entries at once, in several profiles, mixed with scalar keys
		Random rnd = new Random(0xFA11L);
		for (int i = 0; i < 1500; i++) {
			Properties p = new Properties();
			for (int j = 0; j < 1 + rnd.nextInt(8); j++) {
				p.setProperty(List.of("easy", "hard", "elite").get(rnd.nextInt(3)) + ".damage-type." + ids.get(rnd.nextInt(ids.size())), values.get(rnd.nextInt(values.size())));
			}
			p.setProperty(NUMERIC_KEYS.get(rnd.nextInt(NUMERIC_KEYS.size())), NUMBER_VALUES.get(rnd.nextInt(NUMBER_VALUES.size())));
			compare(p, "family combo #" + i);
		}
	}

	// ------------------------------------------------------------------ whole-file text and the I/O paths

	@Test
	void unusualFileTextSpellingsAndStructure() {
		List<String> texts = List.of(
			"", "\n\n", "# only a comment\n", "! bang comment\n", "easy.damage-multiplier=0.5", "easy.damage-multiplier = 0.5", "easy.damage-multiplier:0.5",
			"easy.damage-multiplier 0.5", "easy.damage-multiplier\t0.5", "easy.damage-multiplier=0.5\neasy.damage-multiplier=0.7\n", "easy.damage-multiplier=0.5\\\n 1\n",
			"easy.damage-multiplier=\n", "easy.damage-multiplier\n", "=0.5\n", "easy\\u002edamage-multiplier=0.5\n", "easy.damage-multiplier=\\u0030.5\n", "easy.damage-multiplier=\\u00zz\n",
			"easy.damage-multiplier=0.5 # trailing\n", "  easy.damage-multiplier=0.5\n", "EASY.DAMAGE-MULTIPLIER=0.5\n", "elite.priority.enabled=TRUE\nelite.effects.enabled=no\n",
			"easy.damage-multiplier=0.5\r\nhard.damage-multiplier=1.5\r\n", "\ufeffeasy.damage-multiplier=0.5\n", "easy.damage-multiplier=0.5\u0000\n", "k=v\nunknown.key=1\n"
		);
		for (String text : texts) {
			LegacyConfigLoader.Result e = ApprovedDefaults.parse(text);
			PdConfigLoader.Result a = PdConfigLoader.parse(text);
			assertEquals(e.config(), a.config(), "config for text: " + text);
			assertEquals(e.readFailed(), a.readFailed(), "readFailed for text: " + text);
			assertEquals(e.warnings().stream().filter(w -> !family(w)).toList(), a.warnings().stream().filter(w -> !family(w)).toList(), "warnings for text: " + text);
		}
	}

	@Test
	void loadAndLoadOrCreateOnRealFilesAndFailures(@TempDir Path dir) throws IOException {
		Path good = dir.resolve("good.properties");
		Files.writeString(good, "easy.damage-multiplier=0.5\nelite.priority.scan-interval-ticks=7.5\nhard.hunger-multiplier=99\nelite.effects.enabled=maybe\n");
		assertSameResult(ApprovedDefaults.load(good), PdConfigLoader.load(good), "good file");

		Path missing = dir.resolve("missing.properties");
		assertSameResult(ApprovedDefaults.load(missing), PdConfigLoader.load(missing), "missing file");

		Path directory = Files.createDirectory(dir.resolve("a-directory"));
		assertSameResult(ApprovedDefaults.load(directory), PdConfigLoader.load(directory), "directory at the path");

		Path badEscape = dir.resolve("bad.properties");
		Files.writeString(badEscape, "easy.damage-multiplier=\\u12\n");
		assertSameResult(ApprovedDefaults.load(badEscape), PdConfigLoader.load(badEscape), "malformed escape");

		Path invalidUtf8 = dir.resolve("utf8.properties");
		Files.write(invalidUtf8, new byte[] {'e', 'a', 's', 'y', '.', 'd', 'a', 'm', 'a', 'g', 'e', '-', 'm', 'u', 'l', 't', 'i', 'p', 'l', 'i', 'e', 'r', '=', (byte) 0xff, (byte) 0xfe, '\n'});
		assertSameResult(ApprovedDefaults.load(invalidUtf8), PdConfigLoader.load(invalidUtf8), "invalid utf-8");

		Path createdByLegacy = dir.resolve("legacy").resolve("c.properties");
		Path createdByNew = dir.resolve("new").resolve("c.properties");
		LegacyConfigLoader.Result e = ApprovedDefaults.loadOrCreate(createdByLegacy);
		PdConfigLoader.Result a = PdConfigLoader.loadOrCreate(createdByNew);
		assertSameResult(e, a, "loadOrCreate on a fresh path");
		assertEquals(Files.readString(createdByLegacy), Files.readString(createdByNew), "the created default file is identical");

		Path blocker = dir.resolve("blocker");
		Files.writeString(blocker, "i am a file");
		assertSameResult(ApprovedDefaults.loadOrCreate(blocker.resolve("x.properties")), PdConfigLoader.loadOrCreate(blocker.resolve("x.properties")), "unwritable location");
	}

	private static void assertSameResult(LegacyConfigLoader.Result e, PdConfigLoader.Result a, String label) {
		assertEquals(e.config(), a.config(), label);
		assertEquals(e.readFailed(), a.readFailed(), label);
		assertEquals(e.warnings().size(), a.warnings().size(), label + ": " + e.warnings() + " vs " + a.warnings());
		for (int i = 0; i < e.warnings().size(); i++) {
			String ew = e.warnings().get(i);
			String aw = a.warnings().get(i);
			// the message names the file path: compare after removing it
			assertEquals(ew.replaceAll("(config|default config) \\S+:", "$1 <path>:"), aw.replaceAll("(config|default config) \\S+:", "$1 <path>:"), label);
		}
	}
}
