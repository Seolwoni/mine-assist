package com.seolwoni.assistcore.util;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;

/** What changed between Minecraft versions, in one place (26.3: the swing takes its animation). */
public final class Compat {
	private Compat() {
	}

	public static void swing(LivingEntity p, InteractionHand hand) {
		p.swing(hand, p.getItemInHand(hand).getSwingAnimation(), true);
	}
}
