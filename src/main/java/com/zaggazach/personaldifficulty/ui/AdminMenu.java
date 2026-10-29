package com.zaggazach.personaldifficulty.ui;

import com.zaggazach.personaldifficulty.PdPermissions;
import com.zaggazach.personaldifficulty.PdRuntime;
import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.admin.AdminActor;
import com.zaggazach.personaldifficulty.admin.AdminResult;
import com.zaggazach.personaldifficulty.admin.AdminService;
import com.zaggazach.personaldifficulty.admin.PlayerDirectory;
import com.zaggazach.personaldifficulty.logic.SelectionPolicy;
import com.zaggazach.personaldifficulty.store.PlayerRecord;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * The administrator menu (Phase 3, layout redesigned in 1.3.1-rc.2): Players and Server Difficulties. One server-side state machine; the client sends
 * only a slot index and a click type, and nothing else (page, selected player, pending allow-list) is ever taken from it.
 *
 * <p>Every change goes through {@link AdminService} with this menu's {@link #actor}, so it is the same code, the same refusals, the
 * same audit line and the same operator broadcast as the equivalent {@code /personaldifficulty admin ...} command. The menu never
 * sends its own chat feedback (that would duplicate the command-style message); it shows the last result on the status card.
 */
public final class AdminMenu extends PdAdminMenu {
	public static final String TITLE = "Personal Difficulty: Admin";
	static final int ROWS = 6;
	static final int PAGE_SIZE = 45;
	private static final int MAX_LORE_LINES = 14;

	// ---- the navigation bar: identical on every page (an empty slot where an action does not apply)
	static final int NAV_BACK = 45;
	static final int NAV_CLOSE = 49;
	static final int NAV_PREV = 52;
	static final int NAV_NEXT = 53;

	// ---- hub
	static final int HUB_PLAYERS = 20;
	static final int HUB_DIFFICULTIES = 24;

	// ---- player list
	static final int LIST_PAGE_INFO = 47;

	// ---- player page
	static final int PLAYER_CARD = 4;
	static final int RESULT_CARD = 8;
	static final int SET_LABEL = 18;
	static final int[] SET_SLOTS = {20, 21, 22, 23, 24};
	static final int ALLOW_LABEL = 27;
	static final int[] ALLOW_SLOTS = {29, 30, 31, 32, 33};
	static final int ALLOW_SAVE = 35;
	static final int PLAYER_LOCK = 38;
	static final int PLAYER_ALLOW_ALL = 40;
	static final int PLAYER_RESET = 42;
	static final int PLAYER_REPAIR = 22;

	// ---- server difficulties
	static final int[] POLICY_SLOTS = {20, 21, 22, 23, 24};
	static final int POLICY_ENABLE_ALL = 31;

	// ---- confirmation screens
	static final int CONFIRM_LEFT = 20;
	static final int CONFIRM_MIDDLE = 22;
	static final int CONFIRM_RIGHT = 24;

	enum Screen {
		HUB,
		PLAYERS,
		PLAYER,
		POLICY,
		CONFIRM_RESTRICT,
		CONFIRM_RESET
	}

	private Screen screen = Screen.HUB;
	private int page;
	private List<PlayerDirectory.Entry> entries = List.of();
	private @Nullable NameAndId selected;
	/** The allow-list being edited on the player page; saved only by the Save button. */
	private final Set<PersonalDifficultyMode> pending = EnumSet.noneOf(PersonalDifficultyMode.class);
	private String last = "";

	private AdminMenu(int containerId, ServerPlayer player, AdminActor actor) {
		super(containerId, player, ROWS, actor);
		render();
	}

	/** Opens the menu if, and only if, the player may administer right now. Returns false (and opens nothing) otherwise. */
	public static boolean open(ServerPlayer player) {
		return open(player, AdminActor.fromPlayer(player));
	}

	/**
	 * As {@link #open(ServerPlayer)} but reporting results to {@code feedback}, so a test can observe exactly what the menu reports.
	 * Only the feedback can be supplied: the actor's identity and its live authority are always the menu player's own.
	 */
	public static boolean open(ServerPlayer player, AdminActor.Feedback feedback) {
		return open(player, AdminActor.fromPlayer(player, feedback));
	}

