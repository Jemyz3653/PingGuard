package dev.jemyz.pingguard.server;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.jemyz.pingguard.CardLayout;
import dev.jemyz.pingguard.LinkLevel;
import dev.jemyz.pingguard.PingGuard;
import dev.jemyz.pingguard.PingGuardConfig;
import dev.jemyz.pingguard.net.HelloPayload;
import dev.jemyz.pingguard.net.RttPayload;

/** Measures every player's ping and decides what to show them. */
public final class LinkMonitor {
	/** Ping ids used by PingGuard: 0x50470000 | sequence. */
	private static final int PING_ID_MASK = 0xFFFF0000;
	private static final int PING_ID_BASE = 0x50470000;

	/** Armed card: fade-in clock, restarted every ARM_REFRESH_TICKS so it never becomes visible. */
	private static final int ARM_REFRESH_TICKS = 10;
	private static final int ARM_STAY_TICKS = 72000;
	/** Re-send the whole title now and then, in case the client dropped it (respawn, /title clear...). */
	private static final int CARD_RESEND_TICKS = 300;
	private static final int ACTIONBAR_REFRESH_TICKS = 20;

	private static final Map<UUID, PlayerLink> LINKS = new ConcurrentHashMap<>();
	private static final ThreadLocal<Boolean> OWN_SEND = ThreadLocal.withInitial(() -> false);

	private LinkMonitor() {
	}

	public static PlayerLink get(ServerPlayer player) {
		return LINKS.get(player.getUUID());
	}

	private static PlayerLink link(ServerPlayer player) {
		return LINKS.computeIfAbsent(player.getUUID(), u -> {
			PlayerLink l = new PlayerLink();
			l.joinedAtMs = System.currentTimeMillis();
			return l;
		});
	}

	public static void clear() {
		LINKS.clear();
	}

	// ------------------------------------------------------------------ lifecycle

	public static void onJoin(ServerPlayer player) {
		if (isLocal(player)) return;

		PlayerLink link = link(player);
		link.joinedAtMs = System.currentTimeMillis();
		PingGuardConfig cfg = PingGuardConfig.get();

		if (ServerPlayNetworking.canSend(player, RttPayload.TYPE)) {
			link.client = PlayerLink.Client.MOD;
			ServerPlayNetworking.send(player, new HelloPayload(cfg.poorPingMs, cfg.badPingMs, cfg.criticalPingMs,
					cfg.pingIntervalTicks * 50, cfg.recoverMs));
			return;
		}

		link.client = PlayerLink.Client.VANILLA;
		offerPack(player, link);
	}

	public static void onLeave(ServerPlayer player) {
		LINKS.remove(player.getUUID());
	}

	static void offerPack(ServerPlayer player, PlayerLink link) {
		PingGuardConfig cfg = PingGuardConfig.get();
		if (!cfg.sendResourcePack) return;

		Optional<PackHost.Offer> offer = PackHost.offerFor(player.connection.getConnection());

		if (offer.isEmpty()) {
			return;
		}

		Optional<Component> prompt = cfg.resourcePackPrompt.isEmpty() ? Optional.empty() : Optional.of(Component.literal(cfg.resourcePackPrompt));
		link.pack = PlayerLink.Pack.PENDING;
		send(player, new ClientboundResourcePackPushPacket(PackHost.PACK_ID, offer.get().url(), offer.get().sha1(), cfg.requireResourcePack, prompt));
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

		if (packet instanceof ClientboundSetTitlesAnimationPacket times) {
			link.foreignTimesTicks = Math.max(0, times.getFadeIn()) + Math.max(0, times.getStay()) + Math.max(0, times.getFadeOut());
			link.timesOurs = false;
		} else if (packet instanceof ClientboundSetTitleTextPacket) {
			// Someone else shows a title: give the title slot back with vanilla timings.
			if (link.timesOurs) {
				send(player, new ClientboundSetTitlesAnimationPacket(10, 70, 20));
				link.timesOurs = false;
				link.foreignTimesTicks = 100;
			}

			if (link.subtitleOurs) {
				send(player, new ClientboundSetSubtitleTextPacket(Component.empty()));
				link.subtitleOurs = false;
			}

			link.foreignTitleUntilMs = System.currentTimeMillis() + link.foreignTimesTicks * 50L + 250L;
		} else if (packet instanceof ClientboundClearTitlesPacket clear) {
			link.foreignTitleUntilMs = 0;
			if (clear.shouldResetTimes()) link.foreignTimesTicks = 100;
			link.timesOurs = false;
			link.subtitleOurs = false;
		} else if (packet instanceof ClientboundSetSubtitleTextPacket) {
			link.subtitleOurs = false;
		}
	}

	// ------------------------------------------------------------------ tick

