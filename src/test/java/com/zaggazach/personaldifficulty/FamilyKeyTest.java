package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.config.FamilyKey;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The strict grammar of damage-type keys (commands and the overrides file). Expected results are literal. */
class FamilyKeyTest {
	private static FamilyKey ok(String text) {
		FamilyKey.Parsed p = FamilyKey.parse(text);
		assertInstanceOf(FamilyKey.Ok.class, p, text + " -> " + p);
		return ((FamilyKey.Ok) p).key();
	}

	private static String invalid(String text) {
		FamilyKey.Parsed p = FamilyKey.parse(text);
		assertInstanceOf(FamilyKey.Invalid.class, p, text + " -> " + p);
		return ((FamilyKey.Invalid) p).message();
	}

	@Test
	void validKeys() {
		FamilyKey a = ok("easy.damage-type.minecraft.fall");
		assertEquals("easy", a.family().profile());
		assertEquals("minecraft", a.namespace());
		assertEquals("fall", a.path());
		assertEquals("minecraft:fall", a.id());
		assertEquals("easy.damage-type.minecraft.fall", a.key());
		assertEquals("ns:a.b", ok("hard.damage-type.ns.a.b").id(), "the first dot separates namespace from path");
		assertEquals("elite", ok("elite.damage-type.mod.my_type").family().profile());
		assertEquals("m-1:x-y", ok("easy.damage-type.m-1.x-y").id());
		assertEquals("a_b:c0", ok("hard.damage-type.a_b.c0").id());
	}

	@Test
	void modesWithoutAProfileAreRefusedWithAnExplanation() {
		for (String mode : List.of("normal", "peaceful", "Easy", "EASY", "", "easyx", "inherit")) {
			String message = invalid(mode + ".damage-type.minecraft.fall");
			assertTrue(message.contains("easy, hard or elite"), mode + ": " + message);
		}
	}

	@Test
	void malformedIdentifiersAreRefused() {
		assertTrue(invalid("easy.damage-type.").contains("<namespace>.<path>"));
		assertTrue(invalid("easy.damage-type.minecraft").contains("<namespace>.<path>"));
		assertTrue(invalid("easy.damage-type.minecraft.").contains("<namespace>.<path>"));
		assertTrue(invalid("easy.damage-type..fall").contains("<namespace>.<path>"));
		assertTrue(invalid("easy.damage-type.Minecraft.fall").contains("namespace"));
		assertTrue(invalid("easy.damage-type.mine craft.fall").contains("namespace"));
		assertTrue(invalid("easy.damage-type.münchen.x").contains("namespace"));
		assertEquals("mine:craft.fall.x", ok("easy.damage-type.mine.craft.fall.x").id(), "dots after the first belong to the path");
		assertTrue(invalid("easy.damage-type.minecraft.Fall").contains("path"));
		assertTrue(invalid("easy.damage-type.minecraft.fa ll").contains("path"));
		assertTrue(invalid("easy.damage-type.minecraft.fa:ll").contains("path"));
		assertTrue(invalid("easy.damage-type.minecraft.fä").contains("path"));
	}

	@Test
	void aSlashInThePathIsRefusedWithAPointerToThePropertiesFile() {
		String message = invalid("easy.damage-type.minecraft.my/type");
		assertTrue(message.contains("/") && message.contains("personaldifficulty.properties"), message);
	}

	@Test
	void textThatIsNotAFamilyKeyIsReportedAsSuch() {
		for (String t : List.of("", "easy.damage-multiplier", "easy.damage_type.minecraft.fall", "damage-type.minecraft.fall.x", "easy.damage-typeminecraft.fall", "elite")) {
			assertInstanceOf(FamilyKey.NotAFamilyKey.class, FamilyKey.parse(t), t);
		}
	}
}
