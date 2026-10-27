package com.zaggazach.personaldifficulty.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.zaggazach.personaldifficulty.PdRuntime;
import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Shared helpers for the in-server tests. Everything here is synchronous unless stated. */
public final class PdTestSupport {
	public static final float EPS = 1e-4F;

	private PdTestSupport() {
	}

	// ------------------------------------------------------------------ assertions

	public static PdRuntime rt() {
		PdRuntime rt = PdRuntime.get();
		if (rt == null) {
			throw new AssertionError("PdRuntime is not installed: SERVER_STARTING hook did not run");
		}
		return rt;
	}

	public static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	public static void near(float expected, float actual, String what) {
		check(Math.abs(expected - actual) <= EPS * Math.max(1.0F, Math.abs(expected)), what + ": expected " + expected + " but was " + actual);
	}

	public static void nearRel(double expected, double actual, double relTolerance, String what) {
		check(Math.abs(expected - actual) <= relTolerance * Math.max(1e-9, Math.abs(expected)), what + ": expected " + expected + " (+-" + relTolerance * 100 + "%) but was " + actual);
	}

	// ------------------------------------------------------------------ players

	/** A real, placed, "client loaded", survival-mode ServerPlayer (mock players are otherwise invulnerable). */
	public static ServerPlayer player(GameTestHelper h, String name, PersonalDifficultyMode mode) {
		ServerPlayer p = placeNewPlayer(h, UUID.randomUUID(), name);
		Vec3 at = h.absoluteVec(new Vec3(6.5, 1.0, 2.5));
		p.snapTo(at.x, at.y, at.z);
		setMode(p, mode);
		return p;
	}

