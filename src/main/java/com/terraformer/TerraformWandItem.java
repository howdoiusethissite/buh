package com.terraformer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Right-click a block to mark the first corner, right-click a second block to terraform the box between them.
 * Sneak + right-click clears a pending first corner.
 */
public class TerraformWandItem extends Item {
	public TerraformWandItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		if (!(level instanceof ServerLevel serverLevel) || !(context.getPlayer() instanceof ServerPlayer player)) {
			return InteractionResult.SUCCESS; // client: swing the arm, the server does the work
		}
		BlockPos clicked = context.getClickedPos();

		if (player.isShiftKeyDown()) {
			Selections.clearFirstCorner(player);
			Selections.actionBar(player, "Selection cleared.");
			return InteractionResult.CONSUME;
		}

		BlockPos first = Selections.firstCorner(player, serverLevel);
		if (first == null) {
			Selections.setFirstCorner(player, serverLevel, clicked);
			Selections.actionBar(player, "First corner set at " + clicked.toShortString()
				+ ". Right-click the opposite corner.");
			return InteractionResult.CONSUME;
		}

		TerraformJob.Outcome outcome = TerraformJob.run(serverLevel, first, clicked);
		if (outcome.error() != null) {
			Selections.actionBar(player, outcome.error());
			return InteractionResult.CONSUME;
		}
		Selections.clearFirstCorner(player);
		Selections.rememberForUndo(player, serverLevel, outcome.changes());
		Selections.actionBar(player, "Terraformed " + outcome.sizeX() + "x" + outcome.sizeZ() + " at y="
			+ first.getY() + ": " + outcome.changes().size() + " blocks changed, " + outcome.chests()
			+ " chest(s) of removed blocks on the platform"
			+ (outcome.lostStacks() > 0 ? " (" + outcome.lostStacks() + " stacks did not fit)" : "")
			+ (outcome.protectedColumns() > 0 ? ", " + outcome.protectedColumns() + " columns with builds left untouched" : "")
			+ ". /terraform undo to revert.");
		return InteractionResult.CONSUME;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		// Sneak + right-click in the air also clears a pending selection.
		if (player.isShiftKeyDown() && player instanceof ServerPlayer serverPlayer) {
			Selections.clearFirstCorner(serverPlayer);
			Selections.actionBar(serverPlayer, "Selection cleared.");
			return InteractionResult.CONSUME;
		}
		return InteractionResult.PASS;
	}
}
