package dev.jemyz.pingguard.server;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.jemyz.pingguard.CardLayout;
import dev.jemyz.pingguard.LinkLevel;
import dev.jemyz.pingguard.PingGuard;
import dev.jemyz.pingguard.PingGuardConfig;
import dev.jemyz.pingguard.mixin.ServerCommonPacketListenerImplAccessor;
import dev.jemyz.pingguard.net.HelloPayload;
import dev.jemyz.pingguard.net.RttPayload;

/**
 * Measures every player's ping and decides what to show them.
 *
 * <p>Everything time-critical (pings, the "armed" card heartbeat, the RTT stream to client mods)
 * runs on PingGuard's own thread, not on the server thread. A lagging server (low TPS, autosave,
 * world generation) therefore neither delays the measurement nor lets the card pop up: only a real
 * network problem - or the whole server process dying - does.
 */
public final class LinkMonitor {
	/** Ping ids used by PingGuard: 0x50470000 | sequence. */
	private static final int PING_ID_MASK = 0xFFFF0000;
	private static final int PING_ID_BASE = 0x50470000;

	private static final long LOOP_MS = 50;
	/** Armed card: restart the client's title timer this often so the card stays invisible. */
	private static final long ARM_REFRESH_MS = 250;
	private static final int ARM_STAY_TICKS = 72000;
	/** Re-send the whole title now and then, in case the client dropped it (respawn, /title clear...). */
	private static final long CARD_RESEND_MS = 15_000;
	private static final long PLAIN_RESEND_MS = 1_000;
	private static final long ACTIONBAR_REFRESH_MS = 1_000;

	private static final Map<UUID, PlayerLink> LINKS = new ConcurrentHashMap<>();
	private static final ThreadLocal<Boolean> OWN_SEND = ThreadLocal.withInitial(() -> false);

	private static ScheduledExecutorService heartbeat;
	private static long lastErrorLogMs;

	private LinkMonitor() {
	}

	public static PlayerLink get(ServerPlayer player) {
		return LINKS.get(player.getUUID());
	}

	// ------------------------------------------------------------------ lifecycle

