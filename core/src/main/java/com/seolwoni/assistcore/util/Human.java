package com.seolwoni.assistcore.util;

import java.util.Random;

/** Human-looking randomness: clustered delays with an occasional slow reaction. */
public final class Human {
	public static final Random RANDOM = new Random();

	/**
	 * Whether delays use the human-like distribution. Each mod sets this from its own setting
	 * at the start of its tick (all mods tick on the same thread, one after another).
	 */
	public static boolean humanLike = true;

	private Human() {
	}

	public static int rand(int min, int max) {
		if (max <= min) return min;
		return min + RANDOM.nextInt(max - min + 1);
	}

	public static int delay(int min, int max) {
		if (max <= min) return min;
		if (!humanLike) return rand(min, max);
		double span = max - min;
		double v = RANDOM.nextDouble() < 0.08
				? min + span * (0.6 + 0.4 * RANDOM.nextDouble())
				: min + span * 0.3 + RANDOM.nextGaussian() * span * 0.15;
		return (int) Math.max(min, Math.min(max, Math.round(v)));
	}

	/** A small random offset (in blocks) for aim points, so the crosshair never lands on the exact centre. */
	public static double jitter(double amount) {
		return (RANDOM.nextDouble() * 2 - 1) * amount;
	}
}
