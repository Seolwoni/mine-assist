package com.seolwoni.mineassist.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.seolwoni.mineassist.config.JobState;
import com.seolwoni.mineassist.config.MineConfig;
import com.seolwoni.mineassist.mining.Miner;
import com.seolwoni.mineassist.util.Inv;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Two HUD boxes: the status box and the ore statistics box. */
public final class MineHud {
	public enum Box { STATUS, STATS }

	private static final int PAD = 3;
	private static final int GOLD = 0xFFFFAA00, WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA, GREEN = 0xFF55FF55, RED = 0xFFFF5555;
	private static final int[] W = {140, 140}, H = {60, 60};

	private record Seg(String t, int c) {
	}

	private MineHud() {
	}

	public static boolean enabled(Box b) {
		MineConfig c = MineConfig.get();
		return b == Box.STATUS ? c.statusHud : c.statsHud;
	}

	public static void render(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		MineConfig cfg = MineConfig.get();
		if (mc.player == null || !cfg.modEnabled) return;
		if (mc.gui.screen() instanceof HudEditScreen) return;
		for (Box b : Box.values()) if (enabled(b)) draw(g, b, false);
	}

	public static float scale(Box b) {
		MineConfig c = MineConfig.get();
		return b == Box.STATUS ? c.hudScale : c.statsScale;
	}

	public static void setScale(Box b, float s) {
		MineConfig c = MineConfig.get();
		s = Math.round(Math.max(0.5f, Math.min(3f, s)) * 100f) / 100f;
		if (b == Box.STATUS) c.hudScale = s;
		else c.statsScale = s;
	}

	public static void setPos(Box b, float x, float y) {
		MineConfig c = MineConfig.get();
		if (b == Box.STATUS) {
			c.hudX = x;
			c.hudY = y;
		} else {
			c.statsX = x;
			c.statsY = y;
		}
	}

	public static int baseWidth(Box b) {
		return W[b.ordinal()];
	}

