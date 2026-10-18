package com.seolwoni.mineassist;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;
import com.seolwoni.mineassist.config.MineConfig;
import com.seolwoni.mineassist.hud.MineHud;
import com.seolwoni.mineassist.mining.Miner;
import com.seolwoni.mineassist.mining.Storage;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class MineAssistClient implements ClientModInitializer {
	public static final String MOD_ID = "mineassist";
	public static final Logger LOGGER = LoggerFactory.getLogger("Mine Assist");

	public static KeyMapping markChestKey;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitializeClient() {
		MineConfig.load();
		Miner.loadJob();

		KeyMapping.Category category = KeyMapping.Category.register(id("main"));
		markChestKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.mineassist.mark_chest", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (markChestKey.consumeClick()) {
				if (MineConfig.get().modEnabled) Storage.markLookedAt(client);
			}
			if (MineConfig.get().modEnabled) Miner.tick(client);
		});

		HudElementRegistry.addLast(id("hud"), (graphics, delta) -> MineHud.render(graphics));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			if (MineConfig.get().running) Miner.stop(null, null, false);
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			if (MineConfig.get().running) Miner.stop(null, null, false);
			MineConfig.save();
		});

		LOGGER.info("Mine Assist loaded - made by seolwoni");
	}
}
