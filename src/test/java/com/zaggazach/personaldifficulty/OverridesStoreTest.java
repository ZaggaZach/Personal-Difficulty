package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.zaggazach.personaldifficulty.config.OverridesLayer;
import com.zaggazach.personaldifficulty.config.OverridesStore;
import com.zaggazach.personaldifficulty.config.OverridesStore.Change;
import com.zaggazach.personaldifficulty.config.OverridesStore.LoadState;
import com.zaggazach.personaldifficulty.config.OverridesStore.Put;
import com.zaggazach.personaldifficulty.config.OverridesStore.Remove;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The overrides file: format, validation, unknown and invalid entries, corrupt/unsupported/changed-behind-our-back handling,
 * verified backups with pruning, evidence copies, failed writes and the explicit recovery path. Persistence first.
 */
class OverridesStoreTest {
	private static final String PVP = "pvp.damage-multiplier";
	private static final String SCAN = "elite.priority.scan-interval-ticks";
	private static final String FLAG = "elite.effects.enabled";
	private static final String FALL = "easy.damage-type.minecraft.fall";

	/** Real I/O with switchable faults at each step. */
	static final class FaultyIo implements OverridesStore.Io {
		final AtomicBoolean failCopy = new AtomicBoolean();
		final AtomicBoolean failWrite = new AtomicBoolean();
		final AtomicBoolean failBeforeMove = new AtomicBoolean();
		final AtomicBoolean shortCopy = new AtomicBoolean();
		int writes;

		@Override
		public void copy(Path from, Path to) throws IOException {
			if (failCopy.get()) {
				throw new IOException("injected copy failure");
			}
			if (shortCopy.get()) {
				Files.writeString(to, "{");
				return;
			}
			OverridesStore.Io.REAL.copy(from, to);
		}

		@Override
		public void writeAtomically(Path target, byte[] data) throws IOException {
			if (failWrite.get()) {
				throw new IOException("injected write failure");
			}
			if (failBeforeMove.get()) {
				Files.write(target.resolveSibling(target.getFileName() + ".tmp"), data);
				throw new IOException("injected failure before the atomic move");
			}
			writes++;
			OverridesStore.Io.REAL.writeAtomically(target, data);
		}
	}

	private static OverridesStore store(Path file, FaultyIo io) {
		AtomicLong clock = new AtomicLong(1000);
		return new OverridesStore(file, io, clock::incrementAndGet, id -> true);
	}

	private static OverridesStore store(Path file, FaultyIo io, Predicate<String> exists) {
		AtomicLong clock = new AtomicLong(1000);
		return new OverridesStore(file, io, clock::incrementAndGet, exists);
	}

	private static OverridesStore loaded(Path file) {
		OverridesStore s = store(file, new FaultyIo());
		s.load();
		return s;
	}

	private static void apply(OverridesStore s, Change c) throws IOException {
		s.commit(s.prepare(c));
	}

	private static Put put(String key, Object value) {
		JsonElement e = value instanceof Boolean b ? new JsonPrimitive(b) : value instanceof Integer i ? new JsonPrimitive(i) : new JsonPrimitive((Double) value);
		return new Put(key, e);
	}

