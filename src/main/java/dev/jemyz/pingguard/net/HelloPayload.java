package dev.jemyz.pingguard.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import dev.jemyz.pingguard.PingGuard;

/** Server -> client mod: "this server runs PingGuard", plus its settings. */
public record HelloPayload(int poorMs, int badMs, int criticalMs, int intervalMs, int recoverMs, int confirmMs,
		int levelChangeGraceMs) implements CustomPacketPayload {
	public static final Type<HelloPayload> TYPE = new Type<>(PingGuard.id("hello"));
	public static final StreamCodec<FriendlyByteBuf, HelloPayload> CODEC = CustomPacketPayload.codec(HelloPayload::write, HelloPayload::read);

	private static HelloPayload read(FriendlyByteBuf buf) {
		return new HelloPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
				buf.readVarInt(), buf.readVarInt());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(poorMs);
		buf.writeVarInt(badMs);
		buf.writeVarInt(criticalMs);
		buf.writeVarInt(intervalMs);
		buf.writeVarInt(recoverMs);
		buf.writeVarInt(confirmMs);
		buf.writeVarInt(levelChangeGraceMs);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
