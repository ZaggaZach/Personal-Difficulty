package com.zaggazach.personaldifficulty.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An immutable snapshot of the whole configuration: the two layers and the effective {@link PdConfig} composed from them
 * (defaults, then properties, then overrides). The runtime publishes one snapshot at a time through a single reference, so a
 * failed change can never leave a half-applied configuration.
 */
public record ConfigState(PropertiesLayer properties, OverridesLayer overrides, PdConfig effective, Map<String, Object> effectiveValues) {
	public enum Source {
		DEFAULT,
		PROPERTIES,
		OVERRIDE;

		public String label() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	public static ConfigState compose(PropertiesLayer properties, OverridesLayer overrides) {
		Map<String, Object> values = new LinkedHashMap<>(properties.values());
		values.putAll(overrides.scalars());
		Map<String, Map<String, Double>> family = new LinkedHashMap<>();
		for (FamilyDef f : SettingRegistry.families()) {
			Map<String, Double> merged = new java.util.TreeMap<>(properties.family().getOrDefault(f.profile(), Map.of()));
			merged.putAll(overrides.family().getOrDefault(f.profile(), Map.of()));
			family.put(f.profile(), merged);
		}
		return new ConfigState(properties, overrides, ConfigAssembler.assemble(values, family), Map.copyOf(values));
	}

	public Source sourceOf(SettingDef d) {
		if (overrides.scalars().containsKey(d.key())) {
			return Source.OVERRIDE;
		}
		return properties.explicit().contains(d.key()) ? Source.PROPERTIES : Source.DEFAULT;
	}

	/** The value the setting has without the overrides layer (properties after the lenient rules, else the default). */
	public Object lowerValue(SettingDef d) {
		return properties.values().get(d.key());
	}

	public Object effectiveValue(SettingDef d) {
		return effectiveValues.get(d.key());
	}

	/** The properties layer's value for a damage-type entry, if the file declared one. */
	public Optional<Double> lowerFamilyValue(FamilyKey k) {
		return Optional.ofNullable(properties.family().getOrDefault(k.family().profile(), Map.of()).get(k.id()));
	}

	public Optional<Double> overrideFamilyValue(FamilyKey k) {
		return Optional.ofNullable(overrides.family().getOrDefault(k.family().profile(), Map.of()).get(k.id()));
	}

	public Optional<Double> effectiveFamilyValue(FamilyKey k) {
		return overrideFamilyValue(k).or(() -> lowerFamilyValue(k));
	}

	public Source sourceOf(FamilyKey k) {
		return overrideFamilyValue(k).isPresent() ? Source.OVERRIDE : lowerFamilyValue(k).isPresent() ? Source.PROPERTIES : Source.DEFAULT;
	}
}
