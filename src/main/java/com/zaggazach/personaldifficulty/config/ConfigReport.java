package com.zaggazach.personaldifficulty.config;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Plain-text reports for {@code admin config} and {@code admin get}: effective values with their source, the setting's range and
 * apply timing, and stored overrides that are kept but not in use. Pure (no Minecraft types) so it can be unit-tested; the command
 * layer only sends the returned lines as chat messages (each line group is one message, so the full output stays readable).
 */
public final class ConfigReport {
	/** Settings per chat message in the table. */
	static final int LINES_PER_MESSAGE = 12;

	private ConfigReport() {
	}

	/** Banner lines that must be visible whatever the filter: the state of the overrides file. */
	public static List<String> banner(ConfigState state, OverridesStore store) {
		List<String> out = new ArrayList<>();
		out.add("Settings: " + state.overrides().size() + " override(s) active from " + store.file().getFileName() + " (state: " + store.state() + ").");
		if (store.blockedReason() != null) {
			out.add("WARNING: setting changes cannot be saved and the overrides file is not in use: " + store.blockedReason());
		}
		if (!store.notApplied().isEmpty()) {
			out.add("WARNING: " + store.notApplied().size() + " stored override entr" + (store.notApplied().size() == 1 ? "y is" : "ies are") + " kept but NOT applied (see the lines marked 'not applied' below, or the log).");
		}
		return out;
	}

	/**
	 * The table of effective values and their sources, optionally limited to keys starting with {@code prefix}
	 * (case-insensitive). Each element is one chat message.
	 */
	public static List<String> table(ConfigState state, OverridesStore store, String prefix) {
		String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
		List<String> lines = new ArrayList<>();
		for (SettingEntry entry : SettingRegistry.entries()) {
			if (entry instanceof SettingDef d) {
				if (d.key().startsWith(p)) {
					String mark = state.sourceOf(d) == ConfigState.Source.OVERRIDE ? "*" : "";
					lines.add(d.key() + " = " + StrictValue.format(state.effectiveValue(d)) + "  [" + state.sourceOf(d).label() + "]" + mark);
				}
			} else if (entry instanceof FamilyDef f) {
				Map<String, Double> effective = new TreeMap<>(f.read().apply(state.effective()));
				for (Map.Entry<String, Double> e : effective.entrySet()) {
					String key = f.prefix() + e.getKey().replace(':', '.');
					if (!key.startsWith(p)) {
						continue;
					}
					ConfigState.Source source = state.overrideFamilyValue(keyOf(f, e.getKey())).isPresent() ? ConfigState.Source.OVERRIDE : ConfigState.Source.PROPERTIES;
					lines.add(key + " = " + e.getValue() + "  [" + source.label() + "]" + (source == ConfigState.Source.OVERRIDE ? "*" : ""));
				}
			}
		}
		for (Map.Entry<String, String> e : new TreeMap<>(store.notApplied()).entrySet()) {
			if (e.getKey().toLowerCase(Locale.ROOT).startsWith(p)) {
				JsonElement raw = store.rawEntry(e.getKey()).orElse(null);
				lines.add(e.getKey() + " = " + raw + "  [stored override: not applied, " + e.getValue() + "]");
			}
		}
		List<String> messages = new ArrayList<>();
		if (lines.isEmpty()) {
			messages.add(p.isEmpty() ? "No settings." : "No setting key starts with '" + prefix + "'. Try a shorter prefix such as 'elite.' or 'ranged.'.");
			return messages;
		}
		messages.add("Effective settings" + (p.isEmpty() ? "" : " starting with '" + prefix + "'") + " (" + lines.size() + "): value  [source]; * marks an override. Use 'admin config <prefix>' to narrow, 'admin get <key>' for details.");
		for (int i = 0; i < lines.size(); i += LINES_PER_MESSAGE) {
			messages.add(String.join("\n", lines.subList(i, Math.min(lines.size(), i + LINES_PER_MESSAGE))));
		}
		return messages;
	}

	private static FamilyKey keyOf(FamilyDef f, String id) {
		int colon = id.indexOf(':');
		return new FamilyKey(f, id.substring(0, colon), id.substring(colon + 1));
	}

	/** Details of one scalar setting for {@code admin get}. */
	public static List<String> describe(SettingDef d, ConfigState state, OverridesStore store) {
		List<String> out = new ArrayList<>();
		out.add(d.key() + ": " + d.display());
		out.add("  value: " + StrictValue.format(state.effectiveValue(d)) + "  (source: " + state.sourceOf(d).label() + ")");
		if (state.sourceOf(d) == ConfigState.Source.OVERRIDE) {
			out.add("  without the override: " + StrictValue.format(state.lowerValue(d)) + "  (" + (state.properties().explicit().contains(d.key()) ? "properties file" : "default") + ")");
		}
		out.add("  default: " + StrictValue.format(d.defaultValue()) + "; allowed: " + d.rangeText());
		out.add("  applies: " + timing(d, state));
		appendNotApplied(out, d.key(), store);
		return out;
	}

	/** Details of one damage-type entry for {@code admin get}. */
	public static List<String> describe(FamilyKey k, ConfigState state, OverridesStore store, boolean existsOnServer) {
		List<String> out = new ArrayList<>();
		out.add(k.key() + ": damage multiplier for " + k.id() + " in " + k.family().profile().toUpperCase(Locale.ROOT) + " (replaces " + k.family().profile() + ".damage-multiplier for that damage type)");
		var v = state.effectiveFamilyValue(k);
		if (v.isPresent()) {
			out.add("  value: " + v.get() + "  (source: " + state.sourceOf(k).label() + ")");
			if (state.sourceOf(k) == ConfigState.Source.OVERRIDE) {
				out.add("  without the override: " + state.lowerFamilyValue(k).map(String::valueOf).orElse("no entry") + "  (properties file)");
			}
		} else {
			out.add("  no entry: " + k.family().profile() + ".damage-multiplier (" + state.effectiveValues().get(k.family().profile() + ".damage-multiplier") + ") applies to this damage type");
		}
		out.add("  allowed: a number from 0.0 to " + PdConfig.MAX_MULTIPLIER + (existsOnServer ? "" : "; WARNING: this damage type does not exist on this server"));
		out.add("  applies: immediately");
		appendNotApplied(out, k.key(), store);
		return out;
	}

	private static void appendNotApplied(List<String> out, String key, OverridesStore store) {
		String reason = store.notApplied().get(key);
		if (reason != null) {
			out.add("  NOTE: the stored override " + store.rawEntry(key).orElse(null) + " is kept but NOT applied (" + reason + "); 'admin set " + key + " <value>' replaces it, 'admin unset " + key + "' removes it.");
		}
	}

	private static String timing(SettingDef d, ConfigState state) {
		return switch (d.timing()) {
			case LIVE -> "immediately (LIVE)";
			case NEXT_SCAN -> "at each mob's next scan, within " + state.effective().eliteHunt().scanIntervalTicks() + " ticks (NEXT_SCAN)";
			case RESTART -> "after a restart (RESTART)";
		};
	}
}
