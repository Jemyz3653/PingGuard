package dev.jemyz.pingguard.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;

import dev.jemyz.pingguard.server.HandshakeHosts;

@Mixin(ServerHandshakePacketListenerImpl.class)
abstract class ServerHandshakePacketListenerImplMixin {
	@Shadow
	@Final
	private Connection connection;

	@Inject(method = "handleIntention", at = @At("HEAD"))
	private void pingguard$rememberHost(ClientIntentionPacket packet, CallbackInfo ci) {
		HandshakeHosts.remember(this.connection, packet.hostName(), packet.port());
	}
}
