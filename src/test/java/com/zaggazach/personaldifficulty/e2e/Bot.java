package com.zaggazach.personaldifficulty.e2e;

import io.netty.channel.ChannelFuture;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.client.multiplayer.RegistryDataCollector;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.HashedStack;
import net.minecraft.network.PacketListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A headless Minecraft client speaking the real network protocol (login, configuration, play) with the game's
 * own packet codecs. It is NOT a vanilla client: it renders nothing and is driven by tests. It exists so that
 * the mod can be exercised end-to-end over a socket against a real dedicated server process.
 */
public final class Bot implements AutoCloseable {
	public final String name;
	private Connection connection;
	private RegistryAccess.Frozen registries;
	private volatile boolean connected;
	private volatile String disconnectReason;

	public final List<String> chat = new CopyOnWriteArrayList<>();
	private final Map<Integer, List<ItemStack>> containers = new ConcurrentHashMap<>();
	private volatile int menuId;
	private volatile int menuStateId;
	private volatile String menuTitle = "";
	private volatile String menuTypeName = "";
	private volatile ItemStack carried = ItemStack.EMPTY;
	private final ItemStack[] inventory = new ItemStack[36];
	private volatile int openCount;
	private volatile List<String> lastSuggestions = List.of();
	private volatile int lastSuggestionsId = -1;
	private int suggestionCounter = 100;

	/** Asks the server for tab-completion of a partially typed command (without the leading slash). */
	public List<String> suggest(String partialCommand) {
		int id = ++suggestionCounter;
		connection.send(new ServerboundCommandSuggestionPacket(id, "/" + partialCommand));
		await(() -> lastSuggestionsId == id, 10_000);
		return lastSuggestionsId == id ? lastSuggestions : List.of();
	}

	private static final boolean DEBUG = Boolean.getBoolean("pd.e2e.debug");

