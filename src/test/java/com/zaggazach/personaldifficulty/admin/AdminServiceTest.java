package com.zaggazach.personaldifficulty.admin;

import static com.zaggazach.personaldifficulty.PersonalDifficultyMode.*;
import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.PdRuntime;
import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.admin.AdminResult.Outcome;
import com.zaggazach.personaldifficulty.config.ConfigState;
import com.zaggazach.personaldifficulty.config.OverridesStore;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import com.zaggazach.personaldifficulty.logic.SelectionPolicy.RestrictFlag;
import com.zaggazach.personaldifficulty.store.AvailabilityPolicy;
import com.zaggazach.personaldifficulty.store.PlayerRecord;
import com.zaggazach.personaldifficulty.store.PlayerSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.players.NameAndId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shared administrative service: authority is checked live before any change, a failed write changes nothing, the audit
 * feedback is sent exactly once, and a restriction decision is re-validated against the target's current record. (That the commands
 * kept their exact wording and behaviour is pinned separately by the golden transcript GameTest.)
 */
class AdminServiceTest {
	private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000000ad");
	private static final NameAndId ALICE = new NameAndId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "alice");
	private static final NameAndId BOB = new NameAndId(UUID.fromString("00000000-0000-0000-0000-0000000000b2"), "bob");

	/** Records every message with its broadcast flag. */
	private static final class Recorder implements AdminActor.Feedback {
		final List<String> successes = new ArrayList<>();
		final List<Boolean> broadcasts = new ArrayList<>();
		final List<String> failures = new ArrayList<>();

		@Override
		public void success(String message, boolean broadcast) {
			successes.add(message);
			broadcasts.add(broadcast);
		}

		@Override
		public void failure(String message) {
			failures.add(message);
		}

		int total() {
			return successes.size() + failures.size();
		}
	}

	private static final class FaultyIo implements PlayerSettings.Io {
		final AtomicBoolean failWrite = new AtomicBoolean();
		final AtomicBoolean failCopy = new AtomicBoolean();

		@Override
		public void copy(Path from, Path to) throws IOException {
			if (failCopy.get()) {
				throw new IOException("injected backup failure");
			}
			PlayerSettings.Io.REAL.copy(from, to);
		}

		@Override
		public void writeAtomically(Path target, byte[] data) throws IOException {
			if (failWrite.get()) {
				throw new IOException("injected write failure");
			}
			PlayerSettings.Io.REAL.writeAtomically(target, data);
		}
	}

	private record Fixture(PdRuntime rt, Path players, FaultyIo io, Recorder feedback, AtomicBoolean authorised, AtomicInteger onlineLookups) {
		AdminActor actor() {
			return new AdminActor(ADMIN, "admin", authorised::get, feedback, id -> {
				onlineLookups.incrementAndGet();
				return null;
			});
		}

		String file() throws IOException {
			return Files.exists(players) ? Files.readString(players) : "";
		}
	}

	private static Fixture fixture(Path dir) throws IOException {
		Path props = dir.resolve("p.properties");
		Files.writeString(props, "");
		Path players = dir.resolve("players.json");
		FaultyIo io = new FaultyIo();
		PlayerSettings settings = new PlayerSettings(players, io, () -> 1000L);
		settings.load();
		OverridesStore store = new OverridesStore(dir.resolve("o.json"));
		store.load();
		PdRuntime rt = new PdRuntime(props, ConfigState.compose(PdConfigLoader.layerOf(new Properties()), store.layer()), store, () -> List.of(), settings, new AvailabilityPolicy(dir.resolve("a.json")));
		return new Fixture(rt, players, io, new Recorder(), new AtomicBoolean(true), new AtomicInteger());
	}

	private static PlayerRecord rec(Fixture f, NameAndId p) {
		return f.rt().settings().record(p.id());
	}

	// ------------------------------------------------------------------ authority

	@Test
	void everyMutatingMethodRefusesAnActorWithoutAuthorityBeforeTouchingAnything(@TempDir Path dir) throws IOException {
		Fixture f = fixture(dir);
		f.rt().settings().put(ALICE.id(), PlayerRecord.DEFAULT.withMode(HARD));
		String before = f.file();
		f.authorised().set(false);
		AdminActor actor = f.actor();
		List<AdminResult> results = List.of(
			AdminService.assign(actor, f.rt(), ALICE, ELITE, true),
			AdminService.setLock(actor, f.rt(), ALICE, true),
			AdminService.unrestrict(actor, f.rt(), ALICE),
			AdminService.restrict(actor, f.rt(), ALICE, EnumSet.of(HARD), RestrictFlag.NONE),
			AdminService.reset(actor, f.rt(), ALICE),
			AdminService.setEnabled(actor, f.rt(), HARD, false),
			AdminService.enableAll(actor, f.rt()));
		for (AdminResult r : results) {
			assertEquals(Outcome.UNAUTHORISED, r.outcome(), r.message());
		}
		assertEquals(before, f.file(), "players.json untouched");
		assertEquals(HARD, rec(f, ALICE).mode());
		assertTrue(f.rt().availability().isEnabled(HARD));
		assertEquals(0, f.onlineLookups().get(), "no hook, no lookup");
		assertEquals(results.size(), f.feedback().failures.size(), "one refusal message each");
		assertTrue(f.feedback().successes.isEmpty());
	}

	@Test
	void authorityIsReadLiveOnEveryCall_aDemotionBetweenTwoActionsStopsTheSecond(@TempDir Path dir) throws IOException {
		Fixture f = fixture(dir);
		AdminActor actor = f.actor();
		assertTrue(AdminService.assign(actor, f.rt(), ALICE, HARD, false).applied());
		f.authorised().set(false); // /deop
		AdminResult second = AdminService.assign(actor, f.rt(), ALICE, ELITE, false);
		assertEquals(Outcome.UNAUTHORISED, second.outcome());
		assertEquals(HARD, rec(f, ALICE).mode(), "the second change never happened");
		f.authorised().set(true);
		assertTrue(AdminService.assign(actor, f.rt(), ALICE, ELITE, false).applied(), "and works again once authority is back");
	}

	@Test
	void theActorsIdentityIsWhatIsRecordedAsTheAdministrator(@TempDir Path dir) throws IOException {
		Fixture f = fixture(dir);
		AdminService.assign(f.actor(), f.rt(), ALICE, HARD, false);
		assertEquals(ADMIN, rec(f, ALICE).by());
		AdminActor console = new AdminActor(null, "console", () -> true, f.feedback(), id -> null);
		AdminService.assign(console, f.rt(), BOB, ELITE, false);
		assertNull(rec(f, BOB).by(), "console and command blocks have no UUID");
	}

	// ------------------------------------------------------------------ feedback: exactly once, command-equivalent broadcast flags

	@Test
	void everyActionSendsExactlyOneMessage_appliedBroadcastsAndNothingElseDoes(@TempDir Path dir) throws IOException {
		Fixture f = fixture(dir);
		AdminActor a = f.actor();
		AdminService.assign(a, f.rt(), ALICE, HARD, false);
		assertEquals(1, f.feedback().total());
		assertEquals(List.of(true), f.feedback().broadcasts, "a stored change is broadcast to operators like the command does");
		AdminService.assign(a, f.rt(), ALICE, HARD, false);
		assertEquals(2, f.feedback().total());
		assertEquals(List.of(true, false), f.feedback().broadcasts, "'nothing changed' is not broadcast");
		AdminService.setLock(a, f.rt(), ALICE, true);
		AdminService.setLock(a, f.rt(), ALICE, true);
		AdminService.restrict(a, f.rt(), ALICE, EnumSet.of(HARD, ELITE), RestrictFlag.NONE);
		AdminService.unrestrict(a, f.rt(), ALICE);
		AdminService.unrestrict(a, f.rt(), ALICE);
		AdminService.reset(a, f.rt(), ALICE);
		AdminService.reset(a, f.rt(), ALICE);
		AdminService.setEnabled(a, f.rt(), EASY, false);
		AdminService.setEnabled(a, f.rt(), EASY, false);
		AdminService.enableAll(a, f.rt());
		AdminService.enableAll(a, f.rt());
		assertEquals(13, f.feedback().total(), "thirteen calls, thirteen messages, no extras");
		assertTrue(f.feedback().failures.isEmpty());
		assertEquals(List.of(true, false, true, false, true, true, false, true, false, true, false, true, false), f.feedback().broadcasts);
	}

	// ------------------------------------------------------------------ atomicity

	@Test
	void aFailedWriteChangesNothing_runsNoHook_andTheSameRequestWorksOnceTheFaultIsGone(@TempDir Path dir) throws IOException {
		Fixture f = fixture(dir);
		AdminService.assign(f.actor(), f.rt(), ALICE, HARD, false);
		String before = f.file();
		int lookups = f.onlineLookups().get();
		f.io().failWrite.set(true);
		for (AdminResult r : List.of(
			AdminService.assign(f.actor(), f.rt(), ALICE, ELITE, false),
			AdminService.setLock(f.actor(), f.rt(), ALICE, true),
			AdminService.restrict(f.actor(), f.rt(), ALICE, EnumSet.of(HARD), RestrictFlag.NONE),
			AdminService.reset(f.actor(), f.rt(), ALICE))) {
			assertEquals(Outcome.REFUSED, r.outcome(), r.message());
			assertTrue(r.message().contains("so nothing was changed"), r.message());
		}
		assertEquals(before, f.file(), "the file is byte-identical");
		assertEquals(HARD, rec(f, ALICE).mode());
		assertFalse(rec(f, ALICE).locked());
		assertEquals(lookups, f.onlineLookups().get(), "the mode-change hook never ran for a change that was not stored");
		f.io().failWrite.set(false);
		assertTrue(AdminService.assign(f.actor(), f.rt(), ALICE, ELITE, false).applied());
		assertEquals(ELITE, rec(f, ALICE).mode());
		assertEquals(lookups + 1, f.onlineLookups().get(), "the hook lookup happens only after a stored mode change");
	}

	@Test
	void aLockOnlyChangeNeverRunsTheModeHook(@TempDir Path dir) throws IOException {
		Fixture f = fixture(dir);
		AdminService.assign(f.actor(), f.rt(), ALICE, HARD, false);
		int lookups = f.onlineLookups().get();
		AdminService.setLock(f.actor(), f.rt(), ALICE, true);
		assertEquals(lookups, f.onlineLookups().get());
	}

	// ------------------------------------------------------------------ restriction: preview, choice and revalidation

	@Test
	void aRestrictionThatExcludesTheCurrentModeNeedsAChoice_switchAndKeepResolveIt_cancelIsSimplyNotCalling(@TempDir Path dir) throws IOException {
		Fixture f = fixture(dir);
		AdminService.assign(f.actor(), f.rt(), ALICE, NORMAL, false);
		AdminService.assign(f.actor(), f.rt(), BOB, NORMAL, false);
		var plan = AdminService.previewRestrict(f.rt(), ALICE, EnumSet.of(HARD, ELITE), RestrictFlag.NONE, ADMIN);
		assertEquals(com.zaggazach.personaldifficulty.logic.SelectionPolicy.RestrictStatus.NEEDS_CHOICE, plan.status());
		assertEquals(NORMAL, rec(f, ALICE).mode(), "previewing changes nothing");

		AdminResult refused = AdminService.restrict(f.actor(), f.rt(), ALICE, EnumSet.of(HARD, ELITE), RestrictFlag.NONE);
		assertEquals(Outcome.NEEDS_CHOICE, refused.outcome());
		assertNull(rec(f, ALICE).allowed(), "nothing stored without a choice");

		assertTrue(AdminService.restrict(f.actor(), f.rt(), ALICE, EnumSet.of(HARD, ELITE), RestrictFlag.SWITCH).applied());
		assertEquals(HARD, rec(f, ALICE).mode(), "switched to the lowest listed mode");
		assertEquals(EnumSet.of(HARD, ELITE), rec(f, ALICE).allowed());

		assertTrue(AdminService.restrict(f.actor(), f.rt(), BOB, EnumSet.of(HARD, ELITE), RestrictFlag.KEEP).applied());
		assertEquals(NORMAL, rec(f, BOB).mode(), "kept");
		assertTrue(rec(f, BOB).isException());
	}

	@Test
	void theChoiceIsRevalidatedAgainstTheCurrentRecord_aStateChangedMeanwhileIsNotOverridden(@TempDir Path dir) throws IOException {
		Fixture f = fixture(dir);
		AdminService.assign(f.actor(), f.rt(), ALICE, NORMAL, false);
		assertEquals(Outcome.NEEDS_CHOICE, AdminService.restrict(f.actor(), f.rt(), ALICE, EnumSet.of(HARD, ELITE), RestrictFlag.NONE).outcome());
		// while the administrator looks at the confirmation, the player (or another administrator) moves to HARD
		AdminService.assign(f.actor(), f.rt(), ALICE, HARD, false);
		AdminResult confirmed = AdminService.restrict(f.actor(), f.rt(), ALICE, EnumSet.of(HARD, ELITE), RestrictFlag.SWITCH);
		assertTrue(confirmed.applied());
		assertEquals(HARD, rec(f, ALICE).mode(), "no needless switch: the decision was taken on the CURRENT state");
	}

	@Test
	void aTargetThatBecameInvalidBetweenPreviewAndConfirmationIsRefusedAndKeptUntouched(@TempDir Path dir) throws IOException {
		Fixture f = fixture(dir);
		AdminService.assign(f.actor(), f.rt(), ALICE, NORMAL, false);
		// the file is damaged behind the mod's back and re-read (what a reload or a restart does)
		JsonFileHelper.corruptEntry(f.players(), ALICE.id(), "{\"mode\":\"NORMAL\",\"locked\":\"yes\"}");
		f.rt().settings().load();
		assertTrue(f.rt().settings().isQuarantined(ALICE.id()));
		String before = f.file();
		AdminResult r = AdminService.restrict(f.actor(), f.rt(), ALICE, EnumSet.of(HARD), RestrictFlag.SWITCH);
		assertEquals(Outcome.REFUSED, r.outcome());
		assertTrue(r.message().contains("invalid stored record") && r.message().contains("admin reset alice"), r.message());
		assertEquals(before, f.file());
	}

	// ------------------------------------------------------------------ quarantine: refuse ordinary changes, repair only by explicit reset

	@Test
	void aQuarantinedRecordIsNeverModifiedByOrdinaryActions_onlyTheExplicitResetRepairsItWithABackup(@TempDir Path dir) throws IOException {
		Fixture f = fixture(dir);
		AdminService.assign(f.actor(), f.rt(), BOB, EASY, false);
		JsonFileHelper.corruptEntry(f.players(), ALICE.id(), "{\"mode\":\"ELITE\",\"locked\":\"yes\",\"nick\":\"z\"}");
		f.rt().settings().load();
		String before = f.file();
		for (AdminResult r : List.of(
			AdminService.assign(f.actor(), f.rt(), ALICE, HARD, false),
			AdminService.assign(f.actor(), f.rt(), ALICE, HARD, true),
			AdminService.setLock(f.actor(), f.rt(), ALICE, false),
			AdminService.unrestrict(f.actor(), f.rt(), ALICE),
			AdminService.restrict(f.actor(), f.rt(), ALICE, EnumSet.of(HARD), RestrictFlag.KEEP))) {
			assertEquals(Outcome.REFUSED, r.outcome(), r.message());
			assertTrue(r.message().contains("invalid stored record") && r.message().contains("admin reset alice"), r.message());
		}
		assertEquals(before, f.file(), "byte-identical after every refused attempt");
		assertTrue(f.rt().settings().isQuarantined(ALICE.id()));

		AdminResult fix = AdminService.reset(f.actor(), f.rt(), ALICE);
		assertTrue(fix.applied(), fix.message());
		assertTrue(fix.message().contains("backup: players.json.backup-"), fix.message());
		assertFalse(f.rt().settings().isQuarantined(ALICE.id()));
		assertEquals(NORMAL, rec(f, ALICE).mode());
		assertTrue(rec(f, ALICE).extras().has("nick"), "unknown fields are kept");
		try (var files = Files.list(dir)) {
			assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("players.json.backup-")
				&& read(p).equals(before)), "a verified backup identical to the file before the repair");
		}
	}

	private static String read(Path p) {
		try {
			return Files.readString(p);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	// ------------------------------------------------------------------ server-wide policy

	@Test
	void thePolicyKeepsAtLeastOneDifficulty_andReportsHonestly(@TempDir Path dir) throws IOException {
		Fixture f = fixture(dir);
		AdminActor a = f.actor();
		for (PersonalDifficultyMode m : List.of(EASY, HARD, ELITE, PEACEFUL)) {
			assertTrue(AdminService.setEnabled(a, f.rt(), m, false).applied());
		}
		AdminResult last = AdminService.setEnabled(a, f.rt(), NORMAL, false);
		assertEquals(Outcome.REFUSED, last.outcome());
		assertTrue(last.message().contains("last enabled difficulty"), last.message());
		assertTrue(f.rt().availability().isEnabled(NORMAL));
		assertEquals(Outcome.UNCHANGED, AdminService.setEnabled(a, f.rt(), EASY, false).outcome());
		assertTrue(AdminService.enableAll(a, f.rt()).applied());
		assertEquals(Outcome.UNCHANGED, AdminService.enableAll(a, f.rt()).outcome());
	}
}
