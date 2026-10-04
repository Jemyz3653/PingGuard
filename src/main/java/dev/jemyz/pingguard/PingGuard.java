package dev.jemyz.pingguard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> LinkMonitor.onLevelChange(player));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> LinkMonitor.onLevelChange(newPlayer));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> LinkMonitor.start());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> LinkMonitor.stop());

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> PingGuardCommand.register(dispatcher));
	}
}
