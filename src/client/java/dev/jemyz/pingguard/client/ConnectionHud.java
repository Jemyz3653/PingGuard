package dev.jemyz.pingguard.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import dev.jemyz.pingguard.CardLayout;
import dev.jemyz.pingguard.Fonts;
import dev.jemyz.pingguard.LinkLevel;
import dev.jemyz.pingguard.PingGuard;

/** HUD for players who have the mod: a small badge for poor ping, the video card for critical. */
public final class ConnectionHud {
	private static final Identifier FRAMES = PingGuard.id("textures/gui/card_frames.png");
	private static final Identifier BAND = PingGuard.id("textures/gui/card_band.png");
	private static final Identifier LINE1 = PingGuard.id("textures/gui/card_line1.png");
	private static final Identifier LINE2 = PingGuard.id("textures/gui/card_line2.png");
	private static final Identifier SIGNAL_POOR = PingGuard.id("textures/gui/signal_poor.png");
	private static final Identifier SIGNAL_BAD = PingGuard.id("textures/gui/signal_bad.png");
	private static final Identifier SIGNAL_LOST = PingGuard.id("textures/gui/signal_lost.png");

	/** Mod textures are 2x the pack textures. */
	private static final int SCENE_TEX_W = 512;
	private static final int SCENE_TEX_H = 256;
	private static final int BAND_TEX_W = 506;
	private static final int BAND_TEX_H = 92;
	private static final int TEXT_PX_PER_FU = 16;

	private static final float CARD_SCALE = 0.65f;
	private static final int EDGE_MARGIN = 8;
	private static final float STATUS_GAP = 6f * CARD_SCALE;

	private static final int AMBER = 0xFFFFC44D;
	private static final int RED = 0xFFFF5A5F;
	private static final int DIM = 0xFFC9D3E6;

	private ConnectionHud() {
	}

	public static void extract(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;

		LinkLevel level = ClientLink.level();

		if (level == LinkLevel.CRITICAL) {
			drawCard(g, mc.font);
		} else if (level != LinkLevel.GOOD) {
			drawBadge(g, mc.font, level);
		}
	}

	private static void drawBadge(GuiGraphicsExtractor g, Font font, LinkLevel level) {
		boolean poor = level == LinkLevel.POOR;
		int accent = poor ? AMBER : RED;
		Component title = Component.literal("POOR CONNECTION").withStyle(Fonts.orbitron(accent & 0xFFFFFF));
		Component ping = Component.literal(ClientLink.effectiveMs() + " MS").withStyle(Fonts.orbitron(DIM & 0xFFFFFF));

		int x = 4;
		int y = 4;
		int textW = Math.max(font.width(title), font.width(ping));
		int w = 6 + 14 + 6 + textW + 8;
		int h = 26;

		g.fill(x, y, x + w, y + h, 0xC80B1220);
		g.fill(x, y, x + 2, y + h, accent);
		g.fill(x + 2, y + h - 1, x + w, y + h, 0x6650E3FF);

		// blink the icon slowly when it's red
		boolean iconOn = poor || (System.currentTimeMillis() / 500) % 2 == 0;
		Identifier icon = poor ? SIGNAL_POOR : (iconOn ? SIGNAL_BAD : SIGNAL_LOST);
		g.blit(RenderPipelines.GUI_TEXTURED, icon, x + 6, y + 6, 0f, 0f, 14, 14, 72, 72, 72, 72);

		g.text(font, title, x + 26, y + 5, 0xFFFFFFFF);
		g.text(font, ping, x + 26, y + 15, 0xFFFFFFFF);
	}

