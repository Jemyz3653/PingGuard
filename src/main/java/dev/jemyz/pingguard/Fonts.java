package dev.jemyz.pingguard;

import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;

/** Fonts shipped in the resource pack / mod assets. */
public final class Fonts {
	/** Orbitron Black (TTF). */
	public static final FontDescription ORBITRON = new FontDescription.Resource(PingGuard.id("orbitron"));
	/** Video card glyphs (bitmap) + space glyphs. Only in the server resource pack. */
	public static final FontDescription CARD = new FontDescription.Resource(PingGuard.id("card"));
	/** Signal icons for the action bar + space glyphs. Only in the server resource pack. */
	public static final FontDescription ICONS = new FontDescription.Resource(PingGuard.id("icons"));

	private Fonts() {
	}

	public static Style orbitron(int rgb) {
		return Style.EMPTY.withFont(ORBITRON).withColor(rgb);
	}

	public static String glyph(int codePoint) {
		return new String(Character.toChars(codePoint));
	}

	/** A run of space glyphs moving the cursor by {@code advance} font units (fonts card/icons). */
	public static String space(int advance) {
		StringBuilder sb = new StringBuilder();
		int sign = advance < 0 ? -1 : 1;
		int left = Math.abs(advance);

		for (int step = 64; step >= 1; step >>= 1) {
			while (left >= step) {
				sb.appendCodePoint(CardLayout.spaceChar(sign * step));
				left -= step;
			}
		}

		return sb.toString();
	}
}
