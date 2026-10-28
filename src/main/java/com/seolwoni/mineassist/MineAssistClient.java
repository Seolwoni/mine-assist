package com.seolwoni.mineassist;

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
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class MineAssistClient implements ClientModInitializer {
	public static final String MOD_ID = "mineassist";
	public static final Logger LOGGER = LoggerFactory.getLogger("Mine Assist");

	public static KeyMapping markChestKey;
	/** Go back to the start (no key by default: set one in Controls). */
	public static KeyMapping returnKey;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitializeClient() {
		MineConfig.load();
		Miner.loadJob();
		com.seolwoni.mineassist.hud.MineStats.loadTotal();

		KeyMapping.Category category = KeyMapping.Category.register(id("main"));
		markChestKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.mineassist.mark_chest", InputConstants.Type.KEYBOARD, com.mojang.blaze3d.platform.InputConstants.KEY_K, category));
		returnKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.mineassist.return_home", InputConstants.Type.KEYBOARD, com.mojang.blaze3d.platform.InputConstants.KEY_UNKNOWN, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (markChestKey.consumeClick()) {
				if (MineConfig.get().modEnabled) Storage.markLookedAt(client);
			}
			while (returnKey.consumeClick()) {
				if (MineConfig.get().running) Miner.returnHome(client);
			}
			if (MineConfig.get().modEnabled) Miner.tick(client);
		});

		MineHud.register();

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			if (MineConfig.get().running) Miner.stop(null, null, false);
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			if (MineConfig.get().running) Miner.stop(null, null, false);
			MineConfig.save();
			com.seolwoni.mineassist.hud.MineStats.saveTotal();
		});

		LOGGER.info("Mine Assist loaded - made by seolwoni");
	}
}
