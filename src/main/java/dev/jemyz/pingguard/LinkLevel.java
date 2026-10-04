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

	public static LinkLevel classify(long pingMs, int poor, int bad, int critical) {
		if (pingMs >= critical) return CRITICAL;
		if (pingMs >= bad) return BAD;
		if (pingMs >= poor) return POOR;
		return GOOD;
	}

	public static LinkLevel byId(int id) {
		LinkLevel[] values = values();
		return values[Math.max(0, Math.min(values.length - 1, id))];
	}

	/** Goes up immediately, goes down only after the connection stayed better for {@code recoverMs}. */
	public static final class Tracker {
		private LinkLevel shown = GOOD;
		private long betterSince = -1;

		public LinkLevel update(LinkLevel raw, long nowMs, int recoverMs) {
			if (raw.ordinal() > shown.ordinal()) {
				shown = raw;
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

		public LinkLevel shown() {
			return shown;
		}

		public void reset() {
			shown = GOOD;
			betterSince = -1;
		}
	}
}
