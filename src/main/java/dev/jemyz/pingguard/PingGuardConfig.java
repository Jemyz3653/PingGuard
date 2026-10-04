package dev.jemyz.pingguard;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/** config/pingguard.json */
public final class PingGuardConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static PingGuardConfig instance = new PingGuardConfig();

	/** Ping at which "POOR CONNECTION" (yellow) appears. */
	public int poorPingMs = 500;
	/** Ping at which the indicator turns red. */
	public int badPingMs = 700;
	/** Ping (or time without any answer from the client) at which the video card is shown. */
	public int criticalPingMs = 1000;
	/** How long a bad ping has to last before a warning appears (filters single slow packets). */
	public int confirmMs = 1500;
	/** How long the connection has to stay better before the warning goes away. */
	public int recoverMs = 2000;
	/**
	 * How often the ping is measured, in ticks of 50 ms (10 = twice a second). Measured on PingGuard's
	 * own thread, so server lag (low TPS) does not slow it down or cause false warnings.
	 */
	public int pingIntervalTicks = 10;
	/** No warnings for this long after a player joins (chunk loading makes the first seconds noisy). */
	public int joinGraceMs = 6000;
	/** No warnings for this long after a player changes dimension or respawns. */
	public int levelChangeGraceMs = 3000;
	/** Show the ping number next to the warning. */
	public boolean showPingNumber = true;

	/**
	 * Vanilla clients with the resource pack: keep an invisible card "armed" so it appears on the
	 * client by itself when the server stops answering (needs the title slot, see README).
	 */
	public boolean deadManSwitch = true;

	/** Offer the PingGuard resource pack to players without the client mod. */
	public boolean sendResourcePack = true;
	/** Kick players who decline the pack (vanilla "required" flag). */
	public boolean requireResourcePack = false;
	/** Prompt shown with the pack offer. */
	public String resourcePackPrompt = "PingGuard: shows a warning when your connection to the server is bad.";
	/**
	 * Leave empty to let PingGuard host the pack itself on the game port
	 * (http://&lt;the address the player connected to&gt;/pingguard/&lt;sha1&gt;.zip).
	 * Or put a direct download URL of PingGuard-ResourcePack.zip here.
	 */
	public String resourcePackUrl = "";
	/** SHA-1 of the file at resourcePackUrl (only used together with resourcePackUrl; may stay empty). */
	public String resourcePackSha1 = "";
	/**
	 * Address used in the built-in pack URL, e.g. "93.184.216.34:25565" or "mc.example.org".
	 * Empty = the address each player typed in their server list (works for most setups).
	 */
	public String publicAddress = "";
	/** Serve the pack over HTTP on the Minecraft port. */
	public boolean builtInHttp = true;

	public int pingIntervalMs() {
		return pingIntervalTicks * 50;
	}

	public static PingGuardConfig get() {
		return instance;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("pingguard.json");
	}

	public static void load() {
		Path path = path();
		PingGuardConfig cfg = null;

		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				cfg = GSON.fromJson(reader, PingGuardConfig.class);
			} catch (Exception e) {
				PingGuard.LOGGER.error("Could not read {}, using defaults", path, e);
			}
		}

		if (cfg == null) {
			cfg = new PingGuardConfig();
		}

		cfg.sanitize();
		instance = cfg;
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(path().getParent());

			try (Writer writer = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			PingGuard.LOGGER.error("Could not write {}", path(), e);
		}
	}

	private void sanitize() {
		poorPingMs = clamp(poorPingMs, 50, 60000);
		badPingMs = clamp(badPingMs, poorPingMs, 60000);
		criticalPingMs = clamp(criticalPingMs, badPingMs, 60000);
		confirmMs = clamp(confirmMs, 0, 60000);
		recoverMs = clamp(recoverMs, 0, 60000);
		levelChangeGraceMs = clamp(levelChangeGraceMs, 0, 60000);
		pingIntervalTicks = clamp(pingIntervalTicks, 2, 100);
		joinGraceMs = clamp(joinGraceMs, 0, 60000);
		if (resourcePackPrompt == null) resourcePackPrompt = "";
		if (resourcePackUrl == null) resourcePackUrl = "";
		if (resourcePackSha1 == null) resourcePackSha1 = "";
		if (publicAddress == null) publicAddress = "";
		resourcePackUrl = resourcePackUrl.trim();
		resourcePackSha1 = resourcePackSha1.trim().toLowerCase(java.util.Locale.ROOT);
		publicAddress = publicAddress.trim();
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}
}
