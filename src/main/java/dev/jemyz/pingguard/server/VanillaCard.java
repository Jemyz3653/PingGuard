package dev.jemyz.pingguard.server;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import dev.jemyz.pingguard.CardLayout;
import dev.jemyz.pingguard.Fonts;
import dev.jemyz.pingguard.LinkLevel;

/**
 * Text components for vanilla clients.
 *
 * <p>With the resource pack the whole video card is one title line made of bitmap glyphs
 * (font pingguard:card). Every glyph carries a marker colour #FE50xx / #FE51xx that the
 * pack's text shader recognises: xx is the frame id (or 255 for the static band/text), and the
 * title alpha is used as a clock to pick the frame. See src/pack/.../text.vsh.
 */
public final class VanillaCard {
	public static final int MARK_ARMED = 80;
	public static final int MARK_FORCED = 81;
	public static final int STATIC = 255;

	/** A bitmap glyph advances by round(width) + 1 font units. */
	private static final int GLYPH_GAP = 1;

	private static Component armed;
	private static Component forced;

	private VanillaCard() {
	}

	public static Component armed() {
		if (armed == null) armed = build(MARK_ARMED);
		return armed;
	}

	public static Component forced() {
		if (forced == null) forced = build(MARK_FORCED);
		return forced;
	}

	private static int marker(int mode, int id) {
		return 0xFE0000 | (mode << 8) | id;
	}

	private static Component build(int mode) {
		// Root style: font + no shadow. Children only carry text + marker colour (keeps the packet small).
		MutableComponent root = Component.literal("").withStyle(Style.EMPTY.withFont(Fonts.CARD).withShadowColor(0));
		int back = -(CardLayout.CARD_W + GLYPH_GAP);

		// 1) all animation frames stacked on top of each other; the shader shows one of them
		for (int i = 0; i < CardLayout.FRAME_COUNT; i++) {
			root.append(part(Fonts.glyph(CardLayout.FRAME_CHAR0 + i) + Fonts.space(back), marker(mode, i)));
		}

		// 2) text band under the scene
		root.append(part(Fonts.glyph(CardLayout.BAND_CHAR) + Fonts.space(back), marker(mode, STATIC)));

		// 3) "CHECK YOUR" / "INTERNET CONNECTION", each line split into chunks of <=256 px
		int x1 = (CardLayout.CARD_W - CardLayout.LINE1_W) / 2;
		int x2 = (CardLayout.CARD_W - CardLayout.LINE2_W) / 2;
		StringBuilder text = new StringBuilder();
		text.append(Fonts.space(x1));
		for (int i = 0; i < CardLayout.LINE1_CHUNKS; i++) {
			text.appendCodePoint(CardLayout.LINE1_CHAR0 + i).append(Fonts.space(-GLYPH_GAP));
		}
		text.append(Fonts.space(-(x1 + CardLayout.LINE1_W)));
		text.append(Fonts.space(x2));
		for (int i = 0; i < CardLayout.LINE2_CHUNKS; i++) {
			text.appendCodePoint(CardLayout.LINE2_CHAR0 + i).append(Fonts.space(-GLYPH_GAP));
		}
		// end exactly at CARD_W so the vanilla title centring puts the card in the middle
		text.append(Fonts.space(CardLayout.CARD_W - (x2 + CardLayout.LINE2_W)));
		root.append(part(text.toString(), marker(mode, STATIC)));
		return root;
	}

	private static MutableComponent part(String text, int color) {
		return Component.literal(text).withStyle(Style.EMPTY.withColor(color));
	}

	// ------------------------------------------------------------------ action bar

	private static final int COLOR_POOR = 0xFFC44D;
	private static final int COLOR_BAD = 0xFF5A5F;
	private static final int COLOR_DIM = 0xC9D3E6;

	/** Action bar line for players with the pack (Orbitron + signal icon). */
	public static Component actionBar(LinkLevel level, long pingMs, boolean showPing, boolean noAnswer) {
		int icon = switch (level) {
			case POOR -> CardLayout.SIGNAL_POOR;
			case BAD -> CardLayout.SIGNAL_BAD;
			default -> CardLayout.SIGNAL_LOST;
		};
		int color = level == LinkLevel.POOR ? COLOR_POOR : COLOR_BAD;
		String label = level == LinkLevel.CRITICAL ? (noAnswer ? "NO RESPONSE" : "VERY HIGH PING") : "POOR CONNECTION";

		MutableComponent line = Component.empty();
		line.append(Component.literal(Fonts.glyph(icon) + Fonts.space(4)).withStyle(Style.EMPTY.withFont(Fonts.ICONS).withColor(0xFFFFFF)));
		line.append(Component.literal(label).withStyle(Fonts.orbitron(color)));

		if (showPing) {
			line.append(Component.literal("  " + formatMs(pingMs)).withStyle(Fonts.orbitron(COLOR_DIM)));
		}

		return line;
	}

	/** Action bar line for players without the pack (default font). */
	public static Component plainActionBar(LinkLevel level, long pingMs, boolean showPing, boolean noAnswer) {
		ChatFormatting color = level == LinkLevel.POOR ? ChatFormatting.YELLOW : ChatFormatting.RED;
		String label = level == LinkLevel.CRITICAL ? (noAnswer ? "No response from you" : "Very high ping") : "Poor connection";
		MutableComponent line = Component.literal("⚠ " + label).withStyle(color);

		if (showPing) {
			line.append(Component.literal("  " + pingMs + " ms").withStyle(ChatFormatting.GRAY));
		}

		return line;
	}

	public static Component plainTitle() {
		return Component.literal("CHECK YOUR").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
	}

	public static Component plainSubtitle() {
		return Component.literal("INTERNET CONNECTION").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD);
	}

	private static String formatMs(long ms) {
		if (ms >= 10_000) return (ms / 1000) + " S";
		return ms + " MS";
	}
}
