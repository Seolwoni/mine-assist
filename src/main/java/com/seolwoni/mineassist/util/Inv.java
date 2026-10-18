package com.seolwoni.mineassist.util;

import java.util.function.Predicate;

import com.seolwoni.assistcore.util.InvBase;
import com.seolwoni.mineassist.config.MineConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Mining item kinds and pickaxe choice on top of the shared inventory helpers. */
public final class Inv extends InvBase {
	private Inv() {
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

	public static boolean usablePick(ItemStack s) {
		if (!isPick(s)) return false;
		MineConfig c = MineConfig.get();
		boolean protect = c.pickMode == 1 || c.pickMode == 3;
		return !protect || remaining(s) > c.protectDurability;
	}

	// ---- counting ----
	// ---- clicking ----
	/** Brings a matching item to the hotbar without pushing out the pickaxe, torches, food or cobblestone. */
	public static int bringToHotbar(Minecraft mc, LocalPlayer p, Predicate<ItemStack> f) {
		return InvBase.bringToHotbar(mc, p, f, s -> isPick(s) || isTorch(s) || isFood(s) || isPlug(s));
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
