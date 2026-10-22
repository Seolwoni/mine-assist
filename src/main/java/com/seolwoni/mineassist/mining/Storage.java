package com.seolwoni.mineassist.mining;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.seolwoni.mineassist.config.MineConfig;
import com.seolwoni.assistcore.storage.ChestEntry;
import com.seolwoni.assistcore.storage.Chests;
import com.seolwoni.assistcore.util.Alerts;
import com.seolwoni.assistcore.util.Blocks2;
import com.seolwoni.assistcore.util.Body;
import com.seolwoni.assistcore.util.Human;
import com.seolwoni.mineassist.util.Inv;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Marking storage chests (K) and the trip that sorts loot into them. */
public final class Storage {
	private Storage() {
	}

	/** K key: add or remove the chest being looked at. */
	public static void markLookedAt(Minecraft mc) {
		Chests.toggleLookedAt(mc, MineConfig.get().chests, MineConfig::save);
	}

	static BlockPos pos(ChestEntry e) {
		return e.pos();
	}

	// ------------------------------------------------------------------ what to keep

	/** Inventory indices that must stay with the player. */
	static Set<Integer> keepSet(LocalPlayer p) {
		MineConfig cfg = MineConfig.get();
		Inventory inv = p.getInventory();
		Set<Integer> keep = new HashSet<>();
		keep.add(inv.getSelectedSlot());
		// Shulker boxes and ender chests we carry to store into stay with us (never put into the chest).
		if (MineConfig.get().storeMode != com.seolwoni.assistcore.storage.PortableStorage.CHEST) {
			for (int i = 0; i < 36; i++) if (com.seolwoni.assistcore.storage.PortableStorage.isPortable(inv.getItem(i))) keep.add(i);
		}
		List<Integer> coal = new ArrayList<>();
		List<Integer> plugs = new ArrayList<>();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			if (Inv.isPlug(s)) {
				plugs.add(i);
			} else if (s.is(net.minecraft.tags.ItemTags.SWORDS) || s.is(net.minecraft.tags.ItemTags.AXES) || s.is(net.minecraft.world.item.Items.SHIELD)
					|| s.is(net.minecraft.world.item.Items.BOW) || s.is(net.minecraft.world.item.Items.CROSSBOW)) {
				keep.add(i); // weapons stay with us (Defense fights with them)
			} else if (Inv.isPick(s) || Inv.isTorch(s) || Inv.isLog(s) || Inv.isPlank(s)
					|| Inv.isStick(s) || Inv.isFood(s) || s.is(net.minecraft.world.item.Items.GOLDEN_CARROT) || s.is(net.minecraft.world.item.Items.GOLDEN_APPLE) || s.is(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE) || (cfg.discardEnabled && cfg.discardItems.contains(Inv.id(s)))) {
				keep.add(i);
			} else if (Inv.isCoal(s)) {
				coal.add(i);
			}
		}
		// Cobblestone: keep plugKeep for floors and walls. The rest goes in a chest
		// (or gets thrown away later, if it is on the discard list).
		plugs.sort(Comparator.comparingInt((Integer i) -> inv.getItem(i).getCount()).reversed());
		int keptPlugs = 0;
		for (int i : plugs) {
			if (keptPlugs < Miner.plugKeep()) {
				keep.add(i);
				keptPlugs += inv.getItem(i).getCount();
			} else if (cfg.discardEnabled && cfg.discardItems.contains(Inv.id(inv.getItem(i)))) {
				keep.add(i);
			}
		}
		coal.sort(Comparator.comparingInt((Integer i) -> inv.getItem(i).getCount()).reversed());
		int kept = 0;
		for (int i : coal) {
			if (kept >= cfg.coalKeep) break;
			keep.add(i);
			kept += inv.getItem(i).getCount();
		}
		return keep;
	}

	/** Which chest an item should go to (filtered chests first, then overflow chests), or -1. */
	static int target(String id, boolean[] full) {
		return Chests.target(MineConfig.get().chests, id, full);
	}

	// ------------------------------------------------------------------ trip

	/** Something worth fetching from the chests: torches, a pickaxe, food, torch materials. */
	static boolean needsSupplies(LocalPlayer p) {
		MineConfig cfg = MineConfig.get();
		return (cfg.torches && Inv.count(p, Inv::isTorch) < cfg.torchKeep / 2)
				|| Inv.count(p, Inv::usablePick) == 0
				|| (cfg.autoEat && Inv.count(p, Inv::isFood) < Math.max(1, cfg.foodKeep / 2))
				|| (cfg.torches && Inv.count(p, Inv::isCoal) < 4)
				|| (cfg.torches && Inv.count(p, s -> Inv.isLog(s) || Inv.isPlank(s) || Inv.isStick(s)) == 0);
	}

	/** Stands at the origin, visits each chest that has something to take, then comes back. */
	static final class TripAction implements Miner.Action {
		private final BlockPos origin;
		private final Direction tunnelDir;
		private final List<Integer> order = new ArrayList<>();
		private boolean[] full;
		private int stage, idx, delay, waitTicks, pass, moved;
		/** Assist Path walk to the chest (or back), then the last bit directly. */
		private com.seolwoni.assistpath.walk.PathWalker walker;
		private BlockPos walkerFor;
		private Vec3 aim;
		private Direction face;
		private String reason = "";
		private Miner.Action finalDiscard;

		private final boolean finishing;

		TripAction(BlockPos origin, Direction tunnelDir) {
			this(origin, tunnelDir, false);
		}

		/** finishing = the job is over: store everything and do not fetch supplies. */
		TripAction(BlockPos origin, Direction tunnelDir, boolean finishing) {
			this.finishing = finishing;
			this.origin = origin;
			this.tunnelDir = tunnelDir;
		}

		@Override
		public String failReason() {
			return reason;
		}

		private ChestEntry chest() {
			return MineConfig.get().chests.get(order.get(idx));
		}

		private boolean hasWorkFor(LocalPlayer p, int chestIndex) {
			Set<Integer> keep = keepSet(p);
			for (int i = 0; i < 36; i++) {
				ItemStack s = p.getInventory().getItem(i);
				if (s.isEmpty() || keep.contains(i)) continue;
				if (target(Inv.id(s), full) == chestIndex) return true;
			}
			return needsFrom(p);
		}

		private boolean needsFrom(LocalPlayer p) {
			return !finishing && needsSupplies(p);
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			MineConfig cfg = MineConfig.get();
			switch (stage) {
				case 0 -> {
					if (cfg.chests.isEmpty()) {
						reason = "보관 상자가 없어요";
						return Miner.FAIL;
					}
					full = new boolean[cfg.chests.size()];
					order.clear();
					// Chests with an item list first, overflow chests after.
					for (int i = 0; i < cfg.chests.size(); i++) if (!cfg.chests.get(i).items.isEmpty()) order.add(i);
					for (int i = 0; i < cfg.chests.size(); i++) if (cfg.chests.get(i).items.isEmpty()) order.add(i);
					idx = 0;
					stage = 1;
					Miner.status("상자에 보관하는 중");
					return Miner.RUN;
				}
				case 1 -> { // pick next chest that has work
					while (idx < order.size() && !hasWorkFor(p, order.get(idx))) idx++;
					if (idx >= order.size()) {
						if (++pass < 2 && Inv.freeSlots(p) <= 1) {
							idx = 0;
							return Miner.RUN;
						}
						stage = 7;
						return Miner.RUN;
					}
					BlockPos c = pos(chest());
					if (mc.level.getBlockEntity(c) == null) {
						full[order.get(idx)] = true;
						idx++;
						return Miner.RUN;
					}
					Body.resetWalk();
					Body.newTurn();
					stage = 2;
					return Miner.RUN;
				}
				case 2 -> { // approach until within reach
					BlockPos c = pos(chest());
					if (p.getEyePosition().distanceTo(Vec3.atCenterOf(c)) <= 4.0) {
						Body.release(mc);
						face = Miner.faceToward(p.getEyePosition(), c);
						aim = Miner.facePoint(c, face, 0.1);
						delay = Human.delay(2, 6);
						stage = 3;
						return Miner.RUN;
					}
					if (!c.equals(walkerFor)) {
						walkerFor = c;
						walker = new com.seolwoni.assistpath.walk.PathWalker(new com.seolwoni.assistpath.goals.GoalNear(c, 2));
					}
					if (walker != null) {
						if (walker.tick(mc, p) == Miner.RUN) return Miner.RUN;
						walker = null;
						Body.resetWalk();
						Body.newTurn();
						return Miner.RUN;
					}
					Body.walkTo(p, c);
					if (Body.walkTicks() > 160) {
						Body.release(mc);
						reason = "보관 상자까지 갈 수 없어요 (시작 지점 8칸 안에 두세요)";
						return Miner.FAIL;
					}
					return Miner.RUN;
				}
				case 3 -> { // look and open
					if (!Body.lookAt(p, aim, 3f) || delay-- > 0) return Miner.RUN;
					mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(aim, face, pos(chest()), false));
					p.swing(InteractionHand.MAIN_HAND);
					waitTicks = 40;
					stage = 4;
					return Miner.RUN;
				}
				case 4 -> { // wait for the screen
					if (open(mc) != null) {
						delay = Human.delay(3, 8);
						stage = 5;
						return Miner.RUN;
					}
					if (--waitTicks <= 0) {
						reason = "상자를 열지 못했어요";
						return Miner.FAIL;
					}
					return Miner.RUN;
				}
				case 5 -> { // deposit, then take what we are short of
					AbstractContainerScreen<?> s = open(mc);
					if (s == null) {
						stage = 1;
						idx++;
						return Miner.RUN;
					}
					if (delay-- > 0) return Miner.RUN;
					AbstractContainerMenu menu = s.getMenu();
					if (depositOne(mc, p, menu) || takeOne(mc, p, menu)) {
						delay = Human.delay(1, 4);
						return Miner.RUN;
					}
					delay = Human.delay(3, 8);
					stage = 6;
					return Miner.RUN;
				}
				case 6 -> { // close
					if (delay-- > 0) return Miner.RUN;
					if (open(mc) != null) p.closeContainer();
					idx++;
					stage = 1;
					return Miner.RUN;
				}
				case 7 -> { // throw junk away from the tunnel, then back to the origin block
					if (finalDiscard == null) finalDiscard = new Chores.DiscardAction(tunnelDir.getOpposite(), false);
					if (finalDiscard.tick(mc, p) == Miner.RUN) return Miner.RUN;
					Body.resetWalk();
					stage = 8;
					return Miner.RUN;
				}
				default -> {
					if (!origin.equals(walkerFor)) {
						walkerFor = origin;
						walker = new com.seolwoni.assistpath.walk.PathWalker(new com.seolwoni.assistpath.goals.GoalBlock(origin));
					}
					if (walker != null) {
						if (walker.tick(mc, p) == Miner.RUN) return Miner.RUN;
						walker = null;
						Body.resetWalk();
						return Miner.RUN;
					}
					if (!Body.walkTo(p, origin)) {
						if (Body.walkTicks() > 160) {
							reason = "시작 지점으로 돌아오지 못했어요";
							return Miner.FAIL;
						}
						return Miner.RUN;
					}
					if (Inv.freeSlots(p) <= 1) {
						Miner.stop("보관 상자가 가득 찼어요", "상자를 비우거나 더 지정해주세요", true);
						return Miner.RUN;
					}
					Alerts.actionBar("보관 완료 (" + moved + "칸)", 0xFF55FF55);
					com.seolwoni.mineassist.hud.MineStats.trip();
					return Miner.DONE;
				}
			}
		}

		private static AbstractContainerScreen<?> open(Minecraft mc) {
			return Chests.openScreen(mc);
		}

		private boolean depositOne(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu) {
			int here = order.get(idx);
			Set<Integer> keep = keepSet(p);
			for (int i = 0; i < menu.slots.size(); i++) {
				Slot slot = menu.slots.get(i);
				if (!(slot.container instanceof Inventory)) continue;
				int invIndex = slot.getContainerSlot();
				if (invIndex >= 36 || keep.contains(invIndex)) continue;
				ItemStack s = slot.getItem();
				if (s.isEmpty() || target(Inv.id(s), full) != here) continue;
				int before = s.getCount();
				ItemStack copy = s.copy();
				mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, p);
				ItemStack after = slot.getItem();
				if (!after.isEmpty() && after.getCount() == before && ItemStack.isSameItemSameComponents(after, copy)) {
					full[here] = true;
					return false;
				}
				moved++;
				return true;
			}
			return false;
		}

		/** Takes one stack of something we are running out of: torches, coal, wood, a pickaxe, food. */
		private boolean takeOne(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu) {
			if (finishing || Inv.freeSlots(p) <= 1) return false;
			MineConfig cfg = MineConfig.get();
			List<java.util.function.Predicate<ItemStack>> wants = new ArrayList<>();
			if (cfg.torches && Inv.count(p, Inv::isTorch) < cfg.torchKeep / 2) wants.add(Inv::isTorch);
			if (Inv.count(p, Inv::usablePick) == 0) wants.add(Inv::usablePick);
			if (cfg.autoEat && Inv.count(p, Inv::isFood) < cfg.foodKeep) wants.add(Inv::isFood);
			if (cfg.torches && Inv.count(p, Inv::isCoal) < 4) wants.add(Inv::isCoal);
			if (cfg.torches && Inv.count(p, s -> Inv.isLog(s) || Inv.isPlank(s) || Inv.isStick(s)) == 0) wants.add(Inv::isLog);
			for (java.util.function.Predicate<ItemStack> want : wants) {
				for (int i = 0; i < menu.slots.size(); i++) {
					Slot slot = menu.slots.get(i);
					if (slot.container instanceof Inventory) continue;
					if (!want.test(slot.getItem())) continue;
					mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, p);
					return true;
				}
			}
			return false;
		}
	}

	static boolean isChest(BlockPos p) {
		return Blocks2.level().getBlockEntity(p) instanceof Container;
	}
}
