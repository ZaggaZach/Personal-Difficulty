package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.config.FamilyDef;
import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import com.zaggazach.personaldifficulty.config.SettingDef;
import com.zaggazach.personaldifficulty.config.SettingRegistry;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The registry is the one table of settings: it must cover every {@link PdConfig} leaf and agree with the defaults and the shipped file. */
class SettingRegistryTest {
	/** Leaf paths of PdConfig found by reflection: scalars by their path, maps as their own leaf. */
	private static void leaves(Class<?> type, String prefix, List<String> out) {
		for (RecordComponent c : type.getRecordComponents()) {
			String path = prefix.isEmpty() ? c.getName() : prefix + "." + c.getName();
			Class<?> t = c.getType();
			if (t.isRecord()) {
				leaves(t, path, out);
			} else if (t == double.class || t == int.class || t == boolean.class || Map.class.isAssignableFrom(t)) {
				out.add(path);
			} else {
				fail("PdConfig has a component of an unexpected type, add handling and a registry entry for it: " + path + " : " + t);
			}
		}
	}

	private static Object readByPath(Object root, String path) throws ReflectiveOperationException {
		Object cur = root;
		for (String part : path.split("\\.")) {
			cur = cur.getClass().getMethod(part).invoke(cur);
		}
		return cur;
	}

	@Test
	void everyPdConfigLeafHasARegistryEntryAndEveryEntryAPdConfigLeaf() {
		List<String> configLeaves = new ArrayList<>();
		leaves(PdConfig.class, "", configLeaves);
		Set<String> registryPaths = new TreeSet<>();
		SettingRegistry.scalars().forEach(d -> assertTrue(registryPaths.add(d.javaPath()), "duplicate java path " + d.javaPath()));
		SettingRegistry.families().forEach(f -> assertTrue(registryPaths.add(f.javaPath()), "duplicate java path " + f.javaPath()));
		assertEquals(new TreeSet<>(configLeaves), registryPaths, "a PdConfig field without a registry entry (or an entry for no field)");
	}

	@Test
	void everyEntryReadsTheSameValueAsItsJavaPathAndMatchesTheBuiltInDefault() throws ReflectiveOperationException {
		PdConfig defaults = PdConfig.defaults();
		for (SettingDef d : SettingRegistry.scalars()) {
			Object byPath = readByPath(defaults, d.javaPath());
			assertEquals(byPath, d.read().apply(defaults), d.key() + ": the accessor and the java path disagree");
			assertEquals(byPath, d.defaultValue(), d.key() + ": registry default differs from PdConfig.defaults()");
		}
		for (FamilyDef f : SettingRegistry.families()) {
			assertEquals(readByPath(defaults, f.javaPath()), f.read().apply(defaults), f.javaPath());
		}
	}

	@Test
	void keysAreUniqueLowerCaseDottedAndTheOrderIsUnique() {
		Set<String> keys = new HashSet<>();
		Set<Integer> orders = new HashSet<>();
		for (SettingDef d : SettingRegistry.scalars()) {
			assertTrue(keys.add(d.key()), "duplicate key " + d.key());
			assertTrue(d.key().matches("[a-z]+(\\.[a-z]+(-[a-z]+)*)+"), "unexpected key shape " + d.key());
			assertTrue(orders.add(d.order()), "duplicate order " + d.order());
		}
		SettingRegistry.families().forEach(f -> assertTrue(orders.add(f.order()), "duplicate order " + f.order()));
		assertEquals(SettingRegistry.scalars().size() + SettingRegistry.families().size(), SettingRegistry.entries().size());
		for (int i = 1; i < SettingRegistry.entries().size(); i++) {
			assertTrue(SettingRegistry.entries().get(i - 1).order() < SettingRegistry.entries().get(i).order(), "entries are sorted by evaluation order");
		}
	}

	@Test
	void registryKeysAreExactlyTheKeysOfTheShippedDefaultFile() {
		Set<String> inFile = new TreeSet<>();
		for (String line : PdConfigLoader.defaultFileContents().split("\\R")) {
			if (line.matches("[a-z][a-z.-]*\\s*=.*")) {
				inFile.add(line.substring(0, line.indexOf('=')).trim());
			}
		}
		Set<String> inRegistry = new TreeSet<>();
		SettingRegistry.scalars().forEach(d -> inRegistry.add(d.key()));
		assertEquals(inFile, inRegistry);
		assertEquals(46, inRegistry.size());
	}

