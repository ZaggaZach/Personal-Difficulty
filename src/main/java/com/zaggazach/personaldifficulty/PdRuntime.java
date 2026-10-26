package com.zaggazach.personaldifficulty;

import com.zaggazach.personaldifficulty.config.Closest;
import com.zaggazach.personaldifficulty.config.ConfigState;
import com.zaggazach.personaldifficulty.config.DamageTypeCatalog;
import com.zaggazach.personaldifficulty.config.FamilyKey;
import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import com.zaggazach.personaldifficulty.config.PropertiesLayer;
import com.zaggazach.personaldifficulty.config.SetResult;
import com.zaggazach.personaldifficulty.config.SettingDef;
import com.zaggazach.personaldifficulty.config.SettingRegistry;
import com.zaggazach.personaldifficulty.config.StrictValue;
import com.zaggazach.personaldifficulty.config.OverridesStore;
import com.zaggazach.personaldifficulty.store.AvailabilityPolicy;
import com.zaggazach.personaldifficulty.store.PlayerSettings;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Everything the mixins and commands need for the running server: the layered configuration, the server's availability policy and
 * the per-player selections.
 *
 * <p>There is exactly one server per JVM (dedicated or integrated), so a single volatile "current"
 * reference is created in SERVER_STARTING and dropped in SERVER_STOPPED. It carries no per-player
 * or per-world state of its own beyond the {@link PlayerSettings} that belong to that server's world.
 * Before startup / after shutdown mixins see {@code null} and leave vanilla behaviour untouched.
 *
 * <h2>Configuration state</h2>
 * The configuration is one immutable {@link ConfigState} (properties layer, overrides layer, effective {@link PdConfig})
 * published through one volatile reference. {@code admin set/unset} compute the next state first, write the overrides file, and
 * publish the new state only after the write succeeded, so a failed change can never leave a different configuration in force.
 * Changes and reloads run on the server thread and are additionally serialised by this object's monitor.
 */
public final class PdRuntime {
	private static volatile @Nullable PdRuntime current;

	/**
	 * Result of {@link #reload()}: each layer is re-read independently and keeps its last good data if its file cannot be used.
	 * {@code config()} describes the properties file (kept for callers that only care whether it could be read).
	 */
	public record Reload(
		PdConfigLoader.Result config,
		AvailabilityPolicy.Parsed availability,
		OverridesStore.ReloadOutcome overrides,
		List<String> overridesWarnings,
		@Nullable String overridesBlocked
	) {
		public boolean overridesFailed() {
			return overrides == OverridesStore.ReloadOutcome.KEPT_LAST_GOOD;
		}

		public boolean anyFailed() {
			return config.readFailed() || availability.failed() || overridesFailed();
		}

		/** Every warning of the three layers, each distinct problem once, in order. */
		public List<String> warnings() {
			LinkedHashSet<String> all = new LinkedHashSet<>(config.warnings());
			all.addAll(availability.warnings());
			all.addAll(overridesWarnings);
			return List.copyOf(all);
		}
	}

	private final Path configFile;
	private volatile ConfigState state;
	private final OverridesStore overrides;
	private final DamageTypeCatalog damageTypes;
	private final PlayerSettings settings;
	private final AvailabilityPolicy availability;

	public PdRuntime(Path configFile, ConfigState state, OverridesStore overrides, DamageTypeCatalog damageTypes, PlayerSettings settings, AvailabilityPolicy availability) {
		this.configFile = configFile;
		this.state = state;
		this.overrides = overrides;
		this.damageTypes = damageTypes;
		this.settings = settings;
		this.availability = availability;
	}

	public static @Nullable PdRuntime get() {
		return current;
	}

	public static void install(@Nullable PdRuntime runtime) {
		current = runtime;
	}

	public PdConfig config() {
		return state.effective();
	}

	public ConfigState state() {
		return state;
	}

	public OverridesStore overrides() {
		return overrides;
	}

	public DamageTypeCatalog damageTypes() {
		return damageTypes;
	}

	public PlayerSettings settings() {
		return settings;
	}

	public AvailabilityPolicy availability() {
		return availability;
	}

	public PersonalDifficultyMode modeOf(UUID player) {
		return settings.get(player);
	}

	public Path configFile() {
		return configFile;
	}

	/**
	 * Re-reads the properties file, the overrides file and the availability file. Each layer is applied only if it can be used;
	 * otherwise that layer keeps its last good data (the properties layer its previous values, the overrides their last good
	 * contents with changes still refused). The overrides file is always re-read: this is the administrator's recovery act.
	 */
	public synchronized Reload reload() {
		PdConfigLoader.LayerResult props = PdConfigLoader.loadLayer(configFile);
		PropertiesLayer propertiesLayer = props.readFailed() ? state.properties() : props.layer();
		OverridesStore.ReloadOutcome outcome = overrides.reload();
		ConfigState next = ConfigState.compose(propertiesLayer, overrides.layer());
		this.state = next;
		PdConfigLoader.Result configResult = new PdConfigLoader.Result(next.effective(), props.warnings(), props.readFailed());
		return new Reload(configResult, availability.reload(), outcome, overrides.loadWarnings(), overrides.blockedReason());
	}

