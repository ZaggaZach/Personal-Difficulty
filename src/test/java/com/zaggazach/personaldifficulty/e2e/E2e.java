package com.zaggazach.personaldifficulty.e2e;

import java.nio.file.Path;
import java.util.List;

/** Small shared helpers for the end-to-end tests. */
final class E2e {
	private E2e() {
	}

	static Path newServerDir(String name) {
		return Path.of(System.getProperty("pd.e2e.work", "build/e2e/work")).toAbsolutePath().resolve(name);
	}

	/**
	 * Lines in the server log that indicate a problem: any ERROR (except one known vanilla startup message about flat-world
	 * "layers"), any warning logged by the mod itself, and any stack frame inside the mod's package. Vanilla/Netty notes about a
	 * client that hung up mid-write ("Failed to deliver packet", "Broken pipe") are expected when the tests drop connections on
	 * purpose and are not counted.
	 */
	static List<String> problemLines(ServerRunner server) {
		return server.log.stream()
			.filter(l -> (l.contains("/ERROR]") && !l.contains("No key layers"))
				|| (l.contains("[PersonalDifficulty]") && l.contains("/WARN]"))
				|| l.contains("at com.zaggazach.personaldifficulty.")
				|| l.contains("com.zaggazach.personaldifficulty.") && l.contains("Exception"))
			.toList();
	}
}
