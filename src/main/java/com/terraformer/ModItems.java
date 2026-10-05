package com.terraformer;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

public final class ModItems {
	public static final Item TERRAFORM_WAND = register("terraform_wand",
		key -> new TerraformWandItem(new Item.Properties()
			.setId(key)
			.stacksTo(1)
			// Shimmer so it reads as a "special" stick.
			.component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)));

	private ModItems() {
	}

	private static Item register(String name, java.util.function.Function<ResourceKey<Item>, Item> factory) {
		Identifier id = Terraformer.id(name);
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
		return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(key));
	}

	/** Forces class loading so the items register during mod initialization. */
	public static void init() {
	}
}
