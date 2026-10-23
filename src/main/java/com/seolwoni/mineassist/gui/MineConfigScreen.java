package com.seolwoni.mineassist.gui;

import com.seolwoni.assistcore.gui.PickerScreen;

import com.seolwoni.assistcore.gui.IntSlider;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.seolwoni.mineassist.config.JobState;
import com.seolwoni.mineassist.config.MineConfig;
import com.seolwoni.assistcore.storage.ChestEntry;
import com.seolwoni.assistcore.hud.HudEditScreen;
import com.seolwoni.mineassist.mining.Miner;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Mine Assist settings (opened from Mod Menu). Start/stop sits at the very top. */
public class MineConfigScreen extends Screen {
	private static final String[] TABS = {com.seolwoni.assistcore.i18n.Tr.t("기본"), com.seolwoni.assistcore.i18n.Tr.t("광석"), com.seolwoni.assistcore.i18n.Tr.t("곡괭이"), com.seolwoni.assistcore.i18n.Tr.t("횃불"), com.seolwoni.assistcore.i18n.Tr.t("안전·음식"), com.seolwoni.assistcore.i18n.Tr.t("버리기"), com.seolwoni.assistcore.i18n.Tr.t("보관"), "HUD", com.seolwoni.assistcore.i18n.Tr.t("네더")};
	private static final String[] PICK_MODES = {com.seolwoni.assistcore.i18n.Tr.t("손에 든 곡괭이"), com.seolwoni.assistcore.i18n.Tr.t("손에 든 곡괭이 (파괴보호)"), com.seolwoni.assistcore.i18n.Tr.t("핫바 곡괭이 전부"), com.seolwoni.assistcore.i18n.Tr.t("핫바 곡괭이 전부 (파괴보호)")};
	private static final String[] TORCH_OUT = {com.seolwoni.assistcore.i18n.Tr.t("횃불 없이 계속"), com.seolwoni.assistcore.i18n.Tr.t("멈추고 알림"), com.seolwoni.assistcore.i18n.Tr.t("상자에서 꺼내오기")};
	private static final List<String> ITEM_PRESETS = List.of(
			"minecraft:coal", "minecraft:raw_iron", "minecraft:raw_copper", "minecraft:raw_gold",
			"minecraft:redstone", "minecraft:lapis_lazuli", "minecraft:diamond", "minecraft:emerald",
			"minecraft:ancient_debris", "minecraft:quartz", "minecraft:gold_nugget", "minecraft:amethyst_shard",
			"minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:dirt", "minecraft:gravel",
			"minecraft:flint", "minecraft:tuff", "minecraft:granite", "minecraft:diorite", "minecraft:andesite",
			"minecraft:calcite", "minecraft:netherrack", "minecraft:clay_ball");
	private static final int ROW = 24;
	/** name, best Y, note. The last entry is "current height" (Y taken from the player). */
	private static final Object[][] Y_PRESETS = {
			{com.seolwoni.assistcore.i18n.Tr.t("다이아"), -58, com.seolwoni.assistcore.i18n.Tr.t(" (레드스톤도 많이 나와요)")},
			{com.seolwoni.assistcore.i18n.Tr.t("레드스톤"), -58, ""},
			{com.seolwoni.assistcore.i18n.Tr.t("금"), -16, com.seolwoni.assistcore.i18n.Tr.t(" (악지는 지상에도 많아요)")},
			{com.seolwoni.assistcore.i18n.Tr.t("청금석"), 0, ""},
			{com.seolwoni.assistcore.i18n.Tr.t("철"), 16, ""},
			{com.seolwoni.assistcore.i18n.Tr.t("구리"), 48, ""},
			{com.seolwoni.assistcore.i18n.Tr.t("석탄"), 96, com.seolwoni.assistcore.i18n.Tr.t(" (산 지형)")},
			{com.seolwoni.assistcore.i18n.Tr.t("에메랄드"), 232, com.seolwoni.assistcore.i18n.Tr.t(" (산 바이옴 전용)")},
			{com.seolwoni.assistcore.i18n.Tr.t("고대 잔해"), 15, com.seolwoni.assistcore.i18n.Tr.t(" (네더에서)")},
			{com.seolwoni.assistcore.i18n.Tr.t("지금 높이"), 0, com.seolwoni.assistcore.i18n.Tr.t(" (계단 없이 바로 파기)")}};

