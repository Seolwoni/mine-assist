package com.seolwoni.assistcore.log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * The worker's journal, shared by all Assist mods: one entry per job (from switching on to stopping), with when and
 * where, why it stopped, what came into the bag, what went into chests, what was used up (blocks put, torches), and
 * the scary moments (hurt, falls). Kept on this computer in {@code assist-journal/journal.jsonl}; the newest entries
 * show on the journal screen.
 *
 * Jobs are followed through {@link UsageLog#start} and {@link UsageLog#end}, which every mod already calls: the bag is
 * looked at every tick in between. A count that goes up is "got" (or "taken from a chest" while one is open), one
 * that goes down is "used" (or "stored" while a chest is open).
 */
public final class Journal {
	private Journal() {
	}

	/** One job. */
	public static final class Entry {
		public String mod;
		public long start, end;
		public String dim, from, to;
		public String reason = "", summary = "", what = "";
		public Map<String, Integer> got = new LinkedHashMap<>(), used = new LinkedHashMap<>(), stored = new LinkedHashMap<>(), taken = new LinkedHashMap<>();
		public int hurt, falls;
		public float lowestHealth = 20;
		public boolean died;
	}

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final int KEEP = 300;
	private static final Map<String, Entry> open = new HashMap<>();
	private static Map<String, Integer> last;
	private static float lastHealth = -1;
	private static double fallFrom = Double.NaN;
	private static List<Entry> cache;

	static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("assist-journal").resolve("journal.jsonl");
	}

	/** "overworld", "the_nether", "the_end" (from the level's key, whatever the version calls its parts). */
	private static String dimName(Minecraft mc) {
		if (mc.level == null) return "";
		String k = String.valueOf(mc.level.dimension());
		int slash = k.lastIndexOf('/'), colon = k.lastIndexOf(':');
		String s = k.substring(Math.max(slash, colon) + 1).replace("]", "").trim();
		return s;
	}

	private static String pos(LocalPlayer p) {
		return p == null ? "" : p.blockPosition().getX() + ", " + p.blockPosition().getY() + ", " + p.blockPosition().getZ();
	}

	static void begin(String mod, String what) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		Entry e = new Entry();
		e.mod = mod;
		e.what = what == null ? "" : what;
		e.start = System.currentTimeMillis();
		e.from = pos(p);
		e.dim = dimName(mc);
		if (p != null) e.lowestHealth = p.getHealth();
		open.put(mod, e);
		if (open.size() == 1) {
			last = p == null ? null : counts(p);
			lastHealth = p == null ? -1 : p.getHealth();
			fallFrom = Double.NaN;
		}
	}

	static void finish(String mod, String reason, String summary) {
		Entry e = open.remove(mod);
		if (e == null) return;
		LocalPlayer p = Minecraft.getInstance().player;
		e.end = System.currentTimeMillis();
		e.to = pos(p);
		e.reason = reason == null ? "" : reason;
		e.summary = summary == null ? "" : summary;
		if (p != null && p.isDeadOrDying()) e.died = true;
		write(e);
	}

	/** Every client tick: the bag, health and falls, for the jobs running now. */
	public static void tick(Minecraft mc) {
		if (open.isEmpty() || mc.player == null) return;
		LocalPlayer p = mc.player;
		Map<String, Integer> now = counts(p);
		if (last != null) {
			boolean chest = mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s
					&& !(s instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen);
			Map<String, Integer> delta = new HashMap<>(now);
			for (Map.Entry<String, Integer> o : last.entrySet()) delta.merge(o.getKey(), -o.getValue(), Integer::sum);
			for (Map.Entry<String, Integer> d : delta.entrySet()) {
				int n = d.getValue();
				if (n == 0) continue;
				for (Entry e : open.values()) {
					if (n > 0) (chest ? e.taken : e.got).merge(d.getKey(), n, Integer::sum);
					else (chest ? e.stored : e.used).merge(d.getKey(), -n, Integer::sum);
				}
			}
		}
		last = now;
		float h = p.getHealth();
		for (Entry e : open.values()) {
			if (lastHealth >= 0 && h < lastHealth - 0.01f) e.hurt++;
			e.lowestHealth = Math.min(e.lowestHealth, h);
			if (p.isDeadOrDying()) e.died = true;
		}
		lastHealth = h;
		// A fall: off the ground more than three blocks down, then landing.
		if (!p.onGround() && !p.isInWater() && !p.getAbilities().flying) {
			if (Double.isNaN(fallFrom) || p.getY() > fallFrom) fallFrom = p.getY();
		} else {
			if (!Double.isNaN(fallFrom) && fallFrom - p.getY() > 3.5) for (Entry e : open.values()) e.falls++;
			fallFrom = Double.NaN;
		}
	}

	private static Map<String, Integer> counts(LocalPlayer p) {
		Map<String, Integer> m = new HashMap<>();
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			m.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
		}
		ItemStack carried = p.containerMenu == null ? ItemStack.EMPTY : p.containerMenu.getCarried();
		if (!carried.isEmpty()) m.merge(BuiltInRegistries.ITEM.getKey(carried.getItem()).toString(), carried.getCount(), Integer::sum);
		return m;
	}

	private static synchronized void write(Entry e) {
		try {
			Path f = file();
			Files.createDirectories(f.getParent());
			Files.writeString(f, GSON.toJson(e) + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
			if (lines.size() > KEEP + 100) Files.write(f, lines.subList(lines.size() - KEEP, lines.size()), StandardCharsets.UTF_8);
			cache = null;
		} catch (IOException | RuntimeException ignored) {
			// The journal must never break the game.
		}
	}

	/** The entries, newest first (of one mod, or all when {@code mod} is null). */
	public static synchronized List<Entry> entries(String mod) {
		if (cache == null) {
			List<Entry> all = new ArrayList<>();
			try {
				if (Files.exists(file())) {
					for (String l : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
						try {
							Entry e = GSON.fromJson(l, Entry.class);
							if (e != null && e.mod != null) all.add(e);
						} catch (RuntimeException ignored) {
						}
					}
				}
			} catch (IOException ignored) {
			}
			Collections.reverse(all);
			cache = all;
		}
		if (mod == null) return cache;
		List<Entry> out = new ArrayList<>();
		for (Entry e : cache) if (mod.equals(e.mod)) out.add(e);
		return out;
	}

	/** A job is running for this mod now (its entry is not written yet). */
	public static boolean running(String mod) {
		return open.containsKey(mod);
	}
}
