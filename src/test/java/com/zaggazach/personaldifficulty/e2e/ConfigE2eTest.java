package com.zaggazach.personaldifficulty.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 against REAL server processes running the built JAR: the overrides file survives a graceful restart, a SIGKILL and
 * a burst of writes killed mid-stream; a corrupt or partly invalid file is visible and recoverable; the properties file is never touched.
 */
@Tag("e2e")
class ConfigE2eTest {
	private static Path config(ServerRunner server) {
		return server.dir().resolve("config");
	}

	private static Path overrides(ServerRunner server) {
		return config(server).resolve("personaldifficulty-overrides.json");
	}

	private static String sha(Path p) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
	}

	private static List<Path> withInfix(ServerRunner server, String infix) throws IOException {
		try (Stream<Path> s = Files.list(config(server))) {
			return s.filter(p -> p.getFileName().toString().contains("personaldifficulty-overrides.json" + infix)).sorted().toList();
		}
	}

	/** Runs a console command and waits for the mod's answer. */
	private static void admin(ServerRunner server, String command, String expectedInAnswer) throws Exception {
		int mark = server.logSize();
		server.console("personaldifficulty admin " + command);
		assertTrue(server.awaitLogAfter(expectedInAnswer, mark, 15_000), "no answer containing '" + expectedInAnswer + "' to: " + command + "\n" + server.tail(15));
	}

	private static long count(ServerRunner server, String fragment) {
		return server.log.stream().filter(l -> l.contains(fragment)).count();
	}

	@Test
	void anOverrideSurvivesAGracefulRestartAndAKillMinusNine_andThePropertiesFileIsNeverTouched() throws Exception {
		Path work = E2e.newServerDir("config-restart");
		try (ServerRunner server = new ServerRunner(work)) {
			server.prepare(false);
			server.start();
			String propsHash = sha(config(server).resolve("personaldifficulty.properties"));
			admin(server, "get elite.damage-multiplier", "value: 2.0  (source: properties)");
			admin(server, "set elite.damage-multiplier 3", "elite.damage-multiplier is now 3.0");
			admin(server, "set easy.damage-type.minecraft.fall 0.25", "is now 0.25");
			admin(server, "set elite.priority.scan-interval-ticks 20", "next scan (within 20 ticks)");
			server.stop();
			assertEquals("{\"version\":1,\"values\":{\"easy.damage-type.minecraft.fall\":0.25,\"elite.damage-multiplier\":3.0,\"elite.priority.scan-interval-ticks\":20}}", Files.readString(overrides(server)));

			server.start();
			assertTrue(server.log.stream().anyMatch(l -> l.contains("Personal Difficulty ready") && l.contains("3 setting override(s) active")), server.tail(20));
			admin(server, "get elite.damage-multiplier", "value: 3.0  (source: override)");
			admin(server, "get easy.damage-type.minecraft.fall", "value: 0.25  (source: override)");

			admin(server, "set pvp.damage-multiplier 2.5", "pvp.damage-multiplier is now 2.5");
			server.kill(); // the acknowledgement was only sent after the atomic write: SIGKILL right away
			JsonObject root = JsonParser.parseString(Files.readString(overrides(server))).getAsJsonObject();
			assertEquals(1, root.get("version").getAsInt());
			assertEquals(2.5, root.getAsJsonObject("values").get("pvp.damage-multiplier").getAsDouble());

			server.start();
			admin(server, "get pvp.damage-multiplier", "value: 2.5  (source: override)");
			admin(server, "unset pvp.damage-multiplier", "Override removed");
			admin(server, "unset elite.damage-multiplier", "Override removed");
			assertEquals(propsHash, sha(config(server).resolve("personaldifficulty.properties")), "personaldifficulty.properties is byte-identical after every change and restart");
			server.stop();
			assertEquals(List.of(), E2e.problemLines(server), "the log of the last run is clean");
		}
	}

	@Test
	void killingTheServerInTheMiddleOfARapidWriteBurstNeverLeavesACorruptOverridesFile() throws Exception {
		Path work = E2e.newServerDir("config-burst");
		try (ServerRunner server = new ServerRunner(work)) {
			server.prepare(false);
			server.start();
			admin(server, "set self.damage-multiplier 2", "is now 2.0");
			for (int round = 0; round < 3; round++) {
				int mark = server.logSize();
				for (int i = 0; i < 300; i++) {
					server.console("personaldifficulty admin " + switch (i % 4) {
						case 0 -> "set pvp.damage-multiplier 3";
						case 1 -> "set elite.priority.scan-interval-ticks 17";
						case 2 -> "unset pvp.damage-multiplier";
						default -> "unset elite.priority.scan-interval-ticks";
					});
				}
				assertTrue(server.awaitLogAfter("pvp.damage-multiplier is now", mark, 15_000), "the burst started");
				Thread.sleep(150 + 100L * round);
				server.kill();

				JsonObject root = JsonParser.parseString(Files.readString(overrides(server))).getAsJsonObject();
				assertEquals(1, root.get("version").getAsInt(), "round " + round + ": a complete file, never a partial one");
				assertEquals(2.0, root.getAsJsonObject("values").get("self.damage-multiplier").getAsDouble(), "round " + round + ": the unrelated override is never lost");

				server.start();
				assertFalse(server.log.stream().anyMatch(l -> l.contains("[overrides]") && (l.contains("/WARN]") || l.contains("/ERROR]"))), "round " + round + ": loading after the kill produced no overrides problem:\n" + server.tail(30));
			}
			// A kill between the atomic rename and the pruning step can leave one extra backup until the next completed write prunes it.
			admin(server, "set elite.priority.scan-interval-ticks 23", "elite.priority.scan-interval-ticks is now 23");
			assertTrue(withInfix(server, ".backup-").size() <= 5, "never more than five ordinary backups after a completed write: " + withInfix(server, ".backup-").size());
			server.stop();
		}
	}

	@Test
	void aCorruptOverridesFileAtStartupIsLoud_neverOverwritten_andReloadRecovers_withTheWarningLoggedOncePerLoad() throws Exception {
		Path work = E2e.newServerDir("config-corrupt");
		try (ServerRunner server = new ServerRunner(work)) {
			server.prepare(false);
			Files.createDirectories(config(server));
			Files.writeString(overrides(server), "{ not json");
			server.start(); // the server starts normally on defaults plus the properties file
			assertTrue(server.log.stream().anyMatch(l -> l.contains("/ERROR]") && l.contains("THE OVERRIDES FILE IS NOT IN USE AND IS NOT WRITABLE")), "an ERROR line says so:\n" + server.tail(30));
			assertEquals(1, server.log.stream().filter(l -> l.contains("/WARN]") && l.contains("overrides file is not valid JSON")).count(), "the startup warning is logged once");

			admin(server, "config", "WARNING: setting changes cannot be saved");
			admin(server, "set pvp.damage-multiplier 2", "admin reload");
			assertEquals("{ not json", Files.readString(overrides(server)), "never overwritten");
			assertEquals(1, withInfix(server, ".evidence-").size());
			assertEquals("{ not json", Files.readString(withInfix(server, ".evidence-").get(0)), "a verified evidence copy");

			int mark = server.logSize();
			server.console("personaldifficulty admin reload");
			assertTrue(server.awaitLogAfter("Reload incomplete", mark, 15_000), server.tail(10));
			assertEquals(1, withInfix(server, ".evidence-").size(), "reloading the same bad file creates no further evidence");

			Files.writeString(overrides(server), "{\"version\":1,\"values\":{\"pvp.damage-multiplier\":2.5}}");
			admin(server, "reload", "setting overrides");
			admin(server, "get pvp.damage-multiplier", "value: 2.5  (source: override)");
			admin(server, "set self.damage-multiplier 2", "self.damage-multiplier is now 2.0");
			assertEquals(1, withInfix(server, ".evidence-").size(), "evidence is never pruned");
			server.stop();
		}
	}

	@Test
	void anInvalidStoredValueAndUnknownEntriesAreIgnoredReportedPreserved_otherOverridesStayActive_andAValidSetReplacesIt() throws Exception {
		Path work = E2e.newServerDir("config-invalid");
		try (ServerRunner server = new ServerRunner(work)) {
			server.prepare(false);
			Files.createDirectories(config(server));
			String original = "{\"version\":1,\"note\":\"hello\",\"values\":{\"pvp.damage-multiplier\":\"banana\",\"self.damage-multiplier\":3.0,\"future.setting\":{\"a\":1},\"easy.damage-type.mod.gone\":0.5}}";
			Files.writeString(overrides(server), original);
			server.start();
			assertEquals(1, count(server, "ignored the stored value of pvp.damage-multiplier"), "reported once");
			assertEquals(1, count(server, "damage type mod:gone does not exist"), "the unknown damage type is reported once");
			assertEquals(original, Files.readString(overrides(server)), "loading never rewrites the file");
			assertTrue(withInfix(server, ".backup-").isEmpty() && withInfix(server, ".evidence-").isEmpty());
			admin(server, "get self.damage-multiplier", "value: 3.0  (source: override)");
			admin(server, "get pvp.damage-multiplier", "NOT applied");

			admin(server, "set pvp.damage-multiplier 2.5", "was not in use");
			assertEquals(original, Files.readString(withInfix(server, ".backup-").get(0)), "the previous file is in a verified backup");
			JsonObject after = JsonParser.parseString(Files.readString(overrides(server))).getAsJsonObject();
			assertEquals("hello", after.get("note").getAsString(), "the unknown top-level field is preserved");
			JsonObject values = after.getAsJsonObject("values");
			assertEquals(2.5, values.get("pvp.damage-multiplier").getAsDouble());
			assertEquals(3.0, values.get("self.damage-multiplier").getAsDouble());
			assertEquals("{\"a\":1}", values.get("future.setting").toString(), "unknown entries are preserved");
			assertEquals(0.5, values.get("easy.damage-type.mod.gone").getAsDouble(), "the not-applied damage type entry is preserved");
			server.stop();

			server.start();
			admin(server, "get pvp.damage-multiplier", "value: 2.5  (source: override)");
			server.stop();
		}
	}
}
