package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The reserved permission-node ids (plan section 5, C4) are constants only and their exact text is a promise to server owners. The values
 * are read from the compiled class by reflection: a plain {@code assertEquals(PdPermissions.NODE_ADMIN_GUI, "...")} would be inlined by
 * the compiler and could never fail.
 */
class PdPermissionsNodesTest {
	@Test
	void theReservedNodeIdsHaveExactlyTheApprovedValues_andThereAreNoOthers() throws Exception {
		Map<String, String> found = new TreeMap<>();
		for (Field f : PdPermissions.class.getDeclaredFields()) {
			if (f.getName().startsWith("NODE_")) {
				assertEquals(Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL, f.getModifiers() & (Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL), f.getName());
				assertEquals(String.class, f.getType(), f.getName());
				found.put(f.getName(), (String) f.get(null));
			}
		}
		assertEquals(Map.of(
			"NODE_ADMIN_GUI", "personaldifficulty.admin.gui",
			"NODE_ADMIN_ASSIGN", "personaldifficulty.admin.assign",
			"NODE_ADMIN_LOCK", "personaldifficulty.admin.lock",
			"NODE_ADMIN_CONFIG", "personaldifficulty.admin.config"), found);
	}
}
