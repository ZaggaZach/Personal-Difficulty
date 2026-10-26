package com.zaggazach.personaldifficulty.config;

import java.util.Collection;
import java.util.List;

/** The damage type ids known to the live server (for example {@code minecraft:fall}); datapacks may add more. */
public interface DamageTypeCatalog {
	/** Every id, or an empty collection when the registry is not available (then nothing is rejected). */
	Collection<String> ids();

	default boolean exists(String id) {
		Collection<String> all = ids();
		return all.isEmpty() || all.contains(id);
	}

	DamageTypeCatalog PERMISSIVE = List::of;
}