	/** Places a player with a given UUID (used to simulate a reconnect). */
	public static ServerPlayer placeNewPlayer(GameTestHelper h, UUID id, String name) {
		MinecraftServer server = h.getLevel().getServer();
		GameProfile profile = new GameProfile(id, name);
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
		ServerPlayer p = new ServerPlayer(server, h.getLevel(), profile, ClientInformation.createDefault()) {
			@Override
			public GameType gameMode() {
				return GameType.SURVIVAL;
			}

			// like Mojang's own GameTest mock: let the SERVER simulate movement (gravity, falling, contact hazards)
			@Override
			public boolean isClientAuthoritative() {
				return false;
			}
		};
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, p, cookie);
		p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		// the GameTest server defaults new players to CREATIVE; make them genuinely survival (abilities + game-mode manager)
		p.setGameMode(GameType.SURVIVAL);
		p.getAbilities().invulnerable = false;
		return p;
	}

	/**
	 * Removes a mock player from the server again (disconnect path: events fire, menus are aborted), so tests that create many
	 * players do not leave them standing in the shared world for the mob-AI tests that run later.
	 */
	public static void leave(ServerPlayer p) {
		try {
			p.level().getServer().getPlayerList().remove(p);
		} catch (RuntimeException e) {
			p.discard();
		}
	}

	public static void setMode(ServerPlayer p, PersonalDifficultyMode mode) {
		try {
			rt().settings().set(p.getUUID(), mode);
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * One vanilla player tick. Mock players have no network handler, so nothing else ticks them:
	 * {@code tick()} is the level-driven half, {@code doTick()} the connection-driven half (hazards,
	 * effects, {@code FoodData.tick}).
	 */
	public static void tickPlayer(ServerPlayer p) {
		p.tick();
		p.doTick();
	}

	public static void tickPlayer(ServerPlayer p, int ticks) {
		for (int i = 0; i < ticks; i++) {
			tickPlayer(p);
		}
	}

	public static float lost(ServerPlayer p, DamageSource source, float amount) {
		p.setHealth(20.0F);
		p.setAbsorptionAmount(0.0F);
		p.damageCooldownTime = 0;
		p.hurtServer(p.level(), source, amount);
		return 20.0F - p.getHealth();
	}

	public static float exhaustion(ServerPlayer p) {
		try {
			Field f = FoodData.class.getDeclaredField("exhaustionLevel");
			f.setAccessible(true);
			return f.getFloat(p.getFoodData());
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	public static void setExhaustion(ServerPlayer p, float v) {
		try {
			Field f = FoodData.class.getDeclaredField("exhaustionLevel");
			f.setAccessible(true);
			f.setFloat(p.getFoodData(), v);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/** Builds a stone floor under the 8x8 test arena (the empty structure is all air). */
	public static void floor(GameTestHelper h) {
		for (int x = -2; x <= 9; x++) {
			for (int z = -2; z <= 9; z++) {
				h.setBlock(x, 0, z, Blocks.STONE);
			}
		}
	}

	/**
	 * Runs {@code body} at a tick and turns an assertion failure into a normal GameTest failure. (An exception thrown
	 * from a scheduled callback would otherwise crash the whole GameTest server and hide every other result.)
	 */
	public static void at(GameTestHelper h, long tick, Runnable body) {
		h.runAtTickTime(tick, () -> guarded(h, body));
	}

	/** Same, for the per-tick callback ({@code onEachTick} supports only one callback per test). */
	public static void everyTick(GameTestHelper h, Runnable body) {
		h.onEachTick(() -> guarded(h, body));
	}

	private static void guarded(GameTestHelper h, Runnable body) {
		try {
			body.run();
		} catch (AssertionError | RuntimeException e) {
			h.fail("" + e);
		}
	}

	// ------------------------------------------------------------------ equipment

	public enum Tier {
		NONE, IRON, DIAMOND, NETHERITE, DIAMOND_PROT4, NETHERITE_PROT4
	}

	public static void equip(ServerLevel level, ServerPlayer p, Tier tier) {
		Item[] pieces = switch (tier) {
			case NONE -> new Item[] {null, null, null, null};
			case IRON -> new Item[] {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
			case DIAMOND, DIAMOND_PROT4 -> new Item[] {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
			case NETHERITE, NETHERITE_PROT4 -> new Item[] {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS};
		};
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		var enchantments = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
		boolean enchant = tier == Tier.DIAMOND_PROT4 || tier == Tier.NETHERITE_PROT4;
		for (int i = 0; i < 4; i++) {
			ItemStack stack = pieces[i] == null ? ItemStack.EMPTY : new ItemStack(pieces[i]);
			if (enchant) {
				stack.enchant(enchantments.getOrThrow(Enchantments.PROTECTION), 4);
			}
			p.setItemSlot(slots[i], stack);
		}
		// Armour attributes are only applied by the player's next tick; without this the armour would silently not count.
		tickPlayer(p);
		check(tier == Tier.NONE || p.getArmorValue() > 0, "armour points must really be in effect for " + tier + " (was " + p.getArmorValue() + ")");
	}

	// ------------------------------------------------------------------ config

	public static void withConfig(String properties, Runnable body) {
		PdRuntime rt = rt();
		try {
			Files.writeString(rt.configFile(), properties);
			check(!rt.reload().config().readFailed(), "config reload failed");
			body.run();
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		} finally {
			try {
				Files.writeString(rt.configFile(), PdConfigLoader.defaultFileContents());
			} catch (java.io.IOException ignored) {
				// best effort; the run directory is disposable
			}
			rt.reload();
		}
	}

	// ------------------------------------------------------------------ ranged sampling

	public record Sample(double spread, double meanSpeed, double baseDamage, int shots, List<Vec3> directions) {
		/**
		 * Fraction of shots that would land inside a player-sized box {@code range} blocks away, taking the
		 * sample mean as the aimed direction (noise is zero-mean). Only a statistical illustration of how
		 * often the spread alone causes a miss; real hits also depend on movement, gravity and drag.
		 */
		public double hitFraction(double range, double halfWidth, double halfHeight) {
			Vec3 mean = Vec3.ZERO;
			for (Vec3 d : directions) {
				mean = mean.add(d);
			}
			mean = mean.normalize();
			Vec3 right = mean.cross(new Vec3(0, 1, 0)).normalize();
			Vec3 up = right.cross(mean).normalize();
			int hits = 0;
			for (Vec3 d : directions) {
				Vec3 off = d.subtract(mean).scale(range);
				if (Math.abs(off.dot(right)) <= halfWidth && Math.abs(off.dot(up)) <= halfHeight) {
					hits++;
				}
			}
			return hits / (double) directions.size();
		}
	}

	/** Fires {@code shots} projectiles and measures direction scatter (RMS of unit-vector deviation from the mean). */
	public static Sample sample(ServerLevel level, Mob shooter, int shots, Runnable fire) {
		List<Vec3> dirs = new ArrayList<>();
		double speed = 0;
		double damage = 0;
		int arrows = 0;
		for (int i = 0; i < shots; i++) {
			fire.run();
			List<Projectile> fired = level.getEntitiesOfClass(Projectile.class, shooter.getBoundingBox().inflate(3.0));
			check(fired.size() == 1, "expected exactly one projectile per shot (adjustment applied exactly once) but saw " + fired.size());
			Projectile p = fired.get(0);
			Vec3 v = p.getDeltaMovement();
			speed += v.length();
			dirs.add(v.normalize());
			if (p instanceof AbstractArrow a) {
				damage += baseDamage(a);
				arrows++;
			}
			p.discard();
		}
		Vec3 mean = Vec3.ZERO;
		for (Vec3 d : dirs) {
			mean = mean.add(d);
		}
		mean = mean.scale(1.0 / dirs.size());
		double sq = 0;
		for (Vec3 d : dirs) {
			sq += d.subtract(mean).lengthSqr();
		}
		return new Sample(Math.sqrt(sq / dirs.size()), speed / shots, arrows == 0 ? Double.NaN : damage / arrows, shots, dirs);
	}

	public static double baseDamage(AbstractArrow arrow) {
		try {
			Field f = AbstractArrow.class.getDeclaredField("baseDamage");
			f.setAccessible(true);
			return f.getDouble(arrow);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	public static double vanillaUncertainty(ServerLevel level) {
		return 14 - level.getDifficulty().getId() * 4;
	}

	private static void sameSpeedAndDamage(String what, Sample a, Sample b) {
		check(Math.abs(a.meanSpeed() - b.meanSpeed()) / b.meanSpeed() < 0.03, what + ": projectile speed must not be substituted for aim");
		if (!Double.isNaN(a.baseDamage())) {
			// vanilla randomises per-arrow damage (gaussian + difficulty term), so compare means
			check(Math.abs(a.baseDamage() - b.baseDamage()) < 0.06, what + ": arrow damage must not be substituted for aim (" + a.baseDamage() + " vs " + b.baseDamage() + ")");
		}
	}

	public static void expectWider(String what, Sample easy, Sample normal, Sample nonPlayer, double vanillaUncertainty) {
		System.out.printf("AIM-SPREAD %s: easy %.5f normal %.5f non-player %.5f (vanilla uncertainty %.0f)%n", what, easy.spread(), normal.spread(), nonPlayer.spread(), vanillaUncertainty);
		double expectedEasy = Math.max(vanillaUncertainty, 14.0);
		double expectedRatio = expectedEasy / vanillaUncertainty;
		check(expectedRatio >= 1.5, "test would be vacuous: world difficulty gives vanilla uncertainty " + vanillaUncertainty);
		double ratio = easy.spread() / normal.spread();
		check(ratio > expectedRatio * 0.75 && ratio < expectedRatio * 1.25, what + ": easy/normal spread ratio " + ratio + " should be ~" + expectedRatio);
		check(Math.abs(normal.spread() - nonPlayer.spread()) / nonPlayer.spread() < 0.12,
			what + ": a Normal player must be shot at exactly like a non-player target (normal=" + normal.spread() + ", mob=" + nonPlayer.spread() + ")");
		sameSpeedAndDamage(what, easy, normal);
	}

	public static void expectTighter(String what, Sample elite, Sample normal, Sample nonPlayer, double vanillaUncertainty, double eliteMax) {
		System.out.printf("AIM-SPREAD %s: elite %.5f normal %.5f non-player %.5f (vanilla uncertainty %.0f, elite max %.2f)%n", what, elite.spread(), normal.spread(), nonPlayer.spread(), vanillaUncertainty, eliteMax);
		double expectedRatio = Math.min(vanillaUncertainty, eliteMax) / vanillaUncertainty;
		check(expectedRatio <= 0.6, "test would be vacuous: world difficulty gives vanilla uncertainty " + vanillaUncertainty);
		double ratio = elite.spread() / normal.spread();
		check(ratio > expectedRatio * 0.75 && ratio < expectedRatio * 1.25, what + ": elite/normal spread ratio " + ratio + " should be ~" + expectedRatio);
		check(elite.spread() > 0.0, what + ": Elite must not be a zero-spread guaranteed hit");
		check(Math.abs(normal.spread() - nonPlayer.spread()) / nonPlayer.spread() < 0.12,
			what + ": a Normal player must be shot at exactly like a non-player target (normal=" + normal.spread() + ", mob=" + nonPlayer.spread() + ")");
		sameSpeedAndDamage(what, elite, normal);
	}

	// ------------------------------------------------------------------ commands

	public static final class Capture implements CommandSource {
		public final List<String> messages = new ArrayList<>();

		@Override
		public void sendSystemMessage(Component message) {
			messages.add(message.getString());
		}

		@Override
		public boolean acceptsSuccess() {
			return true;
		}

		@Override
		public boolean acceptsFailure() {
			return true;
		}

		@Override
		public boolean shouldInformAdmins() {
			return false;
		}

		public String all() {
			return String.join("\n", messages);
		}
	}

	public record Outcome(int result, String error, String output) {
	}

	public static Outcome run(MinecraftServer server, String command, CommandSourceStack source, Capture cap) {
		CommandDispatcher<CommandSourceStack> d = server.getCommands().getDispatcher();
		cap.messages.clear();
		try {
			int r = d.execute(command, source);
			return new Outcome(r, null, cap.all());
		} catch (CommandSyntaxException e) {
			return new Outcome(0, e.getMessage(), cap.all());
		}
	}

	public static CommandSourceStack as(ServerPlayer p, PermissionSet permissions, Capture cap) {
		return p.createCommandSourceStack().withSource(cap).withPermission(permissions);
	}

	/** A source like a command block: no entity, level-2 permission. */
	public static CommandSourceStack commandBlock(ServerLevel level, Capture cap) {
		return new CommandSourceStack(cap, Vec3.ZERO, net.minecraft.world.phys.Vec2.ZERO, level, net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER,
			Component.literal("@"), level.getServer());
	}

	public static CommandSourceStack console(MinecraftServer server, Capture cap) {
		return server.createCommandSourceStack().withSource(cap);
	}
}
