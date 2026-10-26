package com.zaggazach.personaldifficulty.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The mod-managed overrides file {@code config/personaldifficulty-overrides.json}:
 * {@code {"version":1,"values":{"elite.damage-multiplier":2.5,...}}}. Written only by {@code admin set/unset} (and the later GUI),
 * never by hand; the hand-edited {@code personaldifficulty.properties} is a separate, read-only layer.
 *
 * <h2>Safety rules (the Phase 1 principles, applied to this file; independent of {@code PlayerSettings})</h2>
 * <ul>
 * <li><b>Judged in this order:</b> missing or blank file = no overrides (blank: nothing to lose); a directory at the path;
 * unreadable, not valid JSON, <em>duplicate JSON keys</em> (ambiguous: a parser would silently keep only one), not an object, or no
 * {@code version} = <b>corrupt</b>; a {@code version} that is not a whole number of at least 1, or is above
 * {@value #FORMAT_VERSION}, = <b>unsupported</b> (the version is judged before the structure); a supported version without a
 * {@code values} object = corrupt.</li>
 * <li><b>A corrupt, unsupported or non-file overrides file is never overwritten.</b> It is left byte-identical, a verified
 * <em>evidence</em> copy ({@code <name>.evidence-<millis>}, reused when identical) is kept, no overrides are applied, and every
 * change is refused with the reason until the file is fixed or replaced and {@code admin reload} adopts it. The server still starts.</li>
 * <li><b>External modification</b> (the file differs from what this store last read or wrote) is detected immediately before every
 * write: the write is refused, evidence is kept, and {@code admin reload} adopts the new content on purpose.</li>
 * <li><b>Ordinary backups:</b> before each rewrite of an existing, non-empty file the previous content is copied to
 * {@code <name>.backup-<millis>} and verified byte for byte; only the newest {@value #MAX_ORDINARY_BACKUPS} are kept.
 * <b>Evidence copies are never pruned</b> (different name); administrators delete them by hand.</li>
 * <li><b>Atomic writes:</b> temporary file, flushed to disk, atomic move. A failed step leaves the file and the in-memory state
 * unchanged; nothing is published until the write succeeded.</li>
 * <li><b>Nothing is silently deleted:</b> unknown keys, keys with invalid values, damage types that do not exist and unknown
 * top-level fields are kept verbatim on every rewrite; only an explicit {@code set}/{@code unset} of that very key replaces or
 * removes its entry (the previous file is then in a verified backup).</li>
 * </ul>
 */
public final class OverridesStore {
	public static final String FILE_NAME = "personaldifficulty-overrides.json";
	public static final int FORMAT_VERSION = 1;
	public static final int MAX_ORDINARY_BACKUPS = 5;

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

	public enum LoadState {
		NOT_PRESENT,
		OK,
		EMPTY_FILE,
		CORRUPT,
		UNSUPPORTED_VERSION,
		NOT_A_FILE,
		/** The file changed behind this store's back; never overwritten until {@code admin reload} adopts it. */
		EXTERNALLY_CHANGED
	}

	/** A requested change of one stored entry. */
	public sealed interface Change permits Put, Remove {
		String key();
	}

	public record Put(String key, JsonElement value) implements Change {
	}

	public record Remove(String key) implements Change {
	}

	/**
	 * A change worked out but not yet written. {@code replaced} is the raw entry that existed under the key before (it may be an
	 * invalid or not-applied value, which a {@code Put}/{@code Remove} of that very key replaces on purpose).
	 */
	public record Prepared(long generation, TreeMap<String, JsonElement> nextRaw, OverridesLayer nextLayer, Map<String, String> nextNotApplied, @Nullable JsonElement replaced, boolean changesFile) {
	}

	public enum ReloadOutcome {
		/** The file is usable and its content is now in force. */
		ADOPTED,
		/** The file is unusable: the last-good in-memory overrides stay in force and writes stay refused. */
		KEPT_LAST_GOOD
	}

	private final Path file;
	private final Io io;
	private final LongSupplier clock;
	private final Predicate<String> damageTypeExists;

	/** Every entry exactly as stored (known, unknown, invalid, not applied), sorted by key. */
	private TreeMap<String, JsonElement> raw = new TreeMap<>();
	/** Unknown top-level fields, written back untouched. */
	private final Map<String, JsonElement> topExtras = new LinkedHashMap<>();
	private OverridesLayer layer = OverridesLayer.EMPTY;
	/** Stored entries that are kept but not applied, with the reason (unknown key, invalid value, unknown damage type). */
	private Map<String, String> notApplied = Map.of();
	private final Set<String> loadWarnings = new LinkedHashSet<>();
	private volatile LoadState state = LoadState.NOT_PRESENT;
	private volatile @Nullable String blocked;
	private byte @Nullable [] diskDigest;
	private long generation;

	public OverridesStore(Path file) {
		this(file, Io.REAL, System::currentTimeMillis, id -> true);
	}

	/** @param damageTypeExists whether a damage type id such as {@code minecraft:fall} exists in the live registry */
	public OverridesStore(Path file, Io io, LongSupplier clock, Predicate<String> damageTypeExists) {
		this.file = file;
		this.io = io;
		this.clock = clock;
		this.damageTypeExists = damageTypeExists;
	}

	public Path file() {
		return file;
	}

	public synchronized OverridesLayer layer() {
		return layer;
	}

	public LoadState state() {
		return state;
	}

	/** Non-null while changes are refused (corrupt, unsupported, a directory, changed externally). */
	public @Nullable String blockedReason() {
		return blocked;
	}

	/** Warnings of the last load, each distinct problem once. */
	public synchronized List<String> loadWarnings() {
		return List.copyOf(loadWarnings);
	}

	/** Stored entries that are kept verbatim but not in use, key to short reason. */
	public synchronized Map<String, String> notApplied() {
		return notApplied;
	}

	/** The raw stored entry for a key (known, unknown or invalid), if any. */
	public synchronized Optional<JsonElement> rawEntry(String key) {
		return Optional.ofNullable(raw.get(key));
	}

	public synchronized int rawEntryCount() {
		return raw.size();
	}

	// ------------------------------------------------------------------ load

	/** Loads the file if present. Never writes to it. Problems are classified, reported and, where needed, block changes. */
	public synchronized void load() {
		raw = new TreeMap<>();
		topExtras.clear();
		layer = OverridesLayer.EMPTY;
		notApplied = Map.of();
		loadWarnings.clear();
		blocked = null;
		diskDigest = null;
		state = LoadState.NOT_PRESENT;
		generation++;
		if (!Files.exists(file)) {
			return;
		}
		if (Files.isDirectory(file)) {
			state = LoadState.NOT_A_FILE;
			String what = "the overrides path " + file + " is a directory, not a file";
			loadWarnings.add(what + "; nothing was backed up and no overrides were loaded; no change can be saved until it is removed");
			blocked = what + ", so settings changes cannot be saved. Remove it and run '/personaldifficulty admin reload'";
			return;
		}
		String text;
		try {
			byte[] bytes = Files.readAllBytes(file);
			diskDigest = sha256(bytes);
			text = new String(bytes, StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			block(LoadState.CORRUPT, "could not be read (" + e.getMessage() + ")");
			return;
		}
		if (text.isBlank()) {
			state = LoadState.EMPTY_FILE;
			loadWarnings.add("overrides file is empty; no overrides are applied (nothing to lose, so no copy was needed)");
			return;
		}
		String duplicate;
		try {
			duplicate = duplicateKey(text);
		} catch (IOException | RuntimeException e) {
			block(LoadState.CORRUPT, "is not valid JSON (" + e.getMessage() + ")");
			return;
		}
		JsonElement root;
		try {
			root = JsonParser.parseString(text);
		} catch (RuntimeException e) {
			block(LoadState.CORRUPT, "is not valid JSON (" + e.getMessage() + ")");
			return;
		}
		if (duplicate != null) {
			block(LoadState.CORRUPT, "contains the JSON key \"" + duplicate + "\" more than once, which is ambiguous");
			return;
		}
		if (!root.isJsonObject()) {
			block(LoadState.CORRUPT, "does not hold a JSON object");
			return;
		}
		JsonObject obj = root.getAsJsonObject();
		if (!obj.has("version")) {
			block(LoadState.CORRUPT, "has no \"version\" field");
			return;
		}
		Integer version = integerVersion(obj.get("version"));
		if (version == null || version < 1) {
			block(LoadState.UNSUPPORTED_VERSION, "has a version value that cannot be interpreted (" + obj.get("version") + ")");
			return;
		}
		if (version > FORMAT_VERSION) {
			block(LoadState.UNSUPPORTED_VERSION, "is format version " + version + ", newer than the newest this mod understands (" + FORMAT_VERSION + ")");
			return;
		}
		if (!obj.has("values") || !obj.get("values").isJsonObject()) {
			block(LoadState.CORRUPT, "has no \"values\" object");
			return;
		}
		state = LoadState.OK;
		for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("values").entrySet()) {
			raw.put(e.getKey(), e.getValue());
		}
		for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
			if (!e.getKey().equals("version") && !e.getKey().equals("values")) {
				topExtras.put(e.getKey(), e.getValue());
			}
		}
		Classified c = classify(raw);
		layer = c.layer();
		notApplied = c.notApplied();
		loadWarnings.addAll(c.warnings());
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

	/** First JSON object key that appears twice in the same object, or null. A standard parser would silently keep only the last. */
	static @Nullable String duplicateKey(String text) throws IOException {
		JsonReader reader = new JsonReader(new StringReader(text));
		reader.setLenient(true);
		return scan(reader);
	}

	private static @Nullable String scan(JsonReader r) throws IOException {
		JsonToken t = r.peek();
		if (t == JsonToken.BEGIN_OBJECT) {
			r.beginObject();
			Set<String> names = new HashSet<>();
			while (r.hasNext()) {
				String name = r.nextName();
				if (!names.add(name)) {
					return name;
				}
				String inner = scan(r);
				if (inner != null) {
					return inner;
				}
			}
			r.endObject();
		} else if (t == JsonToken.BEGIN_ARRAY) {
			r.beginArray();
			while (r.hasNext()) {
				String inner = scan(r);
				if (inner != null) {
					return inner;
				}
			}
			r.endArray();
		} else {
			r.skipValue();
		}
		return null;
	}

	/** A file that must not be written: keep (or reuse) a verified evidence copy, apply no overrides, refuse all changes and say why. */
	private void block(LoadState newState, String what) {
		state = newState;
		String note = evidenceCopy();
		boolean copied = note.startsWith("; original");
		loadWarnings.add("overrides file " + what + "; it is left untouched" + note + "; no overrides are applied (settings are those of the properties file and the defaults, "
			+ "which is NOT what was configured with 'admin set'); no settings change can be saved until it is fixed");
		blocked = "the overrides file " + what + ", so settings changes cannot be saved and the file was left untouched" + (copied ? "" : " (and no evidence copy could be made)")
			+ ". Fix or replace it, then run '/personaldifficulty admin reload'";
	}

	// ------------------------------------------------------------------ classification of the stored entries

	private record Classified(OverridesLayer layer, List<String> warnings, Map<String, String> notApplied) {
	}

	/** Applies strict validation to every stored entry; everything not applied is reported once and stays in {@code entries}. */
	private Classified classify(Map<String, JsonElement> entries) {
		Map<String, Object> scalars = new LinkedHashMap<>();
		Map<String, Map<String, Double>> family = new LinkedHashMap<>();
		Set<String> warnings = new LinkedHashSet<>();
		Map<String, String> notApplied = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : entries.entrySet()) {
			String key = e.getKey();
			JsonElement value = e.getValue();
			Optional<SettingDef> def = SettingRegistry.find(key);
			if (def.isPresent()) {
				StrictValue.Parsed p = StrictValue.fromJson(def.get(), value);
				if (p instanceof StrictValue.Ok ok) {
					scalars.put(key, ok.value());
				} else {
					warnings.add("ignored the stored value of " + key + " (" + ((StrictValue.Error) p).message() + "); the lower layer's value is used and the entry is kept untouched");
					notApplied.put(key, "invalid value: " + ((StrictValue.Error) p).message());
				}
				continue;
			}
			FamilyKey.Parsed fk = FamilyKey.parse(key);
			if (fk instanceof FamilyKey.Ok ok) {
				StrictValue.Parsed p = StrictValue.multiplierFromJson(value);
				if (p instanceof StrictValue.Error err) {
					warnings.add("ignored the stored value of " + key + " (" + err.message() + "); the entry is kept untouched");
					notApplied.put(key, "invalid value: " + err.message());
				} else if (!damageTypeExists.test(ok.key().id())) {
					warnings.add("ignored " + key + ": the damage type " + ok.key().id() + " does not exist on this server; the entry is kept untouched and not applied");
					notApplied.put(key, "the damage type " + ok.key().id() + " does not exist on this server");
				} else {
					family.computeIfAbsent(ok.key().family().profile(), k -> new TreeMap<>()).put(ok.key().id(), (Double) ((StrictValue.Ok) p).value());
				}
			} else if (fk instanceof FamilyKey.Invalid inv) {
				warnings.add("ignored the stored key " + key + " (" + inv.message() + "); the entry is kept untouched and not applied");
				notApplied.put(key, "invalid key: " + inv.message());
			} else {
				warnings.add("ignored the unrecognised stored key " + key + "; the entry is kept untouched and not applied");
				notApplied.put(key, "unrecognised key");
			}
		}
		return new Classified(new OverridesLayer(scalars, family, List.of()), new ArrayList<>(warnings), notApplied);
	}

	// ------------------------------------------------------------------ change = prepare, then commit

	/**
	 * Works out the effect of a change without touching the file or any state. Refused while changes are blocked. The caller computes
	 * the new effective configuration from {@code nextLayer} and only then calls {@link #commit}.
	 */
	public synchronized Prepared prepare(Change change) throws IOException {
		if (blocked != null) {
			throw new IOException(blocked);
		}
		TreeMap<String, JsonElement> next = new TreeMap<>(raw);
		JsonElement previous = raw.get(change.key());
		boolean differs;
		if (change instanceof Put put) {
			next.put(put.key(), put.value());
			differs = !put.value().equals(previous);
		} else {
			next.remove(change.key());
			differs = previous != null;
		}
		Classified c = classify(next);
		return new Prepared(generation, next, c.layer(), c.notApplied(), previous, differs);
	}

	/**
	 * Writes a prepared change: guard against external modification, verified ordinary backup, atomic write. On any failure the file
	 * and this store's state are unchanged and the IOException is thrown; on success the new state is in force and old ordinary
	 * backups beyond {@value #MAX_ORDINARY_BACKUPS} are pruned.
	 */
	public synchronized void commit(Prepared prepared) throws IOException {
		if (prepared.generation() != generation) {
			throw new IllegalStateException("the overrides changed between prepare and commit");
		}
		if (blocked != null) {
			throw new IOException(blocked);
		}
		if (!prepared.changesFile()) {
			return;
		}
		guardAgainstExternalChange();
		boolean existed = state == LoadState.OK && Files.isRegularFile(file); // a blank file holds nothing worth a backup
		if (existed && !newestBackupIsIdentical()) {
			copyToBackup(); // refuses the change if the verified copy cannot be made: never rewrite without the safety copy
		}
		JsonObject root = new JsonObject();
		root.addProperty("version", FORMAT_VERSION);
		JsonObject values = new JsonObject();
		prepared.nextRaw().forEach(values::add);
		root.add("values", values);
		topExtras.forEach(root::add);
		byte[] data = root.toString().getBytes(StandardCharsets.UTF_8);
		io.writeAtomically(file, data);
		// the write succeeded: publish
		raw = prepared.nextRaw();
		layer = prepared.nextLayer();
		notApplied = prepared.nextNotApplied();
		diskDigest = sha256(data);
		state = LoadState.OK;
		generation++;
		if (existed) {
			pruneOrdinaryBackups();
		}
	}

	/**
	 * Re-reads the file. A usable file (including a deleted or blank one: no overrides) is adopted; an unusable one leaves the
	 * last-good in-memory overrides in force and keeps changes refused. This is the administrator's explicit recovery act, and
	 * also what clears {@link LoadState#EXTERNALLY_CHANGED}.
	 */
	public synchronized ReloadOutcome reload() {
		OverridesStore candidate = new OverridesStore(file, io, clock, damageTypeExists);
		candidate.load(); // any evidence copies are made by the candidate's own load
		if (candidate.blocked == null) {
			raw = candidate.raw;
			topExtras.clear();
			topExtras.putAll(candidate.topExtras);
			layer = candidate.layer;
			notApplied = candidate.notApplied;
			loadWarnings.clear();
			loadWarnings.addAll(candidate.loadWarnings);
			diskDigest = candidate.diskDigest;
			state = candidate.state;
			blocked = null;
			generation++;
			return ReloadOutcome.ADOPTED;
		}
		blocked = candidate.blocked;
		state = candidate.state;
		loadWarnings.clear();
		loadWarnings.addAll(candidate.loadWarnings);
		return ReloadOutcome.KEPT_LAST_GOOD;
	}

	// ------------------------------------------------------------------ external-change guard

	private void guardAgainstExternalChange() throws IOException {
		if (!Files.exists(file) || Files.isDirectory(file)) {
			return;
		}
		byte[] now;
		try {
			now = sha256(Files.readAllBytes(file));
		} catch (IOException e) {
			throw new IOException("the overrides file can not be read before saving (" + e.getMessage() + "), so it was not overwritten", e);
		}
		if (diskDigest != null && Arrays.equals(now, diskDigest)) {
			return;
		}
		String note = evidenceCopy();
		state = LoadState.EXTERNALLY_CHANGED;
		loadWarnings.add("overrides file was changed outside the mod after it was last read or written; it was NOT overwritten" + note + "; the in-memory overrides are unchanged until it is reloaded");
		blocked = "the overrides file was changed outside the mod after it was last read or written, so it was not overwritten" + (note.startsWith("; original") ? "" : " (and no evidence copy could be made)")
			+ ". Review it, then run '/personaldifficulty admin reload' to load it (or restore the previous content)";
		throw new IOException(blocked);
	}

	// ------------------------------------------------------------------ backups and evidence

	private Path sibling(String suffix) {
		return file.resolveSibling(file.getFileName() + suffix);
	}

	/** Always makes a NEW ordinary backup {@code <name>.backup-<millis>} and verifies it byte for byte. */
	private Path copyToBackup() throws IOException {
		return verifiedCopy(".backup-");
	}

	private Path verifiedCopy(String infix) throws IOException {
		long stamp = clock.getAsLong();
		for (int attempt = 0; attempt < 1000; attempt++) {
			Path target = sibling(infix + stamp + (attempt == 0 ? "" : "-" + attempt));
			if (!Files.exists(target)) {
				io.copy(file, target);
				if (!Files.isRegularFile(target) || Files.mismatch(file, target) != -1L) {
					Files.deleteIfExists(target);
					throw new IOException("the copy of " + file.getFileName() + " does not match the original");
				}
				return target;
			}
		}
		throw new IOException("no free copy name for " + file.getFileName());
	}

	/** A verified evidence copy of the current file ({@code .evidence-<millis>}); an identical earlier one is reused. Never pruned. */
	private String evidenceCopy() {
		try {
			Path existing = findIdentical(".evidence-");
			if (existing != null) {
				return "; original already kept as " + existing.getFileName();
			}
			return "; original kept as " + verifiedCopy(".evidence-").getFileName();
		} catch (IOException e) {
			return "; evidence copy failed (" + e.getMessage() + ")";
		}
	}

	private @Nullable Path findIdentical(String infix) {
		Path dir = file.toAbsolutePath().getParent();
		String prefix = file.getFileName() + infix;
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

	/**
	 * True if the newest ordinary backup is byte-identical to the current file (for example left by an earlier attempt whose write
	 * failed): it is then already the safety copy, and another identical one would only push older history out of the five kept.
	 */
	private boolean newestBackupIsIdentical() throws IOException {
		List<Path> backups = ordinaryBackups();
		return !backups.isEmpty() && Files.size(backups.get(0)) == Files.size(file) && Files.mismatch(backups.get(0), file) == -1L;
	}

	/** The ordinary backups, newest first. Evidence copies have a different name and are never listed here. */
	List<Path> ordinaryBackups() {
		Path dir = file.toAbsolutePath().getParent();
		String prefix = file.getFileName() + ".backup-";
		try (Stream<Path> siblings = Files.list(dir)) {
			return siblings.filter(x -> x.getFileName().toString().startsWith(prefix) && Files.isRegularFile(x))
				.sorted(Comparator.comparingLong((Path p) -> stampOf(p, prefix)[0]).thenComparingLong(p -> stampOf(p, prefix)[1]).reversed())
				.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private static long[] stampOf(Path p, String prefix) {
		String rest = p.getFileName().toString().substring(prefix.length());
		int dash = rest.indexOf('-');
		try {
			long millis = Long.parseLong(dash < 0 ? rest : rest.substring(0, dash));
			long n = dash < 0 ? 0 : Long.parseLong(rest.substring(dash + 1));
			return new long[] {millis, n};
		} catch (NumberFormatException e) {
			return new long[] {Long.MIN_VALUE, 0};
		}
	}

	private void pruneOrdinaryBackups() {
		List<Path> backups = ordinaryBackups();
		for (int i = MAX_ORDINARY_BACKUPS; i < backups.size(); i++) {
			try {
				Files.deleteIfExists(backups.get(i));
			} catch (IOException ignored) {
				// a backup that can not be deleted is simply kept
			}
		}
	}

	private static byte[] sha256(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
