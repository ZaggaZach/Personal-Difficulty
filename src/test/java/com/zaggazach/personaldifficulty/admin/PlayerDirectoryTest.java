package com.zaggazach.personaldifficulty.admin;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.admin.PlayerDirectory.Entry;
import com.zaggazach.personaldifficulty.store.PlayerRecord;
import com.zaggazach.personaldifficulty.store.PlayerSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.players.NameAndId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** D1: offline player discovery merges settings, online players and usercache.json; deduplicated, sorted, read-only, never fatal. */
class PlayerDirectoryTest {
	private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
	private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
	private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");

	private static String cache(String... entries) {
		return "[" + String.join(",", entries) + "]";
	}

	private static String entry(UUID id, String name) {
		return "{\"name\":\"" + name + "\",\"uuid\":\"" + id + "\",\"expiresOn\":\"2099-01-01 00:00:00 +0000\"}";
	}

	private static List<Entry> collect(Set<UUID> settings, List<NameAndId> online, String cacheJson, Map<UUID, String> resolver) {
		return PlayerDirectory.collect(settings, online, cacheJson, id -> Optional.ofNullable(resolver.get(id)).map(n -> new NameAndId(id, n)));
	}

	@Test
	void mergesAllThreeSourcesDeduplicatesByUuidAndSortsByNameThenUuid() {
		List<Entry> out = collect(Set.of(A, B), List.of(new NameAndId(C, "carol")), cache(entry(B, "Bob"), entry(D, "dave"), entry(A, "alice")), Map.of());
		assertEquals(List.of("alice", "Bob", "carol", "dave"), out.stream().map(Entry::label).toList(), "case-insensitive name order");
		assertEquals(4, out.size(), "each UUID once");
		assertTrue(out.stream().filter(e -> e.id().equals(C)).findFirst().orElseThrow().online());
		assertFalse(out.stream().filter(e -> e.id().equals(A)).findFirst().orElseThrow().online());
		// the same input in another order gives the same list
		assertEquals(out, collect(Set.of(B, A), List.of(new NameAndId(C, "carol")), cache(entry(A, "alice"), entry(D, "dave"), entry(B, "Bob")), Map.of()));
	}

	@Test
	void sameNameIsOrderedByUuidSoTheListIsStable() {
		List<Entry> out = collect(Set.of(B, A), List.of(), null, Map.of(A, "same", B, "same"));
		assertEquals(List.of(A, B), out.stream().map(Entry::id).toList());
	}

	@Test
	void anOnlineNameBeatsTheResolverAndTheResolverBeatsTheCache() {
		List<Entry> out = collect(Set.of(A, B), List.of(new NameAndId(A, "Live")), cache(entry(A, "Cached"), entry(B, "CachedB")), Map.of(A, "Resolved", B, "ResolvedB"));
		assertEquals("Live", out.stream().filter(e -> e.id().equals(A)).findFirst().orElseThrow().name());
		assertEquals("ResolvedB", out.stream().filter(e -> e.id().equals(B)).findFirst().orElseThrow().name());
	}

	@Test
	void aPlayerWithNoKnownNameIsShownByUuid() {
		List<Entry> out = collect(Set.of(A), List.of(), null, Map.of());
		assertEquals(1, out.size());
		assertNull(out.get(0).name());
		assertEquals(A.toString(), out.get(0).label());
		assertEquals(new NameAndId(A, A.toString()), out.get(0).nameAndId());
	}

	@Test
	void missingEmptyAndGarbageUserCachesDegradeToTheOtherSources() {
		for (String bad : new String[] {null, "", "   ", "not json", "{", "{\"a\":1}", "\"x\"", "42", "null", "[1,2,3]", "[null]", "[[]]", "[{}]", "[{\"name\":1,\"uuid\":2}]"}) {
			List<Entry> out = collect(Set.of(A), List.of(new NameAndId(B, "bob")), bad, Map.of(A, "alice"));
			assertEquals(List.of("alice", "bob"), out.stream().map(Entry::label).toList(), "cache: " + bad);
		}
	}

