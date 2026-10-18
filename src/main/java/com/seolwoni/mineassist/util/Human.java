package com.seolwoni.mineassist.util;

import java.util.Random;

import com.seolwoni.mineassist.config.MineConfig;

/** Human-looking randomness: clustered delays with an occasional slow reaction. */
public final class Human {
	public static final Random RANDOM = new Random();

	private Human() {
	}

	public static int rand(int min, int max) {
		if (max <= min) return min;
		return min + RANDOM.nextInt(max - min + 1);
	}

	public static int delay(int min, int max) {
		if (max <= min) return min;
		if (!MineConfig.get().humanDelay) return rand(min, max);
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
