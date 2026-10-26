package com.zaggazach.personaldifficulty.e2e;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 persistence against REAL server processes running the built JAR: the per-player lock, allow-list and assigned mode
 * must survive a graceful restart, a SIGKILL, and a v1 players.json must migrate safely. Protocol-level only (headless client).
 */
@Tag("e2e")
class PlayerPolicyE2eTest {
	private static Path playersFile(ServerRunner server) {
		return server.world().resolve("personaldifficulty").resolve("players.json");
	}

	private static List<Path> backups(ServerRunner server) throws IOException {
		try (Stream<Path> s = Files.list(playersFile(server).getParent())) {
			return s.filter(p -> p.getFileName().toString().startsWith("players.json.backup-")).sorted().toList();
		}
	}

	/** Runs a console command and waits until the mod answers (the answer is only sent after the change has been saved). */
	private static void admin(ServerRunner server, String command, String expectedInAnswer) throws Exception {
		int mark = server.logSize();
		server.console("personaldifficulty admin " + command);
		assertTrue(server.awaitLogAfter(expectedInAnswer, mark, 15_000), "no answer containing '" + expectedInAnswer + "' to: " + command + "\n" + server.tail(15));
	}

	private static JsonObject read(Path file) throws IOException {
		return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
	}

	private static void assertPolicyStillEnforced(ServerRunner server, String name, String expectedMode) throws Exception {
		try (Bot bot = Bot.join("127.0.0.1", server.port(), name)) {
			bot.command("personaldifficulty");
			assertTrue(bot.awaitChat("Personal difficulty: " + expectedMode, 10_000), "status after restart: " + String.join("\n", bot.chat));
			assertTrue(bot.awaitChat("locked by an administrator", 10_000), "the lock is still shown: " + String.join("\n", bot.chat));
			assertTrue(bot.awaitChat("limits you to: normal, elite", 10_000), "the allow-list is still shown: " + String.join("\n", bot.chat));
			for (String attempt : List.of("hard", "normal", "peaceful", "easy")) {
				bot.chat.clear();
				bot.command("personaldifficulty " + attempt);
				assertTrue(bot.awaitChat("locked by an administrator", 10_000), "a locked player cannot choose " + attempt + ": " + String.join("\n", bot.chat));
			}
			bot.chat.clear();
			bot.command("personaldifficulty");
			assertTrue(bot.awaitChat("Personal difficulty: " + expectedMode, 10_000), "mode unchanged after the attempts: " + String.join("\n", bot.chat));
		}
	}

