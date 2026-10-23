package com.seolwoni.assistcore.gui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.seolwoni.assistcore.log.Journal;
import com.seolwoni.assistcore.log.UsageLog;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The worker's journal: the latest jobs, newest first. Each shows when and how long, where, why it stopped, what was
 * got, stored and used, and anything dangerous. One mod's jobs, or all of them.
 */
public class JournalScreen extends Screen {
	private static final int LINE = 11;
	private final Screen parent;
	private final String mod;
	private boolean all;
	private int page;
	private final List<Object[]> lines = new ArrayList<>();

	/** {@code mod}: "mine", "reel", "lumber", "farm", "build" (or null for all of them). */
	public JournalScreen(Screen parent, String mod) {
		super(Component.literal("작업 일지"));
		this.parent = parent;
		this.mod = mod;
		this.all = mod == null;
	}

	static String modName(String m) {
		return switch (m) {
			case "mine" -> "마인";
			case "reel" -> "릴";
			case "lumber" -> "럼버";
			case "farm" -> "팜";
			case "build" -> "빌드";
			default -> m;
		};
	}

	static int modColor(String m) {
		return switch (m) {
			case "mine" -> 0xFF9FD3FF;
			case "reel" -> 0xFF7FE0E0;
			case "lumber" -> 0xFFC8A060;
			case "farm" -> 0xFF9BE07A;
			case "build" -> 0xFFE8A07A;
			default -> 0xFFFFFFFF;
		};
	}

	static String itemName(String id) {
		try {
			var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
			return new ItemStack(item).getHoverName().getString();
		} catch (Exception e) {
			return id.replace("minecraft:", "");
		}
	}

	/** The biggest few of a list of items, "이름 수 · 이름 수 …". */
	static String items(Map<String, Integer> m, int max) {
		if (m == null || m.isEmpty()) return "";
		List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
		l.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (Map.Entry<String, Integer> e : l) {
			if (e.getValue() <= 0) continue;
			if (n == max) {
				sb.append(" 외 ").append(l.size() - max).append("가지");
				break;
			}
			if (n > 0) sb.append(" · ");
			sb.append(itemName(e.getKey())).append(' ').append(e.getValue());
			n++;
		}
		return sb.toString();
	}

	static String dimName(String d) {
		if (d == null) return "";
		return switch (d) {
			case "overworld" -> "오버월드";
			case "the_nether" -> "네더";
			case "the_end" -> "엔드";
			default -> d;
		};
	}

	@Override
	protected void init() {
		lines.clear();
		int left = this.width / 2 - 170, w = 340;
		addRenderableWidget(Button.builder(Component.literal("완료"), b -> onClose()).bounds(this.width / 2 + 120, 4, 50, 18).build());
		if (mod != null) {
			addRenderableWidget(Button.builder(Component.literal(all ? "전체 모드" : modName(mod) + "만"), b -> {
				all = !all;
				page = 0;
				rebuildWidgets();
			}).bounds(left, 4, 70, 18).build());
		}
		List<Journal.Entry> list = Journal.entries(all ? null : mod);
		int y = 28;
		int per = Math.max(1, (this.height - y - 30) / (LINE * 5 + 4));
		int pages = Math.max(1, (list.size() + per - 1) / per);
		page = Math.max(0, Math.min(page, pages - 1));
		if (list.isEmpty()) {
			lines.add(new Object[] {"아직 기록이 없어요. 모드를 켜서 일을 시키면 끝날 때마다 여기에 남아요.", this.width / 2, y + 10, 0xFFAAAAAA, true});
		}
		SimpleDateFormat day = new SimpleDateFormat("M/d HH:mm"), time = new SimpleDateFormat("HH:mm");
		for (int i = page * per; i < Math.min(list.size(), (page + 1) * per); i++) {
			Journal.Entry e = list.get(i);
			String head = "[" + modName(e.mod) + "] " + day.format(new Date(e.start)) + " → " + time.format(new Date(e.end)) + " (" + UsageLog.duration(e.end - e.start) + ") · "
					+ dimName(e.dim) + " " + e.from;
			lines.add(new Object[] {head, left, y, modColor(e.mod), false});
			lines.add(new Object[] {"  끝: " + shorten(e.reason, 60), left, y + LINE, e.died ? 0xFFFF5555 : 0xFFDDDDDD, false});
			String got = items(e.got, 5);
			lines.add(new Object[] {"  얻음: " + (got.isEmpty() ? "-" : got), left, y + 2 * LINE, 0xFF55FF55, false});
			String st = items(e.stored, 4), used = items(e.used, 4);
			lines.add(new Object[] {"  상자에: " + (st.isEmpty() ? "-" : st) + "   씀: " + (used.isEmpty() ? "-" : used), left, y + 3 * LINE, 0xFFBBBBBB, false});
			String danger = e.died ? "죽었어요" : (e.hurt == 0 && e.falls == 0 ? "없음" : (e.hurt > 0 ? "다침 " + e.hurt + "번 (가장 낮은 체력 " + Math.round(e.lowestHealth) + ")" : "")
					+ (e.falls > 0 ? (e.hurt > 0 ? " · " : "") + "떨어짐 " + e.falls + "번" : ""));
			lines.add(new Object[] {"  위험: " + danger, left, y + 4 * LINE, e.died || e.hurt > 0 ? 0xFFFFAA55 : 0xFF888888, false});
			y += LINE * 5 + 4;
		}
		if (pages > 1) {
			int by = this.height - 24;
			addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
				page--;
				rebuildWidgets();
			}).bounds(left, by, 30, 18).build()).active = page > 0;
			addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
				page++;
				rebuildWidgets();
			}).bounds(left + w - 30, by, 30, 18).build()).active = page < pages - 1;
			lines.add(new Object[] {(page + 1) + "/" + pages + " 쪽 · 모두 " + list.size() + "번", this.width / 2, by + 5, 0xFFAAAAAA, true});
		}
	}

	private static String shorten(String s, int max) {
		if (s == null) return "";
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(this.font, this.title, this.width / 2, 9, 0xFFFFFFFF);
		for (Object[] l : lines) {
			if ((boolean) l[4]) g.centeredText(this.font, Component.literal((String) l[0]), (int) l[1], (int) l[2], (int) l[3]);
			else g.text(this.font, Component.literal((String) l[0]).getVisualOrderText(), (int) l[1], (int) l[2], (int) l[3], true);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}
}
