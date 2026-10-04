package dev.jemyz.pingguard.server;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;


import net.minecraft.network.Connection;

/** Remembers which address each client typed (from the handshake), to build the pack URL. */
public final class HandshakeHosts {
	private static final Map<Connection, String> HOSTS = Collections.synchronizedMap(new WeakHashMap<>());

	private HandshakeHosts() {
	}

	public static void remember(Connection connection, String hostName, int port) {
		String host = clean(hostName);
		if (host.isEmpty()) return;

		if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
			host = "[" + host + "]";   // IPv6 literal
		}

		HOSTS.put(connection, port == 80 ? host : host + ":" + port);
	}

	public static String get(Connection connection) {
		return HOSTS.get(connection);
	}

	private static String clean(String raw) {
		if (raw == null) return "";
		String host = raw;
		int nul = host.indexOf('\0');   // BungeeCord / Forge markers
		if (nul >= 0) host = host.substring(0, nul);
		host = host.trim();
		while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
		// only allow characters that are valid in a host name / IP literal
		if (!host.matches("[A-Za-z0-9.\\-:\\[\\]%_]+")) return "";
		return host;
	}
}
