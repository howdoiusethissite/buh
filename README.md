# Terraformer

Fabric mod for Minecraft 26.3. Every player gets a **Terraform Wand** (an enchant-glinting stick) the first time they join.

- **Right-click a block**: marks the first corner. Its Y becomes the height of the flattened platform.
- **Right-click a second block**: flattens the rectangle between the two corners to that height. Hills are cut away, holes are filled, and trees/plants in the way are cleared.
- A **12-block ring of 1-block steps** (1 block of height per block of distance) blends the platform into the surrounding terrain.
- **Sneak + right-click** clears a pending first corner.
- `/terraform undo` reverts your last terraform. `/terraform wand` gives you another wand.

Limits: platform sides up to 96 blocks. Blocks with block entities (chests, etc.) and unbreakable blocks are left untouched.

Tunables live at the top of `TerraformJob.java` (`MARGIN`, `MAX_SIDE`).

## Building

JDK 25 required: `./gradlew build`. Jar lands in `build/libs/`. Run the dev client with `./gradlew runClient`.

## License

CC0-1.0 (from the Fabric template).
