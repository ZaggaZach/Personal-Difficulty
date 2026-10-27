package com.zaggazach.personaldifficulty.admin;

import com.zaggazach.personaldifficulty.PdPermissions;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Who is performing an administrative action, as established by the SERVER: from the command source that Brigadier authenticated, or
 * from the {@link ServerPlayer} that owns an open menu. Nothing here is ever taken from client-supplied data. The authority is not a
 * stored flag: {@link #authorised()} re-reads the live permissions at every call (so /deop takes effect at once), and every
 * {@link AdminService} method calls it before it touches any state.
 *
 * <p>Feedback goes through a {@link Feedback}. The two factories send it through a {@link CommandSourceStack}, which is exactly what
 * the commands always did: the actor sees the message and operators are told (and the server console logs it) when {@code broadcast}
 * is true. A GUI action therefore produces the same audit trail and broadcast as the equivalent command, once.
 *
 * <p>Not related to {@link com.zaggazach.personaldifficulty.logic.SelectionPolicy.Actor}, which describes a SELF-SERVICE request.
 */
public final class AdminActor {
	/** Where the human-readable outcome of an action is reported. */
	public interface Feedback {
		void success(String message, boolean broadcast);

		void failure(String message);
	}

	private final @Nullable UUID id;
	private final String name;
	private final BooleanSupplier authority;
	private final Feedback feedback;
	private final Function<UUID, @Nullable ServerPlayer> online;

	/** Package-private on purpose: only the factories below (and tests in this package) can build an actor, so identity and authority cannot be injected from outside. */
	AdminActor(@Nullable UUID id, String name, BooleanSupplier authority, Feedback feedback, Function<UUID, @Nullable ServerPlayer> online) {
		this.id = id;
		this.name = name;
		this.authority = authority;
		this.feedback = feedback;
		this.online = online;
	}

	/** The actor behind an authenticated command: a player, the console or a command block. */
	public static AdminActor fromCommand(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		return new AdminActor(
			player == null ? null : player.getUUID(),
			source.getTextName(),
			() -> PdPermissions.canAdminister(source),
			new SourceFeedback(source),
			uuid -> source.getServer().getPlayerList().getPlayer(uuid)
		);
	}

	/** The actor behind an open menu: always the menu's own player, re-checked against the live ops list on every use. */
	public static AdminActor fromPlayer(ServerPlayer player) {
		return fromPlayer(player, new SourceFeedback(player));
	}

	/** As {@link #fromPlayer(ServerPlayer)} but reporting to {@code feedback}; identity and authority stay the player's own. */
	public static AdminActor fromPlayer(ServerPlayer player, Feedback feedback) {
		return new AdminActor(
			player.getUUID(),
			player.getGameProfile().name(),
			() -> !player.hasDisconnected() && PdPermissions.canAdminister(player),
			feedback,
			uuid -> player.level().getServer().getPlayerList().getPlayer(uuid)
		);
	}

	/** The administrator's UUID for "last changed by"; null for the console and command blocks. */
	public @Nullable UUID id() {
		return id;
	}

	public String name() {
		return name;
	}

	/** True only if this actor may administer Personal Difficulty right now. */
	public boolean authorised() {
		return authority.getAsBoolean();
	}

	public Feedback feedback() {
		return feedback;
	}

	@Nullable ServerPlayer online(UUID target) {
		return online.apply(target);
	}

	private record SourceFeedback(java.util.function.Supplier<CommandSourceStack> source) implements Feedback {
		SourceFeedback(CommandSourceStack fixed) {
			this(() -> fixed);
		}

		SourceFeedback(ServerPlayer player) {
			this(player::createCommandSourceStack);
		}

		@Override
		public void success(String message, boolean broadcast) {
			source.get().sendSuccess(() -> Component.literal(message), broadcast);
		}

		@Override
		public void failure(String message) {
			source.get().sendFailure(Component.literal(message));
		}
	}
}
