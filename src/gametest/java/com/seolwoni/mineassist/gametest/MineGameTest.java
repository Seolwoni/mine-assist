package com.seolwoni.mineassist.gametest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.seolwoni.mineassist.config.MineConfig;
import com.seolwoni.mineassist.mining.Miner;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One full, short job in a prepared stone block with the things that broke before:
 * gravel falling into the stairs, a cave under the stairs, a cave across the main tunnel,
 * and iron ore in the wall whose drops have to be picked up.
 *
 * Layout (origin O = 0,100,0 facing south, R = west):
 *   stairs S(i) = (0, 100-i, i), i = 1..12   main M(k) = (0, 88, 12+k), lane 1 = x-1
 *   left branch (+x), right branch (-x), every 3 steps, 4 long
 */
public class MineGameTest implements FabricClientGameTest {
	private static final int STAIRS = 12, MAIN = 12, BRANCH = 4, SPACING = 3;
	private final List<String> log = new ArrayList<>();
	private final List<String> failures = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!RealWorld.suite().equals("flat")) return;
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			buildWorld(sp);
			context.waitTicks(40);
			sp.getConnection().waitForChunksRender();

			context.runOnClient(mc -> {
				MineConfig c = MineConfig.get();
				c.modEnabled = true;
				c.resume = false;
				c.targetY = 100 - STAIRS;
				c.mainLength = MAIN;
				c.branchLength = BRANCH;
				c.branchSpacing = SPACING;
				c.torches = true;
				c.torchInterval = 4;
				c.craftTorches = false;
				c.stopOnMob = false;
				c.storeEnabled = true;
				c.chests.clear();
				com.seolwoni.assistcore.storage.ChestEntry ch = new com.seolwoni.assistcore.storage.ChestEntry();
				ch.x = 2;
				ch.y = 100;
				ch.z = -2;
				c.chests.add(ch);
				c.pace = 1;
				c.foodKeep = 16;
				c.autoEat = true;
				Miner.resetJob();
				mc.player.setYRot(0f);
				mc.player.setXRot(10f);
				if (!Miner.start(mc)) failures.add("Miner.start returned false");
				if (Miner.job().mainK != 0) failures.add("new job does not start at main step 0 (mainK=" + Miner.job().mainK + ")");
			});
			context.takeScreenshot("mine-00-start");
			// It should first fetch food from the chest (it starts with none).
			context.waitTicks(200);
			int food = context.computeOnClient(mc -> {
				int n = 0;
				for (int i = 0; i < 36; i++) if (mc.player.getInventory().getItem(i).is(Items.BREAD)) n += mc.player.getInventory().getItem(i).getCount();
				return n;
			});
			log.add("bread after setting off: " + food);
			if (food < 16) failures.add("did not take food from the chest before starting (has " + food + ")");

			int shots = 1;
			int ticks = 0;
			final int limit = 20 * 60 * 12;
			boolean running = true;
			MineRealTest.DropTracker drops = new MineRealTest.DropTracker();
			while (running && ticks < limit) {
				for (int k = 0; k < 8; k++) {
					context.waitTicks(5);
					drops.scan(context, sp, ticks + 5 * (k + 1));
				}
				ticks += 40;
				String line = context.computeOnClient(mc -> String.format("t=%5d pos=%s status=%s",
						0, mc.player.blockPosition().toShortString(), Miner.status()));
				log.add(line.replace("t=    0", "t=" + ticks));
				running = context.computeOnClient(mc -> MineConfig.get().running);
				if (running && ticks == 2400) {
					// Stopped half way (like a player sorting something out down in the mine) and switched on again right
					// there, not at the start: it should carry on from where the job got to.
					String at = context.computeOnClient(mc -> {
						Miner.stop(null, null, false);
						return mc.player.blockPosition().toShortString();
					});
					context.waitTicks(40);
					boolean again = context.computeOnClient(mc -> Miner.start(mc));
					log.add("stopped at " + at + " and switched on again there: " + (again ? "carrying on" : "refused: " + Miner.lastStop));
					if (!again) failures.add("would not carry on from where it was stopped (" + at + ")");
				}
				if (ticks % 600 == 0 && shots < 25) context.takeScreenshot(String.format("mine-%02d-t%d", shots++, ticks));
			}
			context.takeScreenshot("mine-99-end");

			String stop = context.computeOnClient(mc -> Miner.lastStop);
			log.add("stopped after " + ticks + " ticks: " + stop);
			log.add(com.seolwoni.assistpath.AssistPath.Stats.summary());
			if (running) {
				failures.add("did not finish within " + limit + " ticks");
				context.runOnClient(mc -> Miner.stop(null, null, false));
			} else if (!stop.startsWith("채광 완료")) {
				failures.add("stopped early: " + stop);
			}

			double home = context.computeOnClient(mc -> mc.player.position().distanceTo(new net.minecraft.world.phys.Vec3(0.5, 100, 0.5)));
			if (!running && stop.startsWith("채광 완료") && home > 2.0) failures.add("finished but not back at the start (distance " + home + ")");

			int iron = context.computeOnClient(mc -> {
				int n = 0;
				for (int i = 0; i < 36; i++) {
					if (mc.player.getInventory().getItem(i).is(Items.RAW_IRON)) n += mc.player.getInventory().getItem(i).getCount();
				}
				return n;
			});
			int stored = sp.getServer().computeOnServer(server -> {
				int n = 0;
				if (server.overworld().getBlockEntity(new BlockPos(2, 100, -2)) instanceof net.minecraft.world.Container ct) {
					for (int i = 0; i < ct.getContainerSize(); i++) if (ct.getItem(i).is(Items.RAW_IRON)) n += ct.getItem(i).getCount();
				}
				return n;
			});
			log.add("raw iron in inventory: " + iron + ", in chest: " + stored);
			drops.report(sp, log);
			for (String n : context.computeOnClient(mc -> new java.util.ArrayList<>(com.seolwoni.mineassist.mining.Miner.pocketLog))) log.add("  pocket: " + n);
			if (iron + stored < 3) failures.add("picked up only " + (iron + stored) + " of 3 raw iron");
			if (stored < 3 && !running) failures.add("finished without putting the raw iron in the chest");

			checkWorld(sp);
		} catch (Throwable t) {
			failures.add("exception: " + t);
			throw t;
		} finally {
			writeReport();
		}
		if (!failures.isEmpty()) throw new AssertionError("Mine Assist test failed: " + String.join("; ", failures));
	}

	private static void cmd(TestSingleplayerContext sp, String c) {
		sp.getServer().runCommand(c);
	}

	private void buildWorld(TestSingleplayerContext sp) {
		cmd(sp, "forceload add -16 -8 16 44");
		cmd(sp, "difficulty peaceful");
		cmd(sp, "time set noon");
		// Solid stone block, with air above the surface at y 100.
		cmd(sp, "fill -12 70 -3 12 84 40 minecraft:stone");
		cmd(sp, "fill -12 85 -3 12 99 40 minecraft:stone");
		cmd(sp, "fill -12 100 -3 12 110 40 minecraft:air");
		// A cave under and around stairs 4..9 (no floor, no walls).
		cmd(sp, "fill -2 85 4 2 97 9 minecraft:air");
		// A cave across the main tunnel, steps 4..7.
		cmd(sp, "fill -4 84 16 3 90 19 minecraft:air");
		// Lush cave bits in that cave, right where the tunnel walls go (main step 5): glow berry vines hanging from the
		// roof on both sides, and a moss carpet on a ledge. They are no wall: mobs walk through them.
		for (int x : new int[] { 1, -2 }) {
			cmd(sp, "setblock " + x + " 90 17 minecraft:cave_vines_plant");
			cmd(sp, "setblock " + x + " 89 17 minecraft:cave_vines_plant");
			cmd(sp, "setblock " + x + " 88 17 minecraft:cave_vines[berries=true]");
		}
		cmd(sp, "setblock 1 87 18 minecraft:moss_block");
		cmd(sp, "setblock 1 88 18 minecraft:moss_carpet");
		// Gravel above stair 2 and above stair 10: it falls into the path while digging.
		cmd(sp, "setblock 0 100 2 minecraft:gravel");
		cmd(sp, "setblock 0 101 2 minecraft:gravel");
		cmd(sp, "setblock 0 93 10 minecraft:gravel");
		cmd(sp, "setblock 0 94 10 minecraft:gravel");
		// Iron ore in the left wall of the main tunnel at step 8.
		cmd(sp, "setblock 1 88 20 minecraft:iron_ore");
		cmd(sp, "setblock 1 89 20 minecraft:iron_ore");
		cmd(sp, "setblock 2 89 20 minecraft:iron_ore");

		cmd(sp, "gamemode survival @a");
		cmd(sp, "clear @a");
		cmd(sp, "give @a minecraft:netherite_pickaxe");
		cmd(sp, "give @a minecraft:torch 64");
		cmd(sp, "give @a minecraft:cobblestone 128");
		cmd(sp, "setblock 2 100 -2 minecraft:chest[facing=north]");
		cmd(sp, "item replace block 2 100 -2 container.0 with minecraft:bread 32");
		cmd(sp, "effect give @a minecraft:haste infinite 1 true");
		cmd(sp, "effect give @a minecraft:night_vision infinite 0 true");
		cmd(sp, "effect give @a minecraft:saturation infinite 0 true");
		cmd(sp, "tp @a 0.5 100 0.5 0 10");
	}

	private void checkWorld(TestSingleplayerContext sp) {
		List<String> problems = sp.getServer().computeOnServer(server -> {
			List<String> out = new ArrayList<>();
			var level = server.overworld();
			int torches = 0;
			for (int x = -12; x <= 12; x++) {
				for (int y = 70; y <= 110; y++) {
					for (int z = -3; z <= 40; z++) {
						BlockState s = level.getBlockState(new BlockPos(x, y, z));
						if (s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH)) torches++;
					}
				}
			}
			out.add("info: torches placed = " + torches);
			if (torches < 4) out.add("only " + torches + " torches placed");
			for (int i = 1; i <= STAIRS; i++) {
				BlockPos s = new BlockPos(0, 100 - i, i);
				for (int h = 0; h <= 2; h++) {
					if (!open(level.getBlockState(s.above(h)))) out.add("stair " + i + " not open at +" + h);
				}
				if (!level.getBlockState(s.below()).isCollisionShapeFullBlock(level, s.below())) out.add("stair " + i + " has no floor");
			}
			for (int k = 1; k <= MAIN; k++) {
				for (int lane = 0; lane <= 1; lane++) {
					BlockPos m = new BlockPos(-lane, 88, 12 + k);
					for (int h = 0; h <= 1; h++) {
						if (!open(level.getBlockState(m.above(h)))) out.add("main " + k + "/" + lane + " not open at +" + h);
					}
					if (!level.getBlockState(m.below()).isCollisionShapeFullBlock(level, m.below())) out.add("main " + k + "/" + lane + " has no floor");
				}
				if (k % SPACING == 0) {
					for (int d = 1; d <= BRANCH; d++) {
						BlockPos l = new BlockPos(d, 88, 12 + k), r = new BlockPos(-1 - d, 88, 12 + k);
						if (!open(level.getBlockState(l)) || !open(level.getBlockState(l.above()))) out.add("left branch " + k + " not dug at " + d);
						if (!open(level.getBlockState(r)) || !open(level.getBlockState(r.above()))) out.add("right branch " + k + " not dug at " + d);
					}
				}
			}
			// The main tunnel cave should be walled off like a tunnel.
			for (int k = 4; k <= 7; k++) {
				BlockPos wallL = new BlockPos(1, 88, 12 + k), wallR = new BlockPos(-2, 88, 12 + k);
				if (k % SPACING == 0) continue;
				// A real wall: vines or a carpet left hanging there do not count.
				if (!level.getBlockState(wallL).isCollisionShapeFullBlock(level, wallL)) out.add("cave wall missing left of main " + k + " (" + level.getBlockState(wallL).getBlock() + ")");
				if (!level.getBlockState(wallR).isCollisionShapeFullBlock(level, wallR)) out.add("cave wall missing right of main " + k + " (" + level.getBlockState(wallR).getBlock() + ")");
			}
			return out;
		});
		for (String p : problems) {
			if (p.startsWith("info: ")) log.add(p.substring(6));
			else failures.add(p);
		}
	}

	private static boolean open(BlockState s) {
		return s.isAir() || s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH);
	}

	private void writeReport() {
		try {
			List<String> out = new ArrayList<>();
			out.add(failures.isEmpty() ? "RESULT: PASS" : "RESULT: FAIL (" + failures.size() + ")");
			for (String f : failures) out.add("  FAIL " + f);
			out.add("");
			out.addAll(log);
			Path p = FabricLoader.getInstance().getGameDir().resolve("mineassist-test-result.txt");
			Files.write(p, out, StandardCharsets.UTF_8);
		} catch (Exception ignored) {
		}
	}
}
