package com.terraformer;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Terraformer implements ModInitializer {
	public static final String MOD_ID = "terraformer";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Entity tag (saved with the player) recording that the starter wand was already handed out. */
	private static final String STARTER_TAG = MOD_ID + ".starter_wand";

	@Override
	public void onInitialize() {
		ModItems.init();

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			// addTag returns true only the first time, so the wand is given exactly once.
			if (player.addTag(STARTER_TAG)) {
				giveWand(player);
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
			Selections.forget(handler.getPlayer().getUUID()));

		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> registerCommands(dispatcher));
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("terraform")
			.then(Commands.literal("wand").executes(ctx -> {
				giveWand(ctx.getSource().getPlayerOrException());
				return 1;
			}))
			.then(Commands.literal("undo").executes(ctx -> {
				ServerPlayer player = ctx.getSource().getPlayerOrException();
				int restored = Selections.undo(player);
				if (restored < 0) {
					ctx.getSource().sendFailure(Component.literal("Nothing to undo."));
					return 0;
				}
				ctx.getSource().sendSuccess(() -> Component.literal("Restored " + restored + " blocks."), false);
				return 1;
			})));
	}

	private static void giveWand(ServerPlayer player) {
		// Adds to the inventory, or drops at the player's feet if it is full.
		player.getInventory().placeItemBackInInventory(new ItemStack(ModItems.TERRAFORM_WAND));
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
