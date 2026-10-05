# Terraformer

Fabric mod for Minecraft 26.3. Every player gets a **Terraform Wand** (an enchant-glinting stick) the first time they join.

- **Right-click a block**: marks the first corner. Its Y becomes the height of the flattened platform.
- **Right-click a second block**: flattens the rectangle between the two corners to that height. Hills are cut away, holes are filled, and trees/plants in the way are cleared.
- A **12-block ring of 1-block steps** (1 block of height per block of distance) blends the platform into the surrounding terrain.
- **Sneak + right-click** clears a pending first corner.
- `/terraform undo` reverts your last terraform. `/terraform wand` gives you another wand.

**Removed blocks** are collected into chests placed on the platform (rows along the box, one aisle between rows). Stacks that don't fit are reported.

**Player builds are protected.** Minecraft doesn't record who placed a block, so the wand only touches what looks natural (dirt, sand, stone, ores, netherrack, soul sand/soil, nylium, end stone, plants, wild leaves, trees, huge fungi). Any column that contains anything else (planks, glass, torches, chests, placed leaves, bare log pillars...) is left completely untouched, along with anything buried beneath it. Limitations: a roof or wall made of plain dirt/stone is indistinguishable from terrain, and a hut sitting on the platform makes its columns stay at their old height.

Limits: platform sides up to 96 blocks. Blocks with block entities and unbreakable blocks are left untouched.

Tunables live at the top of `TerraformJob.java` (`MARGIN`, `MAX_SIDE`).

## Building

JDK 25 required: `./gradlew build`. Jar lands in `build/libs/`. Run the dev client with `./gradlew runClient`.

## License

CC0-1.0 (from the Fabric template).

## Dimensions

Works in the Overworld, Nether and End. The Nether has a bedrock roof, so there the wand looks for the surface nearest the platform height and carves up to 16 blocks of headroom (`NETHER_HEADROOM`) where the platform cuts into solid rock. The End's obsidian pillars count as builds and are left alone.