	@Test
	void metadataIsSane() {
		Set<String> displays = new HashSet<>();
		for (SettingDef d : SettingRegistry.scalars()) {
			String k = d.key();
			assertNotNull(d.timing(), k);
			assertNotNull(d.defaultValue(), k);
			assertTrue(d.display() != null && !d.display().isBlank(), k + ": display text");
			assertTrue(displays.add(d.display()), k + ": display text must be unique: " + d.display());
			switch (d.type()) {
				case BOOL -> {
					assertTrue(d.defaultValue() instanceof Boolean, k);
					assertEquals(0.0, d.step(), k + ": booleans have no step");
				}
				case INT -> {
					assertTrue(d.defaultValue() instanceof Integer, k);
					assertEquals(Math.rint(d.min()), d.min(), k + ": integer bounds");
					assertEquals(Math.rint(d.max()), d.max(), k + ": integer bounds");
					assertTrue(d.step() >= 1 && d.step() == Math.rint(d.step()), k + ": integer step");
					assertTrue((int) d.min() <= (Integer) d.defaultValue() && (Integer) d.defaultValue() <= (int) d.max(), k + ": default in bounds");
				}
				case DOUBLE -> {
					assertTrue(d.defaultValue() instanceof Double, k);
					assertTrue(d.min() < d.max(), k);
					assertTrue(d.step() > 0 && d.step() <= d.max() - d.min(), k + ": step must be positive and fit the range");
					assertTrue(d.min() <= (Double) d.defaultValue() && (Double) d.defaultValue() <= d.max(), k + ": default in bounds");
				}
			}
		}
		assertEquals(List.of("easy", "hard", "elite"), SettingRegistry.families().stream().map(FamilyDef::profile).toList());
		SettingRegistry.families().forEach(f -> {
			assertTrue(f.mode().hasProfile(), f.profile());
			assertEquals(f.profile() + ".damage-type.", f.prefix());
		});
	}

	@Test
	void boundsAreTheSameConstantsPdConfigAlreadyHas_soALaterPhaseChangesThemInOnePlace() {
		assertEquals(PdConfig.MAX_AI_RANGE_MULTIPLIER, SettingRegistry.find("elite.ai.acquisition-range-multiplier").orElseThrow().max());
		assertEquals(PdConfig.MAX_AI_RANGE_MULTIPLIER, SettingRegistry.find("elite.ai.follow-range-multiplier").orElseThrow().max());
		assertEquals(PdConfig.MAX_MEMORY_MULTIPLIER, SettingRegistry.find("elite.ai.target-memory-multiplier").orElseThrow().max());
		assertEquals(PdConfig.MAX_MULTIPLIER, SettingRegistry.find("easy.damage-multiplier").orElseThrow().max());
		assertEquals(PdConfig.MAX_UNCERTAINTY, SettingRegistry.find("easy.ranged-min-uncertainty").orElseThrow().max());
		assertEquals(PdConfig.MIN_REGEN_MULTIPLIER, SettingRegistry.find("elite.natural-regeneration-multiplier").orElseThrow().min());
		assertEquals((double) PdConfig.MIN_SCAN_INTERVAL_TICKS, SettingRegistry.find("elite.priority.scan-interval-ticks").orElseThrow().min());
		assertEquals((double) PdConfig.MAX_SCAN_INTERVAL_TICKS, SettingRegistry.find("elite.priority.scan-interval-ticks").orElseThrow().max());
		assertEquals((double) PdConfig.MAX_REACQUIRE_SECONDS, SettingRegistry.find("elite.priority.reacquire-hidden-seconds").orElseThrow().max());
		assertEquals((double) PdConfig.MAX_EFFECT_SECONDS, SettingRegistry.find("elite.effects.max-duration-seconds").orElseThrow().max());
	}

	@Test
	void timingLabelsAreAssignedAsDocumented() {
		for (SettingDef d : SettingRegistry.scalars()) {
			SettingDef.Timing expected = d.key().startsWith("elite.priority.") ? SettingDef.Timing.NEXT_SCAN : SettingDef.Timing.LIVE;
			assertEquals(expected, d.timing(), d.key());
		}
	}
}
