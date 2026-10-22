package com.seolwoni.mineassist.config;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.seolwoni.assistcore.storage.ChestEntry;
import com.seolwoni.mineassist.MineAssistClient;

import net.fabricmc.loader.api.FabricLoader;

/** All Mine Assist settings. Saved to config/mineassist.json. */
public class MineConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("mineassist.json");

	public static final List<String> DEFAULT_ORES = List.of(
			"minecraft:coal_ore", "minecraft:deepslate_coal_ore",
			"minecraft:iron_ore", "minecraft:deepslate_iron_ore",
			"minecraft:copper_ore", "minecraft:deepslate_copper_ore",
			"minecraft:gold_ore", "minecraft:deepslate_gold_ore",
			"minecraft:redstone_ore", "minecraft:deepslate_redstone_ore",
			"minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
			"minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
			"minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
			"minecraft:nether_gold_ore", "minecraft:nether_quartz_ore",
			"minecraft:ancient_debris");

	public static final List<String> NETHER_ORES = List.of("minecraft:ancient_debris", "minecraft:nether_quartz_ore", "minecraft:nether_gold_ore");
	// Must come after DEFAULT_ORES: the field initialisers below copy it.
	private static MineConfig instance = new MineConfig();

	// ---- Master / run ----
	public boolean modEnabled = true;
	public boolean running = false;

	// ---- Tunnel ----
	public int targetY = -58;
	public int mainLength = 30;
	public int branchLength = 300;
	public int branchSpacing = 3;
	public boolean resume = true;

	// ---- Timing ----
	public boolean humanDelay = true;
	/** Work pace: 0 = relaxed, 1 = normal, 2 = fast. */
	public int pace = 1;

	// ---- Ores ----
	public List<String> ores = new ArrayList<>(DEFAULT_ORES);
	public boolean mineFloorCeiling = true;
	/** Ore blocks that trigger a title + sound when found. */
	public List<String> alertOres = new ArrayList<>(List.of(
			"minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:ancient_debris",
			"minecraft:emerald_ore", "minecraft:deepslate_emerald_ore"));

	// ---- Pickaxe ----
	/** 0 = hand, 1 = hand (protect), 2 = whole hotbar, 3 = whole hotbar (protect). */
	public int pickMode = 3;
	public int protectDurability = 10;

	// ---- Torches ----
	public boolean torches = true;
	public int torchInterval = 10;
	public int torchKeep = 64;
	public boolean craftTorches = true;
	/** 0 = keep digging without torches, 1 = stop, 2 = fetch from chests. */
	public int torchOutAction = 2;
	public int coalKeep = 16;

	// ---- Safety ----
	public boolean stopOnMob = true;
	/** Mobs: 0 = avoid them (Defense), 1 = avoid and fight back (default for mining), 2 = stop the job, 3 = ignore. */
	public int mobMode = 1;
	/** How the side tunnels are dug: 0 = branches (walk in), 1 = poke holes (1x1 at eye level, depth pokeDepth, from the main tunnel). */
	public int digPattern = 0;
	public int pokeDepth = 4;
	public int mobRange = 8;
	public int minHealth = 8;
	public int plugKeep = 192;

	// ---- Nether (used instead of the overworld values when started in the Nether) ----
	public int netherTargetY = 15;
	public List<String> netherOres = new ArrayList<>(NETHER_ORES);
	/** 0 = branches, 1 = poke holes (the default: debris is rare, seeing more wall pays). */
	public int netherPattern = 1;
	/** Drink a fire resistance potion when burning or touching lava. */
	public boolean netherFireRes = false;
	/** Netherrack kept for plugging lava (in the Nether the plug blocks are netherrack). */
	public int netherPlugKeep = 64;
	/** Settings format version, for one-time migrations. */
	public int cfgVersion = 0;

	// ---- Food ----
	public boolean autoEat = true;
	public int eatThreshold = 14;
	/** Food to carry; topped up from the chests. */
	public int foodKeep = 16;

	// ---- Discard ----
	public boolean discardEnabled = true;
	public List<String> discardItems = new ArrayList<>(List.of(
			"minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:dirt", "minecraft:gravel",
			"minecraft:netherrack", "minecraft:basalt", "minecraft:blackstone"));

	// ---- Storage ----
	public boolean storeEnabled = true;
	/** 보관 방식: 0 상자, 1 엔더 상자, 2 셜커 상자, 3 자동 (see PortableStorage). */
	public int storeMode = 0;
	public List<ChestEntry> chests = new ArrayList<>();

	// ---- HUD ----
	public boolean statusHud = true;
	public float hudX = 0.01f;
	public float hudY = 0.2f;
	public float hudScale = 1.0f;
	public int hudBgAlpha = 110;
	public boolean statsHud = true;
	public float statsX = 0.01f;
	public float statsY = 0.45f;
	public float statsScale = 1.0f;
	/** false = this session, true = all-time totals. */
	public boolean statsTotals = false;


	public static MineConfig get() {
		return instance;
	}

	public static void load() {
		try {
			if (Files.exists(PATH)) {
				try (Reader r = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
					MineConfig c = GSON.fromJson(r, MineConfig.class);
					if (c != null) instance = c;
				}
			}
		} catch (Exception e) {
			MineAssistClient.LOGGER.warn("Failed to load config", e);
		}
		MineConfig c = instance;
		if (c.ores == null) c.ores = new ArrayList<>(DEFAULT_ORES);
		if (c.alertOres == null) c.alertOres = new ArrayList<>();
		if (c.discardItems == null) c.discardItems = new ArrayList<>();
		if (c.chests == null) c.chests = new ArrayList<>();
		for (ChestEntry e : c.chests) if (e.items == null) e.items = new ArrayList<>();
		c.running = false;
		if (c.cfgVersion < 1) {
			// 1.2.5: keep two stacks of cobblestone by default (caves now use a lot for floors and walls).
			if (c.plugKeep <= 64) c.plugKeep = 128;
			c.cfgVersion = 1;
		}
		if (c.cfgVersion < 2) {
			// 1.9.x: three stacks: caves are closed off before digging next to them.
			if (c.plugKeep <= 128) c.plugKeep = 192;
			// Nether mining: its stone is thrown away like cobblestone.
			for (String id : List.of("minecraft:netherrack", "minecraft:basalt", "minecraft:blackstone")) if (!c.discardItems.contains(id)) c.discardItems.add(id);
			c.cfgVersion = 2;
		}
		if (c.netherOres == null) c.netherOres = new ArrayList<>(NETHER_ORES);
		c.netherTargetY = clamp(c.netherTargetY, 1, 126);
		c.netherPattern = clamp(c.netherPattern, 0, 1);
		c.netherPlugKeep = clamp(c.netherPlugKeep, 0, 512);
		c.mainLength = clamp(c.mainLength, 3, 2000);
		c.pace = clamp(c.pace, 0, 2);
		c.foodKeep = clamp(c.foodKeep, 0, 64);
		c.branchLength = clamp(c.branchLength, 0, 2000);
		c.branchSpacing = clamp(c.branchSpacing, 2, 32);
		c.torchInterval = clamp(c.torchInterval, 3, 64);
		c.targetY = clamp(c.targetY, -64, 320);
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer w = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, w);
			}
		} catch (Exception e) {
			MineAssistClient.LOGGER.warn("Failed to save config", e);
		}
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
