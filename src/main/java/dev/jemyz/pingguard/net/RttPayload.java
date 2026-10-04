package dev.jemyz.pingguard.net;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import dev.jemyz.pingguard.PingGuard;

/** Server -> client mod: round-trip time measured by the server, sent every ping interval. */
public record RttPayload(int rttMs) implements CustomPacketPayload {
	public static final Type<RttPayload> TYPE = new Type<>(PingGuard.id("rtt"));
	public static final StreamCodec<ByteBuf, RttPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, RttPayload::rttMs,
			RttPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
