package com.seolwoni.mineassist.hud;

import java.util.ArrayList;
import java.util.List;

import com.seolwoni.mineassist.config.JobState;
import com.seolwoni.mineassist.config.MineConfig;
import com.seolwoni.mineassist.mining.Miner;
import com.seolwoni.mineassist.util.Inv;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** The status box: state, progress, start point, counts, pickaxe and torches. */
public final class MineHud {
	private static final int PAD = 3;
	private static final int GOLD = 0xFFFFAA00, WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA, GREEN = 0xFF55FF55, RED = 0xFFFF5555;
	public static int boxW = 140, boxH = 60;

	private record Seg(String t, int c) {
	}

	private MineHud() {
	}

	public static void render(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		MineConfig cfg = MineConfig.get();
		if (mc.player == null || !cfg.modEnabled || !cfg.statusHud) return;
		if (mc.gui.screen() instanceof HudEditScreen) return;
		draw(g, false);
	}

	public static void draw(GuiGraphicsExtractor g, boolean editing) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		MineConfig cfg = MineConfig.get();
		List<List<Seg>> lines = lines(mc);
		int w = 0;
		for (List<Seg> l : lines) {
			int lw = 0;
			for (Seg s : l) lw += font.width(s.t());
			w = Math.max(w, lw);
		}
		int lh = font.lineHeight + 1;
		boxW = w + PAD * 2;
		boxH = lines.size() * lh + PAD * 2 - 1;
		float[] b = bounds();
		g.pose().pushMatrix();
		g.pose().translate(b[0], b[1]);
		g.pose().scale(cfg.hudScale, cfg.hudScale);
		if (cfg.hudBgAlpha > 0) g.fill(0, 0, boxW, boxH, (cfg.hudBgAlpha & 0xFF) << 24);
		if (editing) g.outline(0, 0, boxW, boxH, 0xFFFFFF55);
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

	public static float[] bounds() {
		Minecraft mc = Minecraft.getInstance();
		MineConfig cfg = MineConfig.get();
		int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
		float w = boxW * cfg.hudScale, h = boxH * cfg.hudScale;
		float x = Math.max(0, Math.min(cfg.hudX * sw, sw - w));
		float y = Math.max(0, Math.min(cfg.hudY * sh, sh - h));
		return new float[] {x, y, w, h};
	}

	private static List<List<Seg>> lines(Minecraft mc) {
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
			out.add(List.of(new Seg("캔 블록: ", GOLD), new Seg(j.blocksMined + "", WHITE),
					new Seg("  광석: ", GOLD), new Seg(j.oresMined + "", WHITE)));
			out.add(List.of(new Seg("시간: ", GOLD), new Seg(time(j.ticks / 20), WHITE)));
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

	private static String time(long s) {
		long h = s / 3600, m = (s % 3600) / 60;
		return h > 0 ? h + "h " + m + "m" : m + "m " + (s % 60) + "s";
	}
}
