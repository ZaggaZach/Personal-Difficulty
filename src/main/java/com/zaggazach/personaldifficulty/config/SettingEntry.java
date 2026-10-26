package com.zaggazach.personaldifficulty.config;

/** One row of the {@link SettingRegistry}: either a scalar {@link SettingDef} or a dynamic {@link FamilyDef}. */
public sealed interface SettingEntry permits SettingDef, FamilyDef {
	/** Position in the legacy properties loader's evaluation order (it fixes the order of warnings). */
	int order();

	String javaPath();
}