	static {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	public Bot(String name) {
		this.name = name;
		Arrays.fill(inventory, ItemStack.EMPTY);
	}

	// ------------------------------------------------------------------------------------------- connection

	private final String[] owner = new String[1];
	private volatile String lastPacket = "none";

	private <T extends PacketListener> T listener(Class<T> iface, ConnectionProtocol protocol, Map<Class<?>, Consumer<Object>> handlers, Runnable onDisconnect) {
		owner[0] = name;
		InvocationHandler h = (proxy, method, args) -> {
			switch (method.getName()) {
				case "flow":
					return PacketFlow.CLIENTBOUND;
				case "protocol":
					return protocol;
				case "isAcceptingMessages":
				case "shouldHandleMessage":
					return true;
				case "onDisconnect":
					if (args != null && args.length > 0 && args[0] instanceof DisconnectionDetails d && disconnectReason == null) {
						disconnectReason = "connection lost: " + d.reason().getString();
					}
					onDisconnect.run();
					return null;
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == args[0];
				case "toString":
					return iface.getSimpleName() + "Proxy";
				default:
					break;
			}
			if (method.isDefault()) {
				return InvocationHandler.invokeDefault(proxy, method, args);
			}
			if (args != null && args.length == 1 && args[0] instanceof Packet<?>) {
				lastPacket = protocol + ":" + args[0].getClass().getSimpleName() + "@" + System.currentTimeMillis();
				if (DEBUG) {
					System.out.println("[bot " + owner[0] + " " + protocol + "] <- " + args[0].getClass().getSimpleName());
				}
				Consumer<Object> c = handlers.get(args[0].getClass());
				if (c != null) {
					c.accept(args[0]);
				}
			}
			return null;
		};
		return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] { iface }, h));
	}

	/** Connects and completes login + configuration; returns once the play phase has begun. */
	public static Bot join(String host, int port, String name) throws Exception {
		Bot bot = new Bot(name);
		bot.connect(host, port);
		return bot;
	}

	private void connect(String host, int port) throws Exception {
		connection = Connection.connectToServer(new InetSocketAddress(host, port), EventLoopGroupHolder.remote(false), null);
		RegistryDataCollector collector = new RegistryDataCollector();
		Runnable lost = () -> connected = false;

		Map<Class<?>, Consumer<Object>> play = new java.util.HashMap<>();
		play.put(ClientboundKeepAlivePacket.class, p -> connection.send(new ServerboundKeepAlivePacket(((ClientboundKeepAlivePacket) p).getId())));
		play.put(ClientboundPingPacket.class, p -> connection.send(new ServerboundPongPacket(((ClientboundPingPacket) p).getId())));
		play.put(ClientboundPlayerPositionPacket.class, p -> {
			ClientboundPlayerPositionPacket t = (ClientboundPlayerPositionPacket) p;
			var pos = t.change().position();
			connection.send(new ServerboundAcceptTeleportationPacket(t.id(), pos.x, pos.y, pos.z, t.change().yRot(), t.change().xRot()));
		});
		play.put(ClientboundDisconnectPacket.class, p -> {
			disconnectReason = ((ClientboundDisconnectPacket) p).reason().getString();
			connected = false;
		});
		play.put(ClientboundSystemChatPacket.class, p -> {
			ClientboundSystemChatPacket c = (ClientboundSystemChatPacket) p;
			if (!c.overlay()) {
				chat.add(c.content().getString());
			}
		});
		play.put(ClientboundOpenScreenPacket.class, p -> {
			ClientboundOpenScreenPacket o = (ClientboundOpenScreenPacket) p;
			synchronized (this) {
				menuId = o.getContainerId();
				menuTitle = o.getTitle().getString();
				menuTypeName = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(o.getType()));
				containers.remove(menuId);
				openCount++;
			}
		});
		play.put(ClientboundCommandSuggestionsPacket.class, p -> {
			ClientboundCommandSuggestionsPacket c = (ClientboundCommandSuggestionsPacket) p;
			lastSuggestions = c.suggestions().stream().map(e -> e.text()).toList();
			lastSuggestionsId = c.id();
		});
		play.put(ClientboundContainerSetContentPacket.class, p -> onContent((ClientboundContainerSetContentPacket) p));
		play.put(ClientboundContainerSetSlotPacket.class, p -> onSlot((ClientboundContainerSetSlotPacket) p));
		play.put(ClientboundSetCursorItemPacket.class, p -> carried = ((ClientboundSetCursorItemPacket) p).contents());
		play.put(ClientboundSetPlayerInventoryPacket.class, p -> {
			ClientboundSetPlayerInventoryPacket s = (ClientboundSetPlayerInventoryPacket) p;
			if (s.slot() >= 0 && s.slot() < 36) {
				inventory[s.slot()] = s.contents();
			}
		});
		play.put(ClientboundContainerClosePacket.class, p -> {
			synchronized (this) {
				if (((ClientboundContainerClosePacket) p).getContainerId() == menuId) {
					menuId = 0;
					menuTitle = "";
				}
			}
		});
		ClientGamePacketListener playListener = listener(ClientGamePacketListener.class, ConnectionProtocol.PLAY, play, lost);

		Map<Class<?>, Consumer<Object>> config = new java.util.HashMap<>();
		config.put(ClientboundSelectKnownPacks.class, p -> connection.send(new ServerboundSelectKnownPacks(List.of())));
		config.put(ClientboundRegistryDataPacket.class, p -> collector.appendContents(((ClientboundRegistryDataPacket) p).registry(), ((ClientboundRegistryDataPacket) p).entries()));
		config.put(ClientboundUpdateTagsPacket.class, p -> collector.appendTags(((ClientboundUpdateTagsPacket) p).tags()));
		config.put(ClientboundPingPacket.class, p -> connection.send(new ServerboundPongPacket(((ClientboundPingPacket) p).getId())));
		config.put(ClientboundKeepAlivePacket.class, p -> connection.send(new ServerboundKeepAlivePacket(((ClientboundKeepAlivePacket) p).getId())));
		config.put(ClientboundDisconnectPacket.class, p -> {
			disconnectReason = ((ClientboundDisconnectPacket) p).reason().getString();
			connected = false;
		});
		config.put(ClientboundFinishConfigurationPacket.class, p -> {
			// Building the registries is slow; do it off the (shared) network thread. The server sends nothing more until
			// it receives our acknowledgement below, so no packet can arrive while the protocol is being switched.
			Thread t = new Thread(() -> {
				try {
					registries = collector.collectGameRegistries(ResourceProvider.EMPTY, ClientRegistryLayer.createRegistryAccess().compositeAccess(), false);
					// The three steps must run in order on the network thread, exactly as in the vanilla client.
					onNetworkThread(() -> {
						connection.setupInboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries)), playListener);
						connection.send(ServerboundFinishConfigurationPacket.INSTANCE);
						connection.setupOutboundProtocol(GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries), new GameProtocols.Context() {
							@Override
							public boolean hasInfiniteMaterials() {
								return true;
							}

							@Override
							public boolean canUseCommandBlocks() {
								return true;
							}
						}));
						connected = true;
					});
				} catch (RuntimeException e) {
					disconnectReason = "bot failed to finish configuration: " + e;
					e.printStackTrace();
				}
			}, "bot-config-" + name);
			t.setDaemon(true);
			t.start();
		});
		ClientConfigurationPacketListener configListener = listener(ClientConfigurationPacketListener.class, ConnectionProtocol.CONFIGURATION, config, lost);

		Map<Class<?>, Consumer<Object>> login = new java.util.HashMap<>();
		login.put(ClientboundLoginCompressionPacket.class, p -> connection.setupCompression(((ClientboundLoginCompressionPacket) p).getCompressionThreshold(), false));
		login.put(ClientboundLoginDisconnectPacket.class, p -> disconnectReason = ((ClientboundLoginDisconnectPacket) p).reason().getString());
		login.put(ClientboundLoginFinishedPacket.class, p -> {
			connection.setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND, configListener);
			connection.send(ServerboundLoginAcknowledgedPacket.INSTANCE);
			connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
		});
		ClientLoginPacketListener loginListener = listener(ClientLoginPacketListener.class, ConnectionProtocol.LOGIN, login, lost);

		// Connection.connectToServer returns when the TCP connect completes, slightly before the channel is marked active.
		if (!await(connection::isConnected, 10_000)) {
			throw new IllegalStateException(name + " could not open a connection");
		}
		if (DEBUG) {
			try {
				var f = Connection.class.getDeclaredField("channel");
				f.setAccessible(true);
				io.netty.channel.Channel ch = (io.netty.channel.Channel) f.get(connection);
				ch.pipeline().addFirst("bot-trace", new io.netty.channel.ChannelDuplexHandler() {
					@Override
					public void write(io.netty.channel.ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) throws Exception {
						System.out.println("[bot " + name + " TRACE] write " + (msg instanceof io.netty.buffer.ByteBuf b ? b.readableBytes() + " bytes" : msg.getClass().getSimpleName()));
						super.write(ctx, msg, promise);
					}

					@Override
					public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) throws Exception {
						System.out.println("[bot " + name + " TRACE] read " + (msg instanceof io.netty.buffer.ByteBuf b ? b.readableBytes() + " bytes" : msg.getClass().getSimpleName()));
						super.channelRead(ctx, msg);
					}

					@Override
					public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) throws Exception {
						System.out.println("[bot " + name + " TRACE] exception " + cause);
						cause.printStackTrace(System.out);
						super.exceptionCaught(ctx, cause);
					}

					@Override
					public void channelInactive(io.netty.channel.ChannelHandlerContext ctx) throws Exception {
						System.out.println("[bot " + name + " TRACE] channel inactive");
						super.channelInactive(ctx);
					}
				});
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}
		// Handshake, protocol switch and hello run in order on the network thread (as the vanilla client does); doing
		// them from this thread would race the thread that encodes the packets.
		onNetworkThread(() -> {
			connection.initiateServerboundPlayConnection(host, port, LoginProtocols.SERVERBOUND, LoginProtocols.CLIENTBOUND, loginListener, false);
			connection.send(new ServerboundHelloPacket(name, UUIDUtil.createOfflinePlayerUUID(name)));
		});
		if (!await(() -> connected || disconnectReason != null, 30_000)) {
			throw new IllegalStateException(name + " could not finish joining (last packet " + lastPacket + ", now " + System.currentTimeMillis()
				+ ", connected=" + connection.isConnected() + ")");
		}
		if (disconnectReason != null) {
			throw new IllegalStateException(name + " was disconnected while joining: " + disconnectReason);
		}
		// wait for the first inventory snapshot
		await(() -> containers.containsKey(0), 15_000);
		// A vanilla client tells the server once it has finished loading the world; until then the server keeps the player
		// invulnerable and paused. Do the same so the bot behaves like a real, fully joined player.
		connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
	}

	private void onNetworkThread(Runnable task) {
		try {
			var field = Connection.class.getDeclaredField("channel");
			field.setAccessible(true);
			io.netty.channel.Channel channel = (io.netty.channel.Channel) field.get(connection);
			java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
			channel.eventLoop().execute(() -> {
				try {
					task.run();
					done.complete(null);
				} catch (Throwable t) {
					done.completeExceptionally(t);
				}
			});
			done.get(30, java.util.concurrent.TimeUnit.SECONDS);
		} catch (ReflectiveOperationException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
			throw new IllegalStateException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	public UUID uuid() {
		return UUIDUtil.createOfflinePlayerUUID(name);
	}

	public boolean isConnected() {
		return connected && connection.isConnected();
	}

	public String disconnectReason() {
		return disconnectReason;
	}

	// ------------------------------------------------------------------------------------------- state tracking

	private void onContent(ClientboundContainerSetContentPacket p) {
		synchronized (this) {
			containers.put(p.containerId(), new ArrayList<>(p.items()));
			if (p.containerId() == menuId) {
				menuStateId = p.stateId();
				carried = p.carriedItem();
			}
			syncInventoryFrom(p.containerId());
		}
	}

	private void onSlot(ClientboundContainerSetSlotPacket p) {
		if (DEBUG) {
			System.out.println("[bot SETSLOT " + name + "] container=" + p.getContainerId() + " slot=" + p.getSlot() + " item=" + p.getItem() + " menuId=" + menuId + " known=" + containers.containsKey(p.getContainerId()));
		}
		synchronized (this) {
			List<ItemStack> list = containers.get(p.getContainerId());
			if (list != null && p.getSlot() >= 0 && p.getSlot() < list.size()) {
				list.set(p.getSlot(), p.getItem());
			}
			if (p.getContainerId() == menuId) {
				menuStateId = p.getStateId();
			}
			syncInventoryFrom(p.getContainerId());
		}
	}

	/** Mirrors the player-inventory part of whichever container just changed into {@link #inventory}. */
	private void syncInventoryFrom(int containerId) {
		List<ItemStack> list = containers.get(containerId);
		if (list == null) {
			return;
		}
		// While another menu is open the server only keeps that menu's copy of the inventory fresh (as for a real client).
		if (containerId != menuId && !(containerId == 0 && menuId == 0)) {
			return;
		}
		if (containerId == 0 && list.size() >= 45) {
			for (int i = 0; i < 27; i++) {
				inventory[9 + i] = list.get(9 + i);
			}
			for (int i = 0; i < 9; i++) {
				inventory[i] = list.get(36 + i);
			}
		} else if (containerId != 0 && list.size() >= 36) {
			int base = list.size() - 36;
			for (int i = 0; i < 27; i++) {
				inventory[9 + i] = list.get(base + i);
			}
			for (int i = 0; i < 9; i++) {
				inventory[i] = list.get(base + 27 + i);
			}
		}
	}

	public synchronized List<ItemStack> menuSlots() {
		List<ItemStack> l = containers.get(menuId);
		return l == null ? List.of() : new ArrayList<>(l);
	}

	public int menuId() {
		return menuId;
	}

	public String menuTitle() {
		return menuTitle;
	}

	public String menuType() {
		return menuTypeName;
	}

	public int openCount() {
		return openCount;
	}

	/** Number of container slots (excluding the 36 inventory slots) of the open menu. */
	public int menuTopSize() {
		return Math.max(0, menuSlots().size() - 36);
	}

	public synchronized ItemStack[] inventory() {
		return inventory.clone();
	}

	public synchronized int count(Item item) {
		int n = 0;
		for (ItemStack s : inventory) {
			if (s.is(item)) {
				n += s.getCount();
			}
		}
		return n;
	}

	public ItemStack carried() {
		return carried;
	}

	/** Display name (custom name, else item name) of a slot in the open menu, or null if empty. */
	public String slotName(int slot) {
		List<ItemStack> l = menuSlots();
		if (slot < 0 || slot >= l.size() || l.get(slot).isEmpty()) {
			return null;
		}
		ItemStack s = l.get(slot);
		return s.getOrDefault(DataComponents.CUSTOM_NAME, s.getHoverName()).getString();
	}

	public int findSlotByName(String contains) {
		List<ItemStack> l = menuSlots();
		for (int i = 0; i < l.size(); i++) {
			String n = slotName(i);
			if (n != null && n.contains(contains)) {
				return i;
			}
		}
		return -1;
	}

	// ------------------------------------------------------------------------------------------- actions

	public void command(String command) {
		connection.send(new ServerboundChatCommandPacket(command));
	}

	/** Sends a container click as a client would (without client-side prediction of changed slots). */
	public void click(int slot, int button, ContainerInput input) {
		int id = menuId;
		if (DEBUG) {
			System.out.println("[bot " + name + "] -> click container=" + id + " state=" + menuStateId + " slot=" + slot + " button=" + button + " " + input);
		}
		connection.send(new ServerboundContainerClickPacket(id, menuStateId, (short) slot, (byte) button, input, new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
	}

	public void clickIn(int containerId, int slot, int button, ContainerInput input) {
		connection.send(new ServerboundContainerClickPacket(containerId, menuStateId, (short) slot, (byte) button, input, new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
	}

	public void click(int slot) {
		click(slot, 0, ContainerInput.PICKUP);
	}

	/**
	 * Closes the open menu like pressing Escape. NB: the server closes whatever menu is open when it receives this,
	 * regardless of the id (vanilla behaviour), so only call it for a menu the server has not closed itself.
	 */
	public void closeMenu() {
		connection.send(new ServerboundContainerClosePacket(menuId));
		synchronized (this) {
			menuId = 0;
			menuTitle = "";
		}
	}

	public void respawn() {
		connection.send(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
	}

	public void sendRaw(Packet<?> packet) {
		connection.send(packet);
	}

	public int stateId() {
		return menuStateId;
	}

	/** Menu slot index of the player's inventory slot {@code invSlot} (0-8 hotbar, 9-35 main) in the open menu. */
	public int menuSlotOfInventory(int invSlot) {
		int base = menuTopSize();
		return invSlot >= 9 ? base + (invSlot - 9) : base + 27 + invSlot;
	}

	public boolean await(BooleanSupplier condition, long timeoutMillis) {
		long end = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < end) {
			if (condition.getAsBoolean()) {
				return true;
			}
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return condition.getAsBoolean();
	}

	public boolean awaitChat(String fragment, long timeoutMillis) {
		return await(() -> chat.stream().anyMatch(s -> s.contains(fragment)), timeoutMillis);
	}

	public boolean awaitMenu(String titleFragment, long timeoutMillis) {
		return await(() -> menuId != 0 && menuTitle.contains(titleFragment) && !menuSlots().isEmpty(), timeoutMillis);
	}

	@Override
	public void close() {
		if (connection != null && connection.isConnected()) {
			connection.disconnect(net.minecraft.network.chat.Component.literal("bye"));
		}
		connected = false;
	}

	public void drop() {
		// abrupt TCP close without a goodbye, like a crashed client
		try {
			var field = Connection.class.getDeclaredField("channel");
			field.setAccessible(true);
			ChannelFuture f = ((io.netty.channel.Channel) field.get(connection)).close();
			f.syncUninterruptibly();
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new IllegalStateException(e);
		}
		connected = false;
	}
}
