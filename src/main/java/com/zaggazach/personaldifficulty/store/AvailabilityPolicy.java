package com.zaggazach.personaldifficulty.store;

import com.google.gson.JsonArray;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Server-wide policy: which difficulties players may <em>newly select</em>. It is deliberately separate
 * from {@link PlayerSettings}: disabling a mode never touches anyone's stored selection.
 *
 * <p>Stored as {@code config/personaldifficulty-availability.json}, {@code {"version":1,"disabled":["elite"]}}.
 * Listing only the disabled modes means everything is enabled by default (also for a missing file or a
 * mode added in a later version). This file is the authority: the admin commands rewrite it atomically
 * (with rollback if the write fails) and the change is live at once.
 *
 * <p>Safeguards: at least one difficulty always stays enabled. A command that would disable the last
 * one is refused, and a file that disables all five is treated as invalid (everything enabled, a
 * warning, and the original is backed up) so the command system can never become unusable. Operators
 * recover with {@code /personaldifficulty admin enable <mode|all>}.
 */
public final class AvailabilityPolicy {
	public static final int FORMAT_VERSION = 1;
	public static final String FILE_NAME = "personaldifficulty-availability.json";

	public enum Change {
		CHANGED, UNCHANGED, REFUSED_LAST_ENABLED
	}

	/** Outcome of reading a policy file. {@code failed} means "do not apply; keep what you have". */
	public record Parsed(Set<PersonalDifficultyMode> disabled, List<String> warnings, boolean failed) {
	}

	private final Path file;
	private volatile Set<PersonalDifficultyMode> disabled = EnumSet.noneOf(PersonalDifficultyMode.class);
	private final List<String> loadWarnings = new ArrayList<>();

	/** @param file backing file, or null for a memory-only instance (tests) */
	public AvailabilityPolicy(Path file) {
		this.file = file;
	}

	public List<String> loadWarnings() {
		return List.copyOf(loadWarnings);
	}

	public boolean isEnabled(PersonalDifficultyMode mode) {
		return !disabled.contains(mode);
	}

	public List<PersonalDifficultyMode> enabledModes() {
		List<PersonalDifficultyMode> out = new ArrayList<>();
		for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
			if (isEnabled(m)) {
				out.add(m);
			}
		}
		return out;
	}

	/** Startup load: any problem falls back to "everything enabled" with a warning. */
	public synchronized void load() {
		loadWarnings.clear();
		disabled = EnumSet.noneOf(PersonalDifficultyMode.class);
		if (file == null || !Files.exists(file)) {
			return;
		}
		Parsed parsed = read();
		loadWarnings.addAll(parsed.warnings());
		if (parsed.failed()) {
			loadWarnings.add("using the default (all difficulties enabled)" + backUp());
		} else {
			disabled = parsed.disabled();
		}
	}

	/** Live reload: on any problem the previous in-memory policy is kept. */
	public synchronized Parsed reload() {
		if (file == null || !Files.exists(file)) {
			return new Parsed(disabled, List.of(), false);
		}
		Parsed parsed = read();
		if (!parsed.failed()) {
			disabled = parsed.disabled();
		}
		return parsed;
	}

	/** Enables or disables one mode and persists; refuses to disable the last enabled mode. */
	public synchronized Change setEnabled(PersonalDifficultyMode mode, boolean enabled) throws IOException {
		if (isEnabled(mode) == enabled) {
			return Change.UNCHANGED;
		}
		Set<PersonalDifficultyMode> next = EnumSet.noneOf(PersonalDifficultyMode.class);
		next.addAll(disabled);
		if (enabled) {
			next.remove(mode);
		} else {
			next.add(mode);
			if (next.size() >= PersonalDifficultyMode.values().length) {
				return Change.REFUSED_LAST_ENABLED;
			}
		}
		swap(next);
		return Change.CHANGED;
	}

	/** Enables every difficulty (the recovery path). */
	public synchronized Change enableAll() throws IOException {
		if (disabled.isEmpty()) {
			return Change.UNCHANGED;
		}
		swap(EnumSet.noneOf(PersonalDifficultyMode.class));
		return Change.CHANGED;
	}

	private void swap(Set<PersonalDifficultyMode> next) throws IOException {
		Set<PersonalDifficultyMode> previous = disabled;
		disabled = next;
		try {
			save();
		} catch (IOException e) {
			disabled = previous; // never report a policy as active if it was not stored
			throw e;
		}
	}

	private Parsed read() {
		try {
			return parse(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException e) {
			return new Parsed(EnumSet.noneOf(PersonalDifficultyMode.class), List.of("could not read availability file: " + e.getMessage()), true);
		}
	}

	/** Validating parser, public for tests. */
	public static Parsed parse(String json) {
		List<String> warnings = new ArrayList<>();
		Set<PersonalDifficultyMode> out = EnumSet.noneOf(PersonalDifficultyMode.class);
		try {
			JsonElement root = JsonParser.parseString(json);
			if (!root.isJsonObject()) {
				return new Parsed(out, List.of("availability file is not a JSON object"), true);
			}
			JsonElement list = root.getAsJsonObject().get("disabled");
			if (list == null) {
				return new Parsed(out, warnings, false);
			}
			if (!list.isJsonArray()) {
				return new Parsed(out, List.of("\"disabled\" is not an array"), true);
			}
			JsonArray array = list.getAsJsonArray();
			for (JsonElement e : array) {
				PersonalDifficultyMode mode = e.isJsonPrimitive() ? PersonalDifficultyMode.parse(e.getAsString()).orElse(null) : null;
				if (mode == null) {
					warnings.add("ignored unknown difficulty " + e + " in \"disabled\"");
				} else {
					out.add(mode);
				}
			}
			if (out.size() >= PersonalDifficultyMode.values().length) {
				return new Parsed(EnumSet.noneOf(PersonalDifficultyMode.class), List.of("file disables every difficulty, which is not allowed"), true);
			}
			return new Parsed(out, warnings, false);
		} catch (RuntimeException e) {
			return new Parsed(EnumSet.noneOf(PersonalDifficultyMode.class), List.of("malformed availability file: " + e.getMessage()), true);
		}
	}

	private String backUp() {
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
		JsonArray array = new JsonArray();
		for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
			if (disabled.contains(m)) {
				array.add(m.commandName());
			}
		}
		root.add("disabled", array);
		Files.createDirectories(file.toAbsolutePath().getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
