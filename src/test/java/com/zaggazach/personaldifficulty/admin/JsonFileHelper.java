package com.zaggazach.personaldifficulty.admin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Test helper: writes a raw (possibly invalid) player entry into a players.json, the way damage or a hand edit would. */
final class JsonFileHelper {
	private JsonFileHelper() {
	}

	static void corruptEntry(Path file, UUID id, String rawEntry) throws IOException {
		JsonObject root = Files.exists(file) ? JsonParser.parseString(Files.readString(file)).getAsJsonObject() : JsonParser.parseString("{\"version\":2,\"players\":{}}").getAsJsonObject();
		root.getAsJsonObject("players").add(id.toString(), JsonParser.parseString(rawEntry));
		Files.writeString(file, root.toString());
	}
}
