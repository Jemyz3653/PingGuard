package dev.jemyz.pingguard.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.network.ServerGamePacketListenerImpl;

import dev.jemyz.pingguard.LinkLevel;

/**
 * Per-player connection state on the server.
 *
 * <p>Owned by the PingGuard heartbeat thread; fields touched from other threads (netty, server
 * thread, commands) are volatile. Card / title state changes happen under {@code synchronized (link)}.
 */
public final class PlayerLink {
	public enum Client { UNKNOWN, MOD, VANILLA }

	public enum Pack { NONE, PENDING, LOADED, DECLINED }

	public enum Card { NONE, ARMED, FORCED, PLAIN }

	/** Stable for the whole connection (survives respawns and dimension changes). */
	volatile ServerGamePacketListenerImpl handler;

	// --- ping (pong answers arrive on netty threads) ---
	final Map<Integer, Long> pending = new ConcurrentHashMap<>();
	volatile long lastRttNanos = -1;
	int nextPingId;
	long lastPingMs;

	// --- classification ---
	final LinkLevel.Tracker tracker = new LinkLevel.Tracker();
	volatile long joinedAtMs;
	volatile long graceUntilMs;
	volatile long effectiveMs;
	volatile boolean noAnswer;

	// --- client ---
	volatile Client client = Client.UNKNOWN;
	volatile Pack pack = Pack.NONE;
	long lastRttSendMs;

	// --- vanilla delivery ---
	Card card = Card.NONE;
	long cardFullMs;
	long cardRefreshMs;
	LinkLevel lastActionLevel = LinkLevel.GOOD;
	long lastActionMs;

	// --- other title senders (commands, other mods) ---
	volatile long foreignTitleUntilMs;
	volatile int foreignTimesTicks = 100;
	volatile boolean timesOurs;
	volatile boolean subtitleOurs;

	// --- /pingguard simulate ---
	volatile int simulateExtraMs;
	volatile long freezeUntilMs;

	public LinkLevel level() {
		return tracker.shown();
	}

	public long effectiveMs() {
		return effectiveMs;
	}

	public Client client() {
		return client;
	}

	public Pack pack() {
		return pack;
	}

	boolean inGrace(long nowMs) {
		return nowMs < graceUntilMs;
	}

	/** max(last RTT, age of the oldest unanswered ping) in ms. */
	long measure(long nowNanos) {
		long waiting = 0;

		for (Long sent : pending.values()) {
			waiting = Math.max(waiting, nowNanos - sent);
		}

		long rtt = Math.max(0, lastRttNanos);
		noAnswer = waiting > rtt && waiting / 1_000_000L >= 1000;
		return Math.max(rtt, waiting) / 1_000_000L;
	}

	/** Forget everything measured so far (after a dimension change the old numbers mean nothing). */
	void resetMeasurement() {
		pending.clear();
		lastRttNanos = -1;
		noAnswer = false;
		effectiveMs = 0;
		tracker.reset();
	}

	void expireOldPings(long nowNanos) {
		pending.values().removeIf(sent -> nowNanos - sent > 40_000_000_000L);
	}
}
