package com.zaggazach.personaldifficulty.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zaggazach.personaldifficulty.admin.AdminResult.Outcome;
import org.junit.jupiter.api.Test;

/**
 * {@code admin enable/disable} report success to Brigadier only when the change was stored or there was nothing to change. The
 * parser's {@code requires} makes UNAUTHORISED unreachable today; the mapping must still never call it a success.
 */
class PolicyCommandExitCodeTest {
	@Test
	void onlyAppliedAndUnchangedAreSuccess_everyOtherOutcomeIsAFailure() {
		assertEquals(1, PdCommands.exitCode(Outcome.APPLIED));
		assertEquals(1, PdCommands.exitCode(Outcome.UNCHANGED));
		assertEquals(0, PdCommands.exitCode(Outcome.REFUSED));
		assertEquals(0, PdCommands.exitCode(Outcome.UNAUTHORISED));
		assertEquals(0, PdCommands.exitCode(Outcome.NEEDS_CHOICE));
	}

	@Test
	void everyOutcomeThatExistsIsCoveredByTheMapping() {
		int successes = 0;
		for (Outcome o : Outcome.values()) {
			successes += PdCommands.exitCode(o);
		}
		assertEquals(2, successes, "a new Outcome must be classified deliberately: exactly APPLIED and UNCHANGED succeed");
	}
}
