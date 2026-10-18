package com.seolwoni.mineassist.util;

import java.util.function.Predicate;

import com.seolwoni.mineassist.config.MineConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Inventory queries and single-click actions on the player's own inventory menu. */
public final class Inv {
	private Inv() {
	}

	public static String id(ItemStack s) {
		return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
	}

	public static String id(Item i) {
		return BuiltInRegistries.ITEM.getKey(i).toString();
	}

	// ---- item kinds ----
	public static boolean isPick(ItemStack s) {
		return !s.isEmpty() && s.is(ItemTags.PICKAXES);
	}

	public static boolean isTorch(ItemStack s) {
		return !s.isEmpty() && s.is(Items.TORCH);
	}

	public static boolean isPlug(ItemStack s) {
		return !s.isEmpty() && (s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE));
	}

	public static boolean isCoal(ItemStack s) {
		return !s.isEmpty() && (s.is(Items.COAL) || s.is(Items.CHARCOAL));
	}

	public static boolean isLog(ItemStack s) {
		return !s.isEmpty() && s.is(ItemTags.LOGS);
	}

	public static boolean isPlank(ItemStack s) {
		return !s.isEmpty() && s.is(ItemTags.PLANKS);
	}

	public static boolean isStick(ItemStack s) {
		return !s.isEmpty() && s.is(Items.STICK);
	}

	public static boolean isFood(ItemStack s) {
		if (s.isEmpty() || !s.has(DataComponents.FOOD)) return false;
		return !(s.is(Items.ROTTEN_FLESH) || s.is(Items.PUFFERFISH) || s.is(Items.SPIDER_EYE)
				|| s.is(Items.POISONOUS_POTATO) || s.is(Items.SUSPICIOUS_STEW) || s.is(Items.CHORUS_FRUIT)
				|| s.is(Items.CHICKEN) || s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)
				|| s.is(Items.GOLDEN_CARROT));
	}

	public static int remaining(ItemStack s) {
		if (!s.isDamageableItem()) return Integer.MAX_VALUE;
		return s.getMaxDamage() - s.getDamageValue();
	}

	public static boolean usablePick(ItemStack s) {
		if (!isPick(s)) return false;
		MineConfig c = MineConfig.get();
		boolean protect = c.pickMode == 1 || c.pickMode == 3;
		return !protect || remaining(s) > c.protectDurability;
	}

	// ---- counting ----
	public static int count(LocalPlayer p, Predicate<ItemStack> f) {
		Inventory inv = p.getInventory();
		int n = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (f.test(s)) n += s.getCount();
		}
		ItemStack off = inv.getItem(40);
		if (f.test(off)) n += off.getCount();
		return n;
	}

	public static int find(LocalPlayer p, Predicate<ItemStack> f, int from, int to) {
		Inventory inv = p.getInventory();
		for (int i = from; i < to; i++) if (f.test(inv.getItem(i))) return i;
		return -1;
	}

	public static int freeSlots(LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
		return n;
	}

	// ---- clicking ----
	public static int menuSlot(int invIndex) {
		return invIndex < 9 ? InventoryMenu.USE_ROW_SLOT_START + invIndex : invIndex;
	}

	public static boolean canClick(Minecraft mc, LocalPlayer p) {
		Screen s = mc.gui.screen();
		if (s instanceof AbstractContainerScreen<?> && !(s instanceof InventoryScreen)) return false;
		return p.inventoryMenu.getCarried().isEmpty();
	}

	public static void click(Minecraft mc, LocalPlayer p, int menuSlot, int button, ContainerInput input) {
		mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, menuSlot, button, input, p);
	}

	/** Swap a main-inventory slot with hotbar slot {@code hotbar}. */
	public static void swapToHotbar(Minecraft mc, LocalPlayer p, int invIndex, int hotbar) {
		click(mc, p, menuSlot(invIndex), hotbar, ContainerInput.SWAP);
	}

	/**
	 * Makes sure an item matching {@code f} is in the hotbar and returns its hotbar slot,
	 * or -1 if there is none. Never displaces a pickaxe if it can avoid it.
	 */
	public static int bringToHotbar(Minecraft mc, LocalPlayer p, Predicate<ItemStack> f) {
		int h = find(p, f, 0, 9);
		if (h != -1) return h;
		int m = find(p, f, 9, 36);
		if (m == -1 || !canClick(mc, p)) return -1;
		Inventory inv = p.getInventory();
		int dst = -1;
		for (int i = 8; i >= 0; i--) {
			if (inv.getItem(i).isEmpty()) {
				dst = i;
				break;
			}
		}
		if (dst == -1) {
			for (int i = 8; i >= 0; i--) {
				ItemStack s = inv.getItem(i);
				if (!isPick(s) && !isTorch(s) && !isFood(s) && !isPlug(s)) {
					dst = i;
					break;
				}
			}
		}
		if (dst == -1) dst = 8;
		swapToHotbar(mc, p, m, dst);
		return dst;
	}

	public static void select(LocalPlayer p, int hotbar) {
		if (p.getInventory().getSelectedSlot() != hotbar) p.getInventory().setSelectedSlot(hotbar);
	}

	/**
	 * Puts the pickaxe to use in hand according to the pickaxe mode. Returns false when no usable
	 * pickaxe is available (the caller stops).
	 */
	public static boolean selectPickaxe(Minecraft mc, LocalPlayer p) {
		MineConfig c = MineConfig.get();
		Inventory inv = p.getInventory();
		if (c.pickMode <= 1) {
			return usablePick(p.getMainHandItem());
		}
		int best = -1, bestRem = Integer.MAX_VALUE;
		for (int i = 0; i < 9; i++) {
			ItemStack s = inv.getItem(i);
			if (!usablePick(s)) continue;
			int r = remaining(s);
			if (r < bestRem) {
				best = i;
				bestRem = r;
			}
		}
		if (best == -1) {
			// Bring one in from the main inventory.
			int m = -1;
			for (int i = 9; i < 36; i++) {
				ItemStack s = inv.getItem(i);
				if (!usablePick(s)) continue;
				int r = remaining(s);
				if (m == -1 || r < bestRem) {
					m = i;
					bestRem = r;
				}
			}
			if (m == -1 || !canClick(mc, p)) return false;
			int dst = -1;
			for (int i = 0; i < 9; i++) {
				ItemStack s = inv.getItem(i);
				if (s.isEmpty() || (isPick(s) && !usablePick(s))) {
					dst = i;
					break;
				}
			}
			if (dst == -1) dst = inv.getSelectedSlot();
			swapToHotbar(mc, p, m, dst);
			best = dst;
		}
		select(p, best);
		return true;
	}
}
