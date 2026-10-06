package com.terraformer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Per-player runtime state: the pending first corner and the last terraform (for undo). Not persisted. */
public final class Selections {
	private record Corner(ResourceKey<Level> dimension, BlockPos pos) {
	}

	private record UndoEntry(ResourceKey<Level> dimension, List<TerraformJob.Change> changes) {
	}

	private static final Map<UUID, Corner> FIRST_CORNERS = new HashMap<>();
	private static final Map<UUID, UndoEntry> UNDO = new HashMap<>();

	private Selections() {
	}

	/** Returns the stored first corner if it is in this dimension, else null. */
	public static BlockPos firstCorner(ServerPlayer player, ServerLevel level) {
		Corner corner = FIRST_CORNERS.get(player.getUUID());
		return corner != null && corner.dimension().equals(level.dimension()) ? corner.pos() : null;
	}

	public static void setFirstCorner(ServerPlayer player, ServerLevel level, BlockPos pos) {
		FIRST_CORNERS.put(player.getUUID(), new Corner(level.dimension(), pos.immutable()));
	}

	public static void clearFirstCorner(ServerPlayer player) {
		FIRST_CORNERS.remove(player.getUUID());
	}

	public static void rememberForUndo(ServerPlayer player, ServerLevel level, List<TerraformJob.Change> changes) {
		UNDO.put(player.getUUID(), new UndoEntry(level.dimension(), changes));
	}

	/** Reverts the player's last terraform. Returns the number of blocks restored, or -1 if there is nothing to undo. */
	public static int undo(ServerPlayer player) {
		UndoEntry entry = UNDO.remove(player.getUUID());
		if (entry == null) {
			return -1;
		}
		ServerLevel level = player.level().getServer().getLevel(entry.dimension());
		if (level == null) {
			return -1;
		}
		List<TerraformJob.Change> changes = entry.changes();
		// Restore in reverse so that overlapping writes unwind correctly.
		for (int i = changes.size() - 1; i >= 0; i--) {
			TerraformJob.Change change = changes.get(i);
			level.setBlock(change.pos(), change.oldState(), TerraformJob.FLAGS);
		}
		return changes.size();
	}

	public static void forget(UUID player) {
		FIRST_CORNERS.remove(player);
		UNDO.remove(player);
	}

	/** Regular chat line, for longer results than the action bar can show. */
	public static void chat(ServerPlayer player, String text) {
		player.sendSystemMessage(Component.literal(text), false);
	}

	public static void actionBar(ServerPlayer player, String text) {
		player.sendSystemMessage(Component.literal(text), true);
	}
}
