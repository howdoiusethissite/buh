package com.terraformer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Flattens the rectangle spanned by two clicked blocks to the Y of the first click and blends it into the
 * surrounding terrain with a ring of 1-block steps (cut when the terrain is higher, filled when lower).
 *
 * <p>Minecraft does not record who placed a block, so player builds are protected by recognising what is
 * natural instead: any column containing a block that is not natural terrain, vegetation or a tree is left
 * completely untouched. Everything that is removed is put into chests on the finished platform.
 */
public final class TerraformJob {
	/** How many blocks beyond the box the stepped slope extends. */
	public static final int MARGIN = 12;
	/** Longest allowed side of the flattened box. */
	public static final int MAX_SIDE = 96;
	/** Update clients, skip neighbour updates (no physics/drop storm while editing thousands of blocks). */
	public static final int FLAGS = 2;
	/** In the Nether, how far above the platform height the ground search looks for open space. */
	private static final int NETHER_HEADROOM = 16;
	private static final int CHEST_SLOTS = 27;

	public record Change(BlockPos pos, BlockState oldState) {
	}

	public record Outcome(String error, int sizeX, int sizeZ, List<Change> changes, int chests, int lostStacks,
			int protectedColumns, String blockers) {
		static Outcome fail(String message) {
			return new Outcome(message, 0, 0, List.of(), 0, 0, 0, "");
		}
	}

	private final ServerLevel level;
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private final List<Change> changes = new ArrayList<>();
	private final Map<Item, Integer> removed = new LinkedHashMap<>();
	private int protectedColumns;
	/** Which blocks caused columns to be skipped, by registry id (reported so a wrong guess is visible). */
	private final Map<String, Integer> protectedBy = new LinkedHashMap<>();

	private void protect(BlockState blocker) {
		protectedColumns++;
		protectedBy.merge(BuiltInRegistries.BLOCK.getKey(blocker.getBlock()).toString(), 1, Integer::sum);
	}

	private String topBlockers() {
		return protectedBy.entrySet().stream()
			.sorted((a, b) -> b.getValue() - a.getValue())
			.limit(3)
			.map(e -> e.getKey() + " x" + e.getValue())
			.collect(java.util.stream.Collectors.joining(", "));
	}

	private TerraformJob(ServerLevel level) {
		this.level = level;
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

		TerraformJob job = new TerraformJob(level);
		int levelY = a.getY();
		for (int x = minX - MARGIN; x <= maxX + MARGIN; x++) {
			for (int z = minZ - MARGIN; z <= maxZ + MARGIN; z++) {
				int dx = Math.max(Math.max(minX - x, x - maxX), 0);
				int dz = Math.max(Math.max(minZ - z, z - maxZ), 0);
				double dist = Math.sqrt(dx * dx + dz * dz);
				if (dist > MARGIN) {
					continue; // corners of the square margin lie outside the rounded slope
				}
				// Allowed deviation from the platform height grows by one block per block of distance.
				job.processColumn(x, z, levelY, (int) Math.ceil(dist));
			}
		}

		int[] chestResult = job.placeChests(minX, maxX, minZ, maxZ, levelY);
		return new Outcome(null, sizeX, sizeZ, job.changes, chestResult[0], chestResult[1], job.protectedColumns,
			job.topBlockers());
	}

	private void processColumn(int x, int z, int levelY, int allowed) {
		int minY = level.getMinY();
		int maxY = level.getMaxY();

		int ground = minY - 1;
		int top;
		BlockState groundState = null;
		if (level.dimension() == Level.NETHER) {
			// The Nether has a bedrock roof, so the heightmap is useless. Instead look for the surface
			// nearest the platform height, with a limited amount of headroom above it.
			int cap = Math.min(maxY - 1, levelY + NETHER_HEADROOM);
			int y = Math.max(minY, Math.min(levelY, cap));
			BlockState here = level.getBlockState(pos.set(x, y, z));
			if (isPassable(pos, here)) {
				for (int g = y - 1; g >= minY; g--) {
					BlockState state = level.getBlockState(pos.set(x, g, z));
					if (isPassable(pos, state)) {
						continue;
					}
					if (isNaturalTerrain(state, false)) {
						ground = g;
						groundState = state;
					} else {
						protect(state);
						return;
					}
					break;
				}
			} else if (!isNaturalTerrain(here, false)) {
				protect(here);
				return;
			} else {
				// Inside rock: walk up to the first opening, or to the headroom limit.
				int g = y;
				while (g + 1 < cap) {
					BlockState above = level.getBlockState(pos.set(x, g + 1, z));
					if (isPassable(pos, above)) {
						break;
					}
					g++;
				}
				ground = g;
				groundState = level.getBlockState(pos.set(x, g, z));
			}
			if (groundState == null) {
				return; // void column
			}
			top = ground + 1;
			while (top < cap && isPassable(pos.set(x, top, z), level.getBlockState(pos))) {
				top++;
			}
		} else {
			// First empty Y above the highest block (leaves, fluids included).
			top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), maxY);

