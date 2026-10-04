package dev.jemyz.pingguard;

/** How bad the connection is. Shared by the server logic and the client HUD. */
public enum LinkLevel {
	GOOD,
	/** poorPingMs..badPingMs: yellow "POOR CONNECTION". */
	POOR,
	/** badPingMs..criticalPingMs: red "POOR CONNECTION". */
	BAD,
	/** >= criticalPingMs or no answer: the video card. */
	CRITICAL;

	private static final LinkLevel[] VALUES = values();

	public static LinkLevel classify(long pingMs, int poor, int bad, int critical) {
		if (pingMs >= critical) return CRITICAL;
		if (pingMs >= bad) return BAD;
		if (pingMs >= poor) return POOR;
		return GOOD;
	}

	public static LinkLevel byId(int id) {
		return VALUES[Math.max(0, Math.min(VALUES.length - 1, id))];
	}

	/**
	 * Debounced level.
	 * <ul>
	 * <li>goes up only after the measurement stayed at (or above) the new level for {@code confirmMs},
	 *     so a single slow packet never flashes a warning;</li>
	 * <li>goes down only after it stayed better for {@code recoverMs}, so the warning doesn't blink.</li>
	 * </ul>
	 */
	public static final class Tracker {
		private LinkLevel shown = GOOD;
		private final long[] atLeastSince = {-1, -1, -1, -1};
		private long betterSince = -1;

		public synchronized LinkLevel update(LinkLevel raw, long nowMs, int confirmMs, int recoverMs) {
			for (LinkLevel l : VALUES) {
				if (raw.ordinal() >= l.ordinal()) {
					if (atLeastSince[l.ordinal()] < 0) atLeastSince[l.ordinal()] = nowMs;
				} else {
					atLeastSince[l.ordinal()] = -1;
				}
			}

			// highest level that has been reached for long enough
			LinkLevel confirmed = GOOD;

			for (LinkLevel l : VALUES) {
				long since = atLeastSince[l.ordinal()];
				if (since >= 0 && nowMs - since >= confirmMs) confirmed = l;
			}

			if (confirmed.ordinal() > shown.ordinal()) {
				shown = confirmed;
				betterSince = -1;
			} else if (raw.ordinal() < shown.ordinal()) {
				if (betterSince < 0) {
					betterSince = nowMs;
				} else if (nowMs - betterSince >= recoverMs) {
					shown = raw;
					betterSince = -1;
				}
			} else {
				betterSince = -1;
			}

			return shown;
		}

		public synchronized LinkLevel shown() {
			return shown;
		}

		public synchronized void reset() {
			shown = GOOD;
			betterSince = -1;
			java.util.Arrays.fill(atLeastSince, -1);
		}
	}
}