	@Test
	void oneMalformedEntryDoesNotHideTheOthers() {
		String json = cache(entry(A, "alice"), "{\"name\":\"x\",\"uuid\":\"not-a-uuid\"}", "{\"uuid\":\"" + B + "\"}", "7", "{\"name\":\"\",\"uuid\":\"" + C + "\"}", entry(D, "dave"));
		assertEquals(List.of("alice", "dave"), collect(Set.of(), List.of(), json, Map.of()).stream().map(Entry::label).toList());
	}

	@Test
	void aMisbehavingResolverIsIgnored() {
		List<Entry> out = PlayerDirectory.collect(Set.of(A), List.of(), cache(entry(A, "alice")), id -> {
			throw new IllegalStateException("boom");
		});
		assertEquals("alice", out.get(0).name());
		assertEquals("alice", PlayerDirectory.collect(Set.of(A), List.of(), cache(entry(A, "alice")), id -> null).get(0).name());
	}

	@Test
	void namesAreCleanedAndBounded() {
		String nasty = "ev\u0000il\n" + "x".repeat(200);
		List<Entry> out = collect(Set.of(A), List.of(), null, Map.of(A, nasty));
		assertTrue(out.get(0).name().length() <= 64);
		assertFalse(out.get(0).name().chars().anyMatch(Character::isISOControl));
		assertNull(collect(Set.of(A), List.of(), null, Map.of(A, "\u0001\u0002")).get(0).name(), "a name that is only control characters is no name");
	}

	@Test
	void anOversizedCacheIsBounded() {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < 25_000; i++) {
			sb.append(i == 0 ? "" : ",").append(entry(new UUID(0, i + 1), "p" + i));
		}
		sb.append("]");
		assertEquals(PlayerDirectory.MAX_CACHE_ENTRIES, PlayerDirectory.parseUserCache(sb.toString()).size());
	}

	@Test
	void viewingCreatesNoRecordAndTheSettingsAreUntouched(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("players.json");
		PlayerSettings settings = new PlayerSettings(file);
		settings.load();
		settings.put(A, PlayerRecord.DEFAULT.withMode(PersonalDifficultyMode.HARD));
		String before = Files.readString(file);
		int explicit = settings.explicitCount();
		int changes = settings.changeCount();
		List<Entry> out = collect(settings.knownIds(), List.of(new NameAndId(B, "bob")), cache(entry(C, "carol")), Map.of());
		assertEquals(3, out.size(), "A (stored), B (online) and C (cache only) are all listed");
		assertEquals(before, Files.readString(file));
		assertEquals(explicit, settings.explicitCount(), "listing a player never creates a record");
		assertEquals(changes, settings.changeCount());
		assertEquals(PersonalDifficultyMode.NORMAL, settings.get(C), "a listed player without a record has the defaults");
		assertFalse(settings.hasExplicit(B));
	}

	@Test
	void knownIdsIncludeQuarantinedEntries(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("players.json");
		Files.writeString(file, "{\"version\":2,\"players\":{\"" + A + "\":{\"mode\":\"HARD\"},\"" + B + "\":{\"mode\":\"ELITE\",\"locked\":\"yes\"}}}");
		PlayerSettings settings = new PlayerSettings(file);
		settings.load();
		assertEquals(Set.of(A, B), settings.knownIds());
		assertTrue(settings.isQuarantined(B));
	}

	@Test
	void readingAMissingOrUnreadableUserCacheFileGivesNothing(@TempDir Path dir) throws IOException {
		assertNull(PlayerDirectory.readUserCache(dir.resolve("absent.json")));
		assertNull(PlayerDirectory.readUserCache(dir), "a directory is not a readable file");
		Path bad = dir.resolve("usercache.json");
		Files.write(bad, new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, (byte) 0x80});
		String text = PlayerDirectory.readUserCache(bad);
		assertTrue(PlayerDirectory.parseUserCache(text).isEmpty());
	}
}
