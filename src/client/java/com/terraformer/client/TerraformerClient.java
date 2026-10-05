package com.terraformer.client;

import net.fabricmc.api.ClientModInitializer;

public class TerraformerClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Nothing client-only yet; the wand reuses the vanilla stick model.
	}
}
