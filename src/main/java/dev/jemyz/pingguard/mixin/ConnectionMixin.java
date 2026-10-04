package dev.jemyz.pingguard.mixin;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.SocketChannel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import dev.jemyz.pingguard.ClientPacketClock;
import dev.jemyz.pingguard.PingGuardConfig;
import dev.jemyz.pingguard.server.HttpSniffer;
import dev.jemyz.pingguard.server.LinkMonitor;

@Mixin(Connection.class)
abstract class ConnectionMixin {
	@Shadow
	@Final
	private PacketFlow receiving;

	@Shadow
	private PacketListener packetListener;

	/** Serve the resource pack over HTTP on the game port. */
	@Inject(method = "channelActive", at = @At("HEAD"))
	private void pingguard$installHttp(ChannelHandlerContext ctx, CallbackInfo ci) {
		if (this.receiving == PacketFlow.SERVERBOUND
				&& ctx.channel() instanceof SocketChannel
				&& PingGuardConfig.get().builtInHttp
				&& ctx.pipeline().get(HttpSniffer.NAME) == null) {
			ctx.pipeline().addFirst(HttpSniffer.NAME, new HttpSniffer());
		}
	}

	/** Client side: remember when the server last sent anything. */
	@Inject(method = "channelRead0", at = @At("HEAD"))
	private void pingguard$markReceived(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
		if (this.receiving == PacketFlow.CLIENTBOUND) {
			ClientPacketClock.mark();
		}
	}

	/** Server side: notice titles sent by commands / other mods so we don't fight over the title slot. */
	@Inject(method = "sendPacket", at = @At("HEAD"))
	private void pingguard$watchTitles(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
		if (this.packetListener instanceof ServerGamePacketListenerImpl game) {
			LinkMonitor.onOutgoing(game.player, packet);
		}
	}
}
