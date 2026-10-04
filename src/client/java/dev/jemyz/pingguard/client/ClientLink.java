package dev.jemyz.pingguard.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import dev.jemyz.pingguard.ClientPacketClock;
import dev.jemyz.pingguard.LinkLevel;
import dev.jemyz.pingguard.net.HelloPayload;
import dev.jemyz.pingguard.net.RttPayload;

/** Client-side view of the connection (mod installed on the client). */
public final class ClientLink {
	// thresholds (defaults, replaced by the server's HelloPayload)
	private static int poorMs = 500;
	private static int badMs = 700;
	private static int criticalMs = 1000;
	private static int intervalMs = 500;
	private static int recoverMs = 2000;
	private static int confirmMs = 1500;
	private static int levelGraceMs = 3000;
	private static final int JOIN_GRACE_MS = 6000;
	/** Servers without PingGuard: how long "nothing received" is tolerated on top of the ping. */
	private static final int FALLBACK_SILENCE_MS = 2000;

	private static boolean serverHasMod;
	private static long joinedAt;
	private static int lastRttMs;
	private static long lastRttAt;
	private static long effectiveMs;
	private static boolean silent;
	private static final LinkLevel.Tracker TRACKER = new LinkLevel.Tracker();
	private static long criticalSince = -1;
	private static boolean active;
	private static Object lastLevel;
	private static long graceUntil;

	// test hooks (client gametests)
	private static LinkLevel forcedLevel;
	private static long forcedMs;
	private static boolean forcedSilent;
	private static boolean disabled;

	private ClientLink() {
	}

	public static void onJoin() {
		serverHasMod = false;
		joinedAt = System.currentTimeMillis();
		lastRttAt = joinedAt;
		lastRttMs = 0;
		TRACKER.reset();
		criticalSince = -1;
		active = true;
		lastLevel = null;
		graceUntil = 0;
		ClientPacketClock.mark();
	}

	public static void onLeave() {
		active = false;
		serverHasMod = false;
		TRACKER.reset();
		criticalSince = -1;
	}

	public static void onHello(HelloPayload hello) {
		serverHasMod = true;
		poorMs = hello.poorMs();
		badMs = hello.badMs();
		criticalMs = hello.criticalMs();
		intervalMs = Math.max(50, hello.intervalMs());
		recoverMs = hello.recoverMs();
		confirmMs = hello.confirmMs();
		levelGraceMs = hello.levelChangeGraceMs();
		lastRttAt = System.currentTimeMillis();
	}

	public static void onRtt(RttPayload rtt) {
		serverHasMod = true;
		lastRttMs = rtt.rttMs();
		lastRttAt = System.currentTimeMillis();
	}

	/** Gametests: pretend the connection is in this state ({@code null} = back to normal). */
	public static void forceForTest(LinkLevel level, long ms, boolean silentServer) {
		forcedLevel = level;
		forcedMs = ms;
		forcedSilent = silentServer;
		criticalSince = level == LinkLevel.CRITICAL ? System.currentTimeMillis() : -1;
	}

	/** Gametests: behave like a client without the mod. */
	public static void setDisabled(boolean value) {
		disabled = value;
	}

	public static void tick(Minecraft mc) {
		if (forcedLevel != null) return;

		if (!active || mc.player == null || mc.hasSingleplayerServer()) {
			TRACKER.reset();
			criticalSince = -1;
			return;
		}

		long now = System.currentTimeMillis();

		// dimension change / respawn: a new ClientLevel. Loading it stalls everything for a moment.
		if (mc.level != lastLevel) {
			if (lastLevel != null) startGrace(now);
			lastLevel = mc.level;
		}

		if (now < graceUntil) {
			TRACKER.reset();
			criticalSince = -1;
			effectiveMs = 0;
			silent = false;
			lastRttAt = now;
			ClientPacketClock.mark();
			return;
		}

		long base;
		long quiet;

		if (serverHasMod) {
			// The server sends us its measured RTT every intervalMs. If those stop, the server is silent.
			base = lastRttMs;
			quiet = now - lastRttAt - intervalMs;
		} else {
			// Server without PingGuard: tab-list latency + "nothing received for a while".
			base = tabLatency(mc);
			quiet = ClientPacketClock.sinceLastMs() - FALLBACK_SILENCE_MS;
		}

		silent = quiet > base && quiet >= criticalMs;
		effectiveMs = Math.max(base, quiet);

		LinkLevel raw = LinkLevel.classify(effectiveMs, poorMs, badMs, criticalMs);
		if (now - joinedAt < JOIN_GRACE_MS) raw = LinkLevel.GOOD;
		LinkLevel level = TRACKER.update(raw, now, confirmMs, recoverMs);

		if (level == LinkLevel.CRITICAL) {
			if (criticalSince < 0) criticalSince = now;
		} else {
			criticalSince = -1;
		}
	}

	private static void startGrace(long now) {
		graceUntil = now + levelGraceMs;
	}

	private static long tabLatency(Minecraft mc) {
		ClientPacketListener connection = mc.getConnection();
		if (connection == null || mc.player == null) return 0;
		PlayerInfo info = connection.getPlayerInfo(mc.player.getUUID());
		return info == null ? 0 : Math.max(0, info.getLatency());
	}

	public static LinkLevel level() {
		if (disabled) return LinkLevel.GOOD;
		return forcedLevel != null ? forcedLevel : TRACKER.shown();
	}

	public static long effectiveMs() {
		return forcedLevel != null ? forcedMs : effectiveMs;
	}

	/** True when the warning is caused by the server not answering at all. */
	public static boolean silent() {
		return forcedLevel != null ? forcedSilent : silent;
	}

	public static long criticalSince() {
		return criticalSince;
	}
}
