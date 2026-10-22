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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Nether mining in a generated world (normal difficulty, mobs on): like a player who has built a small shelter in
 * the Nether, with a chest in it, in iron armour with a gold helmet and an iron sword, two diamond pickaxes, torches
 * and a stack of netherrack. Starts with the Nether settings (target Y 15, poke holes).
 * <ul>
 * <li>nether-a / nether-b / nether-c: three places, stairs down to Y 15.</li>
 * <li>nether-a-fire: place a with fire resistance potions and the setting on.</li>
 * <li>nether-up: place a, target 8 blocks above the start: the stairs climb (no shelter: the stairs go up through
 * where its roof would be).</li>
 * </ul>
 * Checked: no ancient debris, gold or quartz lost, never on fire or in lava (without the potions), finished and
 * back at the start.
 */
public class MineNetherTest implements FabricClientGameTest {
	private final List<String> log = new ArrayList<>();
	private final List<String> failures = new ArrayList<>();
	private final MineRealTest.DropTracker drops = new MineRealTest.DropTracker();

	private static final String IN = "execute in minecraft:the_nether run ";

	@Override
	public void runTest(ClientGameTestContext context) {
		String run = RealWorld.suite();
		if (!run.startsWith("nether-")) return;
		boolean fire = run.endsWith("-fire");
		boolean up = run.equals("nether-up");
		String place = run.replace("-fire", "");
		BlockPos from = switch (place) {
			case "nether-b" -> new BlockPos(180, 0, -140);
			case "nether-c" -> new BlockPos(-220, 0, 190);
			default -> new BlockPos(40, 0, 30);
		};
		drops.dim = Level.NETHER;
		try (TestSingleplayerContext sp = RealWorld.create(context, RealWorld.SEED2)) {
			sp.getConnection().waitForChunksRender();
			RealWorld.cmd(sp, "difficulty normal");
			RealWorld.cmd(sp, "gamemode survival @a");
			RealWorld.cmd(sp, "clear @a");
			RealWorld.cmd(sp, "effect clear @a");
			RealWorld.cmd(sp, IN + "forceload add " + (from.getX() - 64) + " " + (from.getZ() - 64) + " " + (from.getX() + 64) + " " + (from.getZ() + 64));
			// Into the Nether first (the chunks there are generated as the player arrives).
			RealWorld.cmd(sp, IN + "tp @a " + (from.getX() + 0.5) + " 120 " + (from.getZ() + 0.5));
			RealWorld.cmd(sp, "effect give @a minecraft:resistance 10 255 true");
			context.waitTicks(100);
			sp.getConnection().waitForChunksRender();
			int[] at = sp.getServer().computeOnServer(server -> findStart(server.getLevel(Level.NETHER), from));
			if (at == null) {
				failures.add("no place to start found near " + from.toShortString());
				throw new AssertionError("no start");
			}
			int x = at[0], g = at[1], z = at[2];
			log.add("start in the Nether at " + x + ", " + g + ", " + z + (fire ? " (fire resistance on)" : "") + (up ? " (stairs up)" : ""));
			if (!up) {
				// A shelter, the way a player digs in: walls, roof, light; the chest and the stairs inside.
				RealWorld.cmd(sp, IN + "fill " + (x - 3) + " " + (g - 1) + " " + (z - 4) + " " + (x + 4) + " " + (g + 4) + " " + (z + 4) + " minecraft:cobblestone hollow");
				RealWorld.cmd(sp, IN + "fill " + (x - 2) + " " + (g - 1) + " " + (z - 3) + " " + (x + 3) + " " + (g - 1) + " " + (z + 3) + " minecraft:cobblestone");
				RealWorld.cmd(sp, IN + "setblock " + (x - 2) + " " + (g + 2) + " " + (z - 3) + " minecraft:torch");
				RealWorld.cmd(sp, IN + "setblock " + (x + 3) + " " + (g + 2) + " " + (z + 3) + " minecraft:torch");
			} else {
				RealWorld.cmd(sp, IN + "fill " + (x - 2) + " " + g + " " + (z - 3) + " " + (x + 3) + " " + (g + 2) + " " + (z - 1) + " minecraft:air");
				RealWorld.cmd(sp, IN + "fill " + (x - 2) + " " + (g - 1) + " " + (z - 3) + " " + (x + 3) + " " + (g - 1) + " " + (z - 1) + " minecraft:cobblestone");
			}
			RealWorld.cmd(sp, IN + "setblock " + (x + 2) + " " + g + " " + (z - 2) + " minecraft:chest[facing=north]");
			RealWorld.cmd(sp, IN + "item replace block " + (x + 2) + " " + g + " " + (z - 2) + " container.0 with minecraft:bread 32");
			RealWorld.cmd(sp, "give @a minecraft:diamond_pickaxe");
			RealWorld.cmd(sp, "give @a minecraft:diamond_pickaxe");
			RealWorld.cmd(sp, "give @a minecraft:torch 64");
			RealWorld.cmd(sp, "give @a minecraft:iron_sword");
			RealWorld.cmd(sp, "give @a minecraft:netherrack 64");
			RealWorld.cmd(sp, "give @a minecraft:bread 16");
			if (fire) RealWorld.cmd(sp, "give @a minecraft:potion[minecraft:potion_contents={potion:\"minecraft:long_fire_resistance\"}] 3");
			RealWorld.cmd(sp, "item replace entity @a armor.head with minecraft:golden_helmet");
			RealWorld.cmd(sp, "item replace entity @a armor.chest with minecraft:iron_chestplate");
			RealWorld.cmd(sp, "item replace entity @a armor.legs with minecraft:iron_leggings");
			RealWorld.cmd(sp, "item replace entity @a armor.feet with minecraft:iron_boots");
			RealWorld.cmd(sp, "effect give @a minecraft:night_vision infinite 0 true");
			RealWorld.cmd(sp, "effect give @a minecraft:saturation infinite 0 true");
			RealWorld.cmd(sp, IN + "tp @a " + (x + 0.5) + " " + g + " " + (z + 0.5) + " 0 10");
			context.waitTicks(60);
			sp.getConnection().waitForChunksRender();
			final int startY = g;
			BlockPos chest = new BlockPos(x + 2, g, z - 2);

			context.runOnClient(mc -> {
				MineConfig c = MineConfig.get();
				c.modEnabled = true;
				c.resume = false;
				c.netherTargetY = up ? startY + 8 : 15;
				c.netherPattern = 1;
				c.netherFireRes = fire;
				c.netherOres = new ArrayList<>(MineConfig.NETHER_ORES);
				c.mainLength = 16;
				c.branchSpacing = 3;
				c.pokeDepth = 4;
				c.torches = true;
				c.torchInterval = 6;
				c.craftTorches = false;
				c.stopOnMob = false;
				c.storeEnabled = true;
				c.storeMode = 0;
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
				if (!Miner.start(mc)) failures.add("Miner.start returned false: " + Miner.lastStop);
			});
			log.add("target y " + (up ? startY + 8 : 15) + ", main 16, poke holes every 3 (depth 4)");
			context.takeScreenshot(run + "-00-start");

			int ticks = 0, shots = 1, burning = 0, inLava = 0, outside = 0;
			final int limit = 20 * 60 * 35;
			boolean running = true;
			float minHealth = 20f;
			while (running && ticks < limit) {
				for (int k = 0; k < 8; k++) {
					context.waitTicks(5);
					drops.scan(context, sp, ticks + 5 * (k + 1));
				}
				ticks += 40;
				final int t = ticks;
				log.add(context.computeOnClient(mc -> "t=" + t + " pos=" + mc.player.blockPosition().toShortString() + " hp=" + (int) mc.player.getHealth()
						+ (mc.player.isOnFire() ? " BURNING" : "") + (mc.player.isInLava() ? " IN LAVA" : "") + " status=" + Miner.status()));
				if (context.computeOnClient(mc -> mc.player.isOnFire())) burning++;
				if (context.computeOnClient(mc -> mc.player.isInLava())) inLava++;
				if (!context.computeOnClient(mc -> Miner.onDugGround(mc.player.blockPosition()))) outside++;
				minHealth = Math.min(minHealth, context.computeOnClient(mc -> mc.player.getHealth()));
				running = context.computeOnClient(mc -> MineConfig.get().running);
				if (ticks % 600 == 0) context.takeScreenshot(String.format("%s-%03d-s%d", run, shots++, ticks / 20));
			}
			context.takeScreenshot(run + "-99-end");
			String stop = context.computeOnClient(mc -> Miner.lastStop);
			log.add("stopped after " + ticks + " ticks (" + ticks / 20 + " s): " + stop);
			log.add(com.seolwoni.assistpath.AssistPath.Stats.summary());
			log.add(context.computeOnClient(mc -> com.seolwoni.assistpath.defense.Defense.Stats.summary()));
			for (String ev : context.computeOnClient(mc -> new ArrayList<>(com.seolwoni.assistpath.defense.Defense.Stats.events))) log.add("  defense: " + ev);
			log.add("lowest health " + minHealth + ", burning " + burning * 2 + " s, in lava " + inLava * 2 + " s, outside the dug tunnels " + outside * 2 + " s");
			drops.report(sp, log);
			for (String n : context.computeOnClient(mc -> new ArrayList<>(Miner.pocketLog))) log.add("  pocket: " + n);
			for (String n : context.computeOnClient(mc -> Miner.leftOverVoid.stream().map(BlockPos::toShortString).toList())) log.add("  ore left in place: " + n);
			if (!drops.lostValuable.isEmpty()) failures.add("valuable drops lost: " + drops.lostValuable);
			if (inLava > 0) failures.add("was in lava (" + inLava * 2 + " s)");
			if (!fire && burning > 0) failures.add("was on fire (" + burning * 2 + " s)");
			if (running) {
				failures.add("did not finish within " + limit + " ticks");
				context.runOnClient(mc -> Miner.stop(null, null, false));
			} else if (!stop.startsWith("채광 완료")) {
				failures.add("stopped early: " + stop);
			}
			if (minHealth < 10) failures.add("got hurt: health went down to " + minHealth);
			double home = context.computeOnClient(mc -> mc.player.position().distanceTo(new net.minecraft.world.phys.Vec3(x + 0.5, startY, z + 0.5)));
			if (!running && stop.startsWith("채광 완료") && home > 2.0) failures.add("finished but not back at the start (distance " + String.format("%.1f", home) + ")");
			context.runOnClient(mc -> {
				MineStats st = MineStats.session;
				log.add("blocks mined=" + st.blocks + " lava/water plugged=" + st.plugs + " chest trips=" + st.trips);
				for (Map.Entry<String, MineStats.Entry> e : st.ores.entrySet()) log.add("  ore " + e.getKey() + ": " + e.getValue().count);
				log.add("job: stairs " + Miner.job().stairsDone + ", main step " + Miner.job().mainK + ", ores " + Miner.job().oresMined);
			});
			if (!stop.startsWith("채광 완료")) {
				BlockPos me = context.computeOnClient(mc -> mc.player.blockPosition());
				log.add("blocks around " + me.toShortString() + " (rows: z-1, z, z+1; each row x-3..x+3; levels y-3..y+2):");
				log.addAll(sp.getServer().computeOnServer(server -> {
					List<String> out = new ArrayList<>();
					ServerLevel lv = server.getLevel(Level.NETHER);
					for (int dy = 2; dy >= -3; dy--) {
						StringBuilder sb = new StringBuilder("  y" + (me.getY() + dy) + ": ");
						for (int dz = -1; dz <= 1; dz++) {
							for (int dx = -3; dx <= 3; dx++) {
								var st = lv.getBlockState(me.offset(dx, dy, dz));
								String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
								sb.append(id.equals("air") ? "." : id.equals("cave_air") ? ":" : id.substring(0, Math.min(4, id.length()))).append(' ');
							}
							sb.append("| ");
						}
						out.add(sb.toString());
					}
					return out;
				}));
			}
			log.add("inventory: " + context.computeOnClient(mc -> {
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < 36; i++) {
					var st = mc.player.getInventory().getItem(i);
					if (!st.isEmpty()) sb.append(st.getItem()).append(" x").append(st.getCount()).append(", ");
				}
				return sb.toString();
			}));
		} catch (Throwable t) {
			failures.add("exception: " + t);
			throw t;
		} finally {
			RealWorld.writeReport("mineassist-" + run, failures, log);
		}
		if (!failures.isEmpty()) throw new AssertionError("Mine Assist " + run + " failed: " + String.join("; ", failures));
	}

	/**
	 * A spot to start, spiralling out from {@code from}: standing room (three free cells) on solid ground between
	 * Y 40 and 100, and no lava within three blocks. {x, y, z} (y = feet), or null.
	 */
	private static int[] findStart(ServerLevel lv, BlockPos from) {
		for (int r = 0; r <= 48; r += 2) {
			for (int dx = -r; dx <= r; dx += 2) {
				for (int dz = -r; dz <= r; dz += 2) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					int cx = from.getX() + dx, cz = from.getZ() + dz;
					for (int y = 100; y >= 40; y--) {
						BlockPos f = new BlockPos(cx, y, cz);
						if (!lv.getBlockState(f).isAir() || !lv.getBlockState(f.above()).isAir() || !lv.getBlockState(f.above(2)).isAir()) continue;
						var below = lv.getBlockState(f.below());
						if (!below.isSolidRender() || !below.getFluidState().isEmpty()) continue;
						boolean lava = false;
						for (BlockPos q : BlockPos.betweenClosed(f.offset(-3, -3, -3), f.offset(3, 3, 3))) {
							if (!lv.getFluidState(q).isEmpty()) {
								lava = true;
								break;
							}
						}
						if (!lava) return new int[] {cx, y, cz};
					}
				}
			}
		}
		return null;
	}
}
