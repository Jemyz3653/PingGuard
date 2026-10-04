package dev.jemyz.pingguard.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import dev.jemyz.pingguard.PingGuard;
import dev.jemyz.pingguard.net.HelloPayload;
import dev.jemyz.pingguard.net.RttPayload;

public final class PingGuardClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		registerReceivers();

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ClientLink.onJoin());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientLink.onLeave());
		ClientTickEvents.END_CLIENT_TICK.register(ClientLink::tick);

		HudElementRegistry.addLast(PingGuard.id("connection"), ConnectionHud::extract);
	}

	private static void registerReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(HelloPayload.TYPE, (payload, context) -> ClientLink.onHello(payload));
		ClientPlayNetworking.registerGlobalReceiver(RttPayload.TYPE, (payload, context) -> ClientLink.onRtt(payload));
	}

	/** Gametests: make this client look like a vanilla one to the server (or back). */
	public static void setVanillaModeForTest(boolean vanilla) {
		if (vanilla) {
			ClientPlayNetworking.unregisterGlobalReceiver(HelloPayload.TYPE.id());
			ClientPlayNetworking.unregisterGlobalReceiver(RttPayload.TYPE.id());
		} else {
			registerReceivers();
		}

		ClientLink.setDisabled(vanilla);
	}
}
