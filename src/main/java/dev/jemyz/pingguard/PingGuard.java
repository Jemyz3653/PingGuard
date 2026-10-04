package dev.jemyz.pingguard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import dev.jemyz.pingguard.net.HelloPayload;
import dev.jemyz.pingguard.net.RttPayload;
import dev.jemyz.pingguard.server.LinkMonitor;
import dev.jemyz.pingguard.server.PackHost;
import dev.jemyz.pingguard.server.PingGuardCommand;

public final class PingGuard implements ModInitializer {
	public static final String MOD_ID = "pingguard";
	public static final Logger LOGGER = LoggerFactory.getLogger("PingGuard");

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		PingGuardConfig.load();
		PackHost.reload();

		PayloadTypeRegistry.clientboundPlay().register(HelloPayload.TYPE, HelloPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RttPayload.TYPE, RttPayload.CODEC);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> LinkMonitor.onJoin(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LinkMonitor.onLeave(handler.player));
		ServerTickEvents.END_SERVER_TICK.register(LinkMonitor::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> LinkMonitor.clear());

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> PingGuardCommand.register(dispatcher));
	}
}
