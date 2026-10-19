package com.seolwoni.mineassist.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.seolwoni.assistcore.storage.ChestEntry;
import com.seolwoni.mineassist.config.MineConfig;
import com.seolwoni.mineassist.hud.MineStats;
import com.seolwoni.mineassist.mining.Miner;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

/**
 * Real-map tests for Mine Assist (generated world, fixed seed, peaceful): a full job from the surface.
 * real-plains and real-forest start on the ground in that biome, facing south, stairs 30 blocks down,
 * a 16 long main tunnel with 5 long branches every 3 blocks. Diamond pickaxes, torches, cobblestone, a
 * chest with bread next to the start, like a player would set out. No haste.
 */
public class MineRealTest implements FabricClientGameTest {
	private final List<String> log = new ArrayList<>();
	private final List<String> failures = new ArrayList<>();
	private final RealWorld.Timing timing = new RealWorld.Timing();

	@Override
	public void runTest(ClientGameTestContext context) {
		String suite = RealWorld.suite();
		ResourceKey<Biome> biome = switch (suite) {
			case "real-plains" -> Biomes.PLAINS;
			case "real-forest" -> Biomes.FOREST;
			default -> null;
		};
		if (biome == null) return;
		try (TestSingleplayerContext sp = RealWorld.create(context)) {
			sp.getConnection().waitForChunksRender();
			RealWorld.setup(sp);
			// Start a little away from spawn so the two suites are different places.
			BlockPos from = suite.equals("real-plains") ? new BlockPos(300, 64, 0) : new BlockPos(-300, 64, 300);
			BlockPos b = RealWorld.findBiome(sp, biome, from);
			if (b == null) {
				failures.add("biome not found: " + biome);
				return;
			}
			int x = b.getX(), z = b.getZ();
			int g = RealWorld.goTo(context, sp, x, z, 64);
			log.add("start in " + biome + " at " + x + ", " + g + ", " + z);
			// A clear spot to start from (trees or tall grass would only be in the way of the first steps).
			RealWorld.cmd(sp, "fill " + (x - 2) + " " + g + " " + (z - 3) + " " + (x + 3) + " " + (g + 6) + " " + (z + 1) + " minecraft:air");
			RealWorld.cmd(sp, "setblock " + (x + 2) + " " + g + " " + (z - 2) + " minecraft:chest[facing=north]");
			RealWorld.cmd(sp, "item replace block " + (x + 2) + " " + g + " " + (z - 2) + " container.0 with minecraft:bread 32");
			RealWorld.cmd(sp, "give @a minecraft:diamond_pickaxe");
			RealWorld.cmd(sp, "give @a minecraft:diamond_pickaxe");
			RealWorld.cmd(sp, "give @a minecraft:torch 64");
			RealWorld.cmd(sp, "give @a minecraft:cobblestone 128");
			RealWorld.cmd(sp, "effect give @a minecraft:night_vision infinite 0 true");
			RealWorld.cmd(sp, "effect give @a minecraft:saturation infinite 0 true");
			RealWorld.cmd(sp, "tp @a " + (x + 0.5) + " " + g + " " + (z + 0.5) + " 0 10");
			context.waitTicks(40);
			sp.getConnection().waitForChunksRender();
			final int startY = g;
			BlockPos chest = new BlockPos(x + 2, g, z - 2);

			context.runOnClient(mc -> {
				MineConfig c = MineConfig.get();
				c.modEnabled = true;
				c.resume = false;
				c.targetY = startY - 30;
				c.mainLength = 16;
				c.branchLength = 5;
				c.branchSpacing = 3;
				c.torches = true;
				c.torchInterval = 6;
				c.craftTorches = false;
				c.stopOnMob = false;
				c.storeEnabled = true;
				c.chests.clear();
				ChestEntry ch = new ChestEntry();
				ch.x = chest.getX();
				ch.y = chest.getY();
				ch.z = chest.getZ();
				c.chests.add(ch);
				c.pace = 1;
				c.foodKeep = 16;
				c.autoEat = true;
				Miner.resetJob();
				mc.player.setYRot(0f);
				mc.player.setXRot(10f);
				if (!Miner.start(mc)) failures.add("Miner.start returned false");
			});
			log.add("target y " + (startY - 30) + ", main 16, branches 5 every 3");
			context.takeScreenshot(suite + "-00-start");

			int ticks = 0, shots = 1;
			final int limit = 20 * 60 * 40;
			boolean running = true;
			float minHealth = 20f;
			while (running && ticks < limit) {
				long t0 = System.nanoTime();
				context.waitTicks(40);
				timing.add(System.nanoTime() - t0);
				ticks += 40;
				final int t = ticks;
				log.add(context.computeOnClient(mc -> "t=" + t + " pos=" + mc.player.blockPosition().toShortString() + " hp=" + (int) mc.player.getHealth()
						+ " status=" + Miner.status()));
				minHealth = Math.min(minHealth, context.computeOnClient(mc -> mc.player.getHealth()));
				running = context.computeOnClient(mc -> MineConfig.get().running);
				if (ticks % 1200 == 0) context.takeScreenshot(String.format("%s-%02d-min%d", suite, shots++, ticks / 1200));
			}
			context.takeScreenshot(suite + "-99-end");
			String stop = context.computeOnClient(mc -> Miner.lastStop);
			log.add("stopped after " + ticks + " ticks (" + ticks / 20 + " s): " + stop);
			log.add(timing.summary() + ", server tick " + String.format("%.1f", RealWorld.mspt(sp)) + " ms");
			log.add(com.seolwoni.assistpath.AssistPath.Stats.summary());
			log.add("lowest health " + minHealth);
			if (running) {
				failures.add("did not finish within " + limit + " ticks");
				context.runOnClient(mc -> Miner.stop(null, null, false));
			} else if (!stop.startsWith("채광 완료")) {
				failures.add("stopped early: " + stop);
			}
			if (minHealth < 14) failures.add("got hurt: health went down to " + minHealth);
			double home = context.computeOnClient(mc -> mc.player.position().distanceTo(new net.minecraft.world.phys.Vec3(x + 0.5, startY, z + 0.5)));
			if (!running && stop.startsWith("채광 완료") && home > 2.0) failures.add("finished but not back at the start (distance " + String.format("%.1f", home) + ")");
			context.runOnClient(mc -> {
				MineStats st = MineStats.session;
				log.add("blocks mined=" + st.blocks + " lava/water plugged=" + st.plugs + " chest trips=" + st.trips);
				for (Map.Entry<String, MineStats.Entry> e : st.ores.entrySet()) log.add("  ore " + e.getKey() + ": " + e.getValue().count);
				log.add("job: stairs " + Miner.job().stairsDone + ", main step " + Miner.job().mainK + ", ores " + Miner.job().oresMined);
			});
			if (!stop.startsWith("채광 완료")) {
				// What it was looking at when it gave up: the blocks around the player (x across, y down to up).
				BlockPos me = context.computeOnClient(mc -> mc.player.blockPosition());
				log.add("blocks around " + me.toShortString() + " (rows: z-1, z, z+1; each row x-3..x+3; levels y-3..y+2):");
				List<String> dump = sp.getServer().computeOnServer(server -> {
					List<String> out = new ArrayList<>();
					for (int dy = 2; dy >= -3; dy--) {
						StringBuilder sb = new StringBuilder("  y" + (me.getY() + dy) + ": ");
						for (int dz = -1; dz <= 1; dz++) {
							for (int dx = -3; dx <= 3; dx++) {
								var st = server.overworld().getBlockState(me.offset(dx, dy, dz));
								String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
								sb.append(id.equals("air") ? "." : id.equals("cave_air") ? ":" : id.substring(0, Math.min(4, id.length()))).append(' ');
							}
							sb.append("| ");
						}
						out.add(sb.toString());
					}
					return out;
				});
				log.addAll(dump);
			}
			String inChest = sp.getServer().computeOnServer(server -> {
				StringBuilder sb = new StringBuilder();
				if (server.overworld().getBlockEntity(chest) instanceof Container ct) {
					for (int i = 0; i < ct.getContainerSize(); i++) {
						ItemStack s = ct.getItem(i);
						if (!s.isEmpty()) sb.append(s.getItem()).append(" x").append(s.getCount()).append(", ");
					}
				}
				return sb.toString();
			});
			log.add("chest: " + inChest);
		} catch (Throwable t) {
			failures.add("exception: " + t);
			throw t;
		} finally {
			RealWorld.writeReport("mineassist-" + suite, failures, log);
		}
		if (!failures.isEmpty()) throw new AssertionError("Mine Assist " + suite + " failed: " + String.join("; ", failures));
	}
}
