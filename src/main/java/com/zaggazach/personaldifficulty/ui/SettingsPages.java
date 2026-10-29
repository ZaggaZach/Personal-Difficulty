package com.zaggazach.personaldifficulty.ui;

import com.zaggazach.personaldifficulty.config.SettingDef;
import com.zaggazach.personaldifficulty.config.SettingRegistry;
import java.util.List;
import java.util.function.Predicate;

/**
 * Which settings appear on which page of the admin menu's Settings section (Phase 4). Pure data with no Minecraft types, so a unit
 * test can prove that every scalar setting in the {@link SettingRegistry} is reachable on exactly one page.
 *
 * <p>Normal and Peaceful have no page: Normal is vanilla and Peaceful has fixed rules, so neither has a value to adjust. The
 * per-damage-type multipliers ({@code <mode>.damage-type.<id>}) stay command-only ({@code admin set}), because their keys are
 * open-ended.
 */
public enum SettingsPages {
	EASY("Easy", "Easy: ", k -> k.startsWith("easy.")),
	HARD("Hard", "Hard: ", k -> k.startsWith("hard.")),
	ELITE("Elite", "Elite: ", k -> k.startsWith("elite.") && !isEliteAdvanced(k)),
	GENERAL("All difficulties", "", k -> k.startsWith("pvp.") || k.startsWith("self.") || k.startsWith("environmental.") || k.startsWith("void.")
		|| k.startsWith("admin.") || k.startsWith("ranged.")),
	ELITE_ADVANCED("Elite hunting and effects", "", SettingsPages::isEliteAdvanced);

	/** Settings per page: four rows of two. */
	public static final int PER_PAGE = 8;

	private final String title;
	private final String displayPrefix;
	private final Predicate<String> member;

	SettingsPages(String title, String displayPrefix, Predicate<String> member) {
		this.title = title;
		this.displayPrefix = displayPrefix;
		this.member = member;
	}

	private static boolean isEliteAdvanced(String key) {
		return key.startsWith("elite.ai.") || key.startsWith("elite.priority.") || key.startsWith("elite.alert.") || key.startsWith("elite.effects.");
	}

	public String title() {
		return title;
	}

	/** The settings on this page, in registry order. */
	public List<SettingDef> settings() {
		return SettingRegistry.scalars().stream().filter(d -> member.test(d.key())).toList();
	}

	public int pageCount() {
		return Math.max(1, (settings().size() + PER_PAGE - 1) / PER_PAGE);
	}

	/** The settings shown on one sub-page (0-based), at most {@link #PER_PAGE}. */
	public List<SettingDef> settingsOn(int subPage) {
		List<SettingDef> all = settings();
		int from = Math.min(all.size(), Math.max(0, subPage) * PER_PAGE);
		return all.subList(from, Math.min(all.size(), from + PER_PAGE));
	}

	/** The registry's display text without the page's own prefix ("Easy: damage taken multiplier" becomes "Damage taken multiplier"). */
	public String label(SettingDef d) {
		String s = d.display();
		if (!displayPrefix.isEmpty() && s.startsWith(displayPrefix)) {
			s = s.substring(displayPrefix.length());
		}
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** Plain-English apply timing, shown under every setting. */
	public static String timingText(SettingDef d) {
		return switch (d.timing()) {
			case LIVE -> "Applies immediately.";
			case NEXT_SCAN -> "Applies at each mob's next hunting scan.";
			case RESTART -> "Applies after a server restart.";
		};
	}
}
