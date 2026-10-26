package com.zaggazach.personaldifficulty.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The outcome of reading the hand-edited properties file (or text): every scalar value after the lenient rules, which keys the file
 * set explicitly (a usable value, possibly clamped) so a value's source can be shown, the damage-type family entries it declared,
 * and the warnings.
 *
 * @param values every registry key to its value (Double, Integer or Boolean), defaults included
 * @param explicit the keys whose value came from the file
 * @param family profile ({@code easy}, {@code hard}, {@code elite}) to damage type id to multiplier
 */
public record PropertiesLayer(Map<String, Object> values, Set<String> explicit, Map<String, Map<String, Double>> family, List<String> warnings) {
	public PropertiesLayer {
		values = Map.copyOf(values);
		explicit = Set.copyOf(explicit);
		family = Map.copyOf(family);
		warnings = List.copyOf(warnings);
	}
}
