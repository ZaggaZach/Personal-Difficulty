package com.zaggazach.personaldifficulty.config;

import java.util.List;
import java.util.Map;

/**
 * The validated, active contents of the overrides file: scalar values and per-damage-type multipliers that passed strict
 * validation (and, for damage types, exist in the live registry), plus the warnings about everything that did not.
 * Entries that were not applied are kept verbatim by the {@link OverridesStore}, not here.
 */
public record OverridesLayer(Map<String, Object> scalars, Map<String, Map<String, Double>> family, List<String> warnings) {
	public static final OverridesLayer EMPTY = new OverridesLayer(Map.of(), Map.of(), List.of());

	public OverridesLayer {
		scalars = Map.copyOf(scalars);
		family = Map.copyOf(family);
		warnings = List.copyOf(warnings);
	}

	public int size() {
		return scalars.size() + family.values().stream().mapToInt(Map::size).sum();
	}
}