	private static boolean open(ServerPlayer player, AdminActor actor) {
		if (!PdPermissions.canAdminister(player) || PdRuntime.get() == null || !player.getUUID().equals(actor.id())) {
			return false;
		}
		PdMenu.open(player, Component.literal(TITLE), (id, inv, p) -> new AdminMenu(id, player, actor));
		return true;
	}

	// ------------------------------------------------------------------ test and diagnostics access (read only)

	Screen screen() {
		return screen;
	}

	int page() {
		return page;
	}

	@Nullable NameAndId selected() {
		return selected;
	}

	String lastMessage() {
		return last;
	}

	// ------------------------------------------------------------------ clicks

	@Override
	protected void onAdminPress(int slot, ClickKind kind) {
		PdRuntime rt = PdRuntime.get();
		if (rt == null) {
			player.closeContainer();
			return;
		}
		if (slot == NAV_CLOSE) {
			player.closeContainer();
			return;
		}
		switch (screen) {
			case HUB -> pressHub(slot, rt);
			case PLAYERS -> pressPlayers(slot, rt);
			case PLAYER -> pressPlayer(slot, rt);
			case POLICY -> pressPolicy(slot, rt);
			case CONFIRM_RESTRICT -> pressConfirmRestrict(slot, rt);
			case CONFIRM_RESET -> pressConfirmReset(slot, rt);
		}
		if (stillValid(player)) {
			render();
		}
	}

	private void go(Screen next) {
		screen = next;
		page = 0;
	}

	private void result(AdminResult r) {
		last = r.message();
	}

	private void pressHub(int slot, PdRuntime rt) {
		switch (slot) {
			case HUB_PLAYERS -> {
				loadPlayers(rt);
				go(Screen.PLAYERS);
			}
			case HUB_DIFFICULTIES -> go(Screen.POLICY);
			default -> {
			}
		}
	}

	private void loadPlayers(PdRuntime rt) {
		entries = PlayerDirectory.collect(rt, player.level().getServer());
	}

	private int pageCount() {
		return Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
	}

	private void pressPlayers(int slot, PdRuntime rt) {
		if (slot < PAGE_SIZE) {
			int index = page * PAGE_SIZE + slot;
			if (index >= 0 && index < entries.size()) {
				openPlayer(rt, entries.get(index).nameAndId());
			}
			return;
		}
		switch (slot) {
			case NAV_BACK -> go(Screen.HUB);
			case NAV_PREV -> page = Math.max(0, page - 1);
			case NAV_NEXT -> page = Math.min(pageCount() - 1, page + 1);
			case LIST_PAGE_INFO -> {
				loadPlayers(rt);
				page = Math.min(page, pageCount() - 1);
			}
			default -> {
			}
		}
	}

	private void openPlayer(PdRuntime rt, NameAndId target) {
		selected = target;
		loadPending(rt, target);
		go(Screen.PLAYER);
	}

	/** The editor starts from the stored list; with no list (server policy applies) every difficulty starts ticked. */
	private void loadPending(PdRuntime rt, NameAndId target) {
		pending.clear();
		PlayerRecord rec = rt.settings().record(target.id());
		if (rec.allowed() != null) {
			pending.addAll(rec.allowed());
		} else {
			pending.addAll(EnumSet.allOf(PersonalDifficultyMode.class));
		}
	}

	private boolean pendingDiffersFromStored(PdRuntime rt, NameAndId target) {
		PlayerRecord rec = rt.settings().record(target.id());
		Set<PersonalDifficultyMode> stored = rec.allowed() != null ? rec.allowed() : EnumSet.allOf(PersonalDifficultyMode.class);
		return !pending.equals(stored);
	}

