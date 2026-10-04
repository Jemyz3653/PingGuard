package dev.jemyz.pingguard.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.jemyz.pingguard.LinkLevel;

/** Per-player connection state on the server. */
public final class PlayerLink {
	public enum Client { UNKNOWN, MOD, VANILLA }

	public enum Pack { NONE, PENDING, LOADED, DECLINED }

	public enum Card { NONE, ARMED, FORCED, PLAIN }

	// --- ping (pong answers arrive on netty threads) ---
	final Map<Integer, Long> pending = new ConcurrentHashMap<>();
	volatile long lastRttNanos = -1;
	int nextPingId;
	int lastPingTick = Integer.MIN_VALUE / 2;

	// --- classification ---
	final LinkLevel.Tracker tracker = new LinkLevel.Tracker();
	long joinedAtMs;
	long effectiveMs;
	boolean noAnswer;

	// --- client ---
	Client client = Client.UNKNOWN;
	volatile Pack pack = Pack.NONE;
	int lastRttSendTick = Integer.MIN_VALUE / 2;

	// --- vanilla delivery ---
	Card card = Card.NONE;
	int cardFullTick;
	int cardRefreshTick;
	LinkLevel lastActionLevel = LinkLevel.GOOD;
	int lastActionTick;

	// --- other title senders (commands, other mods) ---
	volatile long foreignTitleUntilMs;
	volatile int foreignTimesTicks = 100;
	volatile boolean timesOurs;
	volatile boolean subtitleOurs;

	// --- /pingguard simulate ---
	int simulateExtraMs;
	long freezeUntilMs;

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

	long lastRttMs() {
		long n = lastRttNanos;
		return n < 0 ? -1 : n / 1_000_000L;
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

	void expireOldPings(long nowNanos) {
		pending.values().removeIf(sent -> nowNanos - sent > 40_000_000_000L);
	}
}
