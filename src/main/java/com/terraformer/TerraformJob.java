package com.terraformer;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Flattens the rectangle spanned by two clicked blocks to the Y of the first click and blends it into the
 * surrounding terrain with a ring of 1-block steps (cut when the terrain is higher, filled when lower).
 */
public final class TerraformJob {
	/** How many blocks beyond the box the stepped slope extends. */
	public static final int MARGIN = 12;
	/** Longest allowed side of the flattened box. */
	public static final int MAX_SIDE = 96;
	/** Update clients, skip neighbour updates (no physics/drop storm while editing thousands of blocks). */
	public static final int FLAGS = 2;

	public record Change(BlockPos pos, BlockState oldState) {
	}

	public record Outcome(String error, int sizeX, int sizeZ, List<Change> changes) {
		static Outcome fail(String message) {
			return new Outcome(message, 0, 0, List.of());
		}
	}

	private TerraformJob() {
	}

	public static Outcome run(ServerLevel level, BlockPos a, BlockPos b) {
		int minX = Math.min(a.getX(), b.getX());
		int maxX = Math.max(a.getX(), b.getX());
		int minZ = Math.min(a.getZ(), b.getZ());
		int maxZ = Math.max(a.getZ(), b.getZ());
		int sizeX = maxX - minX + 1;
		int sizeZ = maxZ - minZ + 1;
		if (sizeX > MAX_SIDE || sizeZ > MAX_SIDE) {
			return Outcome.fail("Area too large (" + sizeX + "x" + sizeZ + "); max is " + MAX_SIDE + "x" + MAX_SIDE + ".");
		}

		int levelY = a.getY();
		List<Change> changes = new ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (int x = minX - MARGIN; x <= maxX + MARGIN; x++) {
			for (int z = minZ - MARGIN; z <= maxZ + MARGIN; z++) {
				int dx = Math.max(Math.max(minX - x, x - maxX), 0);
				int dz = Math.max(Math.max(minZ - z, z - maxZ), 0);
				double dist = Math.sqrt(dx * dx + dz * dz);
				if (dist > MARGIN) {
					continue; // corners of the square margin lie outside the rounded slope
				}
				// Allowed deviation from the platform height grows by one block per block of distance.
				int allowed = (int) Math.ceil(dist);
				processColumn(level, pos, changes, x, z, levelY, allowed);
			}
		}
		return new Outcome(null, sizeX, sizeZ, changes);
	}

	private static void processColumn(ServerLevel level, BlockPos.MutableBlockPos pos, List<Change> changes,
			int x, int z, int levelY, int allowed) {
		int minY = level.getMinY();
		int maxY = level.getMaxY();
		// First empty Y above the highest block (leaves, fluids included).
		int top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), maxY);

		// Find the real ground: skip air, fluids, plants/snow layers, leaves and logs.
		int ground = minY - 1;
		BlockState groundState = Blocks.DIRT.defaultBlockState();
		for (int y = top - 1; y >= minY; y--) {
			BlockState state = level.getBlockState(pos.set(x, y, z));
			if (isGround(state)) {
				ground = y;
				groundState = state;
				break;
			}
		}
		if (ground < minY) {
			return; // void column
		}

		int target;
		if (ground > levelY + allowed) {
			target = levelY + allowed;
		} else if (ground < levelY - allowed) {
			target = levelY - allowed;
		} else {
			// Terrain already within the slope window: just clear vegetation/trees sitting above it.
			clearAbove(level, pos, changes, x, z, ground, top);
			return;
		}
		target = Math.max(minY, Math.min(target, maxY - 1));

		if (target > ground) {
			BlockState filler = groundState.is(BlockTags.DIRT) ? Blocks.DIRT.defaultBlockState() : groundState;
			for (int y = ground + 1; y <= target; y++) {
				set(level, pos, changes, x, y, z, y == target ? groundState : filler);
			}
		} else if (target < ground) {
			// Re-grass the new surface if the original surface was grass.
			BlockState newTop = level.getBlockState(pos.set(x, target, z));
			if (groundState.is(Blocks.GRASS_BLOCK) && newTop.is(Blocks.DIRT)) {
				set(level, pos, changes, x, target, z, groundState);
			}
		}
		clearAbove(level, pos, changes, x, z, target, Math.max(top, ground + 1));
	}

	/** Removes everything in (below, top) exclusive of {@code below}, leaving block entities and unbreakables alone. */
	private static void clearAbove(ServerLevel level, BlockPos.MutableBlockPos pos, List<Change> changes,
			int x, int z, int below, int top) {
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int y = below + 1; y < top; y++) {
			BlockState state = level.getBlockState(pos.set(x, y, z));
			if (state.isAir() || !removable(level, pos, state)) {
				continue;
			}
			set(level, pos, changes, x, y, z, air);
		}
	}

	private static boolean isGround(BlockState state) {
		return !state.isAir()
			&& state.getFluidState().isEmpty()
			&& !state.canBeReplaced()
			&& !state.is(BlockTags.LEAVES)
			&& !state.is(BlockTags.LOGS);
	}

	private static boolean removable(ServerLevel level, BlockPos pos, BlockState state) {
		return state.getDestroySpeed(level, pos) >= 0 && level.getBlockEntity(pos) == null;
	}

	private static void set(ServerLevel level, BlockPos.MutableBlockPos pos, List<Change> changes,
			int x, int y, int z, BlockState state) {
		pos.set(x, y, z);
		BlockState old = level.getBlockState(pos);
		if (old == state || !removable(level, pos, old)) {
			return;
		}
		changes.add(new Change(pos.immutable(), old));
		level.setBlock(pos, state, FLAGS);
	}
}
