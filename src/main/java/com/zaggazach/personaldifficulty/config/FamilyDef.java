package com.zaggazach.personaldifficulty.config;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import java.util.Map;
import java.util.function.Function;

/**
 * The dynamic family {@code <mode>.damage-type.<namespace>.<path>} of one difficulty profile: per-damage-type damage multipliers.
 *
 * @param prefix key prefix including the final dot, for example {@code easy.damage-type.}
 * @param read reads the effective map (damage type id such as {@code minecraft:fall} to multiplier) out of a {@link PdConfig}
 */
public record FamilyDef(
	String profile,
	PersonalDifficultyMode mode,
	String prefix,
	int order,
	String javaPath,
	Function<PdConfig, Map<String, Double>> read
) implements SettingEntry {
}
