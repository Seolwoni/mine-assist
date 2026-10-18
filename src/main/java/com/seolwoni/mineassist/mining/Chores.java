package com.seolwoni.mineassist.mining;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import com.seolwoni.mineassist.config.MineConfig;
import com.seolwoni.mineassist.mining.Geo.Loc;
import com.seolwoni.mineassist.util.Alerts;
import com.seolwoni.mineassist.util.Body;
import com.seolwoni.mineassist.util.Human;
import com.seolwoni.mineassist.util.Inv;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/** Things done between tunnel steps: craft torches, eat, throw junk, go store loot. */
final class Chores {
	/** If crafting did not produce torches (e.g. the server refused), wait a while before trying again. */
	static long craftBlockedUntil;

	private Chores() {
	}

	static boolean planChores(Minecraft mc, LocalPlayer p, MineConfig cfg) {
		// No usable pickaxe at all -> stop (or fetch one from the chests once).
		if (Inv.count(p, Inv::usablePick) == 0) {
			if (cfg.storeEnabled && !cfg.chests.isEmpty() && !Miner.torchFetchTried()) {
				Miner.storageTrip(true);
				return true;
			}
			Miner.stop("곡괭이가 없어요", "쓸 수 있는 곡괭이가 없어요", true);
			return true;
		}

		// Torches.
		int torches = Inv.count(p, Inv::isTorch);
		if (torches > 0) Miner.clearTorchFetch();
		if (cfg.torches && cfg.craftTorches && torches <= cfg.torchKeep - 4 && Inv.canClick(mc, p)
				&& Miner.job.ticks >= craftBlockedUntil) {
			CraftAction craft = CraftAction.plan(p, cfg.torchKeep - torches);
			if (craft != null) {
				Miner.status("횃불 만드는 중");
				Miner.add(craft);
				return true;
			}
		}
		if (cfg.torches && torches == 0) {
			switch (cfg.torchOutAction) {
				case 1 -> {
					Miner.stop("횃불이 없어요", "횃불 재료가 떨어졌어요", true);
					return true;
				}
				case 2 -> {
					if (!Miner.torchFetchTried() && cfg.storeEnabled && !cfg.chests.isEmpty()) {
						Miner.storageTrip(true);
						return true;
					}
				}
				default -> {
				}
			}
		}

		// Food.
		if (cfg.autoEat && p.getFoodData().getFoodLevel() <= cfg.eatThreshold && Inv.count(p, Inv::isFood) > 0) {
			Miner.status("먹는 중");
			Miner.add(new EatAction());
			return true;
		}

		// Junk: only when space is getting short, thrown back the way we came.
		int free = Inv.freeSlots(p);
		if (cfg.discardEnabled && free <= 3 && !junkSlots(p).isEmpty() && Inv.canClick(mc, p)) {
			Miner.add(new DiscardAction(backwards(), false));
			return true;
		}

		// Full: go and store.
		if (free <= 1) {
			if (cfg.storeEnabled && !cfg.chests.isEmpty()) {
				Miner.storageTrip(false);
			} else {
				Miner.stop("인벤토리 가득 참!", "보관 상자를 지정하면 자동으로 보관해요", true);
			}
			return true;
		}
		return false;
	}

	/** The direction pointing back the way we came. */
	private static Direction backwards() {
		Geo g = Miner.geo;
		Loc h = Miner.here;
		if (h.kind() == Loc.BRANCH) return h.b() == 0 ? g.r : g.r.getOpposite();
		return g.d.getOpposite();
	}

