package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.PersonalDifficultyMode.*;
import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.config.ConfigState;
import com.zaggazach.personaldifficulty.config.OverridesStore;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import com.zaggazach.personaldifficulty.config.SettingRegistry;
import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Capture;
import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Outcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;

/**
 * Phase 2: {@code admin get/set/unset/config} and the layered configuration, through the real Brigadier dispatcher with real
 * permission sets. Every test restores the overrides file and the shared configuration in {@code finally} (bodies are synchronous).
 */
public class ConfigCommandGameTests {
	private static Path overridesFile() {
		return rt().overrides().file();
	}

	private static Path tmpFile() {
		return overridesFile().resolveSibling(overridesFile().getFileName() + ".tmp");
	}

	/**
	 * Back to "no overrides": delete the file and every backup/evidence copy left by tests (the run directory persists between runs,
	 * so copies from earlier runs must not be counted by later ones) and let the runtime re-read it.
	 */
	private static void cleanOverrides() {
		try {
			Files.deleteIfExists(overridesFile());
			Files.deleteIfExists(tmpFile().resolve("blocker"));
			Files.deleteIfExists(tmpFile());
			for (String infix : List.of(".backup-", ".evidence-")) {
				for (Path debris : withInfix(infix)) {
					Files.deleteIfExists(debris);
				}
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		rt().reload();
		check(rt().overrides().blockedReason() == null && rt().overrides().layer().size() == 0, "overrides cleaned up");
	}

	private static Outcome admin(MinecraftServer server, String command) {
		Capture cap = new Capture();
		return run(server, "personaldifficulty admin " + command, console(server, cap), cap);
	}

	private static String sha(Path p) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private static String read(Path p) {
		try {
			return Files.readString(p);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static List<Path> withInfix(String infix) {
		try (var files = Files.list(overridesFile().getParent())) {
			return files.filter(x -> x.getFileName().toString().contains(OverridesStore.FILE_NAME + infix)).sorted().toList();
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	// ------------------------------------------------------------------ get / set / unset, live apply, the properties file

	@GameTest
	public void config_setGetUnset_applyLiveToRealDamage_andNeverTouchThePropertiesFile(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel level = h.getLevel();
		ServerPlayer easy = player(h, "pd_cfg_easy", EASY);
		ServerPlayer elite = player(h, "pd_cfg_elite", ELITE);
		String propsHash = sha(rt().configFile());
		try {
			Outcome get = admin(server, "get easy.damage-multiplier");
			check(get.result() == 1 && get.output().contains("value: 0.6  (source: properties)") && get.output().contains("allowed: a number from 0.0 to 10.0") && get.output().contains("immediately (LIVE)"), "get: " + get);
			near(6.0F, lost(easy, level.damageSources().fall(), 10.0F), "Easy default x0.6");

			Outcome set = admin(server, "set easy.damage-multiplier 0.7");
			check(set.result() == 1 && set.output().contains("is now 0.7") && set.output().contains("applies immediately"), "set: " + set);
			near(7.0F, lost(easy, level.damageSources().fall(), 10.0F), "the new multiplier is live at once, no restart");
			check(admin(server, "get easy.damage-multiplier").output().contains("value: 0.7  (source: override)"), "get shows the override");
			check(read(overridesFile()).equals("{\"version\":1,\"values\":{\"easy.damage-multiplier\":0.7}}"), "file: " + read(overridesFile()));

			check(admin(server, "set elite.damage-multiplier 3").result() == 1, "elite multiplier");
			near(12.0F, lost(elite, level.damageSources().fall(), 4.0F), "Elite x3 is live");

			Outcome same = admin(server, "set easy.damage-multiplier 0.6"); // equal to the default (lower layer): D1 removes the override
			check(same.result() == 1 && same.output().contains("override was removed"), "equal to the lower layer removes the override: " + same);
			near(6.0F, lost(easy, level.damageSources().fall(), 10.0F), "back to the default");

			Outcome unset = admin(server, "unset elite.damage-multiplier");
			check(unset.result() == 1 && unset.output().contains("now 2.0 (properties)"), "unset: " + unset);
			near(8.0F, lost(elite, level.damageSources().fall(), 4.0F), "Elite back to x2");
			check(admin(server, "unset elite.damage-multiplier").output().contains("There is no override"), "idempotent and honest");

			check(sha(rt().configFile()).equals(propsHash), "personaldifficulty.properties is byte-identical after every set and unset");
		} finally {
			cleanOverrides();
		}
		h.succeed();
	}

	@GameTest
	public void config_sources_theShippedTemplateListsEveryKey_aKeyAbsentFromTheFileIsLabelledDefault(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		try {
			check(admin(server, "get hard.damage-multiplier").output().contains("source: properties"), "a key present in the generated default file is a properties value");
			withConfig("# an empty hand-edited file\n", () -> {
				check(admin(server, "get hard.damage-multiplier").output().contains("value: 1.25  (source: default)"), "absent from the file: default");
				check(admin(server, "config hard.damage-multiplier").output().contains("hard.damage-multiplier = 1.25  [default]"), "config agrees");
			});
		} finally {
			cleanOverrides();
		}
		h.succeed();
	}

	@GameTest
	public void config_damageTypeFamily_setLiveWithRealRegistryValidation(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel level = h.getLevel();
		ServerPlayer easy = player(h, "pd_cfg_dt", EASY);
		try {
			Outcome set = admin(server, "set easy.damage-type.minecraft.fall 0.1");
			check(set.result() == 1 && set.output().contains("is now 0.1"), "set: " + set);
			near(1.0F, lost(easy, level.damageSources().fall(), 10.0F), "fall damage uses the per-damage-type multiplier live");
			check(admin(server, "get easy.damage-type.minecraft.fall").output().contains("value: 0.1  (source: override)"), "get");
			check(admin(server, "config easy.damage-type.").output().contains("easy.damage-type.minecraft.fall = 0.1  [override]*"), "config shows it");
			check(admin(server, "unset easy.damage-type.minecraft.fall").result() == 1, "unset");
			near(6.0F, lost(easy, level.damageSources().fall(), 10.0F), "back to the profile multiplier");

			// identifiers absent from the live registry are refused, with close matches
			Outcome absent = admin(server, "set easy.damage-type.minecraft.fal 0.5");
			check(absent.result() == 0 && absent.output().contains("Unknown damage type 'minecraft:fal'") && absent.output().contains("Did you mean: ") && absent.output().contains("minecraft.fall"), "absent id: " + absent);
			check(admin(server, "set easy.damage-type.nomod.nothing 0.5").output().contains("Unknown damage type"), "unknown namespace");
			check(read(overridesFile()).equals("{\"version\":1,\"values\":{}}"), "nothing written for refused requests: " + read(overridesFile()));
			// malformed keys are refused by the grammar
			for (String bad : List.of("normal.damage-type.minecraft.fall", "easy.damage-type.Minecraft.fall", "easy.damage-type.minecraft", "easy.damage-type..fall")) {
				check(admin(server, "set " + bad + " 0.5").result() == 0, "refused: " + bad);
			}
			check(admin(server, "set easy.damage-type.minecraft.fall 11").output().contains("a number from 0.0 to 10.0"), "range named");
		} finally {
			cleanOverrides();
		}
		h.succeed();
	}

	@GameTest
	public void config_everyRefusedSetNamesTheRangeOrSuggestions_andChangesNothing(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ConfigState before = rt().state();
		try {
			String[][] cases = {
				{"set easy.damage-multiplier 11", "a number from 0.0 to 10.0"},
				{"set easy.damage-multiplier -1", "a number from 0.0 to 10.0"},
				{"set easy.damage-multiplier abc", "not a valid number"},
				{"set easy.damage-multiplier NaN", "not a valid number"},
				{"set elite.priority.scan-interval-ticks 4", "a whole number from 5 to 40"},
				{"set elite.priority.scan-interval-ticks 10.5", "not a whole number"},
				{"set elite.effects.enabled maybe", "true or false"},
				{"set easy.damage-multipliers 1", "Did you mean: easy.damage-multiplier"},
				{"set totally.unknown 1", "Unknown setting"},
				{"unset totally.unknown", "Unknown setting"},
				{"get totally.unknown", "Unknown setting"},
				{"get easy.damage-type.Bad.x", "namespace"}
			};
			for (String[] c : cases) {
				Outcome o = admin(server, c[0]);
				check(o.result() == 0 && o.output().contains(c[1]), c[0] + " -> " + o);
			}
			check(rt().state() == before, "no refusal publishes anything");
			check(!Files.exists(overridesFile()), "and nothing was written");
		} finally {
			cleanOverrides();
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ admin config

	@GameTest
	public void config_overviewKeepsTheServerOverviewAndAddsTheSourcedTable_withAUsableFilter(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		try {
			admin(server, "set pvp.damage-multiplier 2.5");
			Outcome all = admin(server, "config");
			check(all.result() == 1, "ok");
			// the existing overview and the Phase 1 diagnostics are still there
			for (String line : List.of("Personal Difficulty (server overview)", "Players with a stored record", "Config file:", "Selectable difficulties")) {
				check(all.output().contains(line), "overview line kept: " + line + "\n" + all.output());
			}
			// the new sections
			check(all.output().contains("Settings: 1 override(s) active"), "banner: " + all.output());
			for (var d : SettingRegistry.scalars()) {
				check(all.output().lines().filter(l -> l.startsWith(d.key() + " = ")).count() == 1, d.key() + " is listed exactly once");
			}
			check(all.output().contains("pvp.damage-multiplier = 2.5  [override]*"), "source shown");
			check(all.output().contains("hard.damage-multiplier = 1.25  [properties]"), "the shipped template lists every key, so its values are labelled properties");
			check(all.output().length() < 20000, "the unfiltered output stays a sane size: " + all.output().length());

			Outcome filtered = admin(server, "config elite.ai.");
			check(filtered.result() == 1 && filtered.output().contains("elite.ai.acquisition-range-multiplier = ") && !filtered.output().contains("pvp.damage-multiplier"), "filter: " + filtered);
			check(!filtered.output().contains("Players with a stored record"), "a filtered query omits the server overview");
			check(filtered.output().contains("Settings: 1 override(s) active"), "but the overrides banner is always shown");
			check(admin(server, "config nothing.here").output().contains("No setting key starts with 'nothing.here'"), "no match is explained");
		} finally {
			cleanOverrides();
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ reload, recovery, E27

	@GameTest
	public void config_corruptOverridesFile_isVisible_neverOverwritten_andReloadRecoversWithoutRestart(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		cleanOverrides();
		try {
			admin(server, "set pvp.damage-multiplier 2.5");
			String good = read(overridesFile());
			try {
				Files.writeString(overridesFile(), "{ truncated by something");
			} catch (IOException e) {
				throw new AssertionError(e);
			}
			Outcome failed = admin(server, "reload");
			check(failed.result() == 0 && failed.output().contains("Reload incomplete") && failed.output().contains("overrides file") && failed.output().contains("previous values stay in force"), "reload: " + failed);
			check(rt().config().pvpDamageMultiplier() == 2.5, "the last good overrides stay in force");
			check(admin(server, "config").output().contains("WARNING: setting changes cannot be saved"), "visible in admin config");
			Outcome refused = admin(server, "set self.damage-multiplier 2");
			check(refused.result() == 0 && refused.output().contains("admin reload"), "changes refused with the way out: " + refused);
			check(read(overridesFile()).equals("{ truncated by something"), "never overwritten");
			check(withInfix(".evidence-").size() == 1 && read(withInfix(".evidence-").get(0)).equals("{ truncated by something"), "verified evidence copy");
			check(admin(server, "reload").result() == 0 && withInfix(".evidence-").size() == 1, "reloading the same bad file adds no evidence");

			try {
				Files.writeString(overridesFile(), good);
			} catch (IOException e) {
				throw new AssertionError(e);
			}
			Outcome ok = admin(server, "reload");
			check(ok.result() == 1 && ok.output().contains("setting overrides"), "repaired: " + ok);
			check(admin(server, "set self.damage-multiplier 2").result() == 1, "changes work again");
		} finally {
			cleanOverrides();
		}
		h.succeed();
	}

	@GameTest
	public void config_invalidStoredOverride_isIgnoredReported_otherOverridesStay_andAValidSetReplacesIt(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		cleanOverrides();
		try {
			String bad = "{\"version\":1,\"values\":{\"pvp.damage-multiplier\":\"banana\",\"self.damage-multiplier\":3.0,\"easy.damage-type.mod.gone\":0.5}}";
			try {
				Files.writeString(overridesFile(), bad);
			} catch (IOException e) {
				throw new AssertionError(e);
			}
			Outcome reload = admin(server, "reload");
			check(reload.result() == 1 && reload.output().contains("banana") && reload.output().contains("does not exist on this server"), "reported once: " + reload);
			check(rt().config().pvpDamageMultiplier() == 1.0, "the invalid value is ignored: the lower layer applies");
			check(rt().config().selfDamageMultiplier() == 3.0, "the unrelated valid override is active");
			check(read(overridesFile()).equals(bad), "reload does not rewrite the file");
			Outcome get = admin(server, "get pvp.damage-multiplier");
			check(get.output().contains("NOT applied") && get.output().contains("banana"), "get names the unused stored value: " + get);
			check(admin(server, "config").output().contains("[stored override: not applied, invalid value"), "config lists it");

			Outcome fix = admin(server, "set pvp.damage-multiplier 2.5");
			check(fix.result() == 1 && fix.output().contains("was not in use") && fix.output().contains("banana"), "explicit set replaces it: " + fix);
			check(rt().config().pvpDamageMultiplier() == 2.5 && rt().config().selfDamageMultiplier() == 3.0, "new value active, other override intact");
			check(withInfix(".backup-").stream().anyMatch(b -> read(b).equals(bad)), "the previous file is in a verified backup");
			check(read(overridesFile()).contains("easy.damage-type.mod.gone"), "the not-applied damage type entry was preserved");

			// a stale entry for a damage type that does not exist can still be removed explicitly
			Outcome stale = admin(server, "unset easy.damage-type.mod.gone");
			check(stale.result() == 1 && !read(overridesFile()).contains("mod.gone"), "stale entry removed: " + stale);
		} finally {
			cleanOverrides();
		}
		h.succeed();
	}

	@GameTest
	public void config_aFileEditedBehindTheModsBack_isNotOverwritten_untilReload(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		try {
			admin(server, "set pvp.damage-multiplier 2.5");
			String external = "{\"version\":1,\"values\":{\"pvp.damage-multiplier\":6.0}}";
			try {
				Files.writeString(overridesFile(), external);
			} catch (IOException e) {
				throw new AssertionError(e);
			}
			Outcome refused = admin(server, "set self.damage-multiplier 2");
			check(refused.result() == 0 && refused.output().contains("changed outside the mod"), "refused: " + refused);
			check(read(overridesFile()).equals(external) && rt().config().pvpDamageMultiplier() == 2.5, "file untouched, active config unchanged");
			check(admin(server, "config").output().contains("changed outside the mod"), "visible to administrators");
			Outcome reload = admin(server, "reload");
			check(reload.result() == 1 && rt().config().pvpDamageMultiplier() == 6.0, "reload adopts the edited file on purpose: " + reload);
			check(admin(server, "set self.damage-multiplier 2").result() == 1, "changes work again");
		} finally {
			cleanOverrides();
		}
		h.succeed();
	}

	@GameTest
	public void config_aFailedSave_refusesAndLeavesTheActiveConfigurationAlone(GameTestHelper h) throws IOException {
		MinecraftServer server = h.getLevel().getServer();
		try {
			admin(server, "set pvp.damage-multiplier 2.5");
			ConfigState before = rt().state();
			String fileBefore = read(overridesFile());
			Files.createDirectories(tmpFile().resolve("blocker")); // a directory where the temp file must go: every write fails
			Outcome set = admin(server, "set pvp.damage-multiplier 3");
			Outcome unset = admin(server, "unset pvp.damage-multiplier");
			for (Outcome o : List.of(set, unset)) {
				check(o.result() == 0 && o.output().contains("nothing was changed"), "refused: " + o);
			}
			check(rt().state() == before && rt().config().pvpDamageMultiplier() == 2.5, "the published configuration is the same object");
			check(read(overridesFile()).equals(fileBefore), "file byte-identical");
			Files.deleteIfExists(tmpFile().resolve("blocker"));
			Files.deleteIfExists(tmpFile());
			check(admin(server, "set pvp.damage-multiplier 3").result() == 1 && rt().config().pvpDamageMultiplier() == 3.0, "works once the problem is gone");
		} finally {
			cleanOverrides();
		}
		h.succeed();
	}

	@GameTest
	public void config_propertiesLayerKeepsItsLenientSemantics_andOverridesBeatIt(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		try {
			withConfig("easy.damage-multiplier=99\nhard.hunger-multiplier=abc\nelite.priority.scan-interval-ticks=7.5\n", () -> {
				check(rt().config().easy().damageMultiplier() == 10.0, "out-of-range value is clamped, as always");
				check(rt().config().hard().hungerMultiplier() == 1.25, "invalid value falls back to the default, as always");
				check(rt().config().eliteHunt().scanIntervalTicks() == 8, "7.5 rounds to 8 in the lenient layer");
				check(admin(server, "get easy.damage-multiplier").output().contains("source: properties"), "clamped value is labelled properties");
				check(admin(server, "get hard.hunger-multiplier").output().contains("source: default"), "fallback is labelled default");
				// an override beats the properties file
				check(admin(server, "set easy.damage-multiplier 0.4").result() == 1 && rt().config().easy().damageMultiplier() == 0.4, "override wins");
				// strict layer refuses what the lenient layer would accept
				check(admin(server, "set elite.priority.scan-interval-ticks 7.5").result() == 0, "admin set is strict");
			});
		} finally {
			cleanOverrides();
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ completion and permissions

	@GameTest
	public void config_tabCompletion_keysValuesOverridesAndPrefixes(GameTestHelper h) throws Exception {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_cfg_tab", NORMAL);
		var d = server.getCommands().getDispatcher();
		Capture cap = new Capture();
		CommandSourceStack op = as(p, LevelBasedPermissionSet.GAMEMASTER, cap);
		CommandSourceStack plain = as(p, PermissionSet.NO_PERMISSIONS, cap);
		try {
			List<String> keys = d.getCompletionSuggestions(d.parse("personaldifficulty admin set ", op)).get().getList().stream().map(s -> s.getText()).toList();
			check(keys.containsAll(SettingRegistry.scalars().stream().map(x -> x.key()).toList()), "all scalar keys offered");
			check(keys.contains("easy.damage-type.") && keys.contains("elite.damage-type."), "family prefixes offered");
			List<String> fam = d.getCompletionSuggestions(d.parse("personaldifficulty admin set easy.damage-type.minecraft.fa", op)).get().getList().stream().map(s -> s.getText()).toList();
			check(fam.contains("easy.damage-type.minecraft.fall"), "live damage types offered once the prefix is typed: " + fam);
			List<String> bools = d.getCompletionSuggestions(d.parse("personaldifficulty admin set elite.effects.enabled ", op)).get().getList().stream().map(s -> s.getText()).sorted().toList();
			check(bools.equals(List.of("false", "true")), "booleans: " + bools);
			List<String> nums = d.getCompletionSuggestions(d.parse("personaldifficulty admin set easy.damage-multiplier ", op)).get().getList().stream().map(s -> s.getText()).toList();
			check(nums.contains("0.6") && nums.contains("0.0") && nums.contains("10.0"), "current/default/bounds: " + nums);
			check(d.getCompletionSuggestions(d.parse("personaldifficulty admin unset ", op)).get().getList().isEmpty(), "unset offers nothing while there are no overrides");
			admin(server, "set pvp.damage-multiplier 2");
			List<String> unset = d.getCompletionSuggestions(d.parse("personaldifficulty admin unset ", op)).get().getList().stream().map(s -> s.getText()).toList();
			check(unset.equals(List.of("pvp.damage-multiplier")), "unset offers only overridden keys: " + unset);
			check(d.getCompletionSuggestions(d.parse("personaldifficulty admin config ", op)).get().getList().stream().anyMatch(s -> s.getText().equals("elite.")), "config offers prefixes");
			var adminNode = d.getRoot().getChild("personaldifficulty").getChild("admin");
			check(adminNode.canUse(op) && !adminNode.canUse(plain), "the admin subtree (and so get/set/unset) is usable only by operators");
		} finally {
			cleanOverrides();
		}
		h.succeed();
	}

	private static final List<String> CONFIG_COMMANDS = List.of(
		"personaldifficulty admin get pvp.damage-multiplier",
		"personaldifficulty admin set pvp.damage-multiplier 4",
		"personaldifficulty admin unset pvp.damage-multiplier",
		"personaldifficulty admin config",
		"personaldifficulty admin config elite."
	);

	@GameTest
	public void config_newCommands_permissionMatrix_nonOperatorLevel1Level2ConsoleCommandBlockExecute(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer actor = player(h, "pd_cfg_actor", NORMAL);
		ServerPlayer other = player(h, "pd_cfg_other", NORMAL);
		Capture cap = new Capture();
		try {
			ConfigState before = rt().state();
			for (PermissionSet denied : List.of(PermissionSet.NO_PERMISSIONS, LevelBasedPermissionSet.ALL, LevelBasedPermissionSet.MODERATOR)) {
				for (String cmd : CONFIG_COMMANDS) {
					Outcome o = run(server, cmd, as(actor, denied, cap), cap);
					check(o.error() != null || (o.result() == 0 && !o.output().contains("is now") && !o.output().contains("Effective settings")), "denied for " + denied + ": " + cmd + " -> " + o);
				}
				Outcome exec = run(server, "execute as pd_cfg_other run personaldifficulty admin set pvp.damage-multiplier 4", as(actor, denied, cap), cap);
				check(exec.error() != null, "/execute cannot launder permissions: " + exec);
				check(rt().state() == before && !Files.exists(overridesFile()), "nothing changed by any denied attempt (" + denied + ")");
			}
			Capture console = new Capture();
			Capture block = new Capture();
			java.util.Map<CommandSourceStack, Capture> allowed = new java.util.LinkedHashMap<>();
			allowed.put(console(server, console), console);
			allowed.put(commandBlock(h.getLevel(), block), block);
			allowed.put(as(actor, LevelBasedPermissionSet.GAMEMASTER, cap), cap);
			allowed.put(as(actor, LevelBasedPermissionSet.ADMIN, cap), cap);
			for (var entry : allowed.entrySet()) {
				CommandSourceStack src = entry.getKey();
				Capture c = entry.getValue();
				String who = src.getTextName();
				check(run(server, "personaldifficulty admin set pvp.damage-multiplier 4", src, c).result() == 1 && rt().config().pvpDamageMultiplier() == 4.0, who + ": set " + c.all());
				check(run(server, "personaldifficulty admin get pvp.damage-multiplier", src, c).output().contains("source: override"), who + ": get");
				check(run(server, "personaldifficulty admin config elite.", src, c).result() == 1, who + ": config");
				check(run(server, "personaldifficulty admin unset pvp.damage-multiplier", src, c).result() == 1 && rt().config().pvpDamageMultiplier() == 1.0, who + ": unset " + c.all());
			}
			Outcome exec = run(server, "execute as pd_cfg_other run personaldifficulty admin set pvp.damage-multiplier 4", as(actor, LevelBasedPermissionSet.GAMEMASTER, cap), cap);
			check(exec.error() == null && rt().config().pvpDamageMultiplier() == 4.0, "an operator's /execute chain keeps the operator's own permission: " + exec);
			admin(server, "unset pvp.damage-multiplier");

			// real /op and /deop take effect at once
			server.getPlayerList().op(actor.nameAndId(), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
			check(run(server, "personaldifficulty admin set pvp.damage-multiplier 4", actor.createCommandSourceStack().withSource(cap), cap).result() == 1, "level 2 works at once");
			server.getPlayerList().deop(actor.nameAndId());
			check(run(server, "personaldifficulty admin unset pvp.damage-multiplier", actor.createCommandSourceStack().withSource(cap), cap).error() != null && rt().config().pvpDamageMultiplier() == 4.0, "after /deop the same player is refused at once");
		} finally {
			server.getPlayerList().deop(actor.nameAndId());
			cleanOverrides();
		}
		h.succeed();
	}

	@GameTest
	public void config_noOverridesFile_meansTheConfigurationIsExactlyTheProperties(GameTestHelper h) {
		try {
			cleanOverrides();
			check(!Files.exists(overridesFile()), "no overrides file");
			var fromFile = PdConfigLoader.load(rt().configFile());
			check(!fromFile.readFailed() && fromFile.config().equals(rt().config()), "the running configuration equals what the properties file alone gives");
			check(rt().reload().anyFailed() == false && !Files.exists(overridesFile()), "reading never creates the overrides file");
		} finally {
			cleanOverrides();
		}
		h.succeed();
	}
}