	public static void draw(GuiGraphicsExtractor g, Box box, boolean editing) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		MineConfig cfg = MineConfig.get();
		List<List<Seg>> lines = box == Box.STATUS ? statusLines(mc) : statsLines();
		int w = 0;
		for (List<Seg> l : lines) {
			int lw = 0;
			for (Seg s : l) lw += font.width(s.t());
			w = Math.max(w, lw);
		}
		int lh = font.lineHeight + 1;
		W[box.ordinal()] = w + PAD * 2;
		H[box.ordinal()] = lines.size() * lh + PAD * 2 - 1;
		float[] b = bounds(box);
		g.pose().pushMatrix();
		g.pose().translate(b[0], b[1]);
		g.pose().scale(scale(box), scale(box));
		if (cfg.hudBgAlpha > 0) g.fill(0, 0, W[box.ordinal()], H[box.ordinal()], (cfg.hudBgAlpha & 0xFF) << 24);
		if (editing) g.outline(0, 0, W[box.ordinal()], H[box.ordinal()], 0xFFFFFF55);
		int y = PAD;
		for (List<Seg> l : lines) {
			int x = PAD;
			for (Seg s : l) {
				g.text(font, Component.literal(s.t()).getVisualOrderText(), x, y, s.c(), true);
				x += font.width(s.t());
			}
			y += lh;
		}
		g.pose().popMatrix();
	}

	public static float[] bounds(Box box) {
		Minecraft mc = Minecraft.getInstance();
		MineConfig cfg = MineConfig.get();
		int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
		float s = scale(box);
		float w = W[box.ordinal()] * s, h = H[box.ordinal()] * s;
		float fx = box == Box.STATUS ? cfg.hudX : cfg.statsX;
		float fy = box == Box.STATUS ? cfg.hudY : cfg.statsY;
		float x = Math.max(0, Math.min(fx * sw, sw - w));
		float y = Math.max(0, Math.min(fy * sh, sh - h));
		return new float[] {x, y, w, h};
	}

	// ------------------------------------------------------------------ status box

	private static List<List<Seg>> statusLines(Minecraft mc) {
		MineConfig cfg = MineConfig.get();
		JobState j = Miner.job();
		List<List<Seg>> out = new ArrayList<>();
		out.add(List.of(new Seg("Mine Assist", GOLD)));
		out.add(List.of(new Seg("상태: ", GOLD), new Seg(Miner.status(), cfg.running ? GREEN : RED)));
		if (j.active) {
			String prog = "가운데 " + j.mainK + "/" + j.mainLength;
			if (j.branchSpacing > 0 && j.mainK > 0 && j.mainK % j.branchSpacing == 0 && j.branchSide < 2 && j.branchD > 0) {
				prog += " · " + (j.branchSide == 0 ? "왼쪽" : "오른쪽") + " 가지 " + j.branchD + "/" + j.branchLength;
			}
			int stairs = Math.max(0, j.oy - j.targetY);
			if (j.stairsDone < stairs) prog = "계단 " + j.stairsDone + "/" + stairs;
			out.add(List.of(new Seg("진행: ", GOLD), new Seg(prog, WHITE)));
			out.add(List.of(new Seg("시작 지점: ", GOLD), new Seg(j.ox + ", " + j.oy + ", " + j.oz, WHITE)));
		}
		if (mc.player != null) {
			ItemStack hand = mc.player.getMainHandItem();
			if (Inv.isPick(hand) && hand.isDamageableItem()) {
				int r = Inv.remaining(hand);
				out.add(List.of(new Seg("곡괭이: ", GOLD), new Seg(r + "/" + hand.getMaxDamage(), r <= cfg.protectDurability ? RED : WHITE)));
			}
			if (cfg.torches) {
				int t = Inv.count(mc.player, Inv::isTorch);
				out.add(List.of(new Seg("횃불: ", GOLD), new Seg(t + "개", t == 0 ? RED : WHITE)));
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ stats box

	private static String name(String key) {
		try {
			return BuiltInRegistries.BLOCK.getValue(Identifier.parse(key)).getName().getString();
		} catch (Exception e) {
			return key;
		}
	}

	private static List<List<Seg>> statsLines() {
		MineConfig cfg = MineConfig.get();
		MineStats st = MineStats.shown();
		List<List<Seg>> out = new ArrayList<>();
		out.add(List.of(new Seg("채광 통계 ", GOLD), new Seg(cfg.statsTotals ? "(누적)" : "(이번 세션)", GRAY)));
		long total = st.totalOres();
		long now = System.currentTimeMillis();
		List<Map.Entry<String, MineStats.Entry>> list = new ArrayList<>(st.ores.entrySet());
		list.sort(Comparator.comparingLong((Map.Entry<String, MineStats.Entry> e) -> e.getValue().count).reversed());
		if (list.isEmpty()) out.add(List.of(new Seg("  아직 캔 광석이 없어요", GRAY)));
		for (Map.Entry<String, MineStats.Entry> e : list) {
			MineStats.Entry v = e.getValue();
			out.add(List.of(
					new Seg(ago(now - v.lastMs) + " | ", GRAY),
					new Seg(name(e.getKey()), MineStats.color(e.getKey())),
					new Seg(": " + v.count, WHITE),
					new Seg(" (" + pct(v.count, total) + ")", GRAY)));
		}
		double hours = st.ticks / 20.0 / 3600.0;
		long perHour = hours > 0.01 ? Math.round(total / hours) : 0;
		out.add(List.of(new Seg("총 광석: ", GOLD), new Seg(total + "", WHITE), new Seg(" [" + perHour + "/시간]", GRAY)));
		out.add(List.of(new Seg("캔 블록: ", GOLD), new Seg(st.blocks + "", WHITE)));
		out.add(List.of(new Seg("막은 용암·물: ", GOLD), new Seg(st.plugs + "", WHITE),
				new Seg("  보관: ", GOLD), new Seg(st.trips + "번", WHITE)));
		out.add(List.of(new Seg("채광 시간: ", GOLD), new Seg(time(st.ticks / 20), WHITE)));
		return out;
	}

	private static String pct(long part, long whole) {
		return whole <= 0 ? "0%" : String.format("%.1f%%", part * 100.0 / whole);
	}

	private static String ago(long ms) {
		long s = ms / 1000;
		if (s < 60) return s + "s";
		if (s < 3600) return String.format("%.1fm", s / 60.0);
		if (s < 86400) return String.format("%.1fh", s / 3600.0);
		return String.format("%.1fd", s / 86400.0);
	}

	private static String time(long s) {
		long h = s / 3600, m = (s % 3600) / 60;
		return h > 0 ? h + "h " + m + "m" : m + "m " + (s % 60) + "s";
	}
}