	private void pressPlayer(int slot, PdRuntime rt) {
		NameAndId target = selected;
		if (target == null) {
			go(Screen.HUB);
			return;
		}
		if (slot == NAV_BACK) {
			loadPlayers(rt);
			go(Screen.PLAYERS);
			return;
		}
		if (rt.settings().isQuarantined(target.id())) {
			if (slot == PLAYER_REPAIR) {
				result(AdminService.reset(actor, rt, target));
				loadPending(rt, target);
			}
			return;
		}
		PersonalDifficultyMode[] modes = PersonalDifficultyMode.values();
		for (int i = 0; i < modes.length; i++) {
			if (slot == SET_SLOTS[i]) {
				result(AdminService.assign(actor, rt, target, modes[i], false));
				return;
			}
			if (slot == ALLOW_SLOTS[i]) {
				if (!pending.remove(modes[i])) {
					pending.add(modes[i]);
				}
				return;
			}
		}
		switch (slot) {
			case ALLOW_SAVE -> saveAllowList(rt, target);
			case PLAYER_LOCK -> result(AdminService.setLock(actor, rt, target, !rt.settings().record(target.id()).locked()));
			case PLAYER_ALLOW_ALL -> {
				result(AdminService.unrestrict(actor, rt, target));
				loadPending(rt, target);
			}
			case PLAYER_RESET -> go(Screen.CONFIRM_RESET);
			default -> {
			}
		}
	}

	/** D5: when the list would exclude the player's current mode, ask (Switch / Keep / Cancel) instead of choosing for the administrator. */
	private void saveAllowList(PdRuntime rt, NameAndId target) {
		if (pending.isEmpty()) {
			actor.feedback().failure(EMPTY_LIST_GUI);
			last = EMPTY_LIST_GUI;
			return;
		}
		if (!pendingDiffersFromStored(rt, target)) {
			last = "No changes to save.";
			return;
		}
		if (pending.size() == PersonalDifficultyMode.values().length && rt.settings().record(target.id()).allowed() == null) {
			last = "No changes to save.";
			return;
		}
		SelectionPolicy.RestrictPlan plan = AdminService.previewRestrict(rt, target, pending, SelectionPolicy.RestrictFlag.NONE, actor.id());
		if (plan.status() == SelectionPolicy.RestrictStatus.NEEDS_CHOICE) {
			go(Screen.CONFIRM_RESTRICT);
			return;
		}
		result(AdminService.restrict(actor, rt, target, EnumSet.copyOf(pending), SelectionPolicy.RestrictFlag.NONE));
		loadPending(rt, target);
	}

	static final String EMPTY_LIST_GUI = "An allow-list cannot be empty. Tick at least one difficulty, or use 'Allow all' to remove the restriction.";

	private void pressPolicy(int slot, PdRuntime rt) {
		if (slot == NAV_BACK) {
			go(Screen.HUB);
			return;
		}
		PersonalDifficultyMode[] modes = PersonalDifficultyMode.values();
		for (int i = 0; i < modes.length; i++) {
			if (slot == POLICY_SLOTS[i]) {
				result(AdminService.setEnabled(actor, rt, modes[i], !rt.availability().isEnabled(modes[i])));
				return;
			}
		}
		if (slot == POLICY_ENABLE_ALL) {
			result(AdminService.enableAll(actor, rt));
		}
	}

	private void pressConfirmRestrict(int slot, PdRuntime rt) {
		NameAndId target = selected;
		if (target == null || pending.isEmpty()) {
			go(Screen.HUB);
			return;
		}
		// the service re-reads the target's CURRENT record and re-checks the actor's authority; nothing decided at preview time is trusted
		switch (slot) {
			case CONFIRM_LEFT -> finishRestrict(rt, target, AdminService.restrict(actor, rt, target, EnumSet.copyOf(pending), SelectionPolicy.RestrictFlag.SWITCH));
			case CONFIRM_MIDDLE -> finishRestrict(rt, target, AdminService.restrict(actor, rt, target, EnumSet.copyOf(pending), SelectionPolicy.RestrictFlag.KEEP));
			case CONFIRM_RIGHT, NAV_BACK -> {
				last = "Cancelled; nothing changed.";
				go(Screen.PLAYER);
			}
			default -> {
			}
		}
	}