	public static synchronized void start() {
		stop();
		heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "PingGuard heartbeat");
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY + 1);
			return t;
		});
		heartbeat.scheduleAtFixedRate(LinkMonitor::loop, LOOP_MS, LOOP_MS, TimeUnit.MILLISECONDS);
	}

	public static synchronized void stop() {
		if (heartbeat != null) {
			heartbeat.shutdownNow();
			heartbeat = null;
		}

		LINKS.clear();
	}

	/** Server thread. */
	public static void onJoin(ServerPlayer player) {
		if (isLocal(player.connection)) return;

		PingGuardConfig cfg = PingGuardConfig.get();
		PlayerLink link = new PlayerLink();
		link.handler = player.connection;
		link.joinedAtMs = System.currentTimeMillis();

		if (ServerPlayNetworking.canSend(player, RttPayload.TYPE)) {
			link.client = PlayerLink.Client.MOD;
			send(player.connection, ServerPlayNetworking.createClientboundPacket(new HelloPayload(cfg.poorPingMs, cfg.badPingMs,
					cfg.criticalPingMs, cfg.pingIntervalMs(), cfg.recoverMs, cfg.confirmMs, cfg.levelChangeGraceMs)));
		} else {
			link.client = PlayerLink.Client.VANILLA;
		}

		LINKS.put(player.getUUID(), link);

		if (link.client == PlayerLink.Client.VANILLA) {
			offerPack(player.connection, link);
		}
	}

	public static void onLeave(ServerPlayer player) {
		LINKS.remove(player.getUUID());
	}

	/**
	 * Server thread: the player changed dimension or respawned. Loading the new level makes the
	 * client (and the connection, full of chunk data) slow for a moment - stay quiet for a while.
	 */
	public static void onLevelChange(ServerPlayer player) {
		PlayerLink link = LINKS.get(player.getUUID());
		if (link == null) return;

		int graceMs = PingGuardConfig.get().levelChangeGraceMs;
		if (graceMs <= 0) return;

		synchronized (link) {
			link.graceUntilMs = System.currentTimeMillis() + graceMs;
			link.resetMeasurement();
			hideEverything(link);
		}
	}

	static void offerPack(ServerGamePacketListenerImpl handler, PlayerLink link) {
		PingGuardConfig cfg = PingGuardConfig.get();
		if (!cfg.sendResourcePack) return;

		Optional<PackHost.Offer> offer = PackHost.offerFor(connectionOf(handler));
		if (offer.isEmpty()) return;

		Optional<Component> prompt = cfg.resourcePackPrompt.isEmpty() ? Optional.empty() : Optional.of(Component.literal(cfg.resourcePackPrompt));
		link.pack = PlayerLink.Pack.PENDING;
		send(handler, new ClientboundResourcePackPushPacket(PackHost.PACK_ID, offer.get().url(), offer.get().sha1(), cfg.requireResourcePack, prompt));
	}

	// ------------------------------------------------------------------ network callbacks

	/** Netty thread. */
	public static void onPong(ServerPlayer player, int id) {
		if ((id & PING_ID_MASK) != PING_ID_BASE) return;

		PlayerLink link = LINKS.get(player.getUUID());
		if (link == null) return;

		Long sent = link.pending.remove(id);

		if (sent != null) {
			link.lastRttNanos = System.nanoTime() - sent;
		}
	}

	/** Netty or server thread; may be called twice for the same packet. */
	public static void onPackStatus(ServerPlayer player, ServerboundResourcePackPacket packet) {
		if (!PackHost.PACK_ID.equals(packet.id())) return;

		PlayerLink link = LINKS.get(player.getUUID());
		if (link == null) return;

		switch (packet.action()) {
			case SUCCESSFULLY_LOADED -> link.pack = PlayerLink.Pack.LOADED;
			case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> link.pack = PlayerLink.Pack.DECLINED;
			default -> {
			}
		}
	}

	/** Called for every packet sent to a player; tracks titles sent by commands or other mods. */
	public static void onOutgoing(ServerPlayer player, Packet<?> packet) {
		if (OWN_SEND.get()) return;

		if (!(packet instanceof ClientboundSetTitleTextPacket
				|| packet instanceof ClientboundSetTitlesAnimationPacket
				|| packet instanceof ClientboundClearTitlesPacket
				|| packet instanceof ClientboundSetSubtitleTextPacket)) {
			return;
		}

		PlayerLink link = LINKS.get(player.getUUID());
		if (link == null || link.client == PlayerLink.Client.MOD) return;

		synchronized (link) {
			ServerGamePacketListenerImpl handler = link.handler;

			if (packet instanceof ClientboundSetTitlesAnimationPacket times) {
				link.foreignTimesTicks = Math.max(0, times.getFadeIn()) + Math.max(0, times.getStay()) + Math.max(0, times.getFadeOut());
				link.timesOurs = false;
			} else if (packet instanceof ClientboundSetTitleTextPacket) {
				// Someone else shows a title: give the title slot back with vanilla timings.
				if (link.timesOurs) {
					send(handler, new ClientboundSetTitlesAnimationPacket(10, 70, 20));
					link.timesOurs = false;
					link.foreignTimesTicks = 100;
				}

				if (link.subtitleOurs) {
					send(handler, new ClientboundSetSubtitleTextPacket(Component.empty()));
					link.subtitleOurs = false;
				}

				link.foreignTitleUntilMs = System.currentTimeMillis() + link.foreignTimesTicks * 50L + 250L;
				link.card = PlayerLink.Card.NONE;
			} else if (packet instanceof ClientboundClearTitlesPacket clear) {
				link.foreignTitleUntilMs = 0;
				if (clear.shouldResetTimes()) link.foreignTimesTicks = 100;
				link.timesOurs = false;
				link.subtitleOurs = false;
				link.card = PlayerLink.Card.NONE;
			} else if (packet instanceof ClientboundSetSubtitleTextPacket) {
				link.subtitleOurs = false;
			}
		}
	}

	// ------------------------------------------------------------------ heartbeat thread

	private static void loop() {
		try {
			PingGuardConfig cfg = PingGuardConfig.get();
			long nowMs = System.currentTimeMillis();
			long nowNanos = System.nanoTime();

			for (PlayerLink link : LINKS.values()) {
				ServerGamePacketListenerImpl handler = link.handler;
				if (handler == null) continue;

				Connection connection = connectionOf(handler);

				// not in the play phase (configuration, disconnecting): leave it alone
				if (!connection.isConnected() || connection.getPacketListener() != handler) continue;

				synchronized (link) {
					process(link, handler, cfg, nowMs, nowNanos);
				}
			}
		} catch (Throwable t) {
			long now = System.currentTimeMillis();

			if (now - lastErrorLogMs > 10_000) {
				lastErrorLogMs = now;
				PingGuard.LOGGER.error("PingGuard heartbeat failed", t);
			}
		}
	}

	private static void process(PlayerLink link, ServerGamePacketListenerImpl handler, PingGuardConfig cfg, long nowMs, long nowNanos) {
		boolean frozen = nowMs < link.freezeUntilMs;
		boolean grace = link.inGrace(nowMs) || nowMs - link.joinedAtMs < cfg.joinGraceMs;
		int interval = cfg.pingIntervalMs();

		if (link.inGrace(nowMs)) {
			// whatever we measure while the new dimension loads is noise
			link.pending.clear();
			link.lastRttNanos = -1;
		} else if (!frozen && nowMs - link.lastPingMs >= interval) {
			int id = PING_ID_BASE | (link.nextPingId++ & 0xFFFF);
			link.pending.put(id, System.nanoTime());
			link.lastPingMs = nowMs;
			send(handler, new ClientboundPingPacket(id));
		}

		link.expireOldPings(nowNanos);
		long eff = link.measure(nowNanos) + link.simulateExtraMs;
		link.effectiveMs = eff;

		LinkLevel raw = grace ? LinkLevel.GOOD : LinkLevel.classify(eff, cfg.poorPingMs, cfg.badPingMs, cfg.criticalPingMs);
		LinkLevel level = link.tracker.update(raw, nowMs, cfg.confirmMs, cfg.recoverMs);

		if (frozen) return;   // /pingguard freeze: pretend the server is dead, send nothing at all

		if (link.client == PlayerLink.Client.MOD) {
			if (nowMs - link.lastRttSendMs >= interval) {
				link.lastRttSendMs = nowMs;
				long shown = grace ? 0 : eff;
				send(handler, ServerPlayNetworking.createClientboundPacket(new RttPayload((int) Math.min(Integer.MAX_VALUE, shown))));
			}
		} else if (link.client == PlayerLink.Client.VANILLA) {
			if (link.inGrace(nowMs)) return;   // card and action bar stay off; re-armed after the grace period
			deliverVanilla(handler, link, level, eff, nowMs, cfg);
		}
	}

	private static void deliverVanilla(ServerGamePacketListenerImpl handler, PlayerLink link, LinkLevel level, long eff, long nowMs, PingGuardConfig cfg) {
		boolean pack = link.pack == PlayerLink.Pack.LOADED;

		// --- action bar ---
		if (level != LinkLevel.GOOD) {
			if (level != link.lastActionLevel || nowMs - link.lastActionMs >= ACTIONBAR_REFRESH_MS) {
				Component line = pack
						? VanillaCard.actionBar(level, eff, cfg.showPingNumber, link.noAnswer)
						: VanillaCard.plainActionBar(level, eff, cfg.showPingNumber, link.noAnswer);
				send(handler, new ClientboundSetActionBarTextPacket(line));
				link.lastActionMs = nowMs;
			}
		} else if (link.lastActionLevel != LinkLevel.GOOD) {
			send(handler, new ClientboundSetActionBarTextPacket(Component.empty()));
		}

		link.lastActionLevel = level;

		// --- title: video card ---
		if (nowMs < link.foreignTitleUntilMs && level != LinkLevel.CRITICAL) {
			link.card = PlayerLink.Card.NONE;   // somebody else's title is on screen; re-arm afterwards
			return;
		}

		PlayerLink.Card want;

		if (pack) {
			want = level == LinkLevel.CRITICAL ? PlayerLink.Card.FORCED : (cfg.deadManSwitch ? PlayerLink.Card.ARMED : PlayerLink.Card.NONE);
		} else {
			want = level == LinkLevel.CRITICAL ? PlayerLink.Card.PLAIN : PlayerLink.Card.NONE;
		}

		boolean changed = want != link.card;

		switch (want) {
			case ARMED -> {
				if (changed || nowMs - link.cardFullMs >= CARD_RESEND_MS) {
					sendTimes(handler, link, CardLayout.CLOCK_TICKS, ARM_STAY_TICKS, 0);
					clearSubtitle(handler, link, changed);
					send(handler, new ClientboundSetTitleTextPacket(VanillaCard.armed()));
					link.cardFullMs = nowMs;
					link.cardRefreshMs = nowMs;
				} else if (nowMs - link.cardRefreshMs >= ARM_REFRESH_MS) {
					// restarts the client's title timer -> alpha back to ~0 -> card stays hidden
					sendTimes(handler, link, CardLayout.CLOCK_TICKS, ARM_STAY_TICKS, 0);
					link.cardRefreshMs = nowMs;
				}
			}
			case FORCED -> {
				if (changed || nowMs - link.cardFullMs >= CARD_RESEND_MS) {
					sendTimes(handler, link, 0, 0, CardLayout.CLOCK_TICKS);
					clearSubtitle(handler, link, changed);
					send(handler, new ClientboundSetTitleTextPacket(VanillaCard.forced()));
					link.cardFullMs = nowMs;
				}
			}
			case PLAIN -> {
				if (changed || nowMs - link.cardFullMs >= PLAIN_RESEND_MS) {
					sendTimes(handler, link, changed ? 5 : 0, 40, 10);
					send(handler, new ClientboundSetSubtitleTextPacket(VanillaCard.plainSubtitle()));
					link.subtitleOurs = true;
					send(handler, new ClientboundSetTitleTextPacket(VanillaCard.plainTitle()));
					link.cardFullMs = nowMs;
				}
			}
			case NONE -> {
				if (changed) clearOurTitle(handler, link);
			}
		}

		link.card = want;
	}

	/** Remove our card and action bar right away (start of a grace period). Caller holds the lock. */
	private static void hideEverything(PlayerLink link) {
		ServerGamePacketListenerImpl handler = link.handler;
		if (handler == null || link.client != PlayerLink.Client.VANILLA) return;

		if (link.card != PlayerLink.Card.NONE) {
			clearOurTitle(handler, link);
			link.card = PlayerLink.Card.NONE;
		}

		if (link.lastActionLevel != LinkLevel.GOOD) {
			send(handler, new ClientboundSetActionBarTextPacket(Component.empty()));
			link.lastActionLevel = LinkLevel.GOOD;
		}
	}

	private static void clearOurTitle(ServerGamePacketListenerImpl handler, PlayerLink link) {
		send(handler, new ClientboundClearTitlesPacket(true));
		link.timesOurs = false;
		link.subtitleOurs = false;
	}

	/** A subtitle left over from someone else's title would be drawn across the card. */
	private static void clearSubtitle(ServerGamePacketListenerImpl handler, PlayerLink link, boolean changed) {
		if (changed) {
			send(handler, new ClientboundSetSubtitleTextPacket(Component.empty()));
			link.subtitleOurs = false;
		}
	}

	private static void sendTimes(ServerGamePacketListenerImpl handler, PlayerLink link, int fadeIn, int stay, int fadeOut) {
		send(handler, new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
		link.timesOurs = true;
	}

	/** Thread-safe: Connection.send hands the packet to the channel's event loop. */
	static void send(ServerGamePacketListenerImpl handler, Packet<?> packet) {
		if (handler == null) return;
		boolean prev = OWN_SEND.get();
		OWN_SEND.set(true);

		try {
			handler.send(packet);
		} finally {
			OWN_SEND.set(prev);
		}
	}

	private static boolean isLocal(ServerGamePacketListenerImpl handler) {
		return connectionOf(handler).isMemoryConnection();
	}

	public static Connection connectionOf(ServerCommonPacketListenerImpl handler) {
		return ((ServerCommonPacketListenerImplAccessor) handler).pingguard$connection();
	}

	// ------------------------------------------------------------------ commands

	public static boolean simulate(ServerPlayer player, int extraMs) {
		PlayerLink link = get(player);
		if (link == null) return false;
		link.simulateExtraMs = Math.max(0, extraMs);
		return true;
	}

	public static boolean freeze(ServerPlayer player, int seconds) {
		PlayerLink link = get(player);
		if (link == null) return false;
		link.freezeUntilMs = System.currentTimeMillis() + seconds * 1000L;
		return true;
	}

	public static void reofferPack(ServerPlayer player) {
		PlayerLink link = get(player);
		if (link != null && link.client == PlayerLink.Client.VANILLA) offerPack(player.connection, link);
	}
}