			// Walk down through air, water, plants and natural trees to the ground. Hitting anything else
			// (planks, glass, torches, a placed chest...) means a player build: leave the column alone.
			for (int y = top - 1; y >= minY; y--) {
				BlockPos p = pos.set(x, y, z);
				BlockState state = level.getBlockState(p);
				if (isPassable(p, state)) {
					continue;
				}
				if (isNaturalTerrain(state, false)) {
					ground = y;
					groundState = state;
				} else {
					protect(state);
				}
				break;
			}
			if (groundState == null) {
				return; // void column, or protected
			}
		}

		int target;
		if (ground > levelY + allowed) {
			target = levelY + allowed;
		} else if (ground < levelY - allowed) {
			target = levelY - allowed;
		} else {
			target = ground; // already within the slope window: only clear what grows on top
		}
		target = Math.max(minY, Math.min(target, maxY - 1));

		// Everything we would dig out must be natural too (no basements, tunnels, buried chests).
		for (int y = target + 1; y <= ground; y++) {
			BlockPos p = pos.set(x, y, z);
			BlockState state = level.getBlockState(p);
			if (!isPassable(p, state) && !isNaturalTerrain(state, true)) {
				protect(state);
				return;
			}
		}

		if (target > ground) {
			BlockState filler = groundState.is(BlockTags.DIRT) ? Blocks.DIRT.defaultBlockState() : groundState;
			for (int y = ground + 1; y <= target; y++) {
				set(x, y, z, y == target ? groundState : filler);
			}
		} else if (target < ground) {
			// Re-grass the new surface if the original surface was grass.
			BlockState newTop = level.getBlockState(pos.set(x, target, z));
			if (groundState.is(Blocks.GRASS_BLOCK) && newTop.is(Blocks.DIRT)) {
				set(x, target, z, groundState);
			}
		}

		// Clear everything above the new surface: the dug-out hill, trees and plants.
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int y = target + 1; y < top; y++) {
			set(x, y, z, air);
		}
	}

	/** Air, water/lava, replaceable plants, kelp/seagrass, wild leaves and trees: things a hill or forest has. */
	private boolean isPassable(BlockPos p, BlockState state) {
		if (state.isAir() || state.getBlock() instanceof LiquidBlock || state.canBeReplaced()) {
			return true;
		}
		if (state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT) || state.is(Blocks.SEAGRASS)
				|| state.is(Blocks.TALL_SEAGRASS)
				// Nether and End vegetation
				|| state.is(Blocks.CRIMSON_FUNGUS) || state.is(Blocks.WARPED_FUNGUS)
				|| state.is(Blocks.WEEPING_VINES) || state.is(Blocks.WEEPING_VINES_PLANT)
				|| state.is(Blocks.TWISTING_VINES) || state.is(Blocks.TWISTING_VINES_PLANT)
				|| state.is(Blocks.NETHER_SPROUTS) || state.is(Blocks.NETHER_WART)
				|| state.is(Blocks.CHORUS_PLANT) || state.is(Blocks.CHORUS_FLOWER)
				|| isWartCanopy(state)) {
			return true;
		}
		if (state.is(BlockTags.LEAVES)) {
			// Leaves placed by a player are persistent; ones grown by a tree are not.
			return !(state.hasProperty(BlockStateProperties.PERSISTENT) && state.getValue(BlockStateProperties.PERSISTENT));
		}
		if (state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS) || state.is(BlockTags.SNOW)
				|| state.is(Blocks.SNOW) || state.is(Blocks.VINE) || state.is(Blocks.LILY_PAD)
				|| state.is(Blocks.BROWN_MUSHROOM) || state.is(Blocks.RED_MUSHROOM)
				|| state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.CACTUS) || state.is(Blocks.SUGAR_CANE)
				|| state.is(Blocks.BAMBOO) || state.is(Blocks.BAMBOO_SAPLING) || state.is(Blocks.DEAD_BUSH)
				|| state.is(Blocks.PUMPKIN) || state.is(Blocks.MELON) || state.is(Blocks.MOSS_CARPET)
				|| state.is(Blocks.GLOW_LICHEN) || state.is(Blocks.BROWN_MUSHROOM_BLOCK)
				|| state.is(Blocks.RED_MUSHROOM_BLOCK) || state.is(Blocks.MUSHROOM_STEM)) {
			return true;
		}
		return state.is(BlockTags.LOGS) && isTreeLog(p);
	}

	/** Huge-fungus "leaves": nether/warped wart blocks and shroomlight. */
	private static boolean isWartCanopy(BlockState state) {
		return state.is(Blocks.NETHER_WART_BLOCK) || state.is(Blocks.WARPED_WART_BLOCK)
				|| state.is(Blocks.SHROOMLIGHT);
	}

	/** A log counts as part of a tree if wild (non-persistent) leaves are within two blocks of it. */
	private boolean isTreeLog(BlockPos logPos) {
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		for (int dx = -2; dx <= 2; dx++) {
			for (int dy = -2; dy <= 2; dy++) {
				for (int dz = -2; dz <= 2; dz++) {
					BlockState s = level.getBlockState(q.set(logPos.getX() + dx, logPos.getY() + dy, logPos.getZ() + dz));
					if (isWartCanopy(s) || (s.is(BlockTags.LEAVES) && s.hasProperty(BlockStateProperties.PERSISTENT)
							&& !s.getValue(BlockStateProperties.PERSISTENT))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean isNaturalTerrain(BlockState state, boolean allowSandstone) {
		if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD)
				|| state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.TERRACOTTA)) {
			return true;
		}
		Block block = state.getBlock();
		// Explicit list as well as the tags above, in case a tag does not contain what is expected.
		if (block == Blocks.GRASS_BLOCK || block == Blocks.DIRT || block == Blocks.COARSE_DIRT
				|| block == Blocks.PODZOL || block == Blocks.MYCELIUM || block == Blocks.ROOTED_DIRT
				|| block == Blocks.SAND || block == Blocks.RED_SAND || block == Blocks.STONE
				|| block == Blocks.DEEPSLATE || block == Blocks.GRANITE || block == Blocks.DIORITE
				|| block == Blocks.ANDESITE || block == Blocks.TUFF || block == Blocks.NETHERRACK
				|| block == Blocks.BASALT || block == Blocks.BLACKSTONE) {
			return true;
		}
		if (block == Blocks.GRAVEL || block == Blocks.CLAY || block == Blocks.SNOW_BLOCK
				|| block == Blocks.POWDER_SNOW || block == Blocks.ICE || block == Blocks.PACKED_ICE
				|| block == Blocks.BLUE_ICE || block == Blocks.MUD || block == Blocks.MOSS_BLOCK
				|| block == Blocks.CALCITE || block == Blocks.DRIPSTONE_BLOCK || block == Blocks.MAGMA_BLOCK
				|| block == Blocks.COBWEB
				// Nether and End terrain
				|| block == Blocks.SOUL_SAND || block == Blocks.SOUL_SOIL || block == Blocks.CRIMSON_NYLIUM
				|| block == Blocks.WARPED_NYLIUM || block == Blocks.GLOWSTONE || block == Blocks.ANCIENT_DEBRIS
				|| block == Blocks.END_STONE) {
			return true;
		}
		if (allowSandstone && (block == Blocks.SANDSTONE || block == Blocks.RED_SANDSTONE)) {
			return true;
		}
		return BuiltInRegistries.BLOCK.getKey(block).getPath().endsWith("_ore");
	}

	private static boolean removable(ServerLevel level, BlockPos pos, BlockState state) {
		return state.getDestroySpeed(level, pos) >= 0 && level.getBlockEntity(pos) == null;
	}

	private void set(int x, int y, int z, BlockState state) {
		BlockPos p = pos.set(x, y, z);
		BlockState old = level.getBlockState(p);
		if (old == state || !removable(level, p, old)) {
			return;
		}
		tally(old);
		changes.add(new Change(p.immutable(), old));
		level.setBlock(p, state, FLAGS);
	}

	private void tally(BlockState old) {
		if (old.isAir() || old.getBlock() instanceof LiquidBlock) {
			return;
		}
		// Double-height plants are two blocks but one item.
		if (old.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
				&& old.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
			return;
		}
		Item item = old.getBlock().asItem();
		if (item != net.minecraft.world.item.Items.AIR) {
			removed.merge(item, 1, Integer::sum);
		}
	}


	/**
	 * Puts everything that was removed into chests standing on the platform: rows along the box, one aisle
	 * between rows. Returns {chests placed, stacks that did not fit}.
	 */
	private int[] placeChests(int minX, int maxX, int minZ, int maxZ, int levelY) {
		List<ItemStack> stacks = new ArrayList<>();
		for (Map.Entry<Item, Integer> entry : removed.entrySet()) {
			int max = new ItemStack(entry.getKey()).getMaxStackSize();
			int left = entry.getValue();
			while (left > 0) {
				int n = Math.min(left, max);
				stacks.add(new ItemStack(entry.getKey(), n));
				left -= n;
			}
		}
		if (stacks.isEmpty()) {
			return new int[] {0, 0};
		}

		int chestY = levelY + 1;
		int next = 0;
		int placed = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int z = minZ; z <= maxZ && next < stacks.size(); z += 2) {
			for (int x = minX; x <= maxX && next < stacks.size(); x++) {
				BlockState here = level.getBlockState(p.set(x, chestY, z));
				BlockState below = level.getBlockState(p.set(x, levelY, z));
				BlockState above = level.getBlockState(p.set(x, chestY + 1, z));
				if (!here.isAir() || !above.isAir() || below.isAir() || !below.getFluidState().isEmpty()) {
					continue; // protected build, water, or no floor here
				}
				p.set(x, chestY, z);
				changes.add(new Change(p.immutable(), here));
				level.setBlock(p, Blocks.CHEST.defaultBlockState(), FLAGS);
				BlockEntity be = level.getBlockEntity(p);
				if (be instanceof net.minecraft.world.Container chest) {
					for (int slot = 0; slot < CHEST_SLOTS && next < stacks.size(); slot++) {
						chest.setItem(slot, stacks.get(next++));
					}
					be.setChanged();
				}
				placed++;
			}
		}
		return new int[] {placed, stacks.size() - next};
	}
}