	// ------------------------------------------------------------------ admin set / unset

	/**
	 * {@code admin set <key> <value>}: strict validation, then prepare, write and only then publish. A value equal to the one below
	 * the overrides layer is never stored: it removes any existing override instead (so later edits of the properties file apply).
	 */
	public synchronized SetResult setValue(String key, String valueText) {
		ConfigState cur = state;
		Optional<SettingDef> def = SettingRegistry.find(key);
		if (def.isPresent()) {
			return setScalar(cur, def.get(), valueText);
		}
		return switch (FamilyKey.parse(key)) {
			case FamilyKey.Ok ok -> setFamily(cur, ok.key(), valueText);
			case FamilyKey.Invalid invalid -> refused("Invalid damage-type key '" + key + "': " + invalid.message() + ".");
			case FamilyKey.NotAFamilyKey ignored -> refused(unknownKey(key));
		};
	}

	/** {@code admin unset <key>}: removes the override of that one key; the value falls back to the properties file or the default. */
	public synchronized SetResult unsetValue(String key) {
		ConfigState cur = state;
		Optional<SettingDef> def = SettingRegistry.find(key);
		FamilyKey family = null;
		if (def.isEmpty()) {
			switch (FamilyKey.parse(key)) {
				case FamilyKey.Ok ok -> family = ok.key();
				case FamilyKey.Invalid invalid -> {
					return refused("Invalid damage-type key '" + key + "': " + invalid.message() + ".");
				}
				case FamilyKey.NotAFamilyKey ignored -> {
					return refused(unknownKey(key));
				}
			}
		}
		OverridesStore.Prepared prepared;
		try {
			prepared = overrides.prepare(new OverridesStore.Remove(key));
		} catch (IOException e) {
			return refused("Settings cannot be changed: " + e.getMessage());
		}
		if (!prepared.changesFile()) {
			return new SetResult(SetResult.Kind.UNCHANGED, "There is no override for " + key + "; it is " + describeEffective(cur, def.orElse(null), family) + ".");
		}
		ConfigState next = ConfigState.compose(cur.properties(), prepared.nextLayer());
		try {
			overrides.commit(prepared);
		} catch (IOException e) {
			return refused("Could not save the change to " + key + ", so nothing was changed: " + e.getMessage());
		}
		state = next;
		boolean wasActive = def.isPresent() ? cur.overrides().scalars().containsKey(key) : family != null && cur.overrideFamilyValue(family).isPresent();
		return new SetResult(SetResult.Kind.APPLIED, "Override removed; " + key + " is now " + describeEffective(next, def.orElse(null), family) + "."
			+ (wasActive ? "" : " (The removed stored value was not in use.)") + timing(def.orElse(null), next));
	}

	private SetResult setScalar(ConfigState cur, SettingDef d, String valueText) {
		StrictValue.Parsed parsed = StrictValue.parse(d, valueText);
		if (parsed instanceof StrictValue.Error err) {
			return refused(err.message());
		}
		Object v = ((StrictValue.Ok) parsed).value();
		Object lower = cur.lowerValue(d);
		boolean equalsLower = same(lower, v);
		OverridesStore.Change change = equalsLower ? new OverridesStore.Remove(d.key()) : new OverridesStore.Put(d.key(), StrictValue.toJson(v));
		OverridesStore.Prepared prepared;
		try {
			prepared = overrides.prepare(change);
		} catch (IOException e) {
			return refused("Settings cannot be changed: " + e.getMessage());
		}
		String lowerSource = cur.properties().explicit().contains(d.key()) ? "the properties file" : "the default";
		if (!prepared.changesFile()) {
			return new SetResult(SetResult.Kind.UNCHANGED, d.key() + " is already " + StrictValue.format(v) + (equalsLower ? " (from " + lowerSource + ")" : " (an override)") + "; nothing to store.");
		}
		ConfigState next = ConfigState.compose(cur.properties(), prepared.nextLayer());
		try {
			overrides.commit(prepared);
		} catch (IOException e) {
			return refused("Could not save " + d.key() + ", so nothing was changed: " + e.getMessage());
		}
		state = next;
		String unusedNote = prepared.replaced() != null && !cur.overrides().scalars().containsKey(d.key())
			? " The stored value " + prepared.replaced() + " was not in use and has been replaced (the previous file is in a verified backup)." : "";
		if (equalsLower) {
			return new SetResult(SetResult.Kind.APPLIED, d.key() + " is already " + StrictValue.format(v) + " from " + lowerSource
				+ ", so the override was removed instead of storing an equal value (later edits of the properties file will apply)." + unusedNote + timing(d, next));
		}
		return new SetResult(SetResult.Kind.APPLIED, d.key() + " is now " + StrictValue.format(v) + " (override; was " + StrictValue.format(cur.effectiveValue(d)) + " from "
			+ cur.sourceOf(d).label() + ")." + unusedNote + timing(d, next));
	}