	public static void tick(MinecraftServer server) {
		PingGuardConfig cfg = PingGuardConfig.get();
		int tick = server.getTickCount();
		long nowNanos = System.nanoTime();
		long nowMs = System.currentTimeMillis();

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (isLocal(player)) continue;

			PlayerLink link = link(player);
			boolean frozen = nowMs < link.freezeUntilMs;

			if (!frozen && tick - link.lastPingTick >= cfg.pingIntervalTicks) {
				int id = PING_ID_BASE | (link.nextPingId++ & 0xFFFF);
				link.pending.put(id, System.nanoTime());
				link.lastPingTick = tick;
				send(player, new ClientboundPingPacket(id));
			}

			link.expireOldPings(nowNanos);
			long eff = link.measure(nowNanos) + link.simulateExtraMs;
			link.effectiveMs = eff;

			LinkLevel raw = LinkLevel.classify(eff, cfg.poorPingMs, cfg.badPingMs, cfg.criticalPingMs);
			if (nowMs - link.joinedAtMs < cfg.joinGraceMs) raw = LinkLevel.GOOD;
			LinkLevel level = link.tracker.update(raw, nowMs, cfg.recoverMs);

			if (frozen) continue;   // simulate a dead server: send nothing at all

			if (link.client == PlayerLink.Client.MOD) {
				if (tick - link.lastRttSendTick >= cfg.pingIntervalTicks) {
					link.lastRttSendTick = tick;
					ServerPlayNetworking.send(player, new RttPayload((int) Math.min(Integer.MAX_VALUE, eff)));
				}
			} else if (link.client == PlayerLink.Client.VANILLA) {
				deliverVanilla(player, link, level, eff, tick, nowMs, cfg);
			}
		}
	}

	private static void deliverVanilla(ServerPlayer player, PlayerLink link, LinkLevel level, long eff, int tick, long nowMs, PingGuardConfig cfg) {
		boolean pack = link.pack == PlayerLink.Pack.LOADED;

		// --- action bar ---
		if (level != LinkLevel.GOOD) {
			if (level != link.lastActionLevel || tick - link.lastActionTick >= ACTIONBAR_REFRESH_TICKS) {
				Component line = pack
						? VanillaCard.actionBar(level, eff, cfg.showPingNumber, link.noAnswer)
						: VanillaCard.plainActionBar(level, eff, cfg.showPingNumber, link.noAnswer);
				send(player, new ClientboundSetActionBarTextPacket(line));
				link.lastActionTick = tick;
			}
		} else if (link.lastActionLevel != LinkLevel.GOOD) {
			send(player, new ClientboundSetActionBarTextPacket(Component.empty()));
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
				if (changed || tick - link.cardFullTick >= CARD_RESEND_TICKS) {
					sendTimes(player, link, CardLayout.CLOCK_TICKS, ARM_STAY_TICKS, 0);
					clearSubtitle(player, link, changed);
					send(player, new ClientboundSetTitleTextPacket(VanillaCard.armed()));
					link.cardFullTick = tick;
					link.cardRefreshTick = tick;
				} else if (tick - link.cardRefreshTick >= ARM_REFRESH_TICKS) {
					// restarts the client's title timer -> alpha back to 0 -> card stays hidden
					sendTimes(player, link, CardLayout.CLOCK_TICKS, ARM_STAY_TICKS, 0);
					link.cardRefreshTick = tick;
				}
			}
			case FORCED -> {
				if (changed || tick - link.cardFullTick >= CARD_RESEND_TICKS) {
					sendTimes(player, link, 0, 0, CardLayout.CLOCK_TICKS);
					clearSubtitle(player, link, changed);
					send(player, new ClientboundSetTitleTextPacket(VanillaCard.forced()));
					link.cardFullTick = tick;
				}
			}
			case PLAIN -> {
				if (changed || tick - link.cardFullTick >= ACTIONBAR_REFRESH_TICKS) {
					sendTimes(player, link, changed ? 5 : 0, 40, 10);
					send(player, new ClientboundSetSubtitleTextPacket(VanillaCard.plainSubtitle()));
					link.subtitleOurs = true;
					send(player, new ClientboundSetTitleTextPacket(VanillaCard.plainTitle()));
					link.cardFullTick = tick;
				}
			}
			case NONE -> {
				if (changed) {
					send(player, new ClientboundClearTitlesPacket(true));
					link.timesOurs = false;
					link.subtitleOurs = false;
				}
			}
		}

		link.card = want;
	}

	/** A subtitle left over from someone else's title would be drawn across the card. */
	private static void clearSubtitle(ServerPlayer player, PlayerLink link, boolean changed) {
		if (changed) {
			send(player, new ClientboundSetSubtitleTextPacket(Component.empty()));
			link.subtitleOurs = false;
		}
	}

	private static void sendTimes(ServerPlayer player, PlayerLink link, int fadeIn, int stay, int fadeOut) {
		send(player, new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
		link.timesOurs = true;
	}

	static void send(ServerPlayer player, Packet<?> packet) {
		boolean prev = OWN_SEND.get();
		OWN_SEND.set(true);

		try {
			player.connection.send(packet);
		} finally {
			OWN_SEND.set(prev);
		}
	}

	private static boolean isLocal(ServerPlayer player) {
		Connection connection = player.connection.getConnection();
		return connection.isMemoryConnection();
	}

	// ------------------------------------------------------------------ commands

	public static void simulate(ServerPlayer player, int extraMs) {
		link(player).simulateExtraMs = Math.max(0, extraMs);
	}

	public static void freeze(ServerPlayer player, int seconds) {
		link(player).freezeUntilMs = System.currentTimeMillis() + seconds * 1000L;
	}

	public static void reofferPack(ServerPlayer player) {
		PlayerLink link = link(player);
		if (link.client == PlayerLink.Client.VANILLA) offerPack(player, link);
	}

	public static void logStatus() {
		PingGuard.LOGGER.info("PingGuard tracking {} players", LINKS.size());
	}
}
