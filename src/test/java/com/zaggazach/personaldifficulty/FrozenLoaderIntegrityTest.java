package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * The frozen oracle must not be edited to make a differential comparison pass. Its source is pinned by hash; changing it
 * requires deliberately changing this constant, which a reviewer will see.
 */
class FrozenLoaderIntegrityTest {
	/** SHA-256 of src/test/java/com/zaggazach/personaldifficulty/legacy/LegacyConfigLoader.java (derived from 60bce2a). */
	private static final String EXPECTED_SHA256 = "3a2140bc7e9e83be7759fe1c650ce295909e76d962aaf5fb7ea9e87973cb356a";

	@Test
	void theFrozenLoaderSourceIsUnchanged() throws Exception {
		Path file = Path.of("src/test/java/com/zaggazach/personaldifficulty/legacy/LegacyConfigLoader.java");
		String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
		assertEquals(EXPECTED_SHA256, actual, "LegacyConfigLoader.java was modified: it is a frozen oracle (see its header)");
	}
}