	private static int tab = 0;
	private final Screen parent;
	private final List<Object[]> labels = new ArrayList<>();
	private boolean confirmReset, confirmChests, confirmStats;

	public MineConfigScreen(Screen parent) {
		super(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("Mine Assist 설정")));
		this.parent = parent;
	}

	private MineConfig c() {
		return MineConfig.get();
	}

	@Override
	protected void init() {
		labels.clear();
		int cx = this.width / 2, left = cx - 155, right = cx + 5;

		addRenderableWidget(Button.builder(toggleText(com.seolwoni.assistcore.i18n.Tr.t("모드 전체"), c().modEnabled), b -> {
			c().modEnabled = !c().modEnabled;
			if (!c().modEnabled && c().running) Miner.stop(com.seolwoni.assistcore.i18n.Tr.t("모드를 껐어요"), null, false);
			MineConfig.save();
			rebuildWidgets();
		}).bounds(left, 22, 100, 20).build());
		if (c().running) {
			// Running: stop right here, or go back to the start first (store the loot, carry on later from there).
			addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("정지")).withColor(0xFFFF7777), b -> {
				Miner.stop(com.seolwoni.assistcore.i18n.Tr.t("채광을 멈췄어요"), null, false);
				rebuildWidgets();
			}).bounds(left + 105, 22, 48, 20).build());
			addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("돌아가기")).withColor(0xFFFFDD77), b -> {
				Miner.returnHome(this.minecraft);
				this.minecraft.gui.setScreen(null);
			}).bounds(left + 157, 22, 48, 20).build());
		} else {
			Button run = Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("채광 시작")).withColor(0xFF77FF77), b -> {
				if (Miner.start(this.minecraft)) this.minecraft.gui.setScreen(null);
			}).bounds(left + 105, 22, 100, 20).build();
			run.active = c().modEnabled && this.minecraft.player != null;
			addRenderableWidget(run);
		}

		for (int i = 0; i < TABS.length; i++) {
			final int idx = i;
			int col = i % 5, tw = (310 - 8) / 5;
			Button tb = Button.builder(Component.literal(TABS[i]), b -> {
				tab = idx;
				rebuildWidgets();
			}).bounds(left + col * (tw + 2), i < 5 ? 46 : 68, tw, 20).build();
			tb.active = i != tab;
			addRenderableWidget(tb);
		}

		int y = 96;
		switch (tab) {
			case 0 -> basic(left, right, y);
			case 1 -> ores(left, right, y);
			case 2 -> pick(left, right, y);
			case 3 -> torches(left, right, y);
			case 4 -> safety(left, right, y);
			case 5 -> discard(left, right, y);
			case 6 -> storage(left, right, y);
			case 7 -> hud(left, right, y);
			default -> nether(left, right, y);
		}
		// Up here with the other main buttons: at the bottom it covered the last rows on small screens (phones).
		// The worker's journal (Assist Core): this mod's jobs, newest first.
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("일지")), b -> this.minecraft.gui.setScreen(new com.seolwoni.assistcore.gui.JournalScreen(this, "mine")))
				.bounds(left + 210, 22, 48, 20).build());
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("완료")), b -> onClose())
				.bounds(left + 262, 22, 48, 20).build());
	}

	// ------------------------------------------------------------------ tabs

	private void basic(int left, int right, int y) {
		MineConfig c = c();
		slider(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("목표 Y"), "", -64, 320, c.targetY, v -> c.targetY = v);
		slider(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("가운데 굴"), com.seolwoni.assistcore.i18n.Tr.t("칸"), 3, 500, c.mainLength, v -> c.mainLength = v);
		y += ROW;
		// Best Y per ore (ore distribution since 1.18). Two rows of five.
		for (int i = 0; i < Y_PRESETS.length; i++) {
			String name = (String) Y_PRESETS[i][0];
			int value = i == Y_PRESETS.length - 1 && this.minecraft.player != null
					? this.minecraft.player.blockPosition().getY() : (int) Y_PRESETS[i][1];
			boolean on = c.targetY == value;
			int bw = (310 - 4 * 2) / 5;
			int bx = left + (i % 5) * (bw + 2), by = y + (i / 5) * 22;
			Button pb = Button.builder(Component.literal(name).withColor(on ? 0xFF55FF55 : 0xFFFFFFFF), b -> {
				c.targetY = Math.max(-64, Math.min(320, value));
				MineConfig.save();
				rebuildWidgets();
			}).bounds(bx, by, bw, 20).build();
			pb.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(name + ": Y " + value + (String) Y_PRESETS[i][2])));
			addRenderableWidget(pb);
		}
		y += 44 + 2;
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("굴 방식: ") + (c.digPattern == 1 ? com.seolwoni.assistcore.i18n.Tr.t("구멍 뚫기") : com.seolwoni.assistcore.i18n.Tr.t("가지굴"))), b -> {
			c.digPattern = c.digPattern == 1 ? 0 : 1;
			MineConfig.save();
			rebuildWidgets();
		}).bounds(left, y, 150, 20).build());
		labels.add(new Object[] {c.digPattern == 1 ? com.seolwoni.assistcore.i18n.Tr.t("벽에 눈높이 1칸 구멍: 같은 시간에 2~3배 더 봐요") : com.seolwoni.assistcore.i18n.Tr.t("걸어 들어가는 2칸 높이 가지굴"), right, y + 6, 0xFFAAAAAA, false});
		y += ROW;
		if (c.digPattern == 1) {
			slider(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("구멍 깊이"), com.seolwoni.assistcore.i18n.Tr.t("칸"), 1, 5, c.pokeDepth, v -> c.pokeDepth = v);
			slider(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("구멍 간격"), com.seolwoni.assistcore.i18n.Tr.t("칸"), 2, 16, c.branchSpacing, v -> c.branchSpacing = v);
		} else {
			slider(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("가지굴 길이"), com.seolwoni.assistcore.i18n.Tr.t("칸"), 0, 1000, c.branchLength, v -> c.branchLength = v);
			slider(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("가지 간격"), com.seolwoni.assistcore.i18n.Tr.t("칸"), 2, 16, c.branchSpacing, v -> c.branchSpacing = v);
		}
		y += ROW;
		toggle(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("이어서 파기"), () -> c.resume, v -> c.resume = v);
		toggle(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("사람 같은 딜레이"), () -> c.humanDelay, v -> c.humanDelay = v);
		y += ROW;
		addRenderableWidget(Button.builder(Component.literal(confirmReset ? com.seolwoni.assistcore.i18n.Tr.t("한 번 더 누르면 초기화") : com.seolwoni.assistcore.i18n.Tr.t("진행 초기화")), b -> {
			if (!confirmReset) {
				confirmReset = true;
				rebuildWidgets();
				return;
			}
			confirmReset = false;
			if (c.running) Miner.stop(null, null, false);
			Miner.resetJob();
			rebuildWidgets();
		}).bounds(left, y, 100, 20).build());
		JobState j = Miner.job();
		labels.add(new Object[] {j.active ? com.seolwoni.assistcore.i18n.Tr.t("진행 중: 시작 ") + j.ox + ", " + j.oy + ", " + j.oz + com.seolwoni.assistcore.i18n.Tr.t(" · 가운데 ") + j.mainK + "/" + j.mainLength
				: com.seolwoni.assistcore.i18n.Tr.t("진행 중인 작업 없음"), left + 106, y + 6, 0xFFAAAAAA, false});
		y += ROW;
		String[] paces = {com.seolwoni.assistcore.i18n.Tr.t("느긋"), com.seolwoni.assistcore.i18n.Tr.t("보통"), com.seolwoni.assistcore.i18n.Tr.t("빠름")};
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("작업 속도: ") + paces[Math.max(0, Math.min(2, c.pace))]), b -> {
			c.pace = (c.pace + 1) % 3;
			MineConfig.save();
			rebuildWidgets();
		}).bounds(left, y, 150, 20).build());
		labels.add(new Object[] {com.seolwoni.assistcore.i18n.Tr.t("느긋 = 가장 사람 같게 · 빠름 = 효율 위주"), right, y + 6, 0xFFAAAAAA, false});
		y += ROW;
		note(com.seolwoni.assistcore.i18n.Tr.t("Y·길이는 새로 시작할 때 적용 · 지금보다 아래 Y면 계단으로 내려가요"), y);
	}

	private void ores(int left, int right, int y) {
		MineConfig c = c();
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("캘 광석 고르기 (") + c.ores.size() + com.seolwoni.assistcore.i18n.Tr.t("개)")),
				b -> this.minecraft.gui.setScreen(new PickerScreen(this, com.seolwoni.assistcore.i18n.Tr.t("캘 광석"), c.ores, true, MineConfig.DEFAULT_ORES,
						com.seolwoni.assistcore.i18n.Tr.t("검색해서 다른 블록도 추가할 수 있어요 (예: 자수정, 점토)"), MineConfig::save))).bounds(left, y, 310, 20).build());
		y += ROW;
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("발견 알림 광석 고르기 (") + c.alertOres.size() + com.seolwoni.assistcore.i18n.Tr.t("개)")),
				b -> this.minecraft.gui.setScreen(new PickerScreen(this, com.seolwoni.assistcore.i18n.Tr.t("발견 알림 광석"), c.alertOres, true, MineConfig.DEFAULT_ORES,
						com.seolwoni.assistcore.i18n.Tr.t("찾으면 타이틀과 소리로 알려줘요"), MineConfig::save))).bounds(left, y, 310, 20).build());
		y += ROW;
		toggle(left, y, 310, com.seolwoni.assistcore.i18n.Tr.t("바닥·천장 광석도 캐기"), () -> c.mineFloorCeiling, v -> c.mineFloorCeiling = v);
		y += ROW + 4;
		note(com.seolwoni.assistcore.i18n.Tr.t("굴 벽·바닥·천장에 드러난 광석을 이어진 덩어리째 캐요. 벽 너머는 보지 않아요."), y);
	}

	private void nether(int left, int right, int y) {
		MineConfig c = c();
		slider(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("네더 목표 Y"), "", 1, 126, c.netherTargetY, v -> c.netherTargetY = v);
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("굴 방식: ") + (c.netherPattern == 1 ? com.seolwoni.assistcore.i18n.Tr.t("구멍 뚫기") : com.seolwoni.assistcore.i18n.Tr.t("가지굴"))), b -> {
			c.netherPattern = c.netherPattern == 1 ? 0 : 1;
			MineConfig.save();
			rebuildWidgets();
		}).bounds(right, y, 150, 20).build());
		y += ROW;
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("네더에서 캘 광석 고르기 (") + c.netherOres.size() + com.seolwoni.assistcore.i18n.Tr.t("개)")),
				b -> this.minecraft.gui.setScreen(new PickerScreen(this, com.seolwoni.assistcore.i18n.Tr.t("네더에서 캘 광석"), c.netherOres, true, MineConfig.NETHER_ORES,
						com.seolwoni.assistcore.i18n.Tr.t("고대 잔해·석영·네더 금"), MineConfig::save))).bounds(left, y, 310, 20).build());
		y += ROW;
		toggle(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("불 저항 물약 마시기"), () -> c.netherFireRes, v -> c.netherFireRes = v);
		slider(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("네더랙 남겨두기"), com.seolwoni.assistcore.i18n.Tr.t("개"), 0, 1024, c.netherPlugKeep, v -> c.netherPlugKeep = v);
		y += ROW + 4;
		note(com.seolwoni.assistcore.i18n.Tr.t("네더에서 켜면 이 설정으로 파요 (Y 15 아래면 계단으로 내려가고, 위면 올라가요)."), y);
		note(com.seolwoni.assistcore.i18n.Tr.t("용암이 위·옆에 있으면 먼저 막고, 못 막으면 그 칸은 건너뛰고 좌표를 남겨요."), y + 12);
		note(com.seolwoni.assistcore.i18n.Tr.t("금 갑옷을 하나라도 입어야 피글린이 덤비지 않아요. 좀비 피글린은 절대 치지 않아요."), y + 24);
		note(com.seolwoni.assistcore.i18n.Tr.t("상자는 사방이 막힌 곳에 두세요 (가스트·화염이 부술 수 있어요)."), y + 36);
	}

	private void pick(int left, int right, int y) {
		MineConfig c = c();
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("곡괭이: ") + PICK_MODES[c.pickMode]), b -> {
			c.pickMode = (c.pickMode + 1) % 4;
			MineConfig.save();
			rebuildWidgets();
		}).bounds(left, y, 310, 20).build());
		y += ROW;
		slider(left, y, 310, com.seolwoni.assistcore.i18n.Tr.t("파괴보호 기준 내구도"), "", 1, 200, c.protectDurability, v -> c.protectDurability = v);
		y += ROW + 4;
		note(com.seolwoni.assistcore.i18n.Tr.t("핫바 모드는 내구도가 적은 곡괭이부터 쓰고, 핫바에 없으면 인벤토리에서 꺼내와요."), y);
	}

	private void torches(int left, int right, int y) {
		MineConfig c = c();
		toggle(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("횃불 설치"), () -> c.torches, v -> c.torches = v);
		slider(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("간격"), com.seolwoni.assistcore.i18n.Tr.t("칸"), 3, 32, c.torchInterval, v -> c.torchInterval = v);
		y += ROW;
		toggle(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("자동 제작"), () -> c.craftTorches, v -> c.craftTorches = v);
		slider(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("유지 개수"), com.seolwoni.assistcore.i18n.Tr.t("개"), 8, 64, c.torchKeep, v -> c.torchKeep = v);
		y += ROW;
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("다 떨어지면: ") + TORCH_OUT[c.torchOutAction]), b -> {
			c.torchOutAction = (c.torchOutAction + 1) % 3;
			MineConfig.save();
			rebuildWidgets();
		}).bounds(left, y, 310, 20).build());
		y += ROW;
		slider(left, y, 310, com.seolwoni.assistcore.i18n.Tr.t("보관할 때 남길 석탄"), com.seolwoni.assistcore.i18n.Tr.t("개"), 0, 64, c.coalKeep, v -> c.coalKeep = v);
		y += ROW + 4;
		note(com.seolwoni.assistcore.i18n.Tr.t("석탄(목탄) + 막대기로 만들고, 막대기가 없으면 판자·원목에서 만들어요."), y);
	}

	private void safety(int left, int right, int y) {
		MineConfig c = c();
		addRenderableWidget(net.minecraft.client.gui.components.Button.builder(net.minecraft.network.chat.Component.literal(com.seolwoni.assistcore.i18n.Tr.t("몹: ") + new String[] {com.seolwoni.assistcore.i18n.Tr.t("피하기"), com.seolwoni.assistcore.i18n.Tr.t("피하고 싸우기"), com.seolwoni.assistcore.i18n.Tr.t("멈추기"), com.seolwoni.assistcore.i18n.Tr.t("무시")}[Math.max(0, Math.min(3, c.mobMode))]), b -> {
			c.mobMode = (c.mobMode + 1) % 4;
			c.stopOnMob = c.mobMode == 2;
			com.seolwoni.mineassist.config.MineConfig.save();
			rebuildWidgets();
		}).bounds(left, y, 150, 20).build());
		slider(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("감지 거리"), com.seolwoni.assistcore.i18n.Tr.t("칸"), 3, 24, c.mobRange, v -> c.mobRange = v);
		y += ROW;
		slider(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("체력 이하면 정지"), "", 1, 19, c.minHealth, v -> c.minHealth = v);
		slider(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("남길 조약돌"), com.seolwoni.assistcore.i18n.Tr.t("개"), 16, 256, c.plugKeep, v -> c.plugKeep = v);
		y += ROW;
		toggle(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("자동 밥"), () -> c.autoEat, v -> c.autoEat = v);
		slider(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("배고픔 기준"), "/20", 1, 19, c.eatThreshold, v -> c.eatThreshold = v);
		y += ROW;
		slider(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("챙길 음식"), com.seolwoni.assistcore.i18n.Tr.t("개"), 0, 64, c.foodKeep, v -> c.foodKeep = v);
		y += ROW + 4;
		note(com.seolwoni.assistcore.i18n.Tr.t("용암·물은 캐기 전에 미리 조약돌로 막아요. 막을 수 없으면 그 가지굴을 버리거나 멈춰요."), y);
		note(com.seolwoni.assistcore.i18n.Tr.t("체력 1 = 하트 반 칸"), y + 12);
	}

	private void discard(int left, int right, int y) {
		MineConfig c = c();
		toggle(left, y, 310, com.seolwoni.assistcore.i18n.Tr.t("필요 없는 블록 버리기"), () -> c.discardEnabled, v -> c.discardEnabled = v);
		y += ROW;
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("버릴 아이템 고르기 (") + c.discardItems.size() + com.seolwoni.assistcore.i18n.Tr.t("개)")),
				b -> this.minecraft.gui.setScreen(new PickerScreen(this, com.seolwoni.assistcore.i18n.Tr.t("버릴 아이템"), c.discardItems, false, ITEM_PRESETS, null, MineConfig::save)))
				.bounds(left, y, 310, 20).build());
		y += ROW + 4;
		note(com.seolwoni.assistcore.i18n.Tr.t("조약돌·심층암 조약돌은 '남길 조약돌' 개수만큼 남기고 버려요 (용암·물 막기용)."), y);
		note(com.seolwoni.assistcore.i18n.Tr.t("버린 아이템은 다 판 가지굴 안이나 지나온 쪽으로 던져서 길에 쌓이지 않게 해요."), y + 12);
	}

	private void storage(int left, int right, int y) {
		MineConfig c = c();
		toggle(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("인벤 차면 보관"), () -> c.storeEnabled, v -> c.storeEnabled = v);
		addRenderableWidget(Button.builder(Component.literal(confirmChests ? com.seolwoni.assistcore.i18n.Tr.t("한 번 더 누르면 초기화") : com.seolwoni.assistcore.i18n.Tr.t("상자 전체 초기화")), b -> {
			if (!confirmChests) {
				confirmChests = true;
				rebuildWidgets();
				return;
			}
			confirmChests = false;
			c.chests.clear();
			MineConfig.save();
			rebuildWidgets();
		}).bounds(right, y, 150, 20).build());
		y += ROW;
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("보관 방식: ") + com.seolwoni.assistcore.storage.PortableStorage.LABELS[Math.max(0, Math.min(3, c.storeMode))]), b -> {
			c.storeMode = (c.storeMode + 1) % 4;
			MineConfig.save();
			rebuildWidgets();
		}).bounds(left, y, 150, 20).build());
		labels.add(new Object[] {switch (c.storeMode) {
			case 1 -> com.seolwoni.assistcore.i18n.Tr.t("엔더 상자를 그 자리에 놓고 담아요 (실크터치로 회수)");
			case 2 -> com.seolwoni.assistcore.i18n.Tr.t("셜커 상자를 그 자리에 놓고 담은 뒤 다시 주워요");
			case 3 -> com.seolwoni.assistcore.i18n.Tr.t("엔더(실크터치) → 셜커 → 지정한 상자 순서");
			default -> com.seolwoni.assistcore.i18n.Tr.t("지정한 상자까지 걸어가서 담아요");
		}, right, y + 6, 0xFFAAAAAA, false});
		y += ROW;
		if (c.chests.isEmpty()) note(c.storeMode == 0 ? com.seolwoni.assistcore.i18n.Tr.t("시작 지점 근처 상자를 바라보고 K로 지정하세요 (최대 10개)") : com.seolwoni.assistcore.i18n.Tr.t("상자도 지정해 두면 들고 다니는 보관함이 없을 때 써요"), y + 6);
		int maxRows = Math.max(1, (this.height - y - 40) / 22);
		for (int i = 0; i < Math.min(c.chests.size(), maxRows); i++) {
			ChestEntry e = c.chests.get(i);
			labels.add(new Object[] {(i + 1) + com.seolwoni.assistcore.i18n.Tr.t("번  ") + e.x + ", " + e.y + ", " + e.z, left, y + 6, 0xFFFFFFFF, false});
			String what = e.items.isEmpty() ? com.seolwoni.assistcore.i18n.Tr.t("나머지 전부") : e.items.size() + com.seolwoni.assistcore.i18n.Tr.t("종류");
			int idx = i;
			addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("담을 것: ") + what), b ->
					this.minecraft.gui.setScreen(new PickerScreen(this, (idx + 1) + com.seolwoni.assistcore.i18n.Tr.t("번 상자에 담을 아이템"), e.items, false, ITEM_PRESETS,
							com.seolwoni.assistcore.i18n.Tr.t("아무것도 고르지 않으면 나머지를 차례대로 담아요"), MineConfig::save))).bounds(cx() - 20, y, 120, 20).build());
			addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("삭제")), b -> {
				c.chests.remove(e);
				MineConfig.save();
				rebuildWidgets();
			}).bounds(cx() + 105, y, 50, 20).build());
			y += 22;
		}
	}

	private void hud(int left, int right, int y) {
		MineConfig c = c();
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("HUD 위치 편집")),
				b -> this.minecraft.gui.setScreen(new HudEditScreen(this))).bounds(left, y, 310, 20).build());
		y += ROW;
		toggle(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("상태 HUD"), () -> c.statusHud, v -> c.statusHud = v);
		toggle(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("통계 HUD"), () -> c.statsHud, v -> c.statsHud = v);
		y += ROW;
		slider(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("상태 크기"), "%", 50, 300, Math.round(c.hudScale * 100), v -> c.hudScale = v / 100f);
		slider(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("통계 크기"), "%", 50, 300, Math.round(c.statsScale * 100), v -> c.statsScale = v / 100f);
		y += ROW;
		slider(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("배경 투명도"), "", 0, 255, c.hudBgAlpha, v -> c.hudBgAlpha = v);
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("통계 표시: ") + (c.statsTotals ? com.seolwoni.assistcore.i18n.Tr.t("누적") : com.seolwoni.assistcore.i18n.Tr.t("이번 세션"))), b -> {
			c.statsTotals = !c.statsTotals;
			MineConfig.save();
			rebuildWidgets();
		}).bounds(right, y, 150, 20).build());
		y += ROW;
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("세션 통계 초기화")), b -> {
			com.seolwoni.mineassist.hud.MineStats.session.reset();
			rebuildWidgets();
		}).bounds(left, y, 150, 20).build());
		addRenderableWidget(Button.builder(Component.literal(confirmStats ? com.seolwoni.assistcore.i18n.Tr.t("한 번 더 누르면 초기화") : com.seolwoni.assistcore.i18n.Tr.t("누적 통계 초기화")), b -> {
			if (!confirmStats) {
				confirmStats = true;
				rebuildWidgets();
				return;
			}
			confirmStats = false;
			com.seolwoni.mineassist.hud.MineStats.total.reset();
			com.seolwoni.mineassist.hud.MineStats.saveTotal();
			rebuildWidgets();
		}).bounds(right, y, 150, 20).build());
	}

	// ------------------------------------------------------------------ helpers

	private int cx() {
		return this.width / 2;
	}

	private void slider(int x, int y, int w, String label, String unit, int min, int max, int cur, Consumer<Integer> set) {
		addRenderableWidget(new IntSlider(x, y, w, label, unit, min, max, cur, v -> {
			set.accept(v);
			MineConfig.save();
		}));
	}

	private void toggle(int x, int y, int w, String label, Supplier<Boolean> get, Consumer<Boolean> set) {
		addRenderableWidget(Button.builder(toggleText(label, get.get()), b -> {
			boolean v = !get.get();
			set.accept(v);
			MineConfig.save();
			b.setMessage(toggleText(label, v));
		}).bounds(x, y, w, 20).build());
	}

	private static Component toggleText(String label, boolean on) {
		return Component.literal(label + ": ").append(on
				? Component.literal(com.seolwoni.assistcore.i18n.Tr.t("켜짐")).withColor(0xFF55FF55)
				: Component.literal(com.seolwoni.assistcore.i18n.Tr.t("꺼짐")).withColor(0xFFFF5555));
	}

	private void note(String text, int y) {
		labels.add(new Object[] {text, this.width / 2, y, 0xFFAAAAAA, true});
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
		for (Object[] l : labels) {
			if ((boolean) l[4]) g.centeredText(this.font, Component.literal((String) l[0]), (int) l[1], (int) l[2], (int) l[3]);
			else g.text(this.font, Component.literal((String) l[0]).getVisualOrderText(), (int) l[1], (int) l[2], (int) l[3], true);
		}
	}

	@Override
	public void onClose() {
		MineConfig.save();
		this.minecraft.gui.setScreen(parent);
	}
}
