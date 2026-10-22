package com.seolwoni.mineassist.hud;

import com.seolwoni.assistcore.hud.HudEditScreen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.seolwoni.assistcore.hud.AssistHud;
import com.seolwoni.assistcore.hud.HudBox;
import com.seolwoni.assistcore.hud.HudBox.Seg;
import com.seolwoni.mineassist.config.JobState;
import com.seolwoni.mineassist.config.MineConfig;
import com.seolwoni.mineassist.mining.Miner;
import com.seolwoni.mineassist.util.Inv;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Two HUD boxes: the status box and the ore statistics box. Drawing, moving and hiding come from Assist Core. */
public final class MineHud {
	private static final int GOLD = HudBox.GOLD, WHITE = HudBox.WHITE, GRAY = HudBox.GRAY, GREEN = HudBox.GREEN, RED = HudBox.RED;

	public static final HudBox STATUS = new HudBox() {
		@Override
		public boolean enabled() {
			return MineConfig.get().modEnabled && MineConfig.get().statusHud;
		}

		@Override
		public List<List<Seg>> lines() {
			return statusLines(Minecraft.getInstance());
		}

		@Override
		public float x() {
			return MineConfig.get().hudX;
		}

		@Override
		public float y() {
			return MineConfig.get().hudY;
		}

		@Override
		public float scale() {
			return MineConfig.get().hudScale;
		}

		@Override
		public void setPos(float x, float y) {
			MineConfig.get().hudX = x;
			MineConfig.get().hudY = y;
		}

		@Override
		protected void storeScale(float s) {
			MineConfig.get().hudScale = s;
		}

		@Override
		public int bgAlpha() {
			return MineConfig.get().hudBgAlpha;
		}

		@Override
		public void save() {
			MineConfig.save();
		}
	};

	public static final HudBox STATS = new HudBox() {
		@Override
		public boolean enabled() {
			return MineConfig.get().modEnabled && MineConfig.get().statsHud;
		}

		@Override
		public List<List<Seg>> lines() {
			return statsLines();
		}

		@Override
		public float x() {
			return MineConfig.get().statsX;
		}

		@Override
		public float y() {
			return MineConfig.get().statsY;
		}

		@Override
		public float scale() {
			return MineConfig.get().statsScale;
		}

		@Override
		public void setPos(float x, float y) {
			MineConfig.get().statsX = x;
			MineConfig.get().statsY = y;
		}

		@Override
		protected void storeScale(float s) {
			MineConfig.get().statsScale = s;
		}

		@Override
		public int bgAlpha() {
			return MineConfig.get().hudBgAlpha;
		}

		@Override
		public void save() {
			MineConfig.save();
		}
	};

	private MineHud() {
	}

	public static void register() {
		AssistHud.register(STATUS);
		AssistHud.register(STATS);
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
			int stairs = Math.abs(j.oy - j.targetY);
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