	private static List<Path> withInfix(Path dir, String infix) throws IOException {
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(p -> p.getFileName().toString().contains(infix)).sorted().toList();
		}
	}

	private static List<Path> backups(Path dir) throws IOException {
		return withInfix(dir, ".backup-");
	}

	private static List<Path> evidence(Path dir) throws IOException {
		return withInfix(dir, ".evidence-");
	}

	// ------------------------------------------------------------------ format and round trip

	@Test
	void noFileUntilTheFirstRealChange_andANoOpWritesNothing(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("o.json");
		OverridesStore s = loaded(f);
		assertEquals(LoadState.NOT_PRESENT, s.state());
		assertFalse(Files.exists(f));
		OverridesStore.Prepared p = s.prepare(new Remove(PVP));
		assertFalse(p.changesFile());
		s.commit(p);
		assertFalse(Files.exists(f), "removing something that was never there writes no file");
		assertTrue(backups(dir).isEmpty());
	}

	@Test
	void theFileHasTheApprovedShapeSortedAndEveryTypeRoundTripsExactly(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("o.json");
		OverridesStore s = loaded(f);
		apply(s, put(PVP, 0.30000000000000004));
		apply(s, put(SCAN, 12));
		apply(s, put(FLAG, false));
		apply(s, put(FALL, 0.1));
		assertEquals("{\"version\":1,\"values\":{\"easy.damage-type.minecraft.fall\":0.1,\"elite.effects.enabled\":false,\"elite.priority.scan-interval-ticks\":12,\"pvp.damage-multiplier\":0.30000000000000004}}",
			Files.readString(f));
		OverridesStore again = loaded(f);
		assertEquals(LoadState.OK, again.state());
		assertEquals(s.layer(), again.layer(), "a fresh instance (= restart) sees exactly the same overrides");
		assertEquals(0.30000000000000004, again.layer().scalars().get(PVP));
		assertEquals(12, again.layer().scalars().get(SCAN));
		assertEquals(false, again.layer().scalars().get(FLAG));
		assertEquals(Map.of("easy", Map.of("minecraft:fall", 0.1)), again.layer().family());
		assertTrue(again.loadWarnings().isEmpty());
	}

	// ------------------------------------------------------------------ classification table

	@Test
	void everyFileConditionHasItsOwnStateAndOnlyTheSafeOnesAreWritable(@TempDir Path dir) throws IOException {
		record Case(String name, String content, LoadState state, boolean writable, boolean evidence) {
		}
		List<Case> cases = List.of(
			new Case("valid", "{\"version\":1,\"values\":{}}", LoadState.OK, true, false),
			new Case("blank", "  \n ", LoadState.EMPTY_FILE, true, false),
			new Case("garbage", "{ nope", LoadState.CORRUPT, false, true),
			new Case("truncated", "{\"version\":1,\"values\":{\"pvp.damage-mult", LoadState.CORRUPT, false, true),
			new Case("array", "[]", LoadState.CORRUPT, false, true),
			new Case("string", "\"x\"", LoadState.CORRUPT, false, true),
			new Case("no version", "{\"values\":{}}", LoadState.CORRUPT, false, true),
			new Case("v1 without values", "{\"version\":1}", LoadState.CORRUPT, false, true),
			new Case("v1 values is an array", "{\"version\":1,\"values\":[]}", LoadState.CORRUPT, false, true),
			new Case("duplicate key in values", "{\"version\":1,\"values\":{\"pvp.damage-multiplier\":1,\"pvp.damage-multiplier\":2}}", LoadState.CORRUPT, false, true),
			new Case("duplicate top-level key", "{\"version\":1,\"values\":{},\"version\":1}", LoadState.CORRUPT, false, true),
			new Case("duplicate in nested", "{\"version\":1,\"values\":{},\"x\":{\"a\":1,\"a\":1}}", LoadState.CORRUPT, false, true),
			new Case("duplicate in array object", "{\"version\":1,\"values\":{},\"x\":[{\"a\":1,\"a\":2}]}", LoadState.CORRUPT, false, true),
			new Case("future version", "{\"version\":2,\"values\":{}}", LoadState.UNSUPPORTED_VERSION, false, true),
			new Case("future version restructured", "{\"version\":2,\"settings\":[]}", LoadState.UNSUPPORTED_VERSION, false, true),
			new Case("future huge", "{\"version\":2147483647}", LoadState.UNSUPPORTED_VERSION, false, true),
			new Case("fractional version", "{\"version\":1.5,\"values\":{}}", LoadState.UNSUPPORTED_VERSION, false, true),
			new Case("string version", "{\"version\":\"1\",\"values\":{}}", LoadState.UNSUPPORTED_VERSION, false, true),
			new Case("null version", "{\"version\":null,\"values\":{}}", LoadState.UNSUPPORTED_VERSION, false, true),
			new Case("zero version", "{\"version\":0,\"values\":{}}", LoadState.UNSUPPORTED_VERSION, false, true),
			new Case("same key name in different objects is fine", "{\"version\":1,\"values\":{},\"x\":{\"a\":1},\"y\":{\"a\":2}}", LoadState.OK, true, false)
		);
		int n = 0;
		for (Case c : cases) {
			Path d = Files.createDirectory(dir.resolve("c" + n++));
			Path f = d.resolve("o.json");
			Files.writeString(f, c.content());
			OverridesStore s = loaded(f);
			assertEquals(c.state(), s.state(), c.name());
			assertEquals(c.writable(), s.blockedReason() == null, c.name());
			assertEquals(c.evidence() ? 1 : 0, evidence(d).size(), c.name());
			assertEquals(0, backups(d).size(), "no ordinary backup at load: " + c.name());
			assertEquals(c.content(), Files.readString(f), "load never touches the file: " + c.name());
			if (c.evidence()) {
				assertEquals(c.content(), Files.readString(evidence(d).get(0)), "the evidence copy is the original: " + c.name());
			}
			if (!c.writable()) {
				assertEquals(OverridesLayer.EMPTY.scalars(), s.layer().scalars(), "nothing is applied from an unusable file: " + c.name());
				IOException e = assertThrows(IOException.class, () -> s.prepare(put(PVP, 2.0)), c.name());
				assertTrue(e.getMessage().contains("admin reload"), "tells the administrator how to recover: " + e.getMessage());
				assertFalse(s.loadWarnings().isEmpty(), "reported: " + c.name());
				assertEquals(c.content(), Files.readString(f), "still untouched: " + c.name());
			}
		}
	}

	@Test
	void aDirectoryAtThePathIsReportedAsSuchAndNothingIsClaimedBackedUp(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("o.json");
		Files.createDirectory(f);
		OverridesStore s = loaded(f);
		assertEquals(LoadState.NOT_A_FILE, s.state());
		assertTrue(s.blockedReason().contains("is a directory"));
		assertTrue(s.loadWarnings().get(0).contains("nothing was backed up"));
		assertTrue(evidence(dir).isEmpty() && backups(dir).isEmpty());
		assertThrows(IOException.class, () -> s.prepare(put(PVP, 2.0)));
		Files.delete(f);
		assertEquals(OverridesStore.ReloadOutcome.ADOPTED, s.reload());
		apply(s, put(PVP, 2.0));
		assertEquals(2.0, loaded(f).layer().scalars().get(PVP));
	}

	@Test
	void everyTruncationPointOfAValidFileIsClassifiedWithoutAnExceptionAndNeverWritable(@TempDir Path dir) throws IOException {
		String valid = "{\"version\":1,\"values\":{\"easy.damage-type.minecraft.fall\":0.1,\"pvp.damage-multiplier\":2.5}}";
		int blocked = 0;
		for (int cut = 0; cut < valid.length(); cut++) {
			Path d = Files.createDirectory(dir.resolve("t" + cut));
			Path f = d.resolve("o.json");
			Files.writeString(f, valid.substring(0, cut));
			OverridesStore s = loaded(f);
			assertEquals(valid.substring(0, cut), Files.readString(f));
			if (cut == 0) {
				assertEquals(LoadState.EMPTY_FILE, s.state());
			} else {
				assertNotNull(s.blockedReason(), "cut " + cut);
				blocked++;
			}
		}
		assertEquals(valid.length() - 1, blocked);
	}

	// ------------------------------------------------------------------ unknown keys, invalid values, not-applied entries (E11, E12)

	@Test
	void unknownInvalidAndNotAppliedEntriesAreIgnoredReportedAndKeptVerbatim(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("o.json");
		String content = "{\"version\":1,\"note\":{\"by\":\"someone\"},\"values\":{"
			+ "\"" + PVP + "\":2.5,"
			+ "\"elite.damage-multiplier\":\"loud\","
			+ "\"" + SCAN + "\":12.5,"
			+ "\"future.setting\":{\"a\":[1,2]},"
			+ "\"" + FALL + "\":0.4,"
			+ "\"hard.damage-type.mod.gone\":0.5,"
			+ "\"elite.damage-type.minecraft.explosion\":\"x\","
			+ "\"normal.damage-type.minecraft.fall\":1.0,"
			+ "\"easy.damage-type.minecraft.my/type\":1.0}}";
		Files.writeString(f, content);
		OverridesStore s = store(f, new FaultyIo(), id -> !id.equals("mod:gone"));
		s.load();
		assertEquals(LoadState.OK, s.state());
		assertEquals(Map.of(PVP, 2.5), s.layer().scalars(), "only the valid scalar is active; the invalid ones fall back to the lower layer");
		assertEquals(Map.of("easy", Map.of("minecraft:fall", 0.4)), s.layer().family(), "only the valid, existing damage type is active");
		assertEquals(7, s.loadWarnings().size(), s.loadWarnings().toString());
		assertEquals(content, Files.readString(f), "loading never rewrites the file");
		assertTrue(backups(dir).isEmpty() && evidence(dir).isEmpty());

		// an unrelated change keeps every unusable entry and the unknown top-level field byte for byte
		apply(s, put(FLAG, false));
		JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
		JsonObject values = root.getAsJsonObject("values");
		assertEquals("loud", values.get("elite.damage-multiplier").getAsString());
		assertEquals(12.5, values.get(SCAN).getAsDouble());
		assertEquals("[1,2]", values.getAsJsonObject("future.setting").get("a").toString());
		assertEquals(0.5, values.get("hard.damage-type.mod.gone").getAsDouble());
		assertEquals("x", values.get("elite.damage-type.minecraft.explosion").getAsString());
		assertEquals(1.0, values.get("normal.damage-type.minecraft.fall").getAsDouble());
		assertEquals(1.0, values.get("easy.damage-type.minecraft.my/type").getAsDouble());
		assertEquals("someone", root.getAsJsonObject("note").get("by").getAsString());
		assertEquals(false, values.get(FLAG).getAsBoolean());
		assertEquals(2.5, values.get(PVP).getAsDouble());
		assertEquals(10, values.size());

		// set/unset of a known key preserves the unrelated unknown entries
		apply(s, new Remove(PVP));
		apply(s, put("elite.armor-effectiveness", 0.5));
		JsonObject after = JsonParser.parseString(Files.readString(f)).getAsJsonObject().getAsJsonObject("values");
		assertFalse(after.has(PVP));
		assertEquals("loud", after.get("elite.damage-multiplier").getAsString());
		assertEquals("[1,2]", after.getAsJsonObject("future.setting").get("a").toString());
		assertEquals(10, after.size());
	}

	@Test
	void theDamageTypeRegistryIsConsultedOnlyForSyntacticallyValidEntries(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("o.json");
		Files.writeString(f, "{\"version\":1,\"values\":{\"easy.damage-type.minecraft.fall\":0.4,\"hard.damage-type.mod.gone\":0.5,\"elite.damage-type.mod.bad/slash\":0.5}}");
		java.util.List<String> asked = new java.util.ArrayList<>();
		OverridesStore s = store(f, new FaultyIo(), id -> {
			asked.add(id);
			return id.startsWith("minecraft:");
		});
		s.load();
		assertEquals(List.of("minecraft:fall", "mod:gone"), asked.stream().sorted().toList());
		assertEquals(Map.of("easy", Map.of("minecraft:fall", 0.4)), s.layer().family());
	}

	// ------------------------------------------------------------------ E27: invalid override recovery

	@Test
	void anInvalidValueIsPreservedIgnoredAndReported_otherOverridesStayActive_andAnExplicitSetReplacesItAfterAVerifiedBackup(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("o.json");
		String content = "{\"version\":1,\"values\":{\"" + PVP + "\":\"banana\",\"" + SCAN + "\":20}}";
		Files.writeString(f, content);
		FaultyIo io = new FaultyIo();
		OverridesStore s = store(f, io);
		s.load();
		// 1 + 2: preserved on disk, ignored and reported; the unrelated valid override is active
		assertEquals(content, Files.readString(f));
		assertFalse(s.layer().scalars().containsKey(PVP));
		assertEquals(20, s.layer().scalars().get(SCAN));
		assertTrue(s.loadWarnings().stream().anyMatch(w -> w.contains(PVP) && w.contains("banana")), s.loadWarnings().toString());

		// 5 (first half): a failing write leaves the invalid entry and the in-memory state exactly as they were
		io.failWrite.set(true);
		OverridesStore.Prepared p = s.prepare(put(PVP, 3.0));
		assertEquals("banana", p.replaced().getAsString(), "the entry being replaced is reported so the administrator can be told");
		assertThrows(IOException.class, () -> s.commit(p));
		assertEquals(content, Files.readString(f));
		assertFalse(s.layer().scalars().containsKey(PVP), "the new value is NOT active when the write failed");
		assertEquals("banana", s.rawEntry(PVP).orElseThrow().getAsString());
		io.failWrite.set(false);

		// 3 + 4 + 5: the explicit valid set replaces it; the previous file is verified in a backup; only now is the value active
		apply(s, put(PVP, 3.0));
		assertEquals(3.0, s.layer().scalars().get(PVP));
		assertEquals(20, s.layer().scalars().get(SCAN));
		List<Path> b = backups(dir);
		assertEquals(1, b.size());
		assertEquals(content, Files.readString(b.get(0)), "the backup is the file as it was, with the invalid value");
		assertEquals(3.0, JsonParser.parseString(Files.readString(f)).getAsJsonObject().getAsJsonObject("values").get(PVP).getAsDouble());
		assertTrue(loaded(f).loadWarnings().isEmpty(), "the replaced entry no longer produces a warning");
	}

	// ------------------------------------------------------------------ set / remove semantics

	@Test
	void removeTouchesOnlyThatEntry_andRemovingAnUnusableEntryIsAnExplicitAct(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("o.json");
		Files.writeString(f, "{\"version\":1,\"values\":{\"" + PVP + "\":2.0,\"" + FLAG + "\":false,\"unknown.key\":1,\"self.damage-multiplier\":\"bad\"}}");
		OverridesStore s = loaded(f);
		apply(s, new Remove(PVP));
		JsonObject values = JsonParser.parseString(Files.readString(f)).getAsJsonObject().getAsJsonObject("values");
		assertEquals(3, values.size());
		assertTrue(values.has(FLAG) && values.has("unknown.key") && values.has("self.damage-multiplier"));
		OverridesStore.Prepared p = s.prepare(new Remove("self.damage-multiplier"));
		assertTrue(p.changesFile());
		assertEquals("bad", p.replaced().getAsString());
		s.commit(p);
		assertFalse(JsonParser.parseString(Files.readString(f)).getAsJsonObject().getAsJsonObject("values").has("self.damage-multiplier"));
		assertFalse(s.prepare(new Remove(PVP)).changesFile(), "removing a missing entry is a no-op");
		assertFalse(s.prepare(put(FLAG, false)).changesFile(), "putting the identical stored value is a no-op");
	}

	@Test
	void aPreparedChangeIsRefusedIfTheStoreChangedInBetween(@TempDir Path dir) throws IOException {
		OverridesStore s = loaded(dir.resolve("o.json"));
		OverridesStore.Prepared stale = s.prepare(put(PVP, 2.0));
		apply(s, put(FLAG, false));
		assertThrows(IllegalStateException.class, () -> s.commit(stale));
	}

	@Test
	void aPreparedChangeIsNeverWrittenOverAFileThatBecameCorruptAndWasReloadedInBetween(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("o.json");
		OverridesStore s = loaded(file);
		apply(s, put(FLAG, false));
		OverridesStore.Prepared prepared = s.prepare(put(PVP, 2.0));
		Files.writeString(file, "{ not json");
		s.reload(); // refused: the last-good data stays, but changes are blocked
		assertThrows(IOException.class, () -> s.commit(prepared));
		assertEquals("{ not json", Files.readString(file), "the corrupt file is never overwritten");
	}

	// ------------------------------------------------------------------ failed writes (E13, E17)

	@Test
	void everyFailureLeavesTheFileAndTheStoreUnchanged_andTheNextAttemptWorks(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("o.json");
		FaultyIo io = new FaultyIo();
		OverridesStore s = store(f, io);
		s.load();
		apply(s, put(PVP, 2.0));
		String before = Files.readString(f);
		OverridesLayer layerBefore = s.layer();

		io.failCopy.set(true);
		assertThrows(IOException.class, () -> apply(s, put(FLAG, false)));
		io.failCopy.set(false);
		assertEquals(before, Files.readString(f));
		assertSame(layerBefore, s.layer());
		assertTrue(backups(dir).isEmpty(), "the very first file had no predecessor, and the failed attempt left no stray copy");

		io.failWrite.set(true);
		assertThrows(IOException.class, () -> apply(s, put(FLAG, false)));
		io.failWrite.set(false);
		assertEquals(before, Files.readString(f));
		assertSame(layerBefore, s.layer());

		io.failBeforeMove.set(true);
		assertThrows(IOException.class, () -> apply(s, put(FLAG, false)));
		io.failBeforeMove.set(false);
		assertEquals(before, Files.readString(f), "readers only ever see the complete old or the complete new file");
		assertSame(layerBefore, s.layer());
		assertTrue(Files.exists(dir.resolve("o.json.tmp")), "leftover temp file from the simulated crash");

		apply(s, put(FLAG, false)); // the tmp leftover does not get in the way
		assertEquals(false, loaded(f).layer().scalars().get(FLAG));
	}

	@Test
	void anUnwritableLocationRefusesTheChangeAndKeepsTheState(@TempDir Path dir) throws IOException {
		Path blocker = dir.resolve("blocker");
		Files.writeString(blocker, "a file, not a directory");
		OverridesStore s = loaded(blocker.resolve("o.json"));
		OverridesLayer before = s.layer();
		assertThrows(IOException.class, () -> apply(s, put(PVP, 2.0)));
		assertSame(before, s.layer());
		assertTrue(s.layer().scalars().isEmpty());
	}

	// ------------------------------------------------------------------ backups (E14)

	@Test
	void ordinaryBackupsAreVerifiedAndOnlyTheNewestFiveSurvive_evidenceIsNeverPruned(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("o.json");
		// an evidence copy first: a corrupt file that the administrator then repairs
		Files.writeString(f, "{ corrupt");
		OverridesStore s = loaded(f);
		assertEquals(1, evidence(dir).size());
		Files.writeString(f, "{\"version\":1,\"values\":{}}");
		assertEquals(OverridesStore.ReloadOutcome.ADOPTED, s.reload());
		List<String> previous = new java.util.ArrayList<>();
		for (int i = 1; i <= 9; i++) {
			previous.add(Files.readString(f));
			apply(s, put(PVP, (double) i));
			List<Path> b = backups(dir);
			assertTrue(b.size() <= OverridesStore.MAX_ORDINARY_BACKUPS, "at most five ordinary backups after change " + i + ": " + b.size());
			assertTrue(b.stream().anyMatch(p -> readQuietly(p).equals(previous.get(previous.size() - 1))), "the newest backup is the file as it was before change " + i);
		}
		assertEquals(5, backups(dir).size());
		assertEquals(1, evidence(dir).size(), "evidence copies are never pruned");
		assertEquals("{ corrupt", Files.readString(evidence(dir).get(0)));
		// the five kept are the five most recent previous states
		Set<String> kept = new java.util.HashSet<>();
		for (Path p : backups(dir)) {
			kept.add(readQuietly(p));
		}
		assertEquals(new java.util.HashSet<>(previous.subList(4, 9)), kept);
	}

	private static String readQuietly(Path p) {
		try {
			return Files.readString(p);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void aBackupThatDoesNotMatchTheOriginalIsDetectedDeletedAndTheChangeRefused(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("o.json");
		FaultyIo io = new FaultyIo();
		OverridesStore s = store(f, io);
		s.load();
		apply(s, put(PVP, 2.0));
		String before = Files.readString(f);
		io.shortCopy.set(true);
		IOException e = assertThrows(IOException.class, () -> apply(s, put(PVP, 3.0)));
		assertTrue(e.getMessage().contains("does not match the original"), e.getMessage());
		assertTrue(backups(dir).isEmpty(), "the bad copy was removed");
		assertEquals(before, Files.readString(f));
		assertEquals(2.0, s.layer().scalars().get(PVP));
	}

	@Test
	void evidenceIsReusedWhenIdenticalAndNeverDuplicatedByRepeatedReloadsOrRestarts(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("o.json");
		Files.writeString(f, "{ corrupt");
		OverridesStore s = loaded(f);
		for (int i = 0; i < 3; i++) {
			assertEquals(OverridesStore.ReloadOutcome.KEPT_LAST_GOOD, s.reload());
			loaded(f);
		}
		assertEquals(1, evidence(dir).size());
		Files.writeString(f, "{ corrupt differently");
		s.reload();
		assertEquals(2, evidence(dir).size(), "different content is different evidence");
	}

	// ------------------------------------------------------------------ external modification (E16)

	@Test
	void aFileEditedBehindOurBackIsNeverOverwrittenButCanBeAdoptedOnPurpose(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("o.json");
		OverridesStore s = loaded(f);
		apply(s, put(PVP, 2.0));
		apply(s, put(PVP, 2.5)); // our own writes are never "external"
		String external = "{\"version\":1,\"values\":{\"" + PVP + "\":4.0,\"" + FLAG + "\":false}}";
		Files.writeString(f, external);

		OverridesLayer layerBefore = s.layer();
		IOException e = assertThrows(IOException.class, () -> apply(s, put(SCAN, 9)));
		assertTrue(e.getMessage().contains("changed outside the mod"), e.getMessage());
		assertEquals(LoadState.EXTERNALLY_CHANGED, s.state());
		assertEquals(external, Files.readString(f), "the edited file is untouched");
		assertSame(layerBefore, s.layer(), "the active overrides are unchanged until the administrator reloads");
		assertEquals(1, evidence(dir).size());
		assertEquals(external, Files.readString(evidence(dir).get(0)));
		assertThrows(IOException.class, () -> apply(s, put(SCAN, 9)), "still refusing");
		assertEquals(1, evidence(dir).size(), "no pile-up on repeated attempts");

		assertEquals(OverridesStore.ReloadOutcome.ADOPTED, s.reload());
		assertEquals(4.0, s.layer().scalars().get(PVP));
		assertEquals(false, s.layer().scalars().get(FLAG));
		apply(s, put(SCAN, 9));
		assertEquals(9, loaded(f).layer().scalars().get(SCAN));
		assertEquals(4.0, loaded(f).layer().scalars().get(PVP), "the externally added entries survived");
	}

	@Test
	void rewritingTheFileWithIdenticalContentIsNotAnExternalChange_aDeletedFileIsRecreated_aNewFileIsRefused(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("o.json");
		OverridesStore s = loaded(f);
		apply(s, put(PVP, 2.0));
		Files.writeString(f, Files.readString(f));
		apply(s, put(FLAG, false));

		Files.delete(f);
		apply(s, put(SCAN, 9)); // nothing is destroyed by recreating it from memory
		OverridesStore r = loaded(f);
		assertEquals(Map.of(PVP, 2.0, FLAG, false, SCAN, 9), r.layer().scalars());

		Path g = dir.resolve("new.json");
		OverridesStore t = loaded(g);
		Files.writeString(g, "{\"version\":1,\"values\":{\"" + PVP + "\":5.0}}");
		assertThrows(IOException.class, () -> apply(t, put(FLAG, true)), "a file that appeared behind our back is not overwritten");
		assertEquals("{\"version\":1,\"values\":{\"" + PVP + "\":5.0}}", Files.readString(g));
	}

	// ------------------------------------------------------------------ reload semantics (E18, D-d)

	@Test
	void aFailedReloadKeepsTheLastGoodOverridesAndBlocksWrites_aFixedFileRecovers(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("o.json");
		OverridesStore s = loaded(f);
		apply(s, put(PVP, 2.0));
		apply(s, put(FALL, 0.4));
		OverridesLayer good = s.layer();
		String goodText = Files.readString(f);

		Files.writeString(f, "{ truncated by something");
		assertEquals(OverridesStore.ReloadOutcome.KEPT_LAST_GOOD, s.reload());
		assertSame(good, s.layer(), "the running server keeps the overrides it had");
		assertNotNull(s.blockedReason());
		assertEquals(LoadState.CORRUPT, s.state());
		assertEquals("{ truncated by something", Files.readString(f), "the bad file is preserved as found");
		assertEquals("{ truncated by something", Files.readString(evidence(dir).get(0)));
		assertThrows(IOException.class, () -> apply(s, put(FLAG, false)));
		assertEquals("{ truncated by something", Files.readString(f), "no ordinary change can overwrite it");

		Files.writeString(f, goodText);
		assertEquals(OverridesStore.ReloadOutcome.ADOPTED, s.reload());
		assertNull(s.blockedReason());
		assertEquals(good, s.layer());
		apply(s, put(FLAG, false));
	}

	@Test
	void reloadAlwaysRereadsAHealthyFile_soADeliberateHandEditIsPickedUp(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("o.json");
		OverridesStore s = loaded(f);
		apply(s, put(PVP, 2.0));
		Files.writeString(f, "{\"version\":1,\"values\":{\"" + PVP + "\":7.0}}");
		assertEquals(OverridesStore.ReloadOutcome.ADOPTED, s.reload());
		assertEquals(7.0, s.layer().scalars().get(PVP));
		Files.delete(f);
		assertEquals(OverridesStore.ReloadOutcome.ADOPTED, s.reload());
		assertTrue(s.layer().scalars().isEmpty(), "a deleted file means no overrides");
		assertEquals(LoadState.NOT_PRESENT, s.state());
	}

	// ------------------------------------------------------------------ warning dedupe (D6)

	@Test
	void warningsAreReportedOncePerLoadAndAgainOnALaterLoad_neverAccumulated(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("o.json");
		Files.writeString(f, "{\"version\":1,\"values\":{\"" + PVP + "\":\"bad\",\"mystery\":1}}");
		OverridesStore s = loaded(f);
		assertEquals(2, s.loadWarnings().size());
		assertEquals(s.loadWarnings().size(), new java.util.HashSet<>(s.loadWarnings()).size(), "no duplicates within one load");
		s.reload();
		assertEquals(2, s.loadWarnings().size(), "a later reload reports the problems again, replacing the old report");
		s.reload();
		assertEquals(2, s.loadWarnings().size());
	}
}
