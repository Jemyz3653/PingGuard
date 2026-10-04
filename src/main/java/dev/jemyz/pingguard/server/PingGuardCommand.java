package dev.jemyz.pingguard.server;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;

import dev.jemyz.pingguard.PingGuard;
import dev.jemyz.pingguard.PingGuardConfig;

/**
 * /pingguard status
 * /pingguard simulate &lt;player&gt; &lt;extraMs&gt;     add fake ping (0 = off)
 * /pingguard freeze &lt;player&gt; &lt;seconds&gt;       stop sending PingGuard packets, like a dead server
 * /pingguard reload
 */
public final class PingGuardCommand {
	private PingGuardCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("pingguard")
				.requires(PermissionPredicates.require(PingGuard.id("command"), PermissionLevel.GAMEMASTERS))
				.then(literal("status").executes(PingGuardCommand::status))
				.then(literal("reload").executes(PingGuardCommand::reload))
				.then(literal("simulate")
						.then(argument("player", EntityArgument.player())
								.then(argument("extraMs", IntegerArgumentType.integer(0, 60000))
										.executes(PingGuardCommand::simulate))))
				.then(literal("freeze")
						.then(argument("player", EntityArgument.player())
								.then(argument("seconds", IntegerArgumentType.integer(1, 60))
										.executes(PingGuardCommand::freeze))))
				.then(literal("repack")
						.then(argument("player", EntityArgument.player())
								.executes(PingGuardCommand::repack)))
		);
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		var players = source.getServer().getPlayerList().getPlayers();
		source.sendSuccess(() -> Component.literal("PingGuard - " + players.size() + " player(s)").withStyle(ChatFormatting.AQUA), false);

		for (ServerPlayer player : players) {
			PlayerLink link = LinkMonitor.get(player);
			MutableComponent line = Component.literal(" " + player.getName().getString() + ": ");

			if (link == null) {
				line.append(Component.literal("local / not tracked").withStyle(ChatFormatting.GRAY));
			} else {
				ChatFormatting color = switch (link.level()) {
					case GOOD -> ChatFormatting.GREEN;
					case POOR -> ChatFormatting.YELLOW;
					case BAD -> ChatFormatting.RED;
					case CRITICAL -> ChatFormatting.DARK_RED;
				};
				line.append(Component.literal(link.effectiveMs() + " ms " + link.level()).withStyle(color));
				String kind = switch (link.client()) {
					case MOD -> "client mod";
					case VANILLA -> "vanilla, pack " + link.pack().name().toLowerCase(java.util.Locale.ROOT);
					case UNKNOWN -> "?";
				};
				line.append(Component.literal("  (" + kind + ")").withStyle(ChatFormatting.GRAY));
			}

			source.sendSuccess(() -> line, false);
		}

		return players.size();
	}

	private static int reload(CommandContext<CommandSourceStack> ctx) {
		PingGuardConfig.load();
		PackHost.reload();
		ctx.getSource().sendSuccess(() -> Component.literal("PingGuard config and pack reloaded (pack sha1 " + PackHost.sha1() + ")"), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int simulate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
		int extra = IntegerArgumentType.getInteger(ctx, "extraMs");
		LinkMonitor.simulate(player, extra);
		ctx.getSource().sendSuccess(() -> Component.literal(extra == 0
				? "PingGuard: simulation off for " + player.getName().getString()
				: "PingGuard: +" + extra + " ms fake ping for " + player.getName().getString()), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int freeze(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
		int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
		LinkMonitor.freeze(player, seconds);
		ctx.getSource().sendSuccess(() -> Component.literal("PingGuard: pretending the server is dead for "
				+ player.getName().getString() + " (" + seconds + " s)"), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int repack(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
		LinkMonitor.reofferPack(player);
		ctx.getSource().sendSuccess(() -> Component.literal("PingGuard: resource pack offered again to " + player.getName().getString()), false);
		return Command.SINGLE_SUCCESS;
	}
}
