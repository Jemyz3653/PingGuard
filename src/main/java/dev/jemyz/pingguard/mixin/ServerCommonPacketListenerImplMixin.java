package dev.jemyz.pingguard.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import dev.jemyz.pingguard.server.LinkMonitor;

@Mixin(ServerCommonPacketListenerImpl.class)
abstract class ServerCommonPacketListenerImplMixin {
	@Inject(method = "handlePong", at = @At("HEAD"))
	private void pingguard$onPong(ServerboundPongPacket packet, CallbackInfo ci) {
		if ((Object) this instanceof ServerGamePacketListenerImpl game) {
			LinkMonitor.onPong(game.player, packet.getId());
		}
	}

	@Inject(method = "handleResourcePackResponse", at = @At("HEAD"))
	private void pingguard$onPackResponse(ServerboundResourcePackPacket packet, CallbackInfo ci) {
		if ((Object) this instanceof ServerGamePacketListenerImpl game) {
			LinkMonitor.onPackStatus(game.player, packet);
		}
	}
}