	private static void drawCard(GuiGraphicsExtractor g, Font font) {
		int sw = g.guiWidth();
		int sh = g.guiHeight();

		long since = ClientLink.criticalSince();
		long elapsed = since < 0 ? 0 : System.currentTimeMillis() - since;
		int step = (int) ((elapsed * CardLayout.FPS / 1000L) % CardLayout.TIMELINE.length);
		int frame = CardLayout.TIMELINE[step];

		String status = ClientLink.silent()
				? String.format(java.util.Locale.ROOT, "SERVER NOT RESPONDING  %.1f S", ClientLink.effectiveMs() / 1000.0)
				: "PING " + ClientLink.effectiveMs() + " MS";
		Component line = Component.literal(status).withStyle(Fonts.orbitron((ClientLink.silent() ? RED : DIM) & 0xFFFFFF));

		int cardHeight = CardLayout.SCENE_H + CardLayout.BAND_H;
		// The old centre card used up to 4 GUI pixels per title-font unit.
		float scale = CARD_SCALE * Math.min(4f, Math.min(sw * 0.9f / CardLayout.CARD_W, sh * 0.8f / cardHeight));
		float cardWidth = CardLayout.CARD_W * scale;
		float statusScale = Math.min(CARD_SCALE, cardWidth / font.width(line));
		float centerX = sw - EDGE_MARGIN - cardWidth / 2;
		float statusTop = sh - EDGE_MARGIN - (font.lineHeight + 1) * statusScale;
		float cardBottom = statusTop - STATUS_GAP;
		int fade = (int) Math.min(255, elapsed * 255 / 250);

		int half = CardLayout.CARD_W / 2;
		int bandTop = CardLayout.CARD_TOP + CardLayout.SCENE_H;
		int x1 = -half + (CardLayout.CARD_W - CardLayout.LINE1_W) / 2;
		int x2 = -half + (CardLayout.CARD_W - CardLayout.LINE2_W) / 2;
		int color = (fade << 24) | 0xFFFFFF;

		g.pose().pushMatrix();
		g.pose().translate(centerX, cardBottom - (CardLayout.CARD_TOP + cardHeight) * scale);
		g.pose().scale(scale, scale);

		g.blit(RenderPipelines.GUI_TEXTURED, FRAMES, -half, CardLayout.CARD_TOP, 0f, (float) (frame * SCENE_TEX_H),
				CardLayout.CARD_W, CardLayout.SCENE_H, SCENE_TEX_W, SCENE_TEX_H, SCENE_TEX_W, SCENE_TEX_H * CardLayout.FRAME_COUNT, color);
		g.blit(RenderPipelines.GUI_TEXTURED, BAND, -half, bandTop, 0f, 0f,
				CardLayout.CARD_W, CardLayout.BAND_H, BAND_TEX_W, BAND_TEX_H, BAND_TEX_W, BAND_TEX_H, color);
		g.blit(RenderPipelines.GUI_TEXTURED, LINE1, x1, CardLayout.LINE1_TOP, 0f, 0f,
				CardLayout.LINE1_W, CardLayout.TEXT_BOX_H, CardLayout.LINE1_W * TEXT_PX_PER_FU, CardLayout.TEXT_BOX_H * TEXT_PX_PER_FU,
				CardLayout.LINE1_W * TEXT_PX_PER_FU, CardLayout.TEXT_BOX_H * TEXT_PX_PER_FU, color);
		g.blit(RenderPipelines.GUI_TEXTURED, LINE2, x2, CardLayout.LINE2_TOP, 0f, 0f,
				CardLayout.LINE2_W, CardLayout.TEXT_BOX_H, CardLayout.LINE2_W * TEXT_PX_PER_FU, CardLayout.TEXT_BOX_H * TEXT_PX_PER_FU,
				CardLayout.LINE2_W * TEXT_PX_PER_FU, CardLayout.TEXT_BOX_H * TEXT_PX_PER_FU, color);

		g.pose().popMatrix();

		g.pose().pushMatrix();
		g.pose().translate(centerX, statusTop);
		g.pose().scale(statusScale, statusScale);
		g.centeredText(font, line, 0, 0, color);
		g.pose().popMatrix();
	}
}
