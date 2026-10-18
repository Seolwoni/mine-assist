package com.seolwoni.mineassist.hud;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.seolwoni.mineassist.MineAssistClient;
import com.seolwoni.mineassist.config.MineConfig;

import net.fabricmc.loader.api.FabricLoader;

/** Mining statistics: ores by type (deepslate variants merged), blocks, time, plugs, trips. */
public class MineStats {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("mineassist-stats.json");

	public static MineStats session = new MineStats();
	public static MineStats total = new MineStats();

	public static class Entry {
		public long count;
		public long lastMs;
	}

	/** key = normalized block id (deepslate_ removed) */
	public Map<String, Entry> ores = new LinkedHashMap<>();
	public long blocks;
	public long ticks;
	public long plugs;
	public long trips;

	public static MineStats shown() {
		return MineConfig.get().statsTotals ? total : session;
	}

	public static String normalize(String blockId) {
		return blockId.replace("deepslate_", "");
	}

	public long totalOres() {
		long n = 0;
		for (Entry e : ores.values()) n += e.count;
		return n;
	}

	public void reset() {
		ores.clear();
		blocks = ticks = plugs = trips = 0;
	}

	// ---- recording (both session and total) ----

	public static void ore(String blockId) {
		String key = normalize(blockId);
		long now = System.currentTimeMillis();
		for (MineStats s : new MineStats[] {session, total}) {
			Entry e = s.ores.computeIfAbsent(key, k -> new Entry());
			e.count++;
			e.lastMs = now;
		}
		saveTotal();
	}

	public static void block() {
		session.blocks++;
		total.blocks++;
	}

	public static void tick() {
		session.ticks++;
		total.ticks++;
		if (total.ticks % 1200 == 0) saveTotal();
	}

	public static void plug() {
		session.plugs++;
		total.plugs++;
	}

	public static void trip() {
		session.trips++;
		total.trips++;
		saveTotal();
	}

	public static void loadTotal() {
		try {
			if (Files.exists(PATH)) {
				try (Reader r = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
					MineStats s = GSON.fromJson(r, MineStats.class);
					if (s != null) {
						if (s.ores == null) s.ores = new LinkedHashMap<>();
						total = s;
					}
				}
			}
		} catch (Exception e) {
			MineAssistClient.LOGGER.warn("Failed to load stats", e);
		}
	}

	public static void saveTotal() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer w = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(total, w);
			}
		} catch (Exception e) {
			MineAssistClient.LOGGER.warn("Failed to save stats", e);
		}
	}

	/** Colour by ore rarity / look. */
	public static int color(String key) {
		if (key.contains("diamond")) return 0xFF55FFFF;
		if (key.contains("emerald")) return 0xFF55FF55;
		if (key.contains("ancient_debris")) return 0xFFC08A6A;
		if (key.contains("gold")) return 0xFFFFD84A;
		if (key.contains("redstone")) return 0xFFFF5555;
		if (key.contains("lapis")) return 0xFF6688FF;
		if (key.contains("copper")) return 0xFFE8875E;
		if (key.contains("iron")) return 0xFFDDB89C;
		if (key.contains("coal")) return 0xFFA0A0A0;
		if (key.contains("quartz")) return 0xFFEEEEEE;
		return 0xFFFFFFFF;
	}
}
