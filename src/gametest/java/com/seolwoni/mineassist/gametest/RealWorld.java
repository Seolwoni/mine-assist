package com.seolwoni.mineassist.gametest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Helpers for the "real map" tests: a normally generated world (fixed seed, peaceful, villages on), places
 * found the way a player would go there, timing, screenshots every minute and a report file.
 *
 * Which test runs is chosen with the ASSIST_GT environment variable (the CI runs several in parallel):
 * "flat" (default) is the prepared test field, "real-..." are the real-map suites.
 */
final class RealWorld {
	static final String SEED = "seolwoni-assist";

	private RealWorld() {
	}

	static String suite() {
		String s = System.getenv("ASSIST_GT");
		return s == null || s.isBlank() ? "flat" : s.trim();
	}

	static TestSingleplayerContext create(ClientGameTestContext context) {
		return context.worldBuilder().setUseConsistentSettings(false).adjustSettings(c -> {
			c.setSeed(SEED);
			c.setGenerateStructures(true);
			c.setDifficulty(Difficulty.PEACEFUL);
			c.getGameRules().set(GameRules.ADVANCE_TIME, false, null);
			c.getGameRules().set(GameRules.ADVANCE_WEATHER, false, null);
		}).create();
	}

	static void cmd(TestSingleplayerContext sp, String c) {
		sp.getServer().runCommand(c);
	}

	/** Nearest place with this biome (like /locate biome), or null. */
	static BlockPos findBiome(TestSingleplayerContext sp, ResourceKey<Biome> biome, BlockPos from) {
		return sp.getServer().computeOnServer(server -> {
			var r = server.overworld().findClosestBiome3d(h -> h.is(biome), from, 6400, 32, 64);
			return r == null ? null : r.getFirst();
		});
	}

	/** Nearest village (like /locate structure #village), or null. */
	static BlockPos findVillage(TestSingleplayerContext sp, BlockPos from) {
		return sp.getServer().computeOnServer(server -> server.overworld().findNearestMapStructure(StructureTags.VILLAGE, from, 100, false));
	}

	/** Loads the chunks around a place and puts the player on the ground there. Returns the ground (feet) height. */
	static int goTo(ClientGameTestContext context, TestSingleplayerContext sp, int x, int z, int radius) {
		cmd(sp, "forceload add " + (x - radius) + " " + (z - radius) + " " + (x + radius) + " " + (z + radius));
		// The height is read on the server (that generates the chunk), so the player is put straight on the ground.
		int y = surface(sp, x, z);
		cmd(sp, "tp @a " + (x + 0.5) + " " + y + " " + (z + 0.5));
		context.waitTicks(40);
		sp.getConnection().waitForChunksRender();
		return y;
	}

	/** Height of the first free block above the ground (ignoring leaves). */
	static int surface(TestSingleplayerContext sp, int x, int z) {
		return sp.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
	}

	/** Average server tick time in ms (how heavy the game is right now). */
	static double mspt(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(server -> server.getAverageTickTimeNanos() / 1_000_000.0);
	}

	static void setup(TestSingleplayerContext sp) {
		cmd(sp, "difficulty peaceful");
		cmd(sp, "time set noon");
		cmd(sp, "weather clear");
		cmd(sp, "gamemode survival @a");
		cmd(sp, "clear @a");
		cmd(sp, "effect clear @a");
	}

	/** Collects the timing of each 40-tick step. */
	static final class Timing {
		private long worst, total;
		private int n;

		void add(long nanos) {
			worst = Math.max(worst, nanos);
			total += nanos;
			n++;
		}

		String summary() {
			if (n == 0) return "timing: none";
			return String.format("timing: 40 ticks took %.0f ms on average (game speed %.0f%%), slowest %.0f ms", total / (double) n / 1e6,
					Math.min(100.0, 2000.0 / (total / (double) n / 1e6) * 100), worst / 1e6);
		}
	}

	static void writeReport(String name, List<String> failures, List<String> log) {
		try {
			List<String> out = new ArrayList<>();
			out.add(failures.isEmpty() ? "RESULT: PASS" : "RESULT: FAIL (" + failures.size() + ")");
			for (String f : failures) out.add("  FAIL " + f);
			out.add("");
			out.addAll(log);
			Path p = FabricLoader.getInstance().getGameDir().resolve(name + "-test-result.txt");
			Files.write(p, out, StandardCharsets.UTF_8);
		} catch (Exception ignored) {
		}
	}
}
