package com.zaggazach.personaldifficulty.admin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zaggazach.personaldifficulty.PdRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import org.jspecify.annotations.Nullable;

/**
 * Which players an administrator can pick in a menu: everyone with a stored record (valid or quarantined), everyone currently
 * online, and every entry of {@code usercache.json}, deduplicated by UUID and sorted by name (case-insensitive) then UUID.
 *
 * <p>Read-only by construction: nothing here writes, and a player without a record is simply listed with the defaults (a record is
 * created only when an administrator changes something). A missing, unreadable or malformed user cache is not an error: the list
 * is then built from the other sources, and a malformed entry is skipped without affecting the others.
 */
public final class PlayerDirectory {
	/** The user cache is capped by the server itself; this only bounds a hostile or corrupt file. */
	static final int MAX_CACHE_ENTRIES = 20_000;
	static final int MAX_NAME_LENGTH = 64;

	/** @param name the best known name, or null if none is known (the menu then shows the UUID) */
	public record Entry(UUID id, @Nullable String name, boolean online) {
		/** What the menu shows and what messages call the player. */
		public String label() {
			return name != null ? name : id.toString();
		}

		public NameAndId nameAndId() {
			return new NameAndId(id, label());
		}
	}

	private PlayerDirectory() {
	}

	/** Gathers the sources from the running server and builds the list. Never throws for bad user-cache data. */
	public static List<Entry> collect(PdRuntime rt, MinecraftServer server) {
		List<NameAndId> online = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			online.add(new NameAndId(p.getUUID(), p.getGameProfile().name()));
		}
		return collect(rt.settings().knownIds(), online, readUserCache(server.getFile("usercache.json")),
			uuid -> server.services().nameToIdCache().get(uuid));
	}

	/** Pure merge, used directly by the tests. */
	public static List<Entry> collect(Collection<UUID> settingsIds, Collection<NameAndId> online, @Nullable String userCacheJson, Function<UUID, Optional<NameAndId>> resolver) {
		Map<UUID, String> cached = new LinkedHashMap<>();
		for (NameAndId n : parseUserCache(userCacheJson)) {
			cached.putIfAbsent(n.id(), n.name());
		}
		Map<UUID, String> onlineNames = new LinkedHashMap<>();
		for (NameAndId n : online) {
			onlineNames.put(n.id(), n.name());
		}
		Map<UUID, Boolean> all = new LinkedHashMap<>();
		for (UUID id : settingsIds) {
			all.put(id, onlineNames.containsKey(id));
		}
		for (UUID id : onlineNames.keySet()) {
			all.put(id, true);
		}
		for (UUID id : cached.keySet()) {
			all.putIfAbsent(id, false);
		}
		List<Entry> out = new ArrayList<>();
		for (Map.Entry<UUID, Boolean> e : all.entrySet()) {
			UUID id = e.getKey();
			String name = onlineNames.get(id);
			if (name == null) {
				name = resolve(resolver, id);
			}
			if (name == null) {
				name = cached.get(id);
			}
			out.add(new Entry(id, name == null ? null : clean(name), e.getValue()));
		}
		out.sort(Comparator.comparing((Entry e) -> e.label().toLowerCase(Locale.ROOT)).thenComparing(e -> e.id().toString()));
		return List.copyOf(out);
	}

	private static @Nullable String resolve(Function<UUID, Optional<NameAndId>> resolver, UUID id) {
		try {
			Optional<NameAndId> found = resolver.apply(id);
			return found != null && found.isPresent() ? found.get().name() : null;
		} catch (RuntimeException e) {
			return null; // a misbehaving resolver must not break the list
		}
	}

	private static @Nullable String clean(String name) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < name.length() && sb.length() < MAX_NAME_LENGTH; i++) {
			char c = name.charAt(i);
			if (!Character.isISOControl(c)) {
				sb.append(c);
			}
		}
		String cleaned = sb.toString().strip();
		return cleaned.isEmpty() ? null : cleaned;
	}

	static @Nullable String readUserCache(Path file) {
		try {
			return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Lenient: anything that is not an array of {@code {uuid, name}} objects yields what can be read, never an exception. */
	public static List<NameAndId> parseUserCache(@Nullable String json) {
		List<NameAndId> out = new ArrayList<>();
		if (json == null || json.isBlank()) {
			return out;
		}
		JsonElement root;
		try {
			root = JsonParser.parseString(json);
		} catch (RuntimeException e) {
			return out;
		}
		if (!root.isJsonArray()) {
			return out;
		}
		JsonArray array = root.getAsJsonArray();
		for (int i = 0; i < array.size() && i < MAX_CACHE_ENTRIES; i++) {
			try {
				JsonElement el = array.get(i);
				if (!el.isJsonObject()) {
					continue;
				}
				JsonObject obj = el.getAsJsonObject();
				if (!obj.has("uuid") || !obj.has("name") || !obj.get("name").isJsonPrimitive() || !obj.get("uuid").isJsonPrimitive()) {
					continue;
				}
				String name = obj.get("name").getAsString();
				if (name.isBlank()) {
					continue;
				}
				out.add(new NameAndId(UUID.fromString(obj.get("uuid").getAsString()), name));
			} catch (RuntimeException e) {
				// one malformed entry (bad UUID, wrong types) is skipped; the rest still count
			}
		}
		return out;
	}
}