	private static void configureAlice(ServerRunner server) throws Exception {
		try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice")) {
			admin(server, "assign Alice elite lock", "Alice is now ELITE");
			admin(server, "restrict Alice normal,elite", "Alice is now restricted to normal, elite");
			alice.command("personaldifficulty hard");
			assertTrue(alice.awaitChat("locked by an administrator", 10_000), "the live player is locked at once: " + String.join("\n", alice.chat));
		}
	}

	@Test
	void lockAllowListAndAssignedModeSurviveAGracefulRestart() throws Exception {
		Path work = E2e.newServerDir("policy-restart");
		try (ServerRunner server = new ServerRunner(work)) {
			server.prepare(false);
			server.start();
			configureAlice(server);
			server.stop();

			JsonObject root = read(playersFile(server));
			assertEquals(2, root.get("version").getAsInt(), "format v2 on disk");
			JsonObject rec = root.getAsJsonObject("players").entrySet().iterator().next().getValue().getAsJsonObject();
			assertEquals("ELITE", rec.get("mode").getAsString());
			assertTrue(rec.get("locked").getAsBoolean());
			assertEquals("[\"NORMAL\",\"ELITE\"]", rec.getAsJsonArray("allowed").toString());

			server.start();
			assertPolicyStillEnforced(server, "Alice", "ELITE");
			server.stop();
			assertEquals(List.of(), E2e.problemLines(server), "the server log of the restarted server must be clean");
		}
	}

	@Test
	void lockAllowListAndAssignedModeSurviveAKillMinusNine() throws Exception {
		Path work = E2e.newServerDir("policy-kill");
		try (ServerRunner server = new ServerRunner(work)) {
			server.prepare(false);
			server.start();
			configureAlice(server);
			// the answers above are only sent after the atomic write completed, so the file is already on disk: SIGKILL now
			assertTrue(Files.exists(playersFile(server)), "players.json written before the acknowledgement");
			server.kill();
			assertFalse(server.isAlive());

			JsonObject root = read(playersFile(server)); // still valid JSON after the abrupt kill
			assertEquals(2, root.get("version").getAsInt());

			server.start();
			assertPolicyStillEnforced(server, "Alice", "ELITE");
			server.stop();
			assertEquals(List.of(), E2e.problemLines(server), "log after recovering from the kill must be clean");
		}
	}

	@Test
	void killingTheServerInTheMiddleOfARapidWriteBurstNeverLeavesACorruptOrHalfWrittenFile() throws Exception {
		Path work = E2e.newServerDir("policy-burst");
		try (ServerRunner server = new ServerRunner(work)) {
			server.prepare(false);
			server.start();
			try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice")) {
				admin(server, "assign Alice hard", "Alice is now HARD");
			}
			for (int round = 0; round < 3; round++) {
				int mark = server.logSize();
				for (int i = 0; i < 400; i++) {
					server.console("personaldifficulty admin " + switch (i % 4) {
						case 0 -> "lock Alice";
						case 1 -> "restrict Alice hard,elite";
						case 2 -> "unlock Alice";
						default -> "unrestrict Alice";
					});
				}
				// let some of them run, then pull the plug while the rest are still queued
				assertTrue(server.awaitLogAfter("Alice is", mark, 15_000), "the burst started");
				Thread.sleep(150 + 100L * round);
				server.kill();

				JsonObject root = read(playersFile(server));
				assertEquals(2, root.get("version").getAsInt(), "round " + round + ": a complete v2 file, never a partial one");
				JsonObject rec = root.getAsJsonObject("players").entrySet().iterator().next().getValue().getAsJsonObject();
				assertEquals("HARD", rec.get("mode").getAsString(), "round " + round + ": the mode is never lost");

				server.start();
				assertFalse(server.log.stream().anyMatch(l -> l.contains("[PersonalDifficulty]") && l.contains("/WARN]") && l.contains("[players]")),
					"round " + round + ": loading after the kill produced no players-file warning:\n" + server.tail(30));
			}
			server.stop();
		}
	}

	private static final String V1 = "{\"version\":1,\"players\":{\"00000000-0000-0000-0000-0000000000f1\":\"HARD\",\"not-a-uuid\":\"EASY\"}}";

	@Test
	void aV1FileIsLoadedWithoutBeingRewritten_thenMigratedWithABackupOnTheFirstChange() throws Exception {
		Path work = E2e.newServerDir("policy-migrate");
		try (ServerRunner server = new ServerRunner(work)) {
			server.prepare(false);
			server.start();
			server.stop(); // creates the world
			Files.createDirectories(playersFile(server).getParent());
			Files.writeString(playersFile(server), V1);
			byte[] original = Files.readAllBytes(playersFile(server));

			server.start();
			try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice")) {
				alice.command("personaldifficulty");
				assertTrue(alice.awaitChat("Personal difficulty: NORMAL", 10_000));
			}
			assertArrayEquals(original, Files.readAllBytes(playersFile(server)), "loading and playing never rewrite a v1 file");
			assertTrue(backups(server).isEmpty(), "no backup until something changes");
			assertTrue(server.log.stream().anyMatch(l -> l.contains("unreadable UUID key")), "the unreadable entry is reported, not hidden");

			admin(server, "assign Alice elite", "Alice is now ELITE");
			List<Path> backups = backups(server);
			assertEquals(1, backups.size(), "exactly one timestamped backup before the first v2 write");
			assertArrayEquals(original, Files.readAllBytes(backups.get(0)), "the backup is the old file byte for byte");
			JsonObject root = read(playersFile(server));
			assertEquals(2, root.get("version").getAsInt());
			JsonObject players = root.getAsJsonObject("players");
			assertEquals("HARD", players.getAsJsonObject("00000000-0000-0000-0000-0000000000f1").get("mode").getAsString(), "migrated v1 data kept");
			assertEquals("EASY", players.get("not-a-uuid").getAsString(), "unknown entry preserved verbatim");
			assertEquals(3, players.size());
			server.stop();

			server.start();
			try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice")) {
				alice.command("personaldifficulty");
				assertTrue(alice.awaitChat("Personal difficulty: ELITE", 10_000), String.join("\n", alice.chat));
			}
			assertEquals(1, backups(server).size(), "no further backups on an already-v2 file");
			server.stop();
		}
	}

	@Test
	void aFileFromANewerFormatIsReadButNeverOverwritten() throws Exception {
		Path work = E2e.newServerDir("policy-newer");
		try (ServerRunner server = new ServerRunner(work)) {
			server.prepare(false);
			server.start();
			server.stop();
			Files.createDirectories(playersFile(server).getParent());
			String v3 = "{\"version\":3,\"players\":{\"00000000-0000-0000-0000-0000000000f1\":{\"mode\":\"HARD\",\"flux\":true}}}";
			Files.writeString(playersFile(server), v3);

			server.start();
			assertTrue(server.log.stream().anyMatch(l -> l.contains("format version 3, newer than") && l.contains("no change to any player can be saved")), "warned at startup:\n" + server.tail(40));
			try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice")) {
				alice.command("personaldifficulty hard");
				assertTrue(alice.awaitChat("Could not save your setting", 10_000), "self-service refused with a clear reason: " + String.join("\n", alice.chat));
				assertTrue(alice.chat.stream().anyMatch(l -> l.contains("newer than the newest")), String.join("\n", alice.chat));
				alice.chat.clear();
				alice.command("personaldifficulty");
				assertTrue(alice.awaitChat("Personal difficulty: NORMAL", 10_000), "the refused change was not applied: " + String.join("\n", alice.chat));
			}
			int mark = server.logSize();
			server.console("personaldifficulty admin assign Alice elite");
			assertTrue(server.awaitLogAfter("nothing was changed", mark, 15_000), "admin change refused too:\n" + server.tail(10));
			assertEquals(v3, Files.readString(playersFile(server)), "the newer file is byte-identical");
			assertEquals(1, backups(server).size(), "and a backup copy exists");
			assertNotNull(backups(server).get(0));
			server.stop();
		}
	}

	@Test
	void aCorruptFileAtStartupIsVisibleToAdministrators_neverOverwritten_andReloadRecoversWithoutARestart() throws Exception {
		Path work = E2e.newServerDir("policy-corrupt");
		try (ServerRunner server = new ServerRunner(work)) {
			server.prepare(false);
			server.start();
			server.stop();
			Files.createDirectories(playersFile(server).getParent());
			Files.writeString(playersFile(server), "{ not json");

			server.start();
			assertTrue(server.log.stream().anyMatch(l -> l.contains("/ERROR]") && l.contains("PLAYER SETTINGS ARE NOT LOADED/WRITABLE")), "an ERROR line says so at startup:\n" + server.tail(30));
			try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice")) {
				alice.command("personaldifficulty hard");
				assertTrue(alice.awaitChat("Could not save your setting", 10_000), String.join("\n", alice.chat));
				assertTrue(alice.chat.stream().anyMatch(l -> l.contains("left untouched")), String.join("\n", alice.chat));
				alice.chat.clear();
				alice.command("personaldifficulty");
				assertTrue(alice.awaitChat("Personal difficulty: NORMAL", 10_000), "the refused change was not applied");
			}
			admin(server, "config", "WARNING: changes to players cannot be saved");
			admin(server, "assign Alice elite", "nothing was changed");
			assertEquals("{ not json", Files.readString(playersFile(server)), "never overwritten by any attempt");
			assertEquals(1, backups(server).size());
			assertEquals("{ not json", Files.readString(backups(server).get(0)));
			admin(server, "reload", "still cannot be used");

			// the administrator repairs the file and reloads: no restart needed
			String id;
			try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice")) {
				id = alice.uuid().toString();
			}
			Files.writeString(playersFile(server), "{\"version\":1,\"players\":{\"" + id + "\":\"HARD\"}}");
			admin(server, "reload", "usable again");
			try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice")) {
				alice.command("personaldifficulty");
				assertTrue(alice.awaitChat("Personal difficulty: HARD", 10_000), "the repaired file is in force: " + String.join("\n", alice.chat));
				alice.chat.clear();
				alice.command("personaldifficulty elite");
				assertTrue(alice.awaitChat("now ELITE", 10_000), "changes work again: " + String.join("\n", alice.chat));
			}
			assertEquals(2, read(playersFile(server)).get("version").getAsInt());
			assertEquals(2, backups(server).size(), "the corrupt original, and the v1 file before its migration");
			server.stop();
		}
	}

	@Test
	void anInvalidRecordIsQuarantinedAcrossRestarts_untilAnAdministratorRepairsItWithAVerifiedBackup() throws Exception {
		Path work = E2e.newServerDir("policy-quarantine");
		try (ServerRunner server = new ServerRunner(work)) {
			server.prepare(false);
			server.start();
			String id;
			try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice")) {
				id = alice.uuid().toString();
			}
			server.stop();
			Files.createDirectories(playersFile(server).getParent());
			String original = "{\"version\":2,\"players\":{\"" + id + "\":{\"mode\":\"ELITE\",\"locked\":\"yes\",\"nickname\":\"zed\"}}}";
			Files.writeString(playersFile(server), original);

			for (int run = 0; run < 2; run++) { // the quarantine survives a restart
				server.start();
				assertTrue(server.log.stream().anyMatch(l -> l.contains("invalid stored record")), "reported at startup (run " + run + ")");
				try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice")) {
					alice.command("personaldifficulty");
					assertTrue(alice.awaitChat("could not be read", 10_000), "status says so: " + String.join("\n", alice.chat));
					alice.chat.clear();
					alice.command("personaldifficulty hard");
					assertTrue(alice.awaitChat("could not be read", 10_000), "self-service refused: " + String.join("\n", alice.chat));
				}
				admin(server, "assign Alice hard", "Refused: Alice has an invalid stored record");
				admin(server, "lock Alice", "Refused: Alice has an invalid stored record");
				admin(server, "status Alice", "INVALID STORED RECORD");
				assertEquals(original, Files.readString(playersFile(server)), "byte-identical after every attempt (run " + run + ")");
				assertTrue(backups(server).isEmpty());
				server.stop();
			}

			server.start();
			admin(server, "reset Alice", "was replaced by a default");
			assertEquals(1, backups(server).size());
			assertEquals(original, Files.readString(backups(server).get(0)), "the backup is the file as it was before the repair");
			assertTrue(Files.readString(playersFile(server)).contains("\"nickname\":\"zed\""), "unknown field kept");
			try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice")) {
				alice.command("personaldifficulty hard");
				assertTrue(alice.awaitChat("now HARD", 10_000), "ordinary changes work after the repair: " + String.join("\n", alice.chat));
			}
			server.stop();

			server.start();
			try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice")) {
				alice.command("personaldifficulty");
				assertTrue(alice.awaitChat("Personal difficulty: HARD", 10_000), String.join("\n", alice.chat));
			}
			assertFalse(server.log.stream().anyMatch(l -> l.contains("[PersonalDifficulty]") && l.contains("/WARN]") && l.contains("[players]")), "clean load after the repair:\n" + server.tail(20));
			server.stop();
		}
	}
}