	/** Inventory indices to throw away, keeping {@code plugKeep} cobblestone-type blocks. */
	static List<Integer> junkSlots(LocalPlayer p) {
		MineConfig cfg = MineConfig.get();
		Inventory inv = p.getInventory();
		List<Integer> out = new ArrayList<>();
		List<Integer> plugs = new ArrayList<>();
		int selected = inv.getSelectedSlot();
		for (int i = 0; i < 36; i++) {
			if (i == selected) continue;
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || !cfg.discardItems.contains(Inv.id(s))) continue;
			if (Inv.isPlug(s)) plugs.add(i);
			else out.add(i);
		}
		// Keep the biggest plug stacks until we hold plugKeep, throw the rest.
		plugs.sort(Comparator.comparingInt((Integer i) -> inv.getItem(i).getCount()).reversed());
		int kept = 0;
		ItemStack sel = inv.getItem(selected);
		if (Inv.isPlug(sel)) kept += sel.getCount();
		for (int i : plugs) {
			if (kept < cfg.plugKeep) kept += inv.getItem(i).getCount();
			else out.add(i);
		}
		return out;
	}

	// ------------------------------------------------------------------ discard

	static final class DiscardAction implements Miner.Action {
		private final Direction toward;
		private List<Integer> slots;
		private int stage, delay;
		private float yaw;

		DiscardAction(Direction toward, boolean unused) {
			this.toward = toward;
		}

		@Override
		public int failMode() {
			return Miner.SKIP;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			switch (stage) {
				case 0 -> {
					if (!MineConfig.get().discardEnabled || !Inv.canClick(mc, p)) return Miner.DONE;
					slots = junkSlots(p);
					if (slots.isEmpty()) return Miner.DONE;
					Miner.status("필요 없는 블록 버리는 중");
					yaw = toward.toYRot() + (float) Human.jitter(5);
					Body.newTurn();
					delay = Human.delay(2, 6);
					stage = 1;
					return Miner.RUN;
				}
				case 1 -> {
					if (Body.turnTo(p, yaw, Math.max(-5f, Math.min(30f, p.getXRot())), 3f) && delay-- <= 0) stage = 2;
					return Miner.RUN;
				}
				default -> {
					if (delay-- > 0) return Miner.RUN;
					if (slots.isEmpty()) return Miner.DONE;
					if (!Inv.canClick(mc, p)) return Miner.DONE;
					int i = slots.remove(0);
					// Ctrl+Q style: throw the whole stack.
					Inv.click(mc, p, Inv.menuSlot(i), 1, ContainerInput.THROW);
					delay = Math.max(1, Human.delay(2, 8) / 2);
					return Miner.RUN;
				}
			}
		}
	}

	// ------------------------------------------------------------------ eat

	static final class EatAction implements Miner.Action {
		private int stage, delay, ticks, bites, slot;

		@Override
		public int failMode() {
			return Miner.SKIP;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			switch (stage) {
				case 0 -> {
					slot = Inv.bringToHotbar(mc, p, Inv::isFood);
					if (slot == -1) return Miner.DONE;
					delay = Human.delay(2, 8);
					stage = 1;
					return Miner.RUN;
				}
				case 1 -> {
					if (delay-- > 0) return Miner.RUN;
					Inv.select(p, slot);
					delay = Human.delay(2, 6);
					stage = 2;
					return Miner.RUN;
				}
				case 2 -> {
					if (delay-- > 0) return Miner.RUN;
					mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
					mc.options.keyUse.setDown(true);
					ticks = 0;
					stage = 3;
					return Miner.RUN;
				}
				default -> {
					ticks++;
					if (!((ticks > 5 && !p.isUsingItem()) || ticks > 80)) return Miner.RUN;
					mc.options.keyUse.setDown(false);
					bites++;
					MineConfig cfg = MineConfig.get();
					if (bites < 3 && p.getFoodData().getFoodLevel() <= cfg.eatThreshold && Inv.isFood(p.getMainHandItem())) {
						delay = Human.delay(3, 8);
						stage = 2;
						return Miner.RUN;
					}
					return Miner.DONE;
				}
			}
		}
	}

	// ------------------------------------------------------------------ crafting

	/** Crafts torches in the 2x2 grid, making sticks from planks and planks from logs when needed. */
	static final class CraftAction implements Miner.Action {
		private static final int RESULT = 0, G1 = 1, G3 = 3;
		private final List<Runnable> steps = new ArrayList<>();
		private int delay;
		private int before = -1;

		static CraftAction plan(LocalPlayer p, int wanted) {
			int coal = Inv.count(p, Inv::isCoal);
			if (coal == 0 || wanted < 4) return null;
			int crafts = Math.min(Math.min((wanted + 3) / 4, coal), 16);
			int sticks = Inv.count(p, Inv::isStick);
			int planks = Inv.count(p, Inv::isPlank);
			int logs = Inv.count(p, Inv::isLog);
			int possibleSticks = sticks + 4 * ((planks + 4 * logs) / 2);
			crafts = Math.min(crafts, possibleSticks);
			if (crafts <= 0) return null;
			int stickCrafts = Math.max(0, (crafts - sticks + 3) / 4);
			int plankCrafts = Math.max(0, (2 * stickCrafts - planks + 3) / 4);
			CraftAction a = new CraftAction();
			Minecraft mc = Minecraft.getInstance();
			if (plankCrafts > 0) a.recipe(mc, Inv::isLog, null, Math.min(plankCrafts, 16));
			if (stickCrafts > 0) a.recipe(mc, Inv::isPlank, Inv::isPlank, Math.min(stickCrafts, 16));
			a.recipe(mc, Inv::isCoal, Inv::isStick, crafts);
			return a;
		}

		/** Puts n of {@code top} in slot 1 (and n of {@code bottom} in slot 3), then takes the result. */
		private void recipe(Minecraft mc, Predicate<ItemStack> top, Predicate<ItemStack> bottom, int n) {
			place(mc, top, G1, n);
			if (bottom != null) place(mc, bottom, G3, n);
			steps.add(() -> {
			}); // let the server compute the result
			steps.add(() -> {
			});
			steps.add(() -> click(mc, RESULT, 0, ContainerInput.QUICK_MOVE));
			steps.add(() -> {
			});
			steps.add(() -> returnGrid(mc));
		}

		private void place(Minecraft mc, Predicate<ItemStack> what, int gridSlot, int n) {
			int[] src = {-1};
			steps.add(() -> {
				LocalPlayer p = mc.player;
				src[0] = -1;
				int best = 0;
				for (int i = 0; i < 36; i++) {
					ItemStack s = p.getInventory().getItem(i);
					if (what.test(s) && s.getCount() > best) {
						best = s.getCount();
						src[0] = i;
					}
				}
				if (src[0] != -1) click(mc, Inv.menuSlot(src[0]), 0, ContainerInput.PICKUP);
			});
			for (int j = 0; j < n; j++) {
				steps.add(() -> {
					if (src[0] != -1 && !mc.player.inventoryMenu.getCarried().isEmpty()) {
						click(mc, gridSlot, 1, ContainerInput.PICKUP);
					}
				});
			}
			steps.add(() -> {
				if (src[0] != -1 && !mc.player.inventoryMenu.getCarried().isEmpty()) {
					click(mc, Inv.menuSlot(src[0]), 0, ContainerInput.PICKUP);
				}
			});
		}

		private static void returnGrid(Minecraft mc) {
			for (int s = 1; s <= 4; s++) {
				if (!mc.player.inventoryMenu.getSlot(s).getItem().isEmpty()) click(mc, s, 0, ContainerInput.QUICK_MOVE);
			}
		}

		private static void click(Minecraft mc, int slot, int button, ContainerInput input) {
			mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, slot, button, input, mc.player);
		}

		@Override
		public int failMode() {
			return Miner.SKIP;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			if (before < 0) before = Inv.count(p, Inv::isTorch);
			if (delay-- > 0) return Miner.RUN;
			if (steps.isEmpty()) {
				int now = Inv.count(p, Inv::isTorch);
				if (now > before) {
					Alerts.actionBar("횃불을 만들었어요 (" + now + "개)", 0xFF55FF55);
				} else {
					craftBlockedUntil = Miner.job.ticks + 2400;
				}
				return Miner.DONE;
			}
			steps.remove(0).run();
			delay = Math.max(1, Human.delay(2, 6) / 2);
			return Miner.RUN;
		}
	}
}
