package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.zaggazach.personaldifficulty.config.SettingDef;
import com.zaggazach.personaldifficulty.config.SettingRegistry;
import com.zaggazach.personaldifficulty.config.StrictValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Strict validation (admin set, overrides file). Expected results are literal. */
class StrictValueTest {
	private static SettingDef def(String key) {
		return SettingRegistry.find(key).orElseThrow();
	}

	private static void ok(String key, String text, Object expected) {
		StrictValue.Parsed p = StrictValue.parse(def(key), text);
		assertEquals(new StrictValue.Ok(expected), p, key + " <- '" + text + "'");
	}

	private static String refused(String key, String text) {
		StrictValue.Parsed p = StrictValue.parse(def(key), text);
		assertInstanceOf(StrictValue.Error.class, p, key + " <- '" + text + "' must be refused but gave " + p);
		return ((StrictValue.Error) p).message();
	}

	@Test
	void doublesAreStrictFiniteAndInRange() {
		String k = "easy.damage-multiplier"; // 0 to 10
		ok(k, "0.5", 0.5);
		ok(k, "1", 1.0);
		ok(k, " 2.5 ", 2.5);
		ok(k, "+2.5", 2.5);
		ok(k, "1e1", 10.0);
		ok(k, "1E0", 1.0);
		ok(k, ".5", 0.5);
		ok(k, "5.", 5.0);
		ok(k, "0", 0.0);
		ok(k, "10", 10.0);
		ok(k, "10.0", 10.0);
		for (String bad : List.of("", " ", "abc", "NaN", "nan", "Infinity", "-Infinity", "+Infinity", "-1", "-0.0001", "10.0001", "11", "0x10", "0x1p3", "1.5f", "1.5d", "1,5",
			"1 2", "--1", "1e400", "1e-400x", "e5", "٣", "1_0", "true", "1..5", "1.5.5", "∞")) {
			String message = refused(k, bad);
			assertTrue(message.contains(k) && message.contains("from 0.0 to 10.0") || message.contains("not a finite") || message.contains("outside"), "useful message for '" + bad + "': " + message);
		}
		assertTrue(refused(k, "11").contains("a number from 0.0 to 10.0"), "the allowed range is named");
		assertTrue(refused(k, "abc").contains("easy.damage-multiplier"), "the key is named");
	}

	@Test
	void negativeZeroIsStoredAsZeroAndDoublesRoundTripExactly() {
		StrictValue.Ok ok = (StrictValue.Ok) StrictValue.parse(def("elite.damage-multiplier"), "-0");
		assertEquals(0L, Double.doubleToRawLongBits((Double) ok.value()), "-0 is normalised to +0");
		for (double v : new double[] {0.1, 0.30000000000000004, 1.0 / 3.0, 2.5, 1e-7, 9.999999999999998, Math.nextUp(1.0)}) {
			JsonElement json = JsonParser.parseString(StrictValue.toJson(v).toString());
			assertEquals(new StrictValue.Ok(v), StrictValue.fromJson(def("elite.damage-multiplier"), json), "round trip " + v);
		}
	}

	@Test
	void wholeNumberSettingsTakeOnlyWholeNumbersInRange() {
		String k = "elite.priority.scan-interval-ticks"; // 5 to 40
		ok(k, "5", 5);
		ok(k, "40", 40);
		ok(k, "10", 10);
		ok(k, "+7", 7);
		ok(k, " 12 ", 12);
		for (String bad : List.of("", "4", "41", "10.0", "7.5", "1e1", "abc", "0x10", "-5", "99999999999999999999", "true", "NaN")) {
			String message = refused(k, bad);
			assertTrue(message.contains(k), message);
			assertTrue(message.contains("whole number"), "names the type/range: " + message);
		}
		assertTrue(refused(k, "41").contains("a whole number from 5 to 40"));
	}

