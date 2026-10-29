package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Capture;
import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Outcome;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Characterisation of every administrative player/policy command (Phase 3, D2). A scripted session is run against fixed players and
 * the complete output (result code, error and every message, in order) is compared with a golden transcript that was recorded from
 * the Phase 2 code BEFORE the administrative logic was moved into the shared service. Any change of wording, ordering or outcome of
 * a command therefore fails here. The golden file is never regenerated to make a regression pass.
 */
public class AdminTranscriptGameTests {
	private static final String GOLDEN = "/admin-transcript.golden.txt";
	private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
	private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
	private static final UUID D = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
	private static final UUID OP = UUID.fromString("00000000-0000-0000-0000-0000000000e5");

	private static Path playersFile(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("personaldifficulty").resolve("players.json");
	}

	private static String normalise(String s) {
		return s.replaceAll("at \\d{4}-\\d\\d-\\d\\dT[0-9:.]+Z", "at <time>").replaceAll("backup-\\d+", "backup-<ms>")
			.replaceAll("\\d+ saved selection", "<n> saved selection").replaceAll("\\d+ player\\(s\\) still use", "<n> player(s) still use");
	}

	private static void step(StringBuilder out, MinecraftServer server, String command, ServerPlayer asOp) {
		Capture cap = new Capture();
		Outcome o = asOp == null
			? run(server, "personaldifficulty admin " + command, console(server, cap), cap)
			: run(server, "personaldifficulty admin " + command, as(asOp, LevelBasedPermissionSet.GAMEMASTER, cap), cap);
		out.append("> ").append(asOp == null ? "" : "[op] ").append(command).append('\n');
		out.append("  result=").append(o.result()).append(" error=").append(o.error()).append('\n');
		for (String line : o.output().split("\n", -1)) {
			out.append("  | ").append(normalise(line)).append('\n');
		}
	}

	private static void cleanUp() {
		try {
			rt().availability().enableAll();
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	@GameTest
	public void transcript_everyAdministrativeCommandBehavesExactlyAsRecorded(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer a = placeNewPlayer(h, A, "pd_gold_a");
		ServerPlayer b = placeNewPlayer(h, B, "pd_gold_b");
		ServerPlayer c = placeNewPlayer(h, C, "pd_gold_c");
		ServerPlayer d = placeNewPlayer(h, D, "pd_gold_d");
		ServerPlayer op = placeNewPlayer(h, OP, "pd_gold_op");
		StringBuilder out = new StringBuilder();
		Path file = playersFile(server);
		String original = Files.exists(file) ? readFile(file) : null;
		try {
			JsonObject start = original == null ? JsonParser.parseString("{\"version\":2,\"players\":{}}").getAsJsonObject() : JsonParser.parseString(original).getAsJsonObject();
			for (UUID id : List.of(A, B, C, D, OP)) {
				start.getAsJsonObject("players").remove(id.toString());
			}
			writeFile(file, start.toString());
			rt().settings().load();
			cleanUp();
			// status of nobody-special, assign / lock / unlock including the "nothing changed" answers
			for (String cmd : List.of(
				"status pd_gold_a", "assign pd_gold_a hard", "assign pd_gold_a hard", "assign pd_gold_a elite lock", "status pd_gold_a",
				"lock pd_gold_a", "unlock pd_gold_a", "unlock pd_gold_a", "lock pd_gold_a", "assign pd_gold_a normal", "unlock pd_gold_a",
				"assign pd_gold_a peaceful", "assign pd_gold_a bogus", "assign pd_gold_a Hard", "status pd_gold_a")) {
				step(out, server, cmd, null);
			}
			step(out, server, "assign pd_gold_b easy lock", op);
			step(out, server, "status pd_gold_b", null);
			// restrict: refused until a choice is made, switch, keep, already, empty and malformed lists, unrestrict
			for (String cmd : List.of(
				"restrict pd_gold_a normal,easy", "restrict pd_gold_a normal,easy switch", "status pd_gold_a", "restrict pd_gold_a normal,easy",
				"restrict pd_gold_c hard,elite keep", "status pd_gold_c", "restrict pd_gold_c hard,elite", "restrict pd_gold_c hard", "restrict pd_gold_c hard,bogus",
				"restrict pd_gold_c ,", "restrict pd_gold_c hard switch extra", "restrict pd_gold_c hard nonsense", "restrict pd_gold_c elite,hard,hard",
				"unrestrict pd_gold_c", "unrestrict pd_gold_c", "status pd_gold_c", "restrict pd_gold_a hard,elite keep", "unrestrict pd_gold_a", "status pd_gold_a")) {
				step(out, server, cmd, null);
			}
			// reset: nothing stored, stored with lock and allow-list
			for (String cmd : List.of("reset pd_gold_d", "assign pd_gold_d hard lock", "restrict pd_gold_d hard,elite", "reset pd_gold_d", "reset pd_gold_d", "status pd_gold_d")) {
				step(out, server, cmd, null);
			}
			// the server-wide policy: list, disable, repeat, last-one refusal, enable, enable all
			for (String cmd : List.of(
				"difficulties", "disable hard", "disable hard", "difficulties", "disable easy", "disable elite", "disable peaceful", "disable normal", "difficulties",
				"enable hard", "enable hard", "enable bogus", "disable bogus", "enable all", "enable all", "difficulties")) {
				step(out, server, cmd, null);
			}
			// an invalid stored record: every change is refused, the explicit repair works
			JsonObject root = original == null ? JsonParser.parseString("{\"version\":2,\"players\":{}}").getAsJsonObject() : JsonParser.parseString(original).getAsJsonObject();
			root.getAsJsonObject("players").add(C.toString(), JsonParser.parseString("{\"mode\":\"ELITE\",\"locked\":\"yes\",\"nickname\":\"zed\"}"));
			writeFile(file, root.toString());
			rt().settings().load();
			for (String cmd : List.of(
				"status pd_gold_c", "assign pd_gold_c normal", "lock pd_gold_c", "unlock pd_gold_c", "restrict pd_gold_c easy", "restrict pd_gold_c easy keep", "unrestrict pd_gold_c",
				"reset pd_gold_c", "reset pd_gold_c", "status pd_gold_c")) {
				step(out, server, cmd, null);
			}
		} finally {
			try {
				if (original == null) {
					Files.deleteIfExists(file);
				} else {
					writeFile(file, original);
				}
			} catch (IOException e) {
				throw new AssertionError(e);
			}
			rt().settings().load();
			cleanUp();
			for (ServerPlayer gone : List.of(a, b, c, d, op)) {
				leave(gone);
			}
		}
		String actual = out.toString();
		try {
			Path report = Path.of("build/reports/gametest/admin-transcript.actual.txt");
			Files.createDirectories(report.getParent());
			Files.writeString(report, actual, StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// only a convenience for reviewing a difference
		}
		String golden;
		try (InputStream in = AdminTranscriptGameTests.class.getResourceAsStream(GOLDEN)) {
			check(in != null, "the golden transcript resource " + GOLDEN + " is missing");
			// a Windows checkout may turn the resource's line endings into CRLF; the transcript itself is always built with \n
			golden = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		check(golden.equals(actual), "the administrative command transcript differs from the golden one; see build/reports/gametest/admin-transcript.actual.txt");
		h.succeed();
	}

	private static String readFile(Path p) {
		try {
			return Files.readString(p);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void writeFile(Path p, String s) {
		try {
			Files.createDirectories(p.getParent());
			Files.writeString(p, s);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}
}
