package com.seolwoni.mineassist.hud;

import com.seolwoni.mineassist.config.MineConfig;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Drag the HUD box to move it; scroll over it or drag the yellow corner to resize. */
public class HudEditScreen extends Screen {
	private static final int HANDLE = 6;
	private final Screen parent;
	private boolean dragging, resizing;
	private double grabDx, grabDy;

	public HudEditScreen(Screen parent) {
		super(Component.literal("HUD 위치 편집"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("완료"), b -> onClose())
				.bounds(this.width / 2 - 50, this.height - 28, 100, 20).build());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		MineHud.draw(g, true);
		float[] b = MineHud.bounds();
		int hx = (int) (b[0] + b[2]) - HANDLE, hy = (int) (b[1] + b[3]) - HANDLE;
		g.fill(hx, hy, hx + HANDLE, hy + HANDLE, 0xFFFFFF55);
		g.text(this.font, Component.literal(Math.round(MineConfig.get().hudScale * 100) + "%").getVisualOrderText(),
				(int) b[0], (int) (b[1] + b[3]) + 2, 0xFFFFFF55, true);
		g.centeredText(this.font, Component.literal("드래그로 이동 · 노란 칸을 끌거나 휠로 크기 조절"), this.width / 2, 8, 0xFFFFFFFF);
	}

	private static boolean inside(double mx, double my) {
		float[] b = MineHud.bounds();
		return mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3];
	}

	private static boolean onHandle(double mx, double my) {
		float[] b = MineHud.bounds();
		double hx = b[0] + b[2], hy = b[1] + b[3];
		return mx >= hx - HANDLE - 2 && mx <= hx + 2 && my >= hy - HANDLE - 2 && my <= hy + 2;
	}

	private static void setScale(float s) {
		MineConfig.get().hudScale = Math.round(Math.max(0.5f, Math.min(3f, s)) * 100f) / 100f;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
		if (super.mouseClicked(e, dbl)) return true;
		if (onHandle(e.x(), e.y())) {
			resizing = true;
			return true;
		}
		if (inside(e.x(), e.y())) {
			float[] b = MineHud.bounds();
			dragging = true;
			grabDx = e.x() - b[0];
			grabDy = e.y() - b[1];
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		MineConfig cfg = MineConfig.get();
		if (resizing) {
			float[] b = MineHud.bounds();
			if (MineHud.boxW > 0) setScale((float) ((e.x() - b[0]) / MineHud.boxW));
			return true;
		}
		if (dragging) {
			cfg.hudX = (float) Math.max(0, Math.min(1, (e.x() - grabDx) / this.width));
			cfg.hudY = (float) Math.max(0, Math.min(1, (e.y() - grabDy) / this.height));
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (dragging || resizing) {
			dragging = resizing = false;
			MineConfig.save();
			return true;
		}
		return super.mouseReleased(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (inside(mx, my)) {
			setScale(MineConfig.get().hudScale + (sy > 0 ? 0.1f : -0.1f));
			MineConfig.save();
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	@Override
	public void onClose() {
		MineConfig.save();
		this.minecraft.gui.setScreen(parent);
	}
}