	private void finishRestrict(PdRuntime rt, NameAndId target, AdminResult r) {
		result(r);
		if (r.applied() || r.outcome() == AdminResult.Outcome.UNCHANGED) {
			loadPending(rt, target);
		}
		go(Screen.PLAYER);
	}

	private void pressConfirmReset(int slot, PdRuntime rt) {
		NameAndId target = selected;
		if (target == null) {
			go(Screen.HUB);
			return;
		}
		switch (slot) {
			case CONFIRM_LEFT -> {
				result(AdminService.reset(actor, rt, target));
				loadPending(rt, target);
				go(Screen.PLAYER);
			}
			case CONFIRM_RIGHT, NAV_BACK -> {
				last = "Cancelled; nothing changed.";
				go(Screen.PLAYER);
			}
			default -> {
			}
		}
	}

	// ------------------------------------------------------------------ rendering

	/** One icon per difficulty, rising in toughness from left to right in the menu. */
	static Item itemFor(PersonalDifficultyMode m) {
		return switch (m) {
			case PEACEFUL -> Items.POPPY;
			case EASY -> Items.WOODEN_SWORD;
			case NORMAL -> Items.IRON_SWORD;
			case HARD -> Items.DIAMOND_SWORD;
			case ELITE -> Items.NETHERITE_SWORD;
		};
	}

	private static String title(PersonalDifficultyMode m) {
		String n = m.name();
		return n.charAt(0) + n.substring(1).toLowerCase(java.util.Locale.ROOT);
	}

	private static List<Component> lore(String... lines) {
		List<Component> out = new ArrayList<>();
		for (String l : lines) {
			out.add(UiItems.grey(l));
		}
		return out;
	}

	private void put(int slot, Item item, String name, ChatFormatting color, String... lore) {
		show(slot, UiItems.button(item, Text.item(name, color), lore(lore)));
	}

	private void putOn(int slot, Item item, boolean on, String name, ChatFormatting color, String... lore) {
		show(slot, UiItems.button(item, Text.item(name, color), lore(lore), on));
	}

	private static List<Component> splitLore(String text) {
		List<Component> lines = new ArrayList<>();
		String[] split = text.split("\n");
		for (int i = 0; i < split.length && i < MAX_LORE_LINES; i++) {
			String l = split[i];
			lines.add(UiItems.grey(l.length() > 90 ? l.substring(0, 87) + "..." : l));
		}
		return lines;
	}

	private void statusCard(int slot, String heading, String text) {
		show(slot, UiItems.button(Items.PAPER, Text.item(heading, ChatFormatting.AQUA), splitLore(text)));
	}

	private void lastResult(int slot) {
		if (!last.isEmpty()) {
			statusCard(slot, "Last result", last);
		}
	}

	private @Nullable GameProfile onlineProfile(UUID id) {
		ServerPlayer online = player.level().getServer().getPlayerList().getPlayer(id);
		return online != null ? online.getGameProfile() : null;
	}

	/** The same bar on every page: Back bottom-left, Close bottom-middle, page arrows bottom-right. */
	private void navBar(boolean back) {
		if (back) {
			put(NAV_BACK, Items.ARROW, "Back", ChatFormatting.YELLOW);
		}
		put(NAV_CLOSE, Items.BARRIER, "Close", ChatFormatting.RED);
	}

	private void render() {
		clearDisplay();
		PdRuntime rt = PdRuntime.get();
		if (rt == null) {
			return;
		}
		switch (screen) {
			case HUB -> renderHub();
			case PLAYERS -> renderPlayers(rt);
			case PLAYER -> renderPlayer(rt);
			case POLICY -> renderPolicy(rt);
			case CONFIRM_RESTRICT -> renderConfirmRestrict(rt);
			case CONFIRM_RESET -> renderConfirmReset(rt);
		}
	}