	private SetResult setFamily(ConfigState cur, FamilyKey fk, String valueText) {
		if (!damageTypes.exists(fk.id())) {
			List<String> close = Closest.of(fk.id(), damageTypes.ids(), 3);
			return refused("Unknown damage type '" + fk.id() + "' on this server, so it cannot be configured." + (close.isEmpty() ? "" : " Did you mean: " + String.join(", ", close.stream().map(PdRuntime::asKeyPart).toList()) + "?"));
		}
		StrictValue.Parsed parsed = StrictValue.parseMultiplier(fk.key(), valueText);
		if (parsed instanceof StrictValue.Error err) {
			return refused(err.message());
		}
		double v = (Double) ((StrictValue.Ok) parsed).value();
		Optional<Double> lower = cur.lowerFamilyValue(fk);
		boolean equalsLower = lower.isPresent() && Double.compare(lower.get(), v) == 0;
		OverridesStore.Change change = equalsLower ? new OverridesStore.Remove(fk.key()) : new OverridesStore.Put(fk.key(), StrictValue.toJson(v));
		OverridesStore.Prepared prepared;
		try {
			prepared = overrides.prepare(change);
		} catch (IOException e) {
			return refused("Settings cannot be changed: " + e.getMessage());
		}
		if (!prepared.changesFile()) {
			return new SetResult(SetResult.Kind.UNCHANGED, fk.key() + " is already " + v + (equalsLower ? " (from the properties file)" : " (an override)") + "; nothing to store.");
		}
		ConfigState next = ConfigState.compose(cur.properties(), prepared.nextLayer());
		try {
			overrides.commit(prepared);
		} catch (IOException e) {
			return refused("Could not save " + fk.key() + ", so nothing was changed: " + e.getMessage());
		}
		state = next;
		String unusedNote = prepared.replaced() != null && cur.overrideFamilyValue(fk).isEmpty()
			? " The stored value " + prepared.replaced() + " was not in use and has been replaced (the previous file is in a verified backup)." : "";
		if (equalsLower) {
			return new SetResult(SetResult.Kind.APPLIED, fk.key() + " is already " + v + " from the properties file, so the override was removed instead of storing an equal value." + unusedNote + " It applies immediately.");
		}
		return new SetResult(SetResult.Kind.APPLIED, fk.key() + " is now " + v + " (override; was " + describeEffective(cur, null, fk) + ")." + unusedNote + " It applies immediately.");
	}

	private static String asKeyPart(String id) {
		return id.replace(':', '.');
	}

	private static boolean same(Object a, Object b) {
		if (a instanceof Double x && b instanceof Double y) {
			return Double.compare(x, y) == 0;
		}
		return a.equals(b);
	}

	private static SetResult refused(String message) {
		return new SetResult(SetResult.Kind.REFUSED, message);
	}

	/** The refusal text for a key that is neither a setting nor a damage-type key, with close matches. */
	public static String unknownKey(String key) {
		List<String> close = Closest.of(key, SettingRegistry.scalars().stream().map(SettingDef::key).toList(), 3);
		return "Unknown setting '" + key + "'." + (close.isEmpty() ? " Use 'admin config' to list the settings." : " Did you mean: " + String.join(", ", close) + "?");
	}

	/** "2.5 (override)" / "no entry; uses easy.damage-multiplier = 0.6" for the effective value of a setting. */
	public static String describeEffective(ConfigState s, @Nullable SettingDef d, @Nullable FamilyKey fk) {
		if (d != null) {
			return StrictValue.format(s.effectiveValue(d)) + " (" + s.sourceOf(d).label() + ")";
		}
		Optional<Double> v = s.effectiveFamilyValue(fk);
		return v.map(x -> x + " (" + s.sourceOf(fk).label() + ")").orElse("no entry; the profile's damage-multiplier applies");
	}

	private static String timing(@Nullable SettingDef d, ConfigState next) {
		if (d == null) {
			return "";
		}
		return switch (d.timing()) {
			case LIVE -> " It applies immediately.";
			case NEXT_SCAN -> " It applies at each mob's next scan (within " + next.effective().eliteHunt().scanIntervalTicks() + " ticks).";
			case RESTART -> " It applies after a restart.";
		};
	}
}
