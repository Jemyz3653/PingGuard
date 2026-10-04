package dev.jemyz.pingguard;

/** Time of the last packet the client received from the server (updated from a Connection mixin). */
public final class ClientPacketClock {
	private static volatile long lastNanos = System.nanoTime();

	private ClientPacketClock() {
	}

	public static void mark() {
		lastNanos = System.nanoTime();
	}

	public static long sinceLastMs() {
		return (System.nanoTime() - lastNanos) / 1_000_000L;
	}
}