	private void renderHub() {
		show(HUB_PLAYERS, UiItems.head(player.getUUID(), player.getGameProfile(), Text.item("Players", ChatFormatting.GREEN),
			lore("See every player's difficulty, and set,", "lock or restrict it.")));
		put(HUB_DIFFICULTIES, Items.COMPARATOR, "Server Difficulties", ChatFormatting.GOLD, "Turn difficulties on or off", "for the whole server.");
		lastResult(4);
		navBar(false);
	}

	private String allowedText(PlayerRecord rec) {
		return rec.allowed() == null ? "all (server policy)" : AdminService.names(rec.allowed());
	}

	private void renderPlayers(PdRuntime rt) {
		int from = page * PAGE_SIZE;
		for (int slot = 0; slot < PAGE_SIZE; slot++) {
			int index = from + slot;
			if (index >= entries.size()) {
				break;
			}
			PlayerDirectory.Entry e = entries.get(index);
			String where = e.online() ? "online" : "offline";
			if (rt.settings().isQuarantined(e.id())) {
				put(slot, Items.BARRIER, e.label(), ChatFormatting.RED, "Invalid stored record (" + where + ")", "Click to repair it.");
				continue;
			}
			PlayerRecord rec = rt.settings().record(e.id());
			show(slot, UiItems.head(e.id(), e.online() ? onlineProfile(e.id()) : null, Text.item(e.label(), e.online() ? ChatFormatting.GREEN : ChatFormatting.WHITE),
				lore("Difficulty: " + title(rec.mode()), "Status: " + where, "Locked: " + (rec.locked() ? "yes" : "no"), "Allowed: " + allowedText(rec), "", "Click to manage.")));
		}
		put(LIST_PAGE_INFO, Items.PAPER, "Page " + (page + 1) + " of " + pageCount(), ChatFormatting.AQUA, entries.size() + " player(s) known", "Click to refresh the list.");
		navBar(true);
		if (page > 0) {
			put(NAV_PREV, Items.ARROW, "Previous page", ChatFormatting.YELLOW);
		}
		if (page < pageCount() - 1) {
			put(NAV_NEXT, Items.ARROW, "Next page", ChatFormatting.YELLOW);
		}
	}

	private void renderPlayer(PdRuntime rt) {
		NameAndId target = selected;
		if (target == null) {
			return;
		}
		boolean invalid = rt.settings().isQuarantined(target.id());
		show(PLAYER_CARD, UiItems.head(target.id(), onlineProfile(target.id()), Text.item(target.name(), invalid ? ChatFormatting.RED : ChatFormatting.AQUA),
			splitLore(AdminService.statusText(rt, target))));
		lastResult(RESULT_CARD);
		navBar(true);
		if (invalid) {
			put(PLAYER_REPAIR, Items.REDSTONE, "Repair invalid record", ChatFormatting.RED, "Replaces the unreadable record with a default one", "(Normal, unlocked, no allow-list).", "A verified backup is made first;", "unknown fields are kept.");
			return;
		}
		PlayerRecord rec = rt.settings().record(target.id());
		PersonalDifficultyMode[] modes = PersonalDifficultyMode.values();

		put(SET_LABEL, Items.OAK_SIGN, "Set difficulty", ChatFormatting.WHITE, "Click a difficulty to put", target.name() + " on it. The glowing", "one is their current difficulty.");
		for (int i = 0; i < modes.length; i++) {
			boolean current = rec.mode() == modes[i];
			putOn(SET_SLOTS[i], itemFor(modes[i]), current, title(modes[i]) + (current ? " (current)" : ""), current ? ChatFormatting.GREEN : ChatFormatting.WHITE,
				current ? "This is their difficulty now." : "Click to set " + target.name() + " to " + title(modes[i]) + ".",
				"Works even if it is disabled, not allowed", "for them, or they are locked.");
		}

		put(ALLOW_LABEL, Items.OAK_SIGN, "Allowed difficulties", ChatFormatting.WHITE, "Which difficulties " + target.name(), "may pick for themselves.", "Click to tick or untick, then Save.");
		for (int i = 0; i < modes.length; i++) {
			boolean on = pending.contains(modes[i]);
			putOn(ALLOW_SLOTS[i], itemFor(modes[i]), on, title(modes[i]) + (on ? ": allowed" : ": not allowed"), on ? ChatFormatting.GREEN : ChatFormatting.GRAY,
				"Click to " + (on ? "untick." : "tick."));
		}
		boolean dirty = pendingDiffersFromStored(rt, target);
		putOn(ALLOW_SAVE, Items.WRITABLE_BOOK, dirty, "Save allowed list", dirty ? ChatFormatting.GREEN : ChatFormatting.GRAY,
			dirty ? "You have unsaved changes." : "No changes to save.", "Saved now: " + allowedText(rec));

		putOn(PLAYER_LOCK, Items.TRIPWIRE_HOOK, rec.locked(), "Lock: " + (rec.locked() ? "ON" : "OFF"), rec.locked() ? ChatFormatting.RED : ChatFormatting.GREEN,
			rec.locked() ? "Only an admin can change their difficulty." : "They can change their own difficulty.", "Click to turn " + (rec.locked() ? "off." : "on."));
		put(PLAYER_ALLOW_ALL, Items.MAP, "Allow all", ChatFormatting.YELLOW, "Removes their allowed list, so the", "server's settings apply to them again.");
		put(PLAYER_RESET, Items.REDSTONE, "Reset player", ChatFormatting.RED, "Normal, unlocked, no allowed list.", "Asks you to confirm first.");
	}

