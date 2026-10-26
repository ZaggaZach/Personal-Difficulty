package com.zaggazach.personaldifficulty.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Per-player records, keyed by UUID. A player with no entry is {@link PersonalDifficultyMode#NORMAL}, not
 * locked, with no allow-list, so only players with something to remember are stored.
 *
 * <h2>Format and migration</h2>
 * <b>v2</b> (1.4.0): {@code {"version":2,"players":{"<uuid>":{"mode":"ELITE","locked":true,"allowed":["NORMAL","ELITE"],"by":"<uuid>","at":<millis>}}}}.
 * Every stored player is an object; {@code locked}, {@code allowed}, {@code by} and {@code at} are written only when set.
 * <b>v1</b> (1.0.0 to 1.3.0) stored {@code "<uuid>":"MODE"}. Both shapes are understood in either file version, so a v1 file
 * loads unchanged (an entry becomes a record with only a mode) and a v2 record under a {@code "version":1} header, which
 * is what 1.3.0 writes back if it ever touches a v2 file, is read correctly too.
 *
 * <h2>How a file is judged (in this order)</h2>
 * <ol>
 * <li>Missing file: empty. Blank file: empty (nothing to lose), reported.</li>
 * <li>A directory at the path: reported as such; nothing is claimed backed up; writes are refused.</li>
 * <li>Not valid JSON, not an object, or no {@code version}: <b>corrupt</b>. A copy is kept (or an identical earlier copy is
 * reused) and writes are refused.</li>
 * <li><b>The version is judged before the structure.</b> A version that is not a whole number of at least 1, or is higher
 * than {@value #FORMAT_VERSION}, is <b>unsupported</b>: copied (or an identical copy reused), writes are refused; records that
 * can be understood in a newer-version file are still read (read-only).</li>
 * <li>A supported version without a {@code players} object: corrupt (as above).</li>
 * </ol>
 * While writes are refused every change fails with the reason and the file is never touched. After the file has been
 * fixed or replaced, {@link #reloadIfBlocked()} (run by {@code admin reload}) or a restart loads it again.
 *
 * <h2>Entries that cannot be read: quarantine</h2>
 * An entry for a valid UUID whose known field is invalid (for example {@code "locked":"yes"}), whose mode is unknown, or
 * for which several entries exist, is <b>quarantined</b>: the raw entry is kept verbatim and written back by every save;
 * the player is treated as <b>locked</b> (fail closed) on the entry's own {@code mode} only if that field alone is a valid
 * mode name, otherwise on NORMAL; no other field of the entry is trusted. Ordinary changes for that player (self-service,
 * assign, lock, restrict, ...) are refused until an administrator runs {@link #repairQuarantined}, which first makes a
 * fresh, verified, timestamped copy of the file, then replaces the entry with a default record that keeps the entry's
 * <em>unknown</em> fields (the discarded invalid values remain in the copy). Entries whose key is not a UUID are not players;
 * they are kept verbatim and never interpreted.
 *
 * <h2>Safety rules</h2>
 * <ul>
 * <li>Loading never writes. A v1 file is rewritten as v2 only when something changes, and a timestamped copy
 * ({@code players.json.backup-<millis>}) is made first (an identical existing copy is reused, so retries and restarts do
 * not pile up copies). If that copy cannot be made, the change is refused.</li>
 * <li>Unknown record fields, unknown top-level fields and unparseable-key entries are kept verbatim and written back.</li>
 * <li>Writes go to a temporary file that is flushed to disk and atomically moved over the old file. A failed write
 * rolls the in-memory state back, so a change is never reported active unless it was stored.</li>
 * </ul>
 *
 * <p>Downgrade note: 1.3.0 cannot understand v2 records at all: every entry of a v2 file is an object, so it reads
 * <em>every</em> listed player as NORMAL, unlocked. Restore the {@code .backup-*} file made before the first v2 write
 * to go back.
 *
 * <p>The file lives in the world folder, so separate worlds never share data. Entries of offline players are never pruned.
 */
public final class PlayerSettings {
	public static final int FORMAT_VERSION = 2;

	/** Fields this version understands; everything else in a record is an unknown field and is preserved. */
	private static final Set<String> KNOWN_FIELDS = Set.of("mode", "locked", "allowed", "by", "at");

	/** Filesystem seam so tests can inject failures at exact points (backup copy, flush, atomic move). */
	public interface Io {
		void copy(Path from, Path to) throws IOException;

		void writeAtomically(Path target, byte[] data) throws IOException;

		Io REAL = new Io() {
			@Override
			public void copy(Path from, Path to) throws IOException {
				Files.copy(from, to);
			}

			@Override
			public void writeAtomically(Path target, byte[] data) throws IOException {
				Files.createDirectories(target.toAbsolutePath().getParent());
				Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
				try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
					ByteBuffer buf = ByteBuffer.wrap(data);
					while (buf.hasRemaining()) {
						ch.write(buf);
					}
					ch.force(true);
				}
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
		};
	}

	/** What loading found, so administrators (and tests) can tell a successful load from fallback defaults. */
	public enum LoadState {
		NOT_PRESENT,
		OK,
		EMPTY_FILE,
		CORRUPT,
		UNSUPPORTED_VERSION,
		NOT_A_FILE,
		/** The file was changed by something else after the mod last read or wrote it; it is never overwritten (see {@link #reloadIfBlocked()}). */
		EXTERNALLY_CHANGED
	}

	/** One stored entry exactly as read. */
	public record Raw(String key, JsonElement value) {
	}

	/** A player whose stored entry could not be read; {@code salvagedMode} is trusted only if the entry's mode field alone is valid. */
	public record Quarantine(List<Raw> entries, String reason, PersonalDifficultyMode salvagedMode) {
		public String rawJson() {
			return entries.stream().map(r -> "\"" + r.key() + "\":" + r.value()).reduce((a, b) -> a + ", " + b).orElse("");
		}
	}

	/** Result of {@link #repairQuarantined}. */
	public record Repair(@Nullable Path backup, List<String> keptUnknownFields) {
	}

	private record Parsed(@Nullable PlayerRecord record, String reason, PersonalDifficultyMode salvaged) {
	}

	private final Map<UUID, PlayerRecord> records = new ConcurrentHashMap<>();
	private final Map<UUID, Quarantine> quarantine = new ConcurrentHashMap<>();
	/** Entries that are not players (key is not a UUID); written back untouched so nothing recoverable is lost. */
	private final Map<String, JsonElement> preserved = new LinkedHashMap<>();
	/** Unknown top-level fields, written back untouched. */
	private final Map<String, JsonElement> topExtras = new LinkedHashMap<>();
	private final Path file;
	private final Io io;
	private final LongSupplier clock;
	private final List<String> loadWarnings = new ArrayList<>();
	private volatile int changes;
	private int legacyNormalised;
	/** The existing file is older than v2 and has not been copied yet; copied right before the first v2 write. */
	private boolean backupBeforeWrite;
	private volatile @Nullable String writeBlocked;
	private volatile LoadState state = LoadState.NOT_PRESENT;
	private int onDiskVersion;
	/** SHA-256 of the file as this instance last read or wrote it (null: the file was absent). Detects changes made behind our back. */
	private byte @Nullable [] diskDigest;

	/** @param file backing file, or null for a memory-only instance (tests) */
	public PlayerSettings(@Nullable Path file) {
		this(file, Io.REAL, System::currentTimeMillis);
	}

	public PlayerSettings(@Nullable Path file, Io io, LongSupplier clock) {
		this.file = file;
		this.io = io;
		this.clock = clock;
	}

	public List<String> loadWarnings() {
		return List.copyOf(loadWarnings);
	}

	public LoadState loadState() {
		return state;
	}

	/** Number of stored entries read as NORMAL during the last load (1.0.0 INHERIT / explicit NORMAL). */
	public int legacyNormalisedCount() {
		return legacyNormalised;
	}

	/** Non-null while writes are refused (corrupt or unsupported file, a directory at the path). */
	public @Nullable String writeBlockedReason() {
		return writeBlocked;
	}

	/** Format version of the file as last read (0 = none), or {@link #FORMAT_VERSION} after a successful write. */
	public int onDiskVersion() {
		return onDiskVersion;
	}

	/** Bumped on every change to any record; lets caches of "who is Elite" notice a mode switch within the same tick. */
	public int changeCount() {
		return changes;
	}

	public PersonalDifficultyMode get(UUID id) {
		PlayerRecord r = records.get(id);
		if (r != null) {
			return r.mode();
		}
		Quarantine q = quarantine.isEmpty() ? null : quarantine.get(id);
		return q == null ? PersonalDifficultyMode.DEFAULT : q.salvagedMode();
	}

	/** The player's record; for a quarantined player the fail-closed (locked) view. Never null. */
	public PlayerRecord record(UUID id) {
		PlayerRecord r = records.get(id);
		if (r != null) {
			return r;
		}
		Quarantine q = quarantine.isEmpty() ? null : quarantine.get(id);
		return q == null ? PlayerRecord.DEFAULT : PlayerRecord.quarantinedView(q.salvagedMode());
	}

	public boolean isQuarantined(UUID id) {
		return !quarantine.isEmpty() && quarantine.containsKey(id);
	}

	public Optional<Quarantine> quarantineOf(UUID id) {
		return Optional.ofNullable(quarantine.get(id));
	}

	public int quarantinedCount() {
		return quarantine.size();
	}

	/**
	 * Stores {@code next} for the player and persists it. An all-default record removes the entry. Nothing is written
	 * if the stored meaning is unchanged. On any failure (including refused writes) the previous record is restored and
	 * the IOException is thrown. A quarantined player can not be changed this way (see {@link #repairQuarantined}).
	 */
	public synchronized void put(UUID id, PlayerRecord next) throws IOException {
		Quarantine q = quarantine.get(id);
		if (q != null) {
			throw new IOException("this player's stored record is invalid (" + q.reason() + ") and is kept untouched; an administrator must repair it first with '/personaldifficulty admin reset <player>'");
		}
		PlayerRecord before = record(id);
		if (next.sameState(before)) {
			return;
		}
		PlayerRecord previous = next.isDefault() ? records.remove(id) : records.put(id, next);
		changes++;
		try {
			save();
		} catch (IOException e) {
			// Never report a state as active if it was not durably stored: roll back.
			if (previous == null) {
				records.remove(id);
			} else {
				records.put(id, previous);
			}
			throw e;
		}
	}

	/**
	 * Low-level: sets only the mode, keeping any lock and allow-list. Applies no policy; production code decides through
	 * {@link com.zaggazach.personaldifficulty.logic.SelectionPolicy} first.
	 */
	public void set(UUID id, PersonalDifficultyMode mode) throws IOException {
		put(id, record(id).withMode(mode));
	}

	/**
	 * Administrative repair of a quarantined entry: makes a fresh timestamped copy of the file and verifies it byte for
	 * byte, then replaces the entry by a default record that keeps the entry's unknown fields. The invalid values stay in
	 * the copy. Rolls back completely if anything fails. Returns null if the player is not quarantined.
	 */
	public synchronized @Nullable Repair repairQuarantined(UUID id) throws IOException {
		Quarantine q = quarantine.get(id);
		if (q == null) {
			return null;
		}
		if (writeBlocked != null) {
			throw new IOException(writeBlocked);
		}
		Path backup = file != null && Files.exists(file) ? copyToBackup() : null;
		JsonObject extras = new JsonObject();
		List<String> kept = new ArrayList<>();
		for (Raw raw : q.entries()) {
			if (raw.value().isJsonObject()) {
				for (Map.Entry<String, JsonElement> e : raw.value().getAsJsonObject().entrySet()) {
					if (!KNOWN_FIELDS.contains(e.getKey()) && !extras.has(e.getKey())) {
						extras.add(e.getKey(), e.getValue());
						kept.add(e.getKey());
					}
				}
			}
		}
		PlayerRecord replacement = new PlayerRecord(PersonalDifficultyMode.NORMAL, false, null, null, 0L, extras);
		quarantine.remove(id);
		if (!replacement.isDefault()) {
			records.put(id, replacement);
		}
		changes++;
		try {
			save();
		} catch (IOException e) {
			quarantine.put(id, q);
			records.remove(id);
			throw e;
		}
		return new Repair(backup, List.copyOf(kept));
	}

	public boolean hasExplicit(UUID id) {
		return records.containsKey(id);
	}

	/** Number of players with a stored, valid record (a non-default mode, a lock, an allow-list or unknown fields). */
	public int explicitCount() {
		return records.size();
	}

	/** How many stored records currently use {@code mode}. */
	public long countUsing(PersonalDifficultyMode mode) {
		return records.values().stream().filter(r -> r.mode() == mode).count();
	}

	public long lockedCount() {
		return records.values().stream().filter(PlayerRecord::locked).count();
	}

	public long restrictedCount() {
		return records.values().stream().filter(r -> r.allowed() != null).count();
	}

	// ------------------------------------------------------------------ load

	/**
	 * Re-reads the file only if writes are currently refused (a corrupt or unsupported file, a directory at the path), so a
	 * fixed file can be picked up without a restart and a healthy in-memory state is never replaced. Returns true if the
	 * file is usable afterwards.
	 */
	public synchronized boolean reloadIfBlocked() {
		if (writeBlocked == null) {
			return true;
		}
		PlayerSettings candidate = new PlayerSettings(file, io, clock);
		candidate.load(); // evidence (backups) is kept by the candidate's own load
		if (candidate.writeBlocked == null) {
			records.clear();
			records.putAll(candidate.records);
			quarantine.clear();
			quarantine.putAll(candidate.quarantine);
			preserved.clear();
			preserved.putAll(candidate.preserved);
			topExtras.clear();
			topExtras.putAll(candidate.topExtras);
			loadWarnings.clear();
			loadWarnings.addAll(candidate.loadWarnings);
			legacyNormalised = candidate.legacyNormalised;
			backupBeforeWrite = candidate.backupBeforeWrite;
			onDiskVersion = candidate.onDiskVersion;
			diskDigest = candidate.diskDigest;
			state = candidate.state;
			writeBlocked = null;
			changes++;
			return true;
		}
		// still unusable: keep whatever is in memory (a running server must not drop its locks because a file went bad) and keep refusing writes
		writeBlocked = candidate.writeBlocked;
		state = candidate.state;
		loadWarnings.clear();
		loadWarnings.addAll(candidate.loadWarnings);
		return false;
	}

	/** Loads the file if present. Never writes to it. Problems are classified, reported and, where needed, block writes. */
	public synchronized void load() {
		changes++;
		records.clear();
		quarantine.clear();
		preserved.clear();
		topExtras.clear();
		loadWarnings.clear();
		legacyNormalised = 0;
		backupBeforeWrite = false;
		writeBlocked = null;
		onDiskVersion = 0;
		diskDigest = null;
		state = LoadState.NOT_PRESENT;
		if (file == null || !Files.exists(file)) {
			return;
		}
		if (Files.isDirectory(file)) {
			state = LoadState.NOT_A_FILE;
			String what = "the players path " + file + " is a directory, not a file";
			loadWarnings.add(what + "; nothing was backed up and no player settings were loaded; no change can be saved until it is removed");
			writeBlocked = what + ", so changes cannot be saved. Remove it and run '/personaldifficulty admin reload'";
			return;
		}
		String text;
		try {
			byte[] bytes = Files.readAllBytes(file);
			diskDigest = sha256(bytes);
			text = new String(bytes, StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			block(LoadState.CORRUPT, "could not be read (" + e.getMessage() + ")", true);
			return;
		}
		if (text.isBlank()) {
			state = LoadState.EMPTY_FILE;
			loadWarnings.add("players file is empty; starting empty (nothing to lose, so no backup was needed)");
			return;
		}
		JsonElement root;
		try {
			root = JsonParser.parseString(text);
		} catch (RuntimeException e) {
			block(LoadState.CORRUPT, "is not valid JSON (" + e.getMessage() + ")", true);
			return;
		}
		if (!root.isJsonObject()) {
			block(LoadState.CORRUPT, "does not hold a JSON object", true);
			return;
		}
		JsonObject obj = root.getAsJsonObject();
		// the version is judged BEFORE the structure: a newer file may well have a different structure
		if (!obj.has("version")) {
			block(LoadState.CORRUPT, "has no \"version\" field", true);
			return;
		}
		Integer version = integerVersion(obj.get("version"));
		if (version == null || version < 1) {
			block(LoadState.UNSUPPORTED_VERSION, "has a version value that cannot be interpreted (" + obj.get("version") + ")", true);
			return;
		}
		onDiskVersion = version;
		boolean hasPlayers = obj.has("players") && obj.get("players").isJsonObject();
		if (version > FORMAT_VERSION) {
			block(LoadState.UNSUPPORTED_VERSION, "is format version " + version + ", newer than the newest this mod understands (" + FORMAT_VERSION + ")", !hasPlayers);
			if (hasPlayers) {
				readEntries(obj.getAsJsonObject("players")); // read-only, best effort: locks that can be understood stay in force
			}
			return;
		}
		if (!hasPlayers) {
			block(LoadState.CORRUPT, "has no \"players\" object", true);
			return;
		}
		state = LoadState.OK;
		backupBeforeWrite = version < FORMAT_VERSION;
		readEntries(obj.getAsJsonObject("players"));
		for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
			if (!e.getKey().equals("version") && !e.getKey().equals("players")) {
				topExtras.put(e.getKey(), e.getValue());
			}
		}
	}

	private static @Nullable Integer integerVersion(JsonElement v) {
		if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
			double d = v.getAsDouble();
			if (d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
				return (int) d;
			}
		}
		return null;
	}

	/** A file that must not be written: keep (or reuse) a copy, refuse all writes and say why. */
	private void block(LoadState newState, String what, boolean nothingLoaded) {
		state = newState;
		String note = backUpAtLoad();
		boolean copied = note.startsWith("; original");
		loadWarnings.add("players file " + what + "; it is left untouched" + note + "; "
			+ (nothingLoaded ? "no player settings were loaded (everyone shows as the default, which is NOT their saved state)" : "settings that can be understood are applied read-only")
			+ "; no change to any player can be saved until it is fixed");
		writeBlocked = "the players file " + what + ", so changes cannot be saved and the file was left untouched" + (copied ? "" : " (and no backup could be made)")
			+ ". Fix or replace it, then run '/personaldifficulty admin reload' (or restart)";
	}

	private void readEntries(JsonObject players) {
		Map<UUID, List<Raw>> groups = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : players.entrySet()) {
			UUID id;
			try {
				id = UUID.fromString(e.getKey());
			} catch (IllegalArgumentException ex) {
				preserved.put(e.getKey(), e.getValue());
				loadWarnings.add("kept entry with unreadable UUID key \"" + e.getKey() + "\" untouched (it is not a player)");
				continue;
			}
			groups.computeIfAbsent(id, k -> new ArrayList<>()).add(new Raw(e.getKey(), e.getValue()));
		}
		for (Map.Entry<UUID, List<Raw>> g : groups.entrySet()) {
			UUID id = g.getKey();
			List<Raw> raws = g.getValue();
			if (raws.size() > 1) {
				quarantine(id, raws, "several entries name the same player", PersonalDifficultyMode.NORMAL);
				continue;
			}
			Parsed parsed = parseEntry(raws.get(0).value());
			if (parsed.record() == null) {
				quarantine(id, raws, parsed.reason(), parsed.salvaged());
			} else if (parsed.record().isDefault()) {
				legacyNormalised++; // INHERIT (1.0.0), explicit NORMAL or an empty record: all simply the default now
			} else {
				records.put(id, parsed.record());
			}
		}
	}

	private void quarantine(UUID id, List<Raw> raws, String reason, PersonalDifficultyMode salvaged) {
		quarantine.put(id, new Quarantine(List.copyOf(raws), reason, salvaged));
		loadWarnings.add("kept entry for " + id + " untouched: " + reason + "; the player is treated as LOCKED on " + salvaged
			+ " until an administrator repairs it with '/personaldifficulty admin reset <player>'");
	}

	private static Parsed parseEntry(JsonElement value) {
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
			PersonalDifficultyMode mode = PersonalDifficultyMode.parseStored(value.getAsString()).orElse(null);
			return mode == null
				? new Parsed(null, "unknown difficulty " + value, PersonalDifficultyMode.NORMAL)
				: new Parsed(PlayerRecord.DEFAULT.withMode(mode), "", mode);
		}
		if (value.isJsonObject()) {
			return parseRecord(value.getAsJsonObject());
		}
		return new Parsed(null, "entry is neither a mode name nor an object", PersonalDifficultyMode.NORMAL);
	}

	/**
	 * Validation policy: {@code mode} (string, a known mode), {@code locked} (true/false) and {@code allowed} (non-empty array
	 * of known mode names, or null meaning "follow the server policy") are security-relevant: any other type or value
	 * quarantines the whole entry. {@code by} and {@code at} are audit-only: an invalid value is kept as an unknown field and
	 * never quarantines. A missing {@code mode} is NORMAL. Everything else is an unknown field and is preserved.
	 */
	private static Parsed parseRecord(JsonObject o) {
		JsonElement modeField = o.get("mode");
		PersonalDifficultyMode salvaged = modeField != null && modeField.isJsonPrimitive() && modeField.getAsJsonPrimitive().isString()
			? PersonalDifficultyMode.parseStored(modeField.getAsString()).orElse(PersonalDifficultyMode.NORMAL)
			: PersonalDifficultyMode.NORMAL;
		PersonalDifficultyMode mode = PersonalDifficultyMode.DEFAULT;
		boolean locked = false;
		EnumSet<PersonalDifficultyMode> allowed = null;
		UUID by = null;
		long at = 0L;
		JsonObject extras = new JsonObject();
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			JsonElement v = e.getValue();
			switch (e.getKey()) {
				case "mode" -> {
					if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
						return new Parsed(null, "\"mode\" is not a mode name (" + v + ")", salvaged);
					}
					PersonalDifficultyMode m = PersonalDifficultyMode.parseStored(v.getAsString()).orElse(null);
					if (m == null) {
						return new Parsed(null, "\"mode\" is an unknown difficulty (" + v + ")", PersonalDifficultyMode.NORMAL);
					}
					mode = m;
				}
				case "locked" -> {
					if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) {
						return new Parsed(null, "\"locked\" is not true or false (" + v + ")", salvaged);
					}
					locked = v.getAsBoolean();
				}
				case "allowed" -> {
					if (v.isJsonNull()) {
						break; // null means "follow the server policy"
					}
					if (!v.isJsonArray() || v.getAsJsonArray().isEmpty()) {
						return new Parsed(null, "\"allowed\" is not a non-empty list (" + v + ")", salvaged);
					}
					allowed = EnumSet.noneOf(PersonalDifficultyMode.class);
					for (JsonElement item : v.getAsJsonArray()) {
						PersonalDifficultyMode m = item.isJsonPrimitive() && item.getAsJsonPrimitive().isString() ? PersonalDifficultyMode.parse(item.getAsString()).orElse(null) : null;
						if (m == null) {
							return new Parsed(null, "\"allowed\" contains something that is not a known difficulty (" + item + ")", salvaged);
						}
						allowed.add(m);
					}
				}
				case "by" -> {
					UUID parsed = null;
					if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
						try {
							parsed = UUID.fromString(v.getAsString());
						} catch (IllegalArgumentException ignored) {
							// kept as an unknown field below
						}
					}
					if (parsed == null) {
						extras.add(e.getKey(), v);
					} else {
						by = parsed;
					}
				}
				case "at" -> {
					if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsDouble() == Math.rint(v.getAsDouble())) {
						at = v.getAsLong();
					} else {
						extras.add(e.getKey(), v);
					}
				}
				default -> extras.add(e.getKey(), v);
			}
		}
		return new Parsed(new PlayerRecord(mode, locked, allowed, by, at, extras), "", mode);
	}

	// ------------------------------------------------------------------ backups

	/** An existing {@code <name>.backup-*} copy that is byte-identical to the current file, or null. */
	private @Nullable Path findIdenticalBackup() {
		Path dir = file.toAbsolutePath().getParent();
		String prefix = file.getFileName() + ".backup-";
		try (Stream<Path> siblings = Files.list(dir)) {
			for (Path p : siblings.filter(x -> x.getFileName().toString().startsWith(prefix) && Files.isRegularFile(x)).sorted().toList()) {
				if (Files.size(p) == Files.size(file) && Files.mismatch(p, file) == -1L) {
					return p;
				}
			}
		} catch (IOException ignored) {
			// no usable copy found: a new one will be made
		}
		return null;
	}

	/** Keeps a verified copy of the current file for recovery: an identical earlier copy if there is one, else a new one. */
	private String backUpAtLoad() {
		try {
			Path existing = findIdenticalBackup();
			if (existing != null) {
				return "; original already kept as " + existing.getFileName();
			}
			return "; original kept as " + copyToBackup().getFileName();
		} catch (IOException e) {
			return "; backup failed (" + e.getMessage() + ")";
		}
	}

	/** Always makes a NEW timestamped copy and verifies it byte for byte; a bad copy is deleted and reported. */
	private Path copyToBackup() throws IOException {
		long stamp = clock.getAsLong();
		for (int attempt = 0; attempt < 1000; attempt++) {
			Path backup = file.resolveSibling(file.getFileName() + ".backup-" + stamp + (attempt == 0 ? "" : "-" + attempt));
			if (!Files.exists(backup)) {
				io.copy(file, backup);
				if (!Files.isRegularFile(backup) || Files.mismatch(file, backup) != -1L) {
					Files.deleteIfExists(backup);
					throw new IOException("the backup copy of " + file.getFileName() + " does not match the original");
				}
				return backup;
			}
		}
		throw new IOException("no free backup name for " + file.getFileName());
	}

	// ------------------------------------------------------------------ save

	private void save() throws IOException {
		if (writeBlocked != null) {
			throw new IOException(writeBlocked);
		}
		if (file == null) {
			return;
		}
		guardAgainstExternalChange();
		if (backupBeforeWrite && Files.exists(file)) {
			if (findIdenticalBackup() == null) {
				copyToBackup(); // refuses the change if the copy fails: never migrate without the safety copy
			}
		}
		backupBeforeWrite = false;
		JsonObject root = new JsonObject();
		root.addProperty("version", FORMAT_VERSION);
		JsonObject players = new JsonObject();
		new TreeMap<>(records).forEach((id, rec) -> players.add(id.toString(), toJson(rec)));
		new TreeMap<>(quarantine).values().forEach(q -> q.entries().forEach(raw -> players.add(raw.key(), raw.value())));
		preserved.forEach(players::add);
		root.add("players", players);
		topExtras.forEach(root::add);
		byte[] data = root.toString().getBytes(StandardCharsets.UTF_8);
		io.writeAtomically(file, data);
		diskDigest = sha256(data);
		onDiskVersion = FORMAT_VERSION;
	}

	/**
	 * Never replace a file that is no longer what this instance last read or wrote: it was edited, corrupted or created behind
	 * our back. A copy of what is there is kept, writes are refused, and the administrator is told to review and run
	 * {@code admin reload}. (A file that has disappeared is simply recreated from memory: nothing is destroyed.)
	 */
	private void guardAgainstExternalChange() throws IOException {
		if (!Files.exists(file) || Files.isDirectory(file)) {
			return;
		}
		byte[] now;
		try {
			now = sha256(Files.readAllBytes(file));
		} catch (IOException e) {
			throw new IOException("the players file can not be read before saving (" + e.getMessage() + "), so it was not overwritten", e);
		}
		if (diskDigest != null && Arrays.equals(now, diskDigest)) {
			return;
		}
		String note = backUpAtLoad();
		state = LoadState.EXTERNALLY_CHANGED;
		loadWarnings.add("players file was changed outside the mod after it was last read or written; it was NOT overwritten" + note + "; the in-memory settings are unchanged until it is reloaded");
		writeBlocked = "the players file was changed outside the mod after it was last read or written, so it was not overwritten" + (note.startsWith("; original") ? "" : " (and no backup could be made)")
			+ ". Review it, then run '/personaldifficulty admin reload' to load it (or restore the previous content)";
		throw new IOException(writeBlocked);
	}

	private static byte[] sha256(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static JsonObject toJson(PlayerRecord rec) {
		JsonObject o = new JsonObject();
		o.addProperty("mode", rec.mode().name());
		if (rec.locked()) {
			o.addProperty("locked", true);
		}
		if (rec.allowed() != null) {
			JsonArray list = new JsonArray();
			for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
				if (rec.allowed().contains(m)) {
					list.add(m.name());
				}
			}
			o.add("allowed", list);
		}
		if (rec.by() != null) {
			o.addProperty("by", rec.by().toString());
		}
		if (rec.at() != 0L) {
			o.addProperty("at", rec.at());
		}
		rec.extras().entrySet().forEach(e -> {
			if (!o.has(e.getKey())) { // a known field written above always wins over an invalid audit value kept as an unknown field
				o.add(e.getKey(), e.getValue());
			}
		});
		return o;
	}
}
