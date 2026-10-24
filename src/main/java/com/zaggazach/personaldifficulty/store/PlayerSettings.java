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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player explicit selections, keyed by UUID. A player with no entry is {@link
 * PersonalDifficultyMode#INHERIT}; only explicit NORMAL/EASY/HARD choices are stored.
 *
 * <p>Persistence is one small JSON file inside the world folder, so separate worlds/servers never
 * share data. It is written (atomically) only when a selection changes, never per tick. Entries of
 * offline players are never pruned.
 */
public final class PlayerSettings {
	public static final int FORMAT_VERSION = 1;

	private final Map<UUID, PersonalDifficultyMode> modes = new ConcurrentHashMap<>();
	private final Path file;
	private final List<String> loadWarnings = new ArrayList<>();

	/** @param file backing file, or null for a memory-only instance (tests) */
	public PlayerSettings(Path file) {
		this.file = file;
	}

	public List<String> loadWarnings() {
		return List.copyOf(loadWarnings);
	}

	public PersonalDifficultyMode get(UUID id) {
		return modes.getOrDefault(id, PersonalDifficultyMode.INHERIT);
	}

	/** Sets and persists. INHERIT removes the entry. */
	public synchronized void set(UUID id, PersonalDifficultyMode mode) throws IOException {
		PersonalDifficultyMode previous = mode == PersonalDifficultyMode.INHERIT ? modes.remove(id) : modes.put(id, mode);
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

	public int explicitCount() {
		return modes.size();
	}

	/** Loads the file if present. Malformed content is ignored entry-by-entry (and reported). */
	public synchronized void load() {
		modes.clear();
		loadWarnings.clear();
		if (file == null || !Files.exists(file)) {
			return;
		}
		try {
			JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			if (!root.isJsonObject() || !root.getAsJsonObject().has("players") || !root.getAsJsonObject().get("players").isJsonObject()) {
				loadWarnings.add("players file has no \"players\" object; starting empty" + backUpUnreadableFile());
				return;
			}
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().getAsJsonObject("players").entrySet()) {
				try {
					UUID id = UUID.fromString(e.getKey());
					PersonalDifficultyMode mode = PersonalDifficultyMode.parse(e.getValue().getAsString()).orElse(null);
					if (mode == null || mode == PersonalDifficultyMode.INHERIT) {
						loadWarnings.add("ignored entry " + e.getKey() + ": invalid mode " + e.getValue());
					} else {
						modes.put(id, mode);
					}
				} catch (RuntimeException ex) {
					loadWarnings.add("ignored malformed entry " + e.getKey());
				}
			}
		} catch (IOException | RuntimeException e) {
			loadWarnings.add("could not read players file (" + e.getMessage() + "); starting empty" + backUpUnreadableFile());
		}
	}

	/** Keeps an unparseable file so the next save cannot silently destroy it. */
	private String backUpUnreadableFile() {
		Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
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
		root.add("players", players);
		Files.createDirectories(file.toAbsolutePath().getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
