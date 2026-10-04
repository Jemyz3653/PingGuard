package dev.jemyz.pingguard.server;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import dev.jemyz.pingguard.PingGuard;

/**
 * First handler of every incoming TCP connection on the game port. If the first bytes look like
 * an HTTP request for the PingGuard pack, answer it and close; otherwise step aside and let
 * Minecraft handle the connection as usual (a Minecraft handshake never starts with "GET "/"HEAD").
 */
public final class HttpSniffer extends ChannelInboundHandlerAdapter {
	public static final String NAME = "pingguard_http";
	private static final int MAX_HEADER = 8192;

	private ByteBuf buffer;
	private boolean http;

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (!(msg instanceof ByteBuf in)) {
			passThrough(ctx, msg);
			return;
		}

		if (buffer == null) buffer = ctx.alloc().buffer();
		buffer.writeBytes(in);
		in.release();

		if (!http) {
			int verdict = looksLikeHttp(buffer);

			if (verdict < 0) {
				ByteBuf data = buffer;
				buffer = null;
				passThrough(ctx, data);
				return;
			}

			if (verdict == 0) return;   // need more bytes
			http = true;
		}

		int end = indexOf(buffer, "\r\n\r\n");

		if (end < 0) {
			if (buffer.readableBytes() > MAX_HEADER) ctx.close();
			return;
		}

		String head = buffer.toString(buffer.readerIndex(), end, StandardCharsets.ISO_8859_1);
		buffer.release();
		buffer = null;
		respond(ctx, head);
	}

	/** 1 = HTTP, -1 = not HTTP, 0 = undecided. */
	private static int looksLikeHttp(ByteBuf buf) {
		String[] methods = {"GET ", "HEAD "};
		int n = buf.readableBytes();
		boolean couldBe = false;

		for (String m : methods) {
			int len = Math.min(n, m.length());
			boolean match = true;

			for (int i = 0; i < len; i++) {
				if (buf.getByte(buf.readerIndex() + i) != m.charAt(i)) {
					match = false;
					break;
				}
			}

			if (match && n >= m.length()) return 1;
			if (match) couldBe = true;
		}

		return couldBe ? 0 : -1;
	}

	private static int indexOf(ByteBuf buf, String needle) {
		byte[] b = needle.getBytes(StandardCharsets.ISO_8859_1);
		int start = buf.readerIndex();
		int end = buf.writerIndex() - b.length;

		outer:
		for (int i = start; i <= end; i++) {
			for (int j = 0; j < b.length; j++) {
				if (buf.getByte(i + j) != b[j]) continue outer;
			}

			return i - start;
		}

		return -1;
	}

	private void respond(ChannelHandlerContext ctx, String head) {
		String requestLine = head.split("\r\n", 2)[0];
		String[] parts = requestLine.split(" ");
		boolean headOnly = parts[0].equals("HEAD");
		String path = parts.length > 1 ? parts[1] : "/";
		int q = path.indexOf('?');
		if (q >= 0) path = path.substring(0, q);

		byte[] pack = PackHost.bytes();
		ByteBuf out;

		if (pack != null && PackHost.servesPath(path)) {
			String headers = "HTTP/1.1 200 OK\r\n"
					+ "Content-Type: application/zip\r\n"
					+ "Content-Length: " + pack.length + "\r\n"
					+ "Cache-Control: public, max-age=86400\r\n"
					+ "Connection: close\r\n\r\n";
			out = Unpooled.wrappedBuffer(headers.getBytes(StandardCharsets.ISO_8859_1));
			if (!headOnly) out = Unpooled.wrappedBuffer(out, Unpooled.wrappedBuffer(pack));
			PingGuard.LOGGER.debug("Serving resource pack to {}", ctx.channel().remoteAddress());
		} else {
			byte[] body = "Not found\n".getBytes(StandardCharsets.ISO_8859_1);
			String headers = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
			out = Unpooled.wrappedBuffer(headers.getBytes(StandardCharsets.ISO_8859_1), body);
		}

		ctx.writeAndFlush(out).addListener(ChannelFutureListener.CLOSE);
	}

	private void passThrough(ChannelHandlerContext ctx, Object msg) {
		ctx.pipeline().remove(this);
		ctx.fireChannelRead(msg);
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) {
		if (buffer != null) {
			buffer.release();
			buffer = null;
		}
	}
}
