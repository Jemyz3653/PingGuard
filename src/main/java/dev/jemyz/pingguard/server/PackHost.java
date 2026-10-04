package dev.jemyz.pingguard.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;


import net.minecraft.network.Connection;

import net.fabricmc.loader.api.FabricLoader;

import dev.jemyz.pingguard.PingGuard;
import dev.jemyz.pingguard.PingGuardConfig;

/** The resource pack for vanilla clients: loading, hashing and building the download URL. */
public final class PackHost {
	public static final UUID PACK_ID = UUID.nameUUIDFromBytes("pingguard:resourcepack".getBytes());
	public static final String PATH_PREFIX = "/pingguard/";

	private static byte[] bytes;
	private static String sha1 = "";

	public record Offer(String url, String sha1) {
	}

	private PackHost() {
	}

	/** config/pingguard/pack.zip overrides the pack bundled in the jar. */
	public static Path overridePath() {
		return FabricLoader.getInstance().getConfigDir().resolve("pingguard").resolve("pack.zip");
	}

	public static void reload() {
		byte[] data = null;
		Path override = overridePath();

		try {
			if (Files.isRegularFile(override)) {
				data = Files.readAllBytes(override);
				PingGuard.LOGGER.info("Using custom resource pack {}", override);
			} else {
				try (InputStream in = PackHost.class.getResourceAsStream("/pingguard/pack.zip")) {
					if (in != null) data = in.readAllBytes();
				}
			}
		} catch (IOException e) {
			PingGuard.LOGGER.error("Could not load the PingGuard resource pack", e);
		}

		if (data == null) {
			PingGuard.LOGGER.warn("PingGuard resource pack not found; vanilla players get plain-text warnings only");
			bytes = null;
			sha1 = "";
			return;
		}

		bytes = data;
		sha1 = sha1Hex(data);
		PingGuard.LOGGER.info("PingGuard resource pack: {} bytes, sha1 {}", data.length, sha1);
	}

	public static byte[] bytes() {
		return bytes;
	}

	public static String sha1() {
		return sha1;
	}

	public static boolean servesPath(String path) {
		return bytes != null && path.startsWith(PATH_PREFIX) && path.endsWith(".zip");
	}

	public static Optional<Offer> offerFor(Connection connection) {
		PingGuardConfig cfg = PingGuardConfig.get();

		if (!cfg.resourcePackUrl.isEmpty()) {
			String hash = cfg.resourcePackSha1.matches("[0-9a-f]{40}") ? cfg.resourcePackSha1 : "";
			return Optional.of(new Offer(cfg.resourcePackUrl, hash));
		}

		if (bytes == null || !cfg.builtInHttp) {
			return Optional.empty();
		}

		String host = cfg.publicAddress.isEmpty() ? HandshakeHosts.get(connection) : cfg.publicAddress;

		if (host == null || host.isEmpty()) {
			PingGuard.LOGGER.warn("Don't know the server address for {}, set publicAddress in pingguard.json", connection.getRemoteAddress());
			return Optional.empty();
		}

		return Optional.of(new Offer("http://" + host + PATH_PREFIX + sha1 + ".zip", sha1));
	}

	private static String sha1Hex(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
