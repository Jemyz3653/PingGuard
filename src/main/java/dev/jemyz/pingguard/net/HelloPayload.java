package dev.jemyz.pingguard.net;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import dev.jemyz.pingguard.PingGuard;

/** Server -> client mod: "this server runs PingGuard", plus its thresholds. */
public record HelloPayload(int poorMs, int badMs, int criticalMs, int intervalMs, int recoverMs) implements CustomPacketPayload {
	public static final Type<HelloPayload> TYPE = new Type<>(PingGuard.id("hello"));
	public static final StreamCodec<ByteBuf, HelloPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, HelloPayload::poorMs,
			ByteBufCodecs.VAR_INT, HelloPayload::badMs,
			ByteBufCodecs.VAR_INT, HelloPayload::criticalMs,
			ByteBufCodecs.VAR_INT, HelloPayload::intervalMs,
			ByteBufCodecs.VAR_INT, HelloPayload::recoverMs,
			HelloPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
