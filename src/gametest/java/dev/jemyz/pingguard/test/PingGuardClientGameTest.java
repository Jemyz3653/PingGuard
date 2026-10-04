package dev.jemyz.pingguard.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import dev.jemyz.pingguard.LinkLevel;
import dev.jemyz.pingguard.PingGuardConfig;
import dev.jemyz.pingguard.client.ClientLink;
import dev.jemyz.pingguard.client.PingGuardClient;
import dev.jemyz.pingguard.server.LinkMonitor;
import dev.jemyz.pingguard.server.PlayerLink;

/**
 * Renders every PingGuard state and saves screenshots (build/run/clientGameTest/screenshots).
 * 1) client mod HUD in singleplayer (forced states)
 * 2) vanilla path end-to-end against a dedicated server: pack download over the game port,
 *    action bar, server-forced card and the client-side "dead man" card.
 */
public class PingGuardClientGameTest implements FabricClientGameTest {
	private final List<String> notes = new ArrayList<>();
	private Path shotDir;

	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1280, 720);
		context.runOnClient(client -> client.options.guiScale().set(2));

		try {
			modHud(context);
		} catch (Throwable t) {
			note("modHud FAILED: " + t);
		}

		try {
			vanillaEndToEnd(context);
		} catch (Throwable t) {
			note("vanillaEndToEnd FAILED: " + t);
		}

		writeNotes();
	}

	private void shot(ClientGameTestContext context, String name) {
		Path p = context.takeScreenshot(name);
		shotDir = p.getParent();
		note("screenshot " + name);
	}

	private void note(String s) {
		notes.add(s);
		System.out.println("[PingGuardTest] " + s);
	}

	private void writeNotes() {
		if (shotDir == null) return;

		try {
			Files.writeString(shotDir.resolve("notes.txt"), String.join("\n", notes) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void modHud(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			shot(context, "mod_0_normal");

			context.runOnClient(c -> ClientLink.forceForTest(LinkLevel.POOR, 612, false));
			context.waitTicks(3);
			shot(context, "mod_1_poor");

			context.runOnClient(c -> ClientLink.forceForTest(LinkLevel.BAD, 845, false));
			context.waitTicks(3);
			shot(context, "mod_2_bad");

			context.runOnClient(c -> ClientLink.forceForTest(LinkLevel.CRITICAL, 1340, false));
			context.waitTicks(8);
			shot(context, "mod_3_critical_a");
			context.waitTicks(9);
			shot(context, "mod_3_critical_b");

			context.runOnClient(c -> ClientLink.forceForTest(LinkLevel.CRITICAL, 3200, true));
			context.waitTicks(30);
			shot(context, "mod_4_not_responding");

			context.runOnClient(c -> ClientLink.forceForTest(null, 0, false));
		}
	}

	private void vanillaEndToEnd(ClientGameTestContext context) {
		context.runOnClient(c -> PingGuardClient.setVanillaModeForTest(true));
		PingGuardConfig.get().sendResourcePack = false;   // offered later via command, after the world loaded
		PingGuardConfig.get().joinGraceMs = 0;

		try (TestDedicatedServerContext server = context.worldBuilder().createServer();
				TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			PingGuardConfig.get().sendResourcePack = true;

			server.runCommand("/pingguard repack @r");
			boolean clicked = false;

			for (int i = 0; i < 200 && !clicked; i++) {
				context.waitTick();
				clicked = context.tryClickScreenButton("gui.yes") || context.tryClickScreenButton("gui.proceed");
			}

			note("pack prompt clicked: " + clicked);

			int waited = server.waitFor(s -> {
				ServerPlayer p = s.getPlayerList().getPlayers().isEmpty() ? null : s.getPlayerList().getPlayers().get(0);
				PlayerLink link = p == null ? null : LinkMonitor.get(p);
				return link != null && link.pack() == PlayerLink.Pack.LOADED;
			}, 1200);
			note("pack loaded after " + waited + " ticks");
			context.waitFor(c -> c.getOverlay() == null, 600);
			context.waitTicks(40);
			shot(context, "vanilla_0_normal_armed");

			server.runCommand("/pingguard simulate @r 600");
			context.waitTicks(30);
			shot(context, "vanilla_1_poor");

			server.runCommand("/pingguard simulate @r 850");
			context.waitTicks(30);
			shot(context, "vanilla_2_bad");

			server.runCommand("/pingguard simulate @r 1500");
			context.waitTicks(25);
			shot(context, "vanilla_3_forced_a");
			context.waitTicks(7);
			shot(context, "vanilla_3_forced_b");
			context.waitTicks(11);
			shot(context, "vanilla_3_forced_c");

			server.runCommand("/pingguard simulate @r 0");
			context.waitTicks(80);
			shot(context, "vanilla_4_recovered");

			server.runCommand("/pingguard freeze @r 20");
			context.waitTicks(12);
			shot(context, "vanilla_5_freeze_12t_hidden");
			context.waitTicks(20);
			shot(context, "vanilla_5_freeze_32t");
			context.waitTicks(6);
			shot(context, "vanilla_5_freeze_38t");
			context.waitTicks(14);
			shot(context, "vanilla_5_freeze_52t");

			// a foreign title while armed must look normal
			server.runCommand("/pingguard freeze @r 0");
			context.waitTicks(400);
			server.runCommand("/title @a title {\"text\":\"Foreign title\",\"color\":\"gold\"}");
			context.waitTicks(30);
			shot(context, "vanilla_6_foreign_title");

			String status = context.computeOnClient(Minecraft::getInstance) != null ? "ok" : "?";
			note("client status " + status);
		} finally {
			context.runOnClient(c -> PingGuardClient.setVanillaModeForTest(false));
		}
	}
}
