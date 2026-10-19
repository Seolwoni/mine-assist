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
	private final java.util.Set<Integer> staleSeen = new java.util.HashSet<>();
	private int valuableLeft;
	private final DropTracker drops = new DropTracker();

	/**
	 * Diagnosis only (changes nothing in the game): follows every ore drop from the moment it appears until it is
	 * picked up or the test ends, and notes what the miner was doing whenever something happened to it.
	 */
	static final class DropTracker {
		static final java.util.regex.Pattern VALUABLE = java.util.regex.Pattern.compile("(raw_\\w+|coal|diamond|emerald|lapis_lazuli|redstone|quartz|gold_nugget|amethyst_shard)");

		static final class Drop {
			int id, count, born, gone = -1;
			String item, end = "";
			BlockPos cell;
			final List<String> story = new ArrayList<>();
			boolean trapped;
		}

		final Map<Integer, Drop> all = new java.util.LinkedHashMap<>();

		void scan(ClientGameTestContext context, TestSingleplayerContext sp, int tick) {
			String status = context.computeOnClient(mc -> Miner.status());
			sp.getServer().runOnServer(server -> {
				var level = server.overworld();
				var pl = server.getPlayerList().getPlayers().get(0);
				java.util.Set<Integer> alive = new java.util.HashSet<>();
				for (var ie : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, pl.getBoundingBox().inflate(12))) {
					String item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(ie.getItem().getItem()).getPath();
					if (!VALUABLE.matcher(item).matches()) continue;
					alive.add(ie.getId());
					BlockPos at = ie.blockPosition();
					String block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).getPath();
					boolean inside = !level.getBlockState(at).getCollisionShape(level, at).isEmpty();
					Drop d = all.get(ie.getId());
					if (d == null) {
						d = new Drop();
						d.id = ie.getId();
						d.item = item;
						d.count = ie.getItem().getCount();
						d.born = tick;
						d.cell = at;
						d.story.add("t" + tick + " appeared at " + at.toShortString() + (inside ? " INSIDE " + block : "") + " | player " + pl.blockPosition().toShortString() + " | " + status);
						all.put(d.id, d);
						continue;
					}
					d.count = ie.getItem().getCount();
					if (!at.equals(d.cell)) {
						d.story.add("t" + tick + " moved " + d.cell.toShortString() + " -> " + at.toShortString() + (inside ? " INSIDE " + block : "") + " | " + status);
						d.cell = at;
					}
					if (inside && !d.trapped) {
						d.story.add("t" + tick + " now INSIDE " + block + " at " + at.toShortString() + " | player " + pl.blockPosition().toShortString() + " | " + status);
					}
					d.trapped = inside;
				}
				for (Drop d : all.values()) {
					if (d.gone >= 0 || alive.contains(d.id)) continue;
					var e = level.getEntity(d.id);
					if (e != null && e.isAlive()) continue; // just out of the scanned box
					d.gone = tick;
					double dist = pl.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(d.cell));
					d.end = dist < 3.0 ? "picked up" : "gone " + String.format("%.1f", dist) + " blocks away";
					d.story.add("t" + tick + " " + d.end + " | " + status);
				}
			});
		}

		int total, lost;
		final List<String> lostValuable = new ArrayList<>();
		static final java.util.regex.Pattern PRECIOUS = java.util.regex.Pattern.compile("(raw_iron|raw_gold|diamond|emerald|lapis_lazuli|redstone|gold_nugget|quartz)");

		void report(TestSingleplayerContext sp, List<String> log) {
			// Whatever is still alive at the end was really lost.
			sp.getServer().runOnServer(server -> {
				for (Drop d : all.values()) {
					if (d.gone >= 0) continue;
					var e = server.overworld().getEntity(d.id);
					if (e == null || !e.isAlive()) d.end = "gone (not seen)";
					else d.end = "LOST";
				}
			});
			int late = 0;
			total = 0;
			lost = 0;
			Map<String, int[]> byFirst = new java.util.TreeMap<>();
			for (Drop d : all.values()) {
				total += d.count;
				if (d.end.equals("picked up") && d.gone - d.born > 300) late += d.count;
				if (!d.end.equals("LOST")) continue;
				lost += d.count;
				if (PRECIOUS.matcher(d.item).matches()) lostValuable.add(d.item + " x" + d.count);
				String born = d.story.get(0);
				String what = born.substring(born.lastIndexOf('|') + 2);
				String how = d.trapped ? "trapped inside a block" : "lying free";
				byFirst.computeIfAbsent(how + " / appeared while: " + what, k -> new int[1])[0] += d.count;
			}
			log.add("DROPS: " + total + " ore items seen, " + lost + " lost (" + (total > 0 ? String.format("%.1f", 100.0 * lost / total) : "0") + "%), "
					+ late + " picked up only after more than 15 s (counted as left behind by the old check)");
			for (var e : byFirst.entrySet()) log.add("  lost " + e.getValue()[0] + ": " + e.getKey());
			for (Drop d : all.values()) {
				if (!d.end.equals("LOST")) continue;
				log.add("  LOST " + d.item + " x" + d.count + ":");
				for (String st : d.story) log.add("      " + st);
			}
		}
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		String run = RealWorld.suite();
		// "-2" runs the same place a second time (to see how much the result varies between runs).
		String suite = run.replaceAll("-2$", "");
		ResourceKey<Biome> biome = switch (suite) {
			case "real-plains", "real-deep" -> Biomes.PLAINS;
			case "real-forest" -> Biomes.FOREST;
			default -> null;
		};
		if (biome == null) return;
		// real-deep: another world, all the way down to diamond level (deepslate, lava lakes, big caves).
		boolean deep = suite.equals("real-deep");
		try (TestSingleplayerContext sp = RealWorld.create(context, deep ? RealWorld.SEED2 : RealWorld.SEED)) {
			sp.getConnection().waitForChunksRender();
			RealWorld.setup(sp);
			// Start a little away from spawn so the two suites are different places.
			BlockPos from = deep ? BlockPos.ZERO : suite.equals("real-plains") ? new BlockPos(300, 64, 0) : new BlockPos(-300, 64, 300);
			BlockPos b = RealWorld.findBiome(sp, biome, from);
			if (b == null) {
				failures.add("biome not found: " + biome);
				throw new AssertionError("biome not found: " + biome);
			}
			RealWorld.goTo(context, sp, b.getX(), b.getZ(), 48);
			// Like a player: start on dry land (a lake or river can be inside a plains biome too).
			int[] dry = RealWorld.dryLand(sp, b.getX(), b.getZ(), 40);
			int x = dry[0], z = dry[1];
			int g = RealWorld.goTo(context, sp, x, z, 64);
			log.add("start in " + biome + " at " + x + ", " + g + ", " + z);
			// A clear spot to start from (trees or tall grass would only be in the way of the first steps).
			RealWorld.cmd(sp, "fill " + (x - 2) + " " + g + " " + (z - 3) + " " + (x + 3) + " " + (g + 6) + " " + (z + 1) + " minecraft:air");
			RealWorld.cmd(sp, "setblock " + (x + 2) + " " + g + " " + (z - 2) + " minecraft:chest[facing=north]");
			RealWorld.cmd(sp, "item replace block " + (x + 2) + " " + g + " " + (z - 2) + " container.0 with minecraft:bread 32");
			RealWorld.cmd(sp, "give @a minecraft:diamond_pickaxe");
			RealWorld.cmd(sp, "give @a minecraft:diamond_pickaxe");
			RealWorld.cmd(sp, "give @a minecraft:torch 64");
			if (deep) RealWorld.cmd(sp, "give @a minecraft:torch 64");
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
				c.targetY = deep ? -54 : startY - 30;
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
			log.add("target y " + (deep ? -54 : startY - 30) + ", main 16, branches 5 every 3");
			context.takeScreenshot(run + "-00-start");

			int ticks = 0, shots = 1;
			final int limit = 20 * 60 * (deep ? 40 : 25);
			boolean running = true;
			float minHealth = 20f;
			while (running && ticks < limit) {
				long t0 = System.nanoTime();
				for (int k = 0; k < 8; k++) {
					context.waitTicks(5);
					drops.scan(context, sp, ticks + 5 * (k + 1));
				}
				timing.add(System.nanoTime() - t0);
				ticks += 40;
				final int t = ticks;
				log.add(context.computeOnClient(mc -> "t=" + t + " pos=" + mc.player.blockPosition().toShortString() + " hp=" + (int) mc.player.getHealth()
						+ " status=" + Miner.status()));
				minHealth = Math.min(minHealth, context.computeOnClient(mc -> mc.player.getHealth()));
				// Drops lying near us for more than 15 s were left behind (items vanish after 5 minutes, so note them now).
				List<String> stale = sp.getServer().computeOnServer(server -> {
					List<String> out = new ArrayList<>();
					var pl = server.getPlayerList().getPlayers().get(0);
					for (var ie : server.overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, pl.getBoundingBox().inflate(8))) {
						if (ie.getAge() < 300 || !staleSeen.add(ie.getId())) continue;
						var at = ie.blockPosition();
						out.add(ie.getItem().getItem() + " x" + ie.getItem().getCount() + " at " + at.toShortString()
								+ (server.overworld().getBlockState(at).isAir() ? "" : " inside " + server.overworld().getBlockState(at).getBlock())
								+ ", player at " + pl.blockPosition().toShortString());
					}
					return out;
				});
				for (String st : stale) {
					log.add("    left behind: " + st);
					if (st.matches("minecraft:(raw_\\w+|coal|diamond|emerald|lapis_lazuli|redstone|quartz|gold_nugget|amethyst_shard).*")) valuableLeft++;
				}
				running = context.computeOnClient(mc -> MineConfig.get().running);
				if (ticks % 600 == 0) context.takeScreenshot(String.format("%s-%03d-s%d", run, shots++, ticks / 20));
			}
			context.takeScreenshot(run + "-99-end");
			String stop = context.computeOnClient(mc -> Miner.lastStop);
			log.add("stopped after " + ticks + " ticks (" + ticks / 20 + " s): " + stop);
			log.add(timing.summary() + ", server tick " + String.format("%.1f", RealWorld.mspt(sp)) + " ms");
			log.add(com.seolwoni.assistpath.AssistPath.Stats.summary());
			log.add(context.computeOnClient(mc -> com.seolwoni.assistpath.defense.Defense.Stats.summary() + " giveUp=" + com.seolwoni.assistpath.defense.Defense.giveUp() + " night=" + RealWorld.night()));
			log.add("lowest health " + minHealth);
			drops.report(sp, log);
			for (String n : context.computeOnClient(mc -> new ArrayList<>(Miner.pocketLog))) log.add("  pocket: " + n);
			// Lost = never picked up by the end (followed one by one). Coal and copper may lose a little; the rest nothing.
			log.add("(old check, drops lying 15 s or more, many picked up later: " + valuableLeft + ")");
			if (drops.total > 0 && drops.lost * 100 > drops.total * 5) failures.add(drops.lost + " of " + drops.total + " ore items lost (more than 5%)");
			if (!drops.lostValuable.isEmpty()) failures.add("valuable drops lost: " + drops.lostValuable);
			for (String n : context.computeOnClient(mc -> Miner.leftOverVoid.stream().map(BlockPos::toShortString).toList())) log.add("  ore left in place (open below): " + n);
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
			// Drops that never got picked up: where are they (stuck in an ore hole, dumped, fallen into a cave)?
			log.add("items left lying around the job: " + sp.getServer().computeOnServer(server -> {
				StringBuilder sb = new StringBuilder();
				int n = 0;
				for (var ie : server.overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
						new net.minecraft.world.phys.AABB(x - 40, startY - 80, z - 10, x + 40, startY + 5, z + 60))) {
					if (n++ < 30) sb.append(ie.getItem().getItem()).append(" x").append(ie.getItem().getCount()).append(" at ")
							.append(ie.blockPosition().toShortString()).append(server.overworld().getBlockState(ie.blockPosition()).isAir() ? "" : " (inside " + server.overworld().getBlockState(ie.blockPosition()).getBlock() + ")").append("; ");
				}
				return n + " — " + sb;
			}));
			log.add("inventory: " + context.computeOnClient(mc -> {
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < 36; i++) {
					ItemStack st = mc.player.getInventory().getItem(i);
					if (!st.isEmpty()) sb.append(st.getItem()).append(" x").append(st.getCount()).append(", ");
				}
				return sb.toString();
			}));
		} catch (Throwable t) {
			failures.add("exception: " + t);
			throw t;
		} finally {
			RealWorld.writeReport("mineassist-" + suite, failures, log);
		}
		if (!failures.isEmpty()) throw new AssertionError("Mine Assist " + suite + " failed: " + String.join("; ", failures));
	}
}
