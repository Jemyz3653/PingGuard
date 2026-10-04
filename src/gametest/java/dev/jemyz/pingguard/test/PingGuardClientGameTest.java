package dev.jemyz.pingguard.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
 *    action bar, server-forced card, the client-side "dead man" card, dimension change grace
 * 3) client mod against a dedicated server: real RTT stream, dimension change grace
 */
public class PingGuardClientGameTest implements FabricClientGameTest {
	private static final String TO_NETHER = "/execute in minecraft:the_nether run tp @r 0 100 0";
	private static final String TO_OVERWORLD = "/execute in minecraft:overworld run tp @r 0 100 0";

	private final List<String> notes = new ArrayList<>();
	private Path shotDir;

	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1280, 720);
		context.runOnClient(client -> client.options.guiScale().set(2));

		run("modHud", () -> modHud(context));
		run("vanillaEndToEnd", () -> vanillaEndToEnd(context));
		run("modEndToEnd", () -> modEndToEnd(context));

		writeNotes();
	}

	private void run(String name, Runnable r) {
		try {
			r.run();
		} catch (Throwable t) {
			note(name + " FAILED: " + t);
			t.printStackTrace();
		}
	}

	/** The server side works in real time (debounce, grace); game ticks in tests can run faster or slower. */
	private static void waitReal(ClientGameTestContext context, long ms) {
		long until = System.currentTimeMillis() + ms;
		context.waitFor(c -> System.currentTimeMillis() >= until, 20 * 120);
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

	private void serverLevel(TestDedicatedServerContext server, String label) {
		server.waitFor(s -> {
			if (!s.getPlayerList().getPlayers().isEmpty()) {
				ServerPlayer p = s.getPlayerList().getPlayers().get(0);
				PlayerLink link = LinkMonitor.get(p);
				note(label + ": server sees " + (link == null ? "no link" : link.level() + " " + link.effectiveMs() + " ms, pack " + link.pack()));
			}

			return true;
		}, 5);
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
			server.runCommand("/gamemode creative @a");   // the nether teleport below must not kill us
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
			context.waitTicks(100);
			waitReal(context, 1000);
			serverLevel(server, "normal");
			shot(context, "vanilla_0_normal_armed");

			// one short spike must NOT show anything (debounce)
			server.runCommand("/pingguard simulate @r 1500");
			waitReal(context, 600);
			server.runCommand("/pingguard simulate @r 0");
			waitReal(context, 1500);
			shot(context, "vanilla_0b_short_spike_ignored");

			server.runCommand("/pingguard simulate @r 600");
			waitReal(context, 2200);
			shot(context, "vanilla_1_poor");

			server.runCommand("/pingguard simulate @r 850");
			waitReal(context, 2200);
			shot(context, "vanilla_2_bad");

			server.runCommand("/pingguard simulate @r 1500");
			waitReal(context, 2200);
			serverLevel(server, "forced");
			shot(context, "vanilla_3_forced_a");
			context.waitTicks(7);
			shot(context, "vanilla_3_forced_b");

			// dimension change: everything must disappear for the grace period, then come back
			server.runCommand(TO_NETHER);
			waitReal(context, 1200);
			serverLevel(server, "after nether tp");
			shot(context, "vanilla_4_dimension_change_1s");
			waitReal(context, 5500);
			serverLevel(server, "after grace");
			shot(context, "vanilla_4_after_grace");

			server.runCommand("/pingguard simulate @r 0");
			server.runCommand(TO_OVERWORLD);
			waitReal(context, 6500);
			shot(context, "vanilla_5_recovered");

			// dead man: the server stops sending anything to this player
			server.runCommand("/pingguard freeze @r 8");
			context.waitTicks(30);
			shot(context, "vanilla_6_freeze_30t_hidden");
			context.waitTicks(16);
			shot(context, "vanilla_6_freeze_46t");
			context.waitTicks(8);
			shot(context, "vanilla_6_freeze_54t");
			waitReal(context, 9000);
			shot(context, "vanilla_6_after_freeze");

			// a foreign title while armed must look normal
			server.runCommand("/title @a title {\"text\":\"Foreign title\",\"color\":\"gold\"}");
			context.waitTicks(30);
			shot(context, "vanilla_7_foreign_title");
		} finally {
			context.runOnClient(c -> PingGuardClient.setVanillaModeForTest(false));
		}
	}

	private void modEndToEnd(ClientGameTestContext context) {
		PingGuardConfig.get().joinGraceMs = 0;

		try (TestDedicatedServerContext server = context.worldBuilder().createServer();
				TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			server.runCommand("/gamemode creative @a");
			waitReal(context, 7000);   // the client mod's own join grace
			serverLevel(server, "mod client normal");
			shot(context, "modsrv_0_normal");

			server.runCommand("/pingguard simulate @r 1500");
			waitReal(context, 3000);
			shot(context, "modsrv_1_critical");

			server.runCommand(TO_NETHER);
			waitReal(context, 1200);
			shot(context, "modsrv_2_dimension_change_1s");
			waitReal(context, 5500);
			shot(context, "modsrv_3_after_grace");

			server.runCommand("/pingguard simulate @r 0");
			waitReal(context, 3000);

			server.runCommand("/pingguard freeze @r 8");
			waitReal(context, 4500);
			shot(context, "modsrv_4_server_silent");
		}
	}
}
