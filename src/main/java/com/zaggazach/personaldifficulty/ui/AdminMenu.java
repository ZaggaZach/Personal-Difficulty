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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * The administrator menu (Phase 3): Player Management and Difficulty Permissions. One server-side state machine; the client sends
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

	enum Screen {
		HUB,
		PLAYERS,
		PLAYER,
		POLICY,
		EDITOR,
		CONFIRM
	}

	/** What choosing a player in the list leads to. */
	enum Purpose {
		MANAGE,
		ALLOW_LIST
	}

	private Screen screen = Screen.HUB;
	private Purpose purpose = Purpose.MANAGE;
	private int page;
	private List<PlayerDirectory.Entry> entries = List.of();
	private @Nullable NameAndId selected;
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
		switch (screen) {
			case HUB -> pressHub(slot, rt);
			case PLAYERS -> pressPlayers(slot, rt);
			case PLAYER -> pressPlayer(slot, rt);
			case POLICY -> pressPolicy(slot, rt);
			case EDITOR -> pressEditor(slot, rt);
			case CONFIRM -> pressConfirm(slot, rt);
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
			case 20 -> {
				purpose = Purpose.MANAGE;
				loadPlayers(rt);
				go(Screen.PLAYERS);
			}
			case 24 -> go(Screen.POLICY);
			case 49 -> player.closeContainer();
			default -> {
			}
		}
	}

	private void loadPlayers(PdRuntime rt) {
		entries = PlayerDirectory.collect(rt, player.level().getServer());
	}

	private void pressPlayers(int slot, PdRuntime rt) {
		if (slot < PAGE_SIZE) {
			int index = page * PAGE_SIZE + slot;
			if (index >= 0 && index < entries.size()) {
				selected = entries.get(index).nameAndId();
				if (purpose == Purpose.ALLOW_LIST && !rt.settings().isQuarantined(selected.id())) {
					pending.clear();
					PlayerRecord rec = rt.settings().record(selected.id());
					if (rec.allowed() != null) {
						pending.addAll(rec.allowed());
					}
					go(Screen.EDITOR);
				} else {
					go(Screen.PLAYER);
				}
			}
			return;
		}
		switch (slot) {
			case 45 -> page = Math.max(0, page - 1);
			case 53 -> {
				if ((page + 1) * PAGE_SIZE < entries.size()) {
					page++;
				}
			}
			case 47 -> {
				loadPlayers(rt);
				page = Math.min(page, Math.max(0, (entries.size() - 1) / PAGE_SIZE));
			}
			case 49 -> go(purpose == Purpose.ALLOW_LIST ? Screen.POLICY : Screen.HUB);
			default -> {
			}
		}
	}

	private static final int[] MODE_SLOTS_ASSIGN = {18, 19, 20, 21, 22};
	private static final int[] MODE_SLOTS_LOCK = {27, 28, 29, 30, 31};

	private void pressPlayer(int slot, PdRuntime rt) {
		NameAndId target = selected;
		if (target == null) {
			go(Screen.HUB);
			return;
		}
		boolean invalid = rt.settings().isQuarantined(target.id());
		PersonalDifficultyMode[] modes = PersonalDifficultyMode.values();
		if (slot == 45) {
			loadPlayers(rt);
			go(Screen.PLAYERS);
			return;
		}
		if (invalid) {
			if (slot == 22) {
				result(AdminService.reset(actor, rt, target));
			}
			return;
		}
		for (int i = 0; i < modes.length; i++) {
			if (slot == MODE_SLOTS_ASSIGN[i]) {
				result(AdminService.assign(actor, rt, target, modes[i], false));
				return;
			}
			if (slot == MODE_SLOTS_LOCK[i]) {
				result(AdminService.assign(actor, rt, target, modes[i], true));
				return;
			}
		}
		switch (slot) {
			case 36 -> result(AdminService.setLock(actor, rt, target, !rt.settings().record(target.id()).locked()));
			case 38 -> {
				pending.clear();
				PlayerRecord rec = rt.settings().record(target.id());
				if (rec.allowed() != null) {
					pending.addAll(rec.allowed());
				}
				go(Screen.EDITOR);
			}
			case 40 -> result(AdminService.unrestrict(actor, rt, target));
			case 42 -> result(AdminService.reset(actor, rt, target));
			default -> {
			}
		}
	}

	private static final int[] POLICY_SLOTS = {11, 12, 13, 14, 15};

	private void pressPolicy(int slot, PdRuntime rt) {
		PersonalDifficultyMode[] modes = PersonalDifficultyMode.values();
		for (int i = 0; i < modes.length; i++) {
			if (slot == POLICY_SLOTS[i]) {
				result(AdminService.setEnabled(actor, rt, modes[i], !rt.availability().isEnabled(modes[i])));
				return;
			}
		}
		switch (slot) {
			case 22 -> result(AdminService.enableAll(actor, rt));
			case 31 -> {
				purpose = Purpose.ALLOW_LIST;
				loadPlayers(rt);
				go(Screen.PLAYERS);
			}
			case 45 -> go(Screen.HUB);
			default -> {
			}
		}
	}

	private void pressEditor(int slot, PdRuntime rt) {
		NameAndId target = selected;
		if (target == null) {
			go(Screen.POLICY);
			return;
		}
		PersonalDifficultyMode[] modes = PersonalDifficultyMode.values();
		for (int i = 0; i < modes.length; i++) {
			if (slot == POLICY_SLOTS[i]) {
				if (!pending.remove(modes[i])) {
					pending.add(modes[i]);
				}
				return;
			}
		}
		switch (slot) {
			case 29 -> applyAllowList(rt, target);
			case 31 -> {
				AdminResult r = AdminService.unrestrict(actor, rt, target);
				result(r);
				pending.clear();
			}
			case 33, 45 -> go(Screen.PLAYER);
			default -> {
			}
		}
	}

	/** D5: when the list would exclude the player's current mode, ask (Switch / Keep / Cancel) instead of choosing for the administrator. */
	private void applyAllowList(PdRuntime rt, NameAndId target) {
		if (pending.isEmpty()) {
			actor.feedback().failure(AdminService.EMPTY_LIST);
			last = AdminService.EMPTY_LIST;
			return;
		}
		SelectionPolicy.RestrictPlan plan = AdminService.previewRestrict(rt, target, pending, SelectionPolicy.RestrictFlag.NONE, actor.id());
		if (plan.status() == SelectionPolicy.RestrictStatus.NEEDS_CHOICE) {
			go(Screen.CONFIRM);
			return;
		}
		AdminResult r = AdminService.restrict(actor, rt, target, EnumSet.copyOf(pending), SelectionPolicy.RestrictFlag.NONE);
		result(r);
		if (r.applied()) {
			go(Screen.PLAYER);
		}
	}

	private void pressConfirm(int slot, PdRuntime rt) {
		NameAndId target = selected;
		if (target == null || pending.isEmpty()) {
			go(Screen.POLICY);
			return;
		}
		// the service re-reads the target's CURRENT record and re-checks the actor's authority; nothing decided at preview time is trusted
		switch (slot) {
			case 20 -> finishRestrict(AdminService.restrict(actor, rt, target, EnumSet.copyOf(pending), SelectionPolicy.RestrictFlag.SWITCH));
			case 22 -> finishRestrict(AdminService.restrict(actor, rt, target, EnumSet.copyOf(pending), SelectionPolicy.RestrictFlag.KEEP));
			case 24 -> {
				last = "Cancelled; nothing changed.";
				go(Screen.EDITOR);
			}
			default -> {
			}
		}
	}

	private void finishRestrict(AdminResult r) {
		result(r);
		go(r.applied() || r.outcome() == AdminResult.Outcome.UNCHANGED ? Screen.PLAYER : Screen.EDITOR);
	}

	// ------------------------------------------------------------------ rendering

	private static Item itemFor(PersonalDifficultyMode m) {
		return switch (m) {
			case NORMAL -> Items.IRON_INGOT;
			case EASY -> Items.EMERALD;
			case HARD -> Items.GOLD_INGOT;
			case ELITE -> Items.NETHERITE_INGOT;
			case PEACEFUL -> Items.DIAMOND;
		};
	}

	private void put(int slot, Item item, String name, ChatFormatting color, String... lore) {
		List<Component> lines = new ArrayList<>();
		for (String l : lore) {
			lines.add(UiItems.grey(l));
		}
		show(slot, UiItems.button(item, Text.item(name, color), lines));
	}

	private void statusCard(int slot, String text) {
		List<Component> lines = new ArrayList<>();
		String[] split = text.split("\n");
		for (int i = 0; i < split.length && i < MAX_LORE_LINES; i++) {
			String l = split[i];
			lines.add(UiItems.grey(l.length() > 90 ? l.substring(0, 87) + "..." : l));
		}
		show(slot, UiItems.button(Items.PAPER, Text.item("Status", ChatFormatting.AQUA), lines));
	}

	private void lastResult(int slot) {
		if (!last.isEmpty()) {
			statusCard(slot, "Last result:\n" + last);
		}
	}

	private void render() {
		clearDisplay();
		PdRuntime rt = PdRuntime.get();
		if (rt == null) {
			return;
		}
		for (int i = 0; i < ROWS * 9; i++) {
			show(i, UiItems.filler());
		}
		switch (screen) {
			case HUB -> renderHub();
			case PLAYERS -> renderPlayers(rt);
			case PLAYER -> renderPlayer(rt);
			case POLICY -> renderPolicy(rt);
			case EDITOR -> renderEditor(rt);
			case CONFIRM -> renderConfirm(rt);
		}
	}

	private void renderHub() {
		put(20, Items.COMPASS, "Player Management", ChatFormatting.GREEN, "Assign, lock and restrict players;", "repair invalid stored records.");
		put(24, Items.BOOK, "Difficulty Permissions", ChatFormatting.GOLD, "Enable or disable difficulties server-wide", "and edit per-player allow-lists.");
		put(49, Items.BARRIER, "Close", ChatFormatting.RED);
		lastResult(4);
	}

	private void renderPlayers(PdRuntime rt) {
		int from = page * PAGE_SIZE;
		for (int slot = 0; slot < PAGE_SIZE; slot++) {
			int index = from + slot;
			if (index >= entries.size()) {
				break;
			}
			PlayerDirectory.Entry e = entries.get(index);
			PlayerRecord rec = rt.settings().record(e.id());
			boolean invalid = rt.settings().isQuarantined(e.id());
			String where = e.online() ? "online" : "offline";
			if (invalid) {
				put(slot, Items.BARRIER, e.label(), ChatFormatting.RED, "INVALID STORED RECORD (" + where + ")", "Click to see how to repair it.");
			} else {
				put(slot, itemFor(rec.mode()), e.label(), e.online() ? ChatFormatting.GREEN : ChatFormatting.WHITE,
					rec.mode().name() + (rec.locked() ? ", locked" : "") + (rec.allowed() != null ? ", restricted" : "") + " (" + where + ")",
					purpose == Purpose.ALLOW_LIST ? "Click to edit the allow-list." : "Click to manage.");
			}
		}
		int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		put(45, Items.ARROW, "Previous page", ChatFormatting.YELLOW);
		put(47, Items.PAPER, "Page " + (page + 1) + " of " + pages, ChatFormatting.AQUA, entries.size() + " player(s) known", "Click to refresh the list.");
		put(49, Items.BARRIER, "Back", ChatFormatting.RED);
		put(53, Items.ARROW, "Next page", ChatFormatting.YELLOW);
	}

	private void renderPlayer(PdRuntime rt) {
		NameAndId target = selected;
		if (target == null) {
			return;
		}
		statusCard(4, AdminService.statusText(rt, target));
		lastResult(8);
		PersonalDifficultyMode[] modes = PersonalDifficultyMode.values();
		if (rt.settings().isQuarantined(target.id())) {
			put(22, Items.REDSTONE, "Repair invalid record", ChatFormatting.RED, "Replaces the unreadable record by a default one", "(NORMAL, unlocked, no allow-list).", "A verified backup is made first;", "unknown fields are kept.");
		} else {
			for (int i = 0; i < modes.length; i++) {
				put(MODE_SLOTS_ASSIGN[i], itemFor(modes[i]), "Assign " + modes[i].name(), ChatFormatting.GREEN, "Sets the difficulty regardless of", "server policy, allow-list or lock.");
				put(MODE_SLOTS_LOCK[i], itemFor(modes[i]), "Assign " + modes[i].name() + " and lock", ChatFormatting.GOLD, "Sets the difficulty and locks it.");
			}
			boolean locked = rt.settings().record(target.id()).locked();
			put(36, Items.TRIPWIRE_HOOK, locked ? "Unlock" : "Lock", ChatFormatting.GOLD, locked ? "The player can choose again." : "Only an administrator can change it.");
			put(38, Items.BOOK, "Edit allow-list", ChatFormatting.AQUA, "Choose which difficulties this", "player may select.");
			put(40, Items.LEVER, "Remove allow-list", ChatFormatting.YELLOW, "The server policy applies again.");
			put(42, Items.REDSTONE, "Reset", ChatFormatting.RED, "NORMAL, unlock, no allow-list.");
		}
		put(45, Items.ARROW, "Back to the list", ChatFormatting.YELLOW);
	}

	private void renderPolicy(PdRuntime rt) {
		PersonalDifficultyMode[] modes = PersonalDifficultyMode.values();
		for (int i = 0; i < modes.length; i++) {
			boolean enabled = rt.availability().isEnabled(modes[i]);
			put(POLICY_SLOTS[i], itemFor(modes[i]), modes[i].name() + ": " + (enabled ? "ENABLED" : "DISABLED"), enabled ? ChatFormatting.GREEN : ChatFormatting.RED,
				enabled ? "Click to disable (saved selections are kept)." : "Click to enable.", rt.settings().countUsing(modes[i]) + " saved selection(s) use it.");
		}
		put(22, Items.EMERALD, "Enable all", ChatFormatting.GREEN);
		put(31, Items.BOOK, "Per-player allow-lists", ChatFormatting.AQUA, "Pick a player to edit.");
		put(45, Items.BARRIER, "Back", ChatFormatting.RED);
		lastResult(4);
	}

	private void renderEditor(PdRuntime rt) {
		NameAndId target = selected;
		if (target == null) {
			return;
		}
		statusCard(4, "Allow-list for " + target.name() + "\n" + AdminService.statusText(rt, target));
		lastResult(8);
		PersonalDifficultyMode[] modes = PersonalDifficultyMode.values();
		for (int i = 0; i < modes.length; i++) {
			boolean on = pending.contains(modes[i]);
			put(POLICY_SLOTS[i], itemFor(modes[i]), (on ? "[x] " : "[ ] ") + modes[i].name(), on ? ChatFormatting.GREEN : ChatFormatting.GRAY, "Click to toggle.");
		}
		put(29, Items.EMERALD, "Apply allow-list", ChatFormatting.GREEN, "At least one difficulty must be ticked.");
		put(31, Items.LEVER, "Remove allow-list", ChatFormatting.YELLOW);
		put(33, Items.BARRIER, "Cancel", ChatFormatting.RED);
		put(45, Items.ARROW, "Back", ChatFormatting.YELLOW);
	}

	private void renderConfirm(PdRuntime rt) {
		NameAndId target = selected;
		if (target == null) {
			return;
		}
		PlayerRecord rec = rt.settings().record(target.id());
		Set<PersonalDifficultyMode> list = pending.isEmpty() ? EnumSet.of(PersonalDifficultyMode.NORMAL) : EnumSet.copyOf(pending);
		statusCard(4, target.name() + " is on " + rec.mode().name() + ",\nwhich is not in the new list (" + AdminService.names(list) + ").\nChoose what happens to them.");
		put(20, Items.ARROW, "Switch", ChatFormatting.GREEN, "Move them to " + SelectionPolicy.switchTarget(list).name() + ",", "then apply the allow-list.");
		put(22, Items.IRON_INGOT, "Keep", ChatFormatting.GOLD, "They stay on " + rec.mode().name() + " as an exception", "outside the list; once they switch away", "they cannot switch back.");
		put(24, Items.BARRIER, "Cancel", ChatFormatting.RED, "Change nothing.");
	}
}