	@Test
	void booleansAreExactlyTrueOrFalse() {
		String k = "elite.effects.enabled";
		ok(k, "true", true);
		ok(k, "false", false);
		ok(k, "TRUE", true);
		ok(k, " False ", false);
		for (String bad : List.of("", "yes", "no", "1", "0", "on", "off", "t", "tru", "true;", "2.5")) {
			assertTrue(refused(k, bad).contains("true or false"), bad);
		}
	}

	@Test
	void everyRegistrySettingRefusesAValueOneStepOutsideItsRange() {
		for (SettingDef d : SettingRegistry.scalars()) {
			switch (d.type()) {
				case DOUBLE -> {
					refused(d.key(), Double.toString(d.max() + 0.001));
					refused(d.key(), Double.toString(d.min() - 0.001));
					ok(d.key(), Double.toString(d.max()), d.max());
					ok(d.key(), Double.toString(d.min()), d.min());
				}
				case INT -> {
					refused(d.key(), Integer.toString((int) d.max() + 1));
					refused(d.key(), Integer.toString((int) d.min() - 1));
					ok(d.key(), Integer.toString((int) d.max()), (int) d.max());
					ok(d.key(), Integer.toString((int) d.min()), (int) d.min());
				}
				case BOOL -> {
					ok(d.key(), "true", true);
					ok(d.key(), "false", false);
					refused(d.key(), "maybe");
				}
			}
		}
	}

	@Test
	void damageTypeMultipliersAreStrictToo() {
		assertEquals(new StrictValue.Ok(0.3), StrictValue.parseMultiplier("easy.damage-type.minecraft.fall", "0.3"));
		assertEquals(new StrictValue.Ok(10.0), StrictValue.parseMultiplier("k", "10"));
		for (String bad : List.of("", "11", "-1", "NaN", "abc", "Infinity")) {
			assertInstanceOf(StrictValue.Error.class, StrictValue.parseMultiplier("easy.damage-type.minecraft.fall", bad), bad);
		}
		assertTrue(((StrictValue.Error) StrictValue.parseMultiplier("k", "11")).message().contains("a number from 0.0 to 10.0"));
	}

	@Test
	void jsonValuesMustAlreadyHaveTheRightType() {
		SettingDef dbl = def("pvp.damage-multiplier");
		SettingDef whole = def("elite.priority.scan-interval-ticks");
		SettingDef flag = def("elite.priority.enabled");
		assertEquals(new StrictValue.Ok(2.5), StrictValue.fromJson(dbl, JsonParser.parseString("2.5")));
		assertEquals(new StrictValue.Ok(3.0), StrictValue.fromJson(dbl, JsonParser.parseString("3")));
		assertEquals(new StrictValue.Ok(12), StrictValue.fromJson(whole, JsonParser.parseString("12")));
		assertEquals(new StrictValue.Ok(12), StrictValue.fromJson(whole, JsonParser.parseString("12.0")));
		assertEquals(new StrictValue.Ok(false), StrictValue.fromJson(flag, JsonParser.parseString("false")));
		for (String bad : List.of("\"2.5\"", "true", "null", "[1]", "{}", "11", "-1", "1e400")) {
			assertInstanceOf(StrictValue.Error.class, StrictValue.fromJson(dbl, JsonParser.parseString(bad)), "double <- " + bad);
		}
		for (String bad : List.of("12.5", "\"12\"", "true", "null", "4", "41", "1e400")) {
			assertInstanceOf(StrictValue.Error.class, StrictValue.fromJson(whole, JsonParser.parseString(bad)), "int <- " + bad);
		}
		for (String bad : List.of("\"false\"", "0", "1", "null", "[]")) {
			assertInstanceOf(StrictValue.Error.class, StrictValue.fromJson(flag, JsonParser.parseString(bad)), "bool <- " + bad);
		}
		assertInstanceOf(StrictValue.Error.class, StrictValue.fromJson(dbl, null));
	}
}
