package com.zaggazach.personaldifficulty.config;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A validated key of the damage-type family: {@code <mode>.damage-type.<namespace>.<path>}.
 *
 * <p>Strict grammar (used by the commands and the overrides file): {@code <mode>} is {@code easy}, {@code hard} or {@code elite};
 * the first dot after {@code damage-type.} separates the namespace, which matches {@code [a-z0-9_-]+}, from the path, which matches
 * {@code [a-z0-9_.-]+}. Slashes in a path cannot be typed in a command and are therefore not supported here (such ids can only be
 * configured through the properties file, whose lenient parsing is unchanged).
 */
public record FamilyKey(FamilyDef family, String namespace, String path) {
	private static final String MARKER = ".damage-type.";
	private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_-]+");
	private static final Pattern PATH = Pattern.compile("[a-z0-9_.-]+");

	public sealed interface Parsed permits Ok, NotAFamilyKey, Invalid {
	}

	public record Ok(FamilyKey key) implements Parsed {
	}

	/** The text does not contain {@code .damage-type.} at all: it is not meant as a family key. */
	public record NotAFamilyKey() implements Parsed {
	}

	/** It looks like a family key but violates the grammar. */
	public record Invalid(String message) implements Parsed {
	}

	/** The damage type id, for example {@code minecraft:fall}. */
	public String id() {
		return namespace + ":" + path;
	}

	/** The canonical key, for example {@code easy.damage-type.minecraft.fall}. */
	public String key() {
		return family.prefix() + namespace + "." + path;
	}

	public static Parsed parse(String text) {
		int at = text.indexOf(MARKER);
		if (at < 0) {
			return new NotAFamilyKey();
		}
		String mode = text.substring(0, at);
		Optional<FamilyDef> family = SettingRegistry.family(mode);
		if (family.isEmpty()) {
			return new Invalid("'" + mode + "' has no per-damage-type multipliers: use easy, hard or elite (as in 'easy" + MARKER + "minecraft.fall')");
		}
		String rest = text.substring(at + MARKER.length());
		int dot = rest.indexOf('.');
		if (dot <= 0 || dot == rest.length() - 1) {
			return new Invalid("expected " + family.get().prefix() + "<namespace>.<path>, for example " + family.get().prefix() + "minecraft.fall");
		}
		String namespace = rest.substring(0, dot);
		String path = rest.substring(dot + 1);
		if (!NAMESPACE.matcher(namespace).matches()) {
			return new Invalid("the namespace '" + namespace + "' must consist of lower-case letters, digits, '_' and '-' only");
		}
		if (path.contains("/")) {
			return new Invalid("the path '" + path + "' contains '/', which cannot be typed in a command; configure such damage types in personaldifficulty.properties");
		}
		if (!PATH.matcher(path).matches()) {
			return new Invalid("the path '" + path + "' must consist of lower-case letters, digits, '_', '-' and '.' only");
		}
		return new Ok(new FamilyKey(family.get(), namespace, path));
	}
}
