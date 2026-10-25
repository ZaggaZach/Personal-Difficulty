package com.zaggazach.personaldifficulty.store;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player selections, keyed by UUID. A player with no entry is {@link PersonalDifficultyMode#NORMAL}
 * (the default), so only EASY/HARD/ELITE/PEACEFUL choices are stored.
 *
 * <h2>Format and migration</h2>
 * The on-disk format is unchanged since 1.0.0 ({@code {"version":1,"players":{"<uuid>":"MODE"}}}), so
 * there is no structural rewrite. 1.0.0 files may contain the retired value {@code INHERIT}; it (and
 * {@code NORMAL}, which now equals the default) is read as NORMAL on every load, so the migration is
 * idempotent, needs no player to join, survives restarts, and the file is only rewritten (without those
 * values) the next time a selection changes. Entries this version cannot understand (unknown mode,
 * malformed UUID key) are never dropped: they are kept verbatim and written back on the next save.
 *
 * <p>The file lives in the world folder, so separate worlds never share data. It is written atomically
 * and only when a selection changes. Entries of offline players are never pruned.
 */
public final class PlayerSettings {
	public static final int FORMAT_VERSION = 1;

	private final Map<UUID, PersonalDifficultyMode> modes = new ConcurrentHashMap<>();
	/** Entries read but not understood; written back untouched so nothing recoverable is lost. */
	private final Map<String, JsonElement> preserved = new LinkedHashMap<>();
	private final Path file;
	private final List<String> loadWarnings = new ArrayList<>();
	private volatile int changes;
	private int legacyNormalised;

	/** @param file backing file, or null for a memory-only instance (tests) */
	public PlayerSettings(Path file) {
		this.file = file;
	}

	public List<String> loadWarnings() {
		return List.copyOf(loadWarnings);
	}

	/** Number of stored entries read as NORMAL during the last load (1.0.0 INHERIT / explicit NORMAL). */
	public int legacyNormalisedCount() {
		return legacyNormalised;
	}

	/** Bumped on every change to any selection; lets caches of "who is Elite" notice a mode switch within the same tick. */
	public int changeCount() {
		return changes;
	}

	public PersonalDifficultyMode get(UUID id) {
		return modes.getOrDefault(id, PersonalDifficultyMode.DEFAULT);
	}

	/** Sets and persists. NORMAL (the default) removes the entry. */
	public synchronized void set(UUID id, PersonalDifficultyMode mode) throws IOException {
		PersonalDifficultyMode previous = mode == PersonalDifficultyMode.DEFAULT ? modes.remove(id) : modes.put(id, mode);
		changes++;
		try {
			save();
		} catch (IOException e) {
			// Never report a mode as active if it was not durably stored: roll back.
			if (previous == null) {
				modes.remove(id);
			} else {
				modes.put(id, previous);
			}
			throw e;
		}
	}

	public boolean hasExplicit(UUID id) {
		return modes.containsKey(id);
	}

	/** Number of players with a non-default selection. */
	public int explicitCount() {
		return modes.size();
	}

	/** How many stored selections currently use {@code mode}. */
	public long countUsing(PersonalDifficultyMode mode) {
		return modes.values().stream().filter(m -> m == mode).count();
	}

	/** Loads the file if present. Malformed content is handled entry-by-entry and reported. */
	public synchronized void load() {
		changes++;
		modes.clear();
		preserved.clear();
		loadWarnings.clear();
		legacyNormalised = 0;
		if (file == null || !Files.exists(file)) {
			return;
		}
		try {
			JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			if (!root.isJsonObject() || !root.getAsJsonObject().has("players") || !root.getAsJsonObject().get("players").isJsonObject()) {
				loadWarnings.add("players file has no \"players\" object; starting empty" + backUpUnreadableFile());
				return;
			}
			JsonObject obj = root.getAsJsonObject();
			if (obj.has("version") && obj.get("version").isJsonPrimitive() && obj.get("version").getAsJsonPrimitive().isNumber()
				&& obj.get("version").getAsInt() > FORMAT_VERSION) {
				loadWarnings.add("players file is a newer format (version " + obj.get("version") + "); reading what is understood" + backUpUnreadableFile());
			}
			for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("players").entrySet()) {
				readEntry(e.getKey(), e.getValue());
			}
		} catch (IOException | RuntimeException e) {
			loadWarnings.add("could not read players file (" + e.getMessage() + "); starting empty" + backUpUnreadableFile());
		}
	}

	private void readEntry(String key, JsonElement value) {
		UUID id;
		try {
			id = UUID.fromString(key);
		} catch (IllegalArgumentException ex) {
			preserved.put(key, value);
			loadWarnings.add("kept entry with unreadable UUID key \"" + key + "\" untouched");
			return;
		}
		PersonalDifficultyMode mode = null;
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
			mode = PersonalDifficultyMode.parseStored(value.getAsString()).orElse(null);
		}
		if (mode == null) {
			preserved.put(key, value);
			loadWarnings.add("kept entry " + key + " with unrecognised mode " + value + " untouched");
		} else if (mode == PersonalDifficultyMode.DEFAULT) {
			legacyNormalised++; // INHERIT (1.0.0) or explicit NORMAL: both are simply the default now
		} else {
			modes.put(id, mode);
		}
	}

	/** Keeps an unparseable file so the next save cannot silently destroy it. */
	private String backUpUnreadableFile() {
		Path backup = file.resolveSibling(file.getFileName() + ".backup-" + System.currentTimeMillis());
		try {
			Files.copy(file, backup);
			return "; original kept as " + backup.getFileName();
		} catch (IOException e) {
			return "; backup failed (" + e.getMessage() + ")";
		}
	}

	private void save() throws IOException {
		if (file == null) {
			return;
		}
		JsonObject root = new JsonObject();
		root.addProperty("version", FORMAT_VERSION);
		JsonObject players = new JsonObject();
		new TreeMap<>(modes).forEach((id, mode) -> players.addProperty(id.toString(), mode.name()));
		preserved.forEach(players::add);
		root.add("players", players);
		Files.createDirectories(file.toAbsolutePath().getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