	private void renderPolicy(PdRuntime rt) {
		PersonalDifficultyMode[] modes = PersonalDifficultyMode.values();
		for (int i = 0; i < modes.length; i++) {
			boolean enabled = rt.availability().isEnabled(modes[i]);
			putOn(POLICY_SLOTS[i], itemFor(modes[i]), enabled, title(modes[i]) + ": " + (enabled ? "ON" : "OFF"), enabled ? ChatFormatting.GREEN : ChatFormatting.RED,
				enabled ? "Players can pick it. Click to turn off." : "Players cannot pick it. Click to turn on.",
				rt.settings().countUsing(modes[i]) + " player(s) are on it now.", "Turning it off never moves anyone.");
		}
		put(POLICY_ENABLE_ALL, Items.EMERALD, "Turn all on", ChatFormatting.GREEN);
		lastResult(4);
		navBar(true);
	}

	private void renderConfirmRestrict(PdRuntime rt) {
		NameAndId target = selected;
		if (target == null) {
			return;
		}
		PlayerRecord rec = rt.settings().record(target.id());
		Set<PersonalDifficultyMode> list = pending.isEmpty() ? EnumSet.of(PersonalDifficultyMode.NORMAL) : EnumSet.copyOf(pending);
		PersonalDifficultyMode to = SelectionPolicy.switchTarget(list);
		statusCard(4, "Choose what happens", target.name() + " is on " + title(rec.mode()) + ",\nwhich is not in the new list (" + AdminService.names(list) + ").");
		put(CONFIRM_LEFT, itemFor(to), "Switch them to " + title(to), ChatFormatting.GREEN, "Move them to " + title(to) + ",", "then save the list.");
		put(CONFIRM_MIDDLE, itemFor(rec.mode()), "Keep them on " + title(rec.mode()), ChatFormatting.GOLD, "They stay on " + title(rec.mode()) + " as an exception;", "once they switch away", "they cannot switch back.");
		put(CONFIRM_RIGHT, Items.ARROW, "Cancel", ChatFormatting.RED, "Change nothing.");
		navBar(true);
	}

	private void renderConfirmReset(PdRuntime rt) {
		NameAndId target = selected;
		if (target == null) {
			return;
		}
		statusCard(4, "Reset " + target.name() + "?", "Sets Normal, unlocks them and removes\ntheir allowed list.");
		put(CONFIRM_LEFT, Items.REDSTONE, "Yes, reset", ChatFormatting.RED, "Reset " + target.name() + " now.");
		put(CONFIRM_RIGHT, Items.ARROW, "Cancel", ChatFormatting.YELLOW, "Change nothing.");
		navBar(true);
	}
}
