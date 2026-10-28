package com.seolwoni.mineassist.mining;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.seolwoni.assistcore.engine.ActionQueue;
import com.seolwoni.mineassist.config.JobState;
import com.seolwoni.mineassist.config.MineConfig;
import com.seolwoni.mineassist.mining.Geo.Loc;
import com.seolwoni.assistcore.util.Alerts;
import com.seolwoni.assistcore.util.Blocks2;
import com.seolwoni.assistcore.util.Body;
import com.seolwoni.assistcore.util.Human;
import com.seolwoni.mineassist.util.Inv;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Runs a branch-mining job as a queue of small actions (mine a block, place a block, walk a step, ...).
 * When the queue is empty the planner adds the next step of the tunnel, or a chore (eat, craft torches,
 * throw away junk, go store loot) when one is due.
 */
public final class Miner {
	public static final int RUN = com.seolwoni.assistcore.engine.Action.RUN, DONE = com.seolwoni.assistcore.engine.Action.DONE,
			FAIL = com.seolwoni.assistcore.engine.Action.FAIL;
	/** What a failure means: skip it, give up this branch, finish the job, or stop right away. */
	public static final int SKIP = com.seolwoni.assistcore.engine.Action.SKIP, ABANDON = com.seolwoni.assistcore.engine.Action.ABANDON,
			END = com.seolwoni.assistcore.engine.Action.END, STOP = com.seolwoni.assistcore.engine.Action.STOP;

	/** Mining actions (a failure stops the job unless the action says otherwise). */
	public interface Action extends com.seolwoni.assistcore.engine.Action {
	}

	static JobState job = new JobState();
	static Geo geo;
	/** Where we were when Defense took over (we walk back there afterwards). */
	private static BlockPos defenseFrom;
	/** Ore blocks we dug out: their holes are not caves, so they are never walled or roofed over (drops lie there). */
	static final Set<BlockPos> minedOre = new HashSet<>();
	/** Cave cells we tried to close before digging next to them (each only once). */
	static final Set<BlockPos> caveTried = new HashSet<>();
	/** Goes at closing each cave cell (at most two, the second only once there is something to build against). */
	static final java.util.Map<BlockPos, Integer> caveTries = new java.util.HashMap<>();

	static boolean hasSupport(BlockPos c) {
		for (Direction d : Direction.values()) if (Blocks2.isSolid(c.relative(d))) return true;
		return false;
	}
	/** Every cell we dug out this session (poke holes, pockets): ours, not a cave, never walled. */
	static final Set<BlockPos> dug = new HashSet<>();
	/** Ores left in place because their drops would fall into a cave or lava below (shown to the player). */
	public static final Set<BlockPos> leftOverVoid = new java.util.LinkedHashSet<>();
	static Loc here = Loc.origin();
	private static final ActionQueue queue = new ActionQueue(Miner::onFail);
	private static String status = com.seolwoni.assistcore.i18n.Tr.t("꺼짐");
	private static int nextPause;
	private static boolean finishing;
	private static boolean torchFetchTried;

	private Miner() {
	}

	public static JobState job() {
		return job;
	}

	public static String status() {
		return status;
	}

	public static boolean running() {
		return MineConfig.get().running;
	}

	static void status(String s) {
		status = s;
	}

	static void pushFront(List<Action> list) {
		queue.pushFront(list);
	}

	static void pushFront(Action a) {
		queue.pushFront(a);
	}

	static void add(Action a) {
		queue.add(a);
	}

	/** A shared Assist Core action (storing into a carried box). */
	static void addCore(com.seolwoni.assistcore.engine.Action a) {
		queue.add(a);
	}

	static void run(Runnable r) {
		add((mc, p) -> {
			r.run();
			return DONE;
		});
	}

	// ------------------------------------------------------------------ start / stop

	public static void loadJob() {
		job = JobState.load();
	}

	private static String worldKey(Minecraft mc) {
		String server = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "local";
		return server + "|" + mc.level.dimension().toString();
	}

	/** Called when the player turns the miner on in the settings. Returns false if it could not start. */
	public static boolean start(Minecraft mc) {
		{
			var dc = com.seolwoni.mineassist.config.MineConfig.get();
			com.seolwoni.assistpath.defense.Defense.config.enabled = dc.mobMode <= 1;
			com.seolwoni.assistpath.defense.Defense.config.attack = dc.mobMode == 1;
			com.seolwoni.assistpath.defense.Defense.reset(mc);
		}
		LocalPlayer p = mc.player;
		MineConfig cfg = MineConfig.get();
		if (p == null || mc.level == null) return false;
		queue.clear();
		lostCount = 0;
		lostEpisodes = 0;
		lastSafe = null;
		sheltering = false;
		ghastShelters = 0;
		edgeWalled.clear();
		finishing = false;
		torchFetchTried = false;
		Chores.dumpBlockedUntil = 0;
		BlockPos feet = p.blockPosition();
		inNether = mc.level.dimension().equals(net.minecraft.world.level.Level.NETHER);
		if (inNether && !wearsGold(p)) {
			// Piglins leave a player alone who wears at least one piece of gold armour.
			Alerts.title(com.seolwoni.assistcore.i18n.Tr.t("금 갑옷이 없어요"), com.seolwoni.assistcore.i18n.Tr.t("피글린이 덤벼요: 금 투구 하나라도 쓰는 걸 권해요"), 0xFFFFAA00);
			Alerts.chat("Mine Assist", com.seolwoni.assistcore.i18n.Tr.t("네더에서는 금 갑옷을 하나라도 입어야 피글린이 덤비지 않아요. 그대로 시작해요."));
		}
		if (p.isInWater() || Blocks2.isFluid(feet) || Blocks2.isFluid(feet.below())) {
			// Stairs dug from under water fill up at once (and the way home is a swim): start on dry ground.
			Alerts.title(com.seolwoni.assistcore.i18n.Tr.t("물에서는 시작할 수 없어요"), com.seolwoni.assistcore.i18n.Tr.t("마른 땅 위에 서서 켜주세요"), 0xFFFF5555);
			Alerts.warning();
			return false;
		}

		if (cfg.resume && job.active && job.world.equals(worldKey(mc))) {
			BlockPos o = new BlockPos(job.ox, job.oy, job.oz);
			if (feet.distManhattan(o) > 1) {
				Alerts.title(com.seolwoni.assistcore.i18n.Tr.t("시작 지점이 아니에요"), com.seolwoni.assistcore.i18n.Tr.t("이어서 파려면 ") + o.getX() + ", " + o.getY() + ", " + o.getZ() + com.seolwoni.assistcore.i18n.Tr.t("에서 켜주세요"), 0xFFFF5555);
				Alerts.chat("Mine Assist", com.seolwoni.assistcore.i18n.Tr.t("이어서 파려면 시작 지점 ") + o.toShortString() + com.seolwoni.assistcore.i18n.Tr.t("으로 가서 켜주세요. 새로 시작하려면 설정 → 기본 → 진행 초기화."));
				Alerts.warning();
				return false;
			}
			geo = new Geo(job);
			minedOre.clear();
			leftOverVoid.clear();
			dug.clear();
			caveTried.clear();
			caveTries.clear();
			collectTries.clear();
			leftDrops.clear();
			// Jobs saved by 1.2.3 and older started with mainK = 1 and would skip main step 1.
			if (job.stairsDone < geo.stairs && job.mainK == 1) job.mainK = 0;
			here = Loc.origin();
			Loc frontier = frontier();
			status(com.seolwoni.assistcore.i18n.Tr.t("이어서 파러 가는 중"));
			addRouteOut(frontier);
			run(() -> here = frontier);
			Alerts.actionBar(com.seolwoni.assistcore.i18n.Tr.t("이어서 파기 시작"), 0xFF55FF55);
		} else {
			if (!inNether && p.blockPosition().getY() < cfg.targetY) {
				Alerts.title(com.seolwoni.assistcore.i18n.Tr.t("목표 Y보다 아래에 있어요"), "Y " + cfg.targetY + com.seolwoni.assistcore.i18n.Tr.t(" 이상에서 시작해주세요"), 0xFFFF5555);
				Alerts.warning();
				return false;
			}
			job = new JobState();
			job.active = true;
			job.world = worldKey(mc);
			job.ox = feet.getX();
			job.oy = feet.getY();
			job.oz = feet.getZ();
			job.dir = Direction.fromYRot(p.getYRot()).get2DDataValue();
			job.targetY = inNether ? cfg.netherTargetY : cfg.targetY;
			job.mainLength = cfg.mainLength;
			job.branchLength = cfg.branchLength;
			job.branchSpacing = cfg.branchSpacing;
			job.pattern = inNether ? cfg.netherPattern : cfg.digPattern;
			job.save();
			geo = new Geo(job);
			minedOre.clear();
			leftOverVoid.clear();
			dug.clear();
			caveTried.clear();
			caveTries.clear();
			collectTries.clear();
			leftDrops.clear();
			here = Loc.origin();
			Alerts.actionBar(com.seolwoni.assistcore.i18n.Tr.t("채광 시작: ") + geo.d.getName() + com.seolwoni.assistcore.i18n.Tr.t(" 방향, Y ") + job.targetY, 0xFF55FF55);
		}
		// Gear up at the chests before setting off (food, torches, a spare pickaxe).
		if (cfg.storeEnabled && !cfg.chests.isEmpty() && Storage.needsSupplies(p)) {
			pushFront(new Storage.TripAction(geo.origin, geo.d));
		}
		nextPause = Human.rand(1500, 4000);
		cfg.running = true;
		MineConfig.save();
		com.seolwoni.assistcore.log.UsageLog.start("mine", com.seolwoni.assistcore.i18n.Tr.t("목표 Y ") + job.targetY + com.seolwoni.assistcore.i18n.Tr.t(", 가운데 굴 ") + job.mainLength + com.seolwoni.assistcore.i18n.Tr.t("칸, 가지굴 ") + job.branchLength + com.seolwoni.assistcore.i18n.Tr.t("칸/") + job.branchSpacing + com.seolwoni.assistcore.i18n.Tr.t("칸 간격, 작업 속도 ") + cfg.pace
				+ (job.mainK > 0 || job.stairsDone > 0 ? com.seolwoni.assistcore.i18n.Tr.t(" (이어서: 계단 ") + job.stairsDone + com.seolwoni.assistcore.i18n.Tr.t(", 가운데 ") + job.mainK + ")" : ""));
		return true;
	}

	/** Why the miner last stopped (title / subtitle), for the debug log and automated tests. */
	public static String lastStop = "";

	public static void stop(String title, String sub, boolean alert) {
		lastStop = (title == null ? "" : title) + (sub == null ? "" : " / " + sub);
		com.seolwoni.mineassist.hud.MineStats ms = com.seolwoni.mineassist.hud.MineStats.session;
		com.seolwoni.assistcore.log.UsageLog.end("mine", lastStop.isEmpty() ? com.seolwoni.assistcore.i18n.Tr.t("직접 껐어요") : lastStop, com.seolwoni.assistcore.i18n.Tr.t("캔 블록 ") + ms.blocks + com.seolwoni.assistcore.i18n.Tr.t(", 광석 ") + job.oresMined + com.seolwoni.assistcore.i18n.Tr.t(", 막은 용암·물 ") + ms.plugs + com.seolwoni.assistcore.i18n.Tr.t(", 상자 왕복 ") + ms.trips + (leftOverVoid.isEmpty() ? "" : com.seolwoni.assistcore.i18n.Tr.t(", 옆·아래가 비어 남겨둔 광석 ") + leftOverVoid.size() + com.seolwoni.assistcore.i18n.Tr.t("개"))
				+ com.seolwoni.assistcore.i18n.Tr.t("번, 계단 ") + job.stairsDone + com.seolwoni.assistcore.i18n.Tr.t(", 가운데 ") + job.mainK + " | " + com.seolwoni.assistpath.AssistPath.Stats.summary());
		Minecraft mc = Minecraft.getInstance();
		MineConfig cfg = MineConfig.get();
		cfg.running = false;
		MineConfig.save();
		queue.clear();
		Body.release(mc);
		mc.options.keyUse.setDown(false);
		if (edgeSneaking) mc.options.keyShift.setDown(false);
		edgeSneaking = false;
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		if (mc.player != null && mc.gui.screen() instanceof AbstractContainerScreen<?> s && !(s instanceof InventoryScreen)) {
			mc.player.closeContainer();
		}
		job.save();
		status(com.seolwoni.assistcore.i18n.Tr.t("꺼짐"));
		if (title != null) {
			if (alert) {
				Alerts.title(title, sub, 0xFFFF5555);
				Alerts.warning();
			} else {
				Alerts.actionBar(title + (sub == null ? "" : " - " + sub), 0xFFFFAA00);
			}
		}
	}

	public static void resetJob() {
		job = new JobState();
		job.save();
		queue.clear();
	}

	/** The last place the job had reached, for resuming. */
	static Loc frontier() {
		if (job.stairsDone < geo.stairs) return Loc.stair(job.stairsDone);
		if (job.mainK == 0) return Loc.stair(geo.stairs);
		if (job.branchSide < 2 && job.mainK % job.branchSpacing == 0 && job.branchD > 0) {
			return Loc.branch(job.mainK, job.branchSide, job.branchD);
		}
		return Loc.main(job.mainK, 0);
	}

	// ------------------------------------------------------------------ tick

	public static void tick(Minecraft mc) {
		MineConfig cfg = MineConfig.get();
		LocalPlayer p = mc.player;
		if (!cfg.running) return;
		if (p == null || mc.level == null || mc.gameMode == null) {
			stop(null, null, false);
			return;
		}
		if (mc.isPaused()) return;
		if (!safety(mc, p, cfg)) return;
		// Defense (Assist Path): while a creeper, an arrow or a mob needs dealing with, it has the controls.
		// Getting away stays inside the tunnels we dug: running out into a cave is how the way back gets lost.
		com.seolwoni.assistpath.defense.Defense.config.region = geo == null ? null : q -> !underground(q) || tunnelSpace(q) || tunnelSpace(q.above()); // up top: anywhere
		if (cfg.mobMode <= 1 && com.seolwoni.assistpath.defense.Defense.guard(mc, p)) {
			if (defenseFrom == null) defenseFrom = p.blockPosition().immutable();
			String why = com.seolwoni.assistpath.defense.Defense.giveUp();
			if (why != null) {
				com.seolwoni.assistpath.defense.Defense.reset(mc);
				stop(why, com.seolwoni.assistcore.i18n.Tr.t("싸우기를 켜거나 안전한 곳에서 다시 켜주세요"), true);
			}
			return;
		}
		if (defenseFrom != null) {
			// Defense moved us (ran away, stepped aside, fought). The step we were in the middle of assumed we stood
			// where we were: drop it, walk back to the last place the job reached, and plan from the job state again.
			defenseFrom = null;
			queue.clear();
			// The shelter steps (if any) were just dropped: without this, ghast watching stayed off for the rest of
			// the job (nether-b 1.10.10: hit by a fireball while waiting, then walked back to the edge unwatched).
			sheltering = false;
			if (inNether && geo != null && lastSafe != null && dangerGhast(mc, p) != null) {
				shelter(mc, p);
				return;
			}
			// A box put down to store into is still standing there: finish with it first.
			var box = com.seolwoni.assistcore.storage.PortableStorage.interrupted(mc);
			if (box != null) queue.add(box);
			BlockPos back = geo.pos(here);
			// Out of the dug tunnels (pushed into a cave, fell through): at most two goes at getting back. After that,
			// no wandering about a dark cave: wall ourselves in where we stand and stop, so the player can come.
			if (onDugGround(p.blockPosition())) {
				lostCount = 0;
				lostEpisodes = 0;
			} else if (++lostEpisodes > 3) {
				// Mobs keep interrupting the way back out there: stop trying.
				shelterAndStop(p);
				return;
			}
			if (com.seolwoni.assistpath.walk.PathWalker.feet(p).distManhattan(back) > 0) {
				// May dig or build a step on the way: after a fall into a cave the way back is up.
				queue.add(new com.seolwoni.assistpath.walk.PathWalkAction(new com.seolwoni.assistpath.goals.GoalBlock(back),
						com.seolwoni.assistpath.PathOptions.FULL, com.seolwoni.assistcore.engine.Action.SKIP));
				status(com.seolwoni.assistcore.i18n.Tr.t("하던 곳으로 돌아가는 중"));
				add((mc2, p2) -> {
					// Did not make it back (the path gave up): that counts as a go too.
					if (!onDugGround(p2.blockPosition()) && ++lostCount >= 2) shelterAndStop(p2);
					return DONE;
				});
			}
			return;
		}
		job.ticks++;
		com.seolwoni.mineassist.hud.MineStats.tick();

		Human.humanLike = cfg.humanDelay;
		Human.sprintJump = true;
		Human.pace = cfg.pace;
		if (ghastWatch(mc, p)) return;
		edgeSneak(mc, p);
		if (queue.tick(mc, p)) return;
		plan(mc, p, cfg);
	}

	private static boolean safety(Minecraft mc, LocalPlayer p, MineConfig cfg) {
		if (p.isDeadOrDying()) {
			BlockPos b = p.blockPosition();
			Alerts.chat("Mine Assist", com.seolwoni.assistcore.i18n.Tr.t("사망 위치: ") + b.getX() + ", " + b.getY() + ", " + b.getZ());
			stop(com.seolwoni.assistcore.i18n.Tr.t("죽었어요"), com.seolwoni.assistcore.i18n.Tr.t("사망 위치를 채팅에 남겼어요"), true);
			return false;
		}
		if (drinkTicks > 0) {
			// Drinking fire resistance: hold use until the potion is down (about 1.6 s).
			drinkTicks++;
			mc.options.keyUse.setDown(true);
			if ((drinkTicks > 8 && !p.isUsingItem()) || drinkTicks > 60) {
				mc.options.keyUse.setDown(false);
				drinkTicks = 0;
			}
			return false;
		}
		if (p.isInLava() || p.isOnFire()) {
			boolean resist = p.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE);
			if (!resist && inNether && cfg.netherFireRes && Inv.count(p, Miner::isFireRes) > 0) {
				int slot = Inv.bringToHotbar(mc, p, Miner::isFireRes);
				if (slot >= 0) {
					Body.release(mc);
					Inv.select(p, slot);
					mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
					drinkTicks = 1;
					status(com.seolwoni.assistcore.i18n.Tr.t("불 저항 물약 마시는 중"));
					com.seolwoni.assistcore.log.UsageLog.event("mine", com.seolwoni.assistcore.i18n.Tr.t("불이 붙어 불 저항 물약을 마셨어요"));
					return false;
				}
			}
			// Burning with fire resistance on: harmless, carry on (but never keep working while in lava).
			if (!(resist && !p.isInLava())) {
				stop(com.seolwoni.assistcore.i18n.Tr.t("위험! 불이나 용암"), com.seolwoni.assistcore.i18n.Tr.t("채광을 멈췄어요"), true);
				return false;
			}
		}
		if (p.getHealth() <= cfg.minHealth) {
			stop(com.seolwoni.assistcore.i18n.Tr.t("체력이 낮아요"), com.seolwoni.assistcore.i18n.Tr.t("채광을 멈췄어요"), true);
			return false;
		}
		if (cfg.mobMode == 2 && job.ticks % 10 == 0) {
			AABB box = p.getBoundingBox().inflate(cfg.mobRange);
			for (Entity e : mc.level.getEntities(p, box)) {
				if (e instanceof Enemy && e.isAlive() && !(e instanceof net.minecraft.world.entity.NeutralMob)
						&& !com.seolwoni.assistpath.defense.Threats.calmPiglin(e)) {
					stop(com.seolwoni.assistcore.i18n.Tr.t("몹이 다가와요"), e.getName().getString() + com.seolwoni.assistcore.i18n.Tr.t(" 근처에 있어요"), true);
					return false;
				}
			}
		}
		return true;
	}

	private static void onFail(com.seolwoni.assistcore.engine.Action a) {
		switch (a.failMode()) {
			case SKIP -> {
			}
			case ABANDON -> {
				// Give up this branch: drop what is queued and let the planner walk back to the main tunnel.
				queue.clear();
				job.branchD = Math.max(job.branchD, job.branchLength);
				Alerts.actionBar(com.seolwoni.assistcore.i18n.Tr.t("가지굴을 더 팔 수 없어서 다음 가지굴로 넘어가요"), 0xFFFFAA00);
			}
			case END -> {
				queue.clear();
				Alerts.actionBar(com.seolwoni.assistcore.i18n.Tr.t("가운데 굴이 막혀서 마무리하고 돌아가요"), 0xFFFFAA00);
				goHomeAndFinish();
			}
			default -> stop(com.seolwoni.assistcore.i18n.Tr.t("채광을 멈췄어요"), a.failReason().isEmpty() ? com.seolwoni.assistcore.i18n.Tr.t("진행할 수 없어요") : a.failReason(), true);
		}
	}

	// ------------------------------------------------------------------ planning

	private static void plan(Minecraft mc, LocalPlayer p, MineConfig cfg) {
		if (finishing) return;

		// Every so often, take a short breather like a person would.
		if (--nextPause <= 0) {
			nextPause = Human.rand(1500, 4000);
			add(new WaitAction(Human.rand(30, 100)));
			status(com.seolwoni.assistcore.i18n.Tr.t("잠깐 쉬는 중"));
			return;
		}
		if (Chores.planChores(mc, p, cfg)) return;
		planStep(cfg);
	}

	/** Leave the tunnel: walk back to the origin and end the job. */
	/**
	 * The "돌아가기" button (or its key): stop digging, pick up what lies close by, walk back to the start along
	 * the tunnels, store the loot and stop there. The job stays open: switching on again at the start carries on.
	 */
	public static void returnHome(Minecraft mc) {
		MineConfig cfg = MineConfig.get();
		if (!cfg.running || geo == null || finishing) return;
		queue.clear();
		Body.release(mc);
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		defenseFrom = null;
		finishing = true;
		com.seolwoni.assistcore.log.UsageLog.event("mine", com.seolwoni.assistcore.i18n.Tr.t("돌아가기 버튼: ") + (mc.player == null ? "" : mc.player.blockPosition().toShortString()) + com.seolwoni.assistcore.i18n.Tr.t("에서 시작 지점으로"));
		Alerts.actionBar(com.seolwoni.assistcore.i18n.Tr.t("하던 것을 멈추고 시작 지점으로 돌아가요"), 0xFFFFAA00);
		add(new CollectAction());
		// Off the known route (fled into a cave, inside a pocket): first back onto where the job had got to.
		addCore(new com.seolwoni.assistpath.walk.PathWalkAction(new com.seolwoni.assistpath.goals.GoalBlock(geo.pos(here)),
				com.seolwoni.assistpath.PathOptions.FULL, SKIP));
		run(() -> status(com.seolwoni.assistcore.i18n.Tr.t("시작 지점으로 돌아가는 중")));
		add(new RouteAction(geo.routeToOrigin(here)));
		run(() -> here = Loc.origin());
		if (cfg.storeEnabled && !cfg.chests.isEmpty()) add(new Storage.TripAction(geo.origin, geo.d, true));
		run(() -> {
			job.save();
			stop(com.seolwoni.assistcore.i18n.Tr.t("시작 지점으로 돌아왔어요"), com.seolwoni.assistcore.i18n.Tr.t("다시 켜면 하던 곳부터 이어서 파요"), true);
		});
	}

	static void goHomeAndFinish() {
		finishing = true;
		status(com.seolwoni.assistcore.i18n.Tr.t("시작 지점으로 돌아가는 중"));
		add(new RouteAction(geo.routeToOrigin(here)));
		run(() -> here = Loc.origin());
		MineConfig cfg = MineConfig.get();
		if (cfg.storeEnabled && !cfg.chests.isEmpty()) {
			add(new Storage.TripAction(geo.origin, geo.d, true));
		}
		run(() -> {
			job.active = false;
			job.save();
			stop(com.seolwoni.assistcore.i18n.Tr.t("채광 완료!"), com.seolwoni.assistcore.i18n.Tr.t("정리까지 끝내고 시작 지점으로 돌아왔어요"), true);
			Alerts.ding();
		});
	}

	private static void planStep(MineConfig cfg) {
		int n = geo.stairs;

		// 1) stairs down to the target Y
		if (job.stairsDone < n) {
			int i = job.stairsDone + 1;
			BlockPos s = geo.stair(i);
			status(com.seolwoni.assistcore.i18n.Tr.t("계단 파는 중 (") + i + "/" + n + ")");
			// Down: the step and two above it. Up: the step and one above it, plus the roof over the step we stand
			// on (room to jump up).
			List<BlockPos> col = geo.up ? List.of(geo.stair(i - 1).above(2), s.above(), s) : List.of(s.above(2), s.above(), s);
			for (BlockPos b : col) add(new MineAction(b, END));
			floor(s.below(), END);
			// Walls beside the new step before stepping onto it: over a big cave it is a bridge, and a knock while
			// walking onto it (walking is never done crouched) must not throw us off. Built up from the floor's side.
			enclose(col);
			add(new WalkAction(s));
			run(() -> {
				job.stairsDone = i;
				here = Loc.stair(i);
				job.save();
			});
			add(new OreScanAction(col));
			add(new CollectAction());
			enclose(col);
				if (cfg.torches && i > 1 && (i - 1) % cfg.torchInterval == 0) {
				add(new PlaceAction(geo.stair(i - 1).above(), PlaceAction.WALL_TORCH, SKIP));
			} else if (cfg.torches && i > 2) {
				torchIfDark(geo.stair(i - 1).above(), geo.stair(i - 1), PlaceAction.WALL_TORCH);
			}
			return;
		}

		int k = job.mainK;
		// 2) branches at this main step
		if (k > 0 && (job.pattern == 1 || job.branchLength > 0) && k % job.branchSpacing == 0 && job.branchSide < 2) {
			if (job.pattern == 1) planPoke(k, cfg);
			else planBranch(k);
			return;
		}
		// 3) next main step, or done
		if (k >= job.mainLength) {
			goHomeAndFinish();
			return;
		}
		int nk = k + 1;
		status(com.seolwoni.assistcore.i18n.Tr.t("가운데 굴 파는 중 (") + nk + "/" + job.mainLength + ")");
		BlockPos m = geo.main(nk), l = geo.lane1(nk);
		List<BlockPos> col = List.of(m.above(), m, l.above(), l);
		for (BlockPos b : col) add(new MineAction(b, END));
		floor(m.below(), END);
		floor(l.below(), END);
		enclose(col);
		add(new WalkAction(m));
		run(() -> {
			job.mainK = nk;
			job.branchSide = 0;
			job.branchD = 0;
			here = Loc.main(nk, 0);
			job.save();
		});
		add(new OreScanAction(col));
		add(new CollectAction());
		enclose(col);
		if (cfg.torches && nk % cfg.torchInterval == 0) add(new PlaceAction(l, PlaceAction.TORCH, SKIP));
		else if (cfg.torches) torchIfDark(l, l, PlaceAction.TORCH);
	}

	/**
	 * Poke holes: from the main tunnel, dig a 1x1 hole at eye level straight into the wall, a few blocks deep, on
	 * each side. Each hole shows the blocks above, below and on both sides of it, so far more stone is seen per
	 * block dug than with walk-in branches, and we never step into it. Ores showing in the holes are dug as usual.
	 */
	private static void planPoke(int k, MineConfig cfg) {
		int side = job.branchSide;
		String sideName = side == 0 ? com.seolwoni.assistcore.i18n.Tr.t("왼쪽") : com.seolwoni.assistcore.i18n.Tr.t("오른쪽");
		// Right side: stand in lane 1 (next to that wall).
		if (side == 1 && !(here.kind() == Loc.MAIN && here.b() == 1)) {
			add(new WalkAction(geo.lane1(k)));
			run(() -> here = Loc.main(k, 1));
			return;
		}
		status(sideName + com.seolwoni.assistcore.i18n.Tr.t(" 벽에 구멍 뚫는 중 (") + (k / job.branchSpacing) + com.seolwoni.assistcore.i18n.Tr.t("번)"));
		int depth = Math.max(1, Math.min(5, cfg.pokeDepth));
		List<BlockPos> hole = new ArrayList<>();
		for (int j = 1; j <= depth; j++) hole.add(geo.branch(k, side, j).above());
		for (BlockPos x : hole) add(new MineAction(x, SKIP));
		add(new OreScanAction(hole, true));
		add(new CollectAction());
		run(() -> {
			job.branchSide = side + 1;
			job.branchD = 0;
			job.save();
		});
	}

	private static void planBranch(int k) {
		MineConfig cfg = MineConfig.get();
		int side = job.branchSide;
		int d = job.branchD;
		String sideName = side == 0 ? com.seolwoni.assistcore.i18n.Tr.t("왼쪽") : com.seolwoni.assistcore.i18n.Tr.t("오른쪽");

		// Finished (or abandoned) this side: walk back to lane 0 of the main tunnel.
		if (d >= job.branchLength) {
			status(sideName + com.seolwoni.assistcore.i18n.Tr.t(" 가지굴에서 돌아오는 중"));
			int reached = here.kind() == Loc.BRANCH ? here.c() : 0;
			List<BlockPos> back = new ArrayList<>();
			for (int j = reached - 1; j >= 1; j--) back.add(geo.branch(k, side, j));
			if (side == 1) back.add(geo.lane1(k));
			back.add(geo.main(k));
			add(new RouteAction(back));
			run(() -> {
				here = Loc.main(k, 0);
				job.branchSide = side + 1;
				job.branchD = 0;
				job.save();
			});
			// Throw the junk into the branch we just finished: we never walk there again.
			add(new Chores.DiscardAction(side == 0 ? geo.r.getOpposite() : geo.r, true));
			return;
		}
		// Step over to lane 1 before starting the right branch.
		if (side == 1 && d == 0 && !(here.kind() == Loc.MAIN && here.b() == 1)) {
			add(new WalkAction(geo.lane1(k)));
			run(() -> here = Loc.main(k, 1));
			return;
		}
		int nd = d + 1;
		BlockPos b = geo.branch(k, side, nd);
		status(sideName + com.seolwoni.assistcore.i18n.Tr.t(" 가지굴 ") + (k / job.branchSpacing) + com.seolwoni.assistcore.i18n.Tr.t("번 (") + nd + "/" + job.branchLength + ")");
		List<BlockPos> col = List.of(b.above(), b);
		for (BlockPos x : col) add(new MineAction(x, ABANDON));
		floor(b.below(), ABANDON);
		add(new WalkAction(b));
		run(() -> {
			job.branchD = nd;
			here = Loc.branch(k, side, nd);
			job.save();
		});
		add(new OreScanAction(col));
		add(new CollectAction());
		enclose(col);
		if (cfg.torches && nd % cfg.torchInterval == 0 && nd > 1) {
			add(new PlaceAction(geo.branch(k, side, nd - 1), PlaceAction.TORCH, SKIP));
		} else if (cfg.torches && nd > 1) {
			torchIfDark(geo.branch(k, side, nd - 1), geo.branch(k, side, nd - 1), PlaceAction.TORCH);
		}
	}

	/**
	 * Now and then, between two steps, look around for a moment (back up the tunnel, at the wall, up at the
	 * roof) like a person digging does, instead of facing the rock face all the time.
	 */
	static void maybeGlance() {
		if (Human.RANDOM.nextDouble() < 0.07 * Human.liveliness()) add(new GlanceAction());
	}

	static final class GlanceAction implements Action {
		private float yaw, pitch;
		private int stage, hold;

		@Override
		public int failMode() {
			return SKIP;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			switch (stage) {
				case 0 -> {
					double r = Human.RANDOM.nextDouble();
					float side = (float) ((25 + Human.RANDOM.nextDouble() * 50) * (Human.RANDOM.nextBoolean() ? 1 : -1));
					if (r < 0.2) side = 150 + (float) Human.RANDOM.nextDouble() * 40; // back the way we came
					yaw = p.getYRot() + side;
					pitch = (float) (r < 0.5 ? -15 + Human.RANDOM.nextDouble() * 20 : 10 + Human.RANDOM.nextDouble() * 30);
					hold = Human.rand(8, 24);
					Body.newTurn();
					stage = 1;
					return RUN;
				}
				case 1 -> {
					if (Body.turnTo(p, yaw, pitch, 4f)) stage = 2;
					return RUN;
				}
				default -> {
					return hold-- > 0 ? RUN : DONE;
				}
			}
		}
	}

	/** Light (block or sky) below which a freshly dug cell gets a torch, like a player keeping the tunnel lit. */
	static final int DARK = 8;

	/**
	 * Once the step is dug: if it is dark there ({@code check} under light {@link #DARK}), put a torch at {@code at}.
	 * The interval setting is the most steps between torches; this fills the dark gaps in between (light falls off
	 * one level per block, so a torch every 10 left the middle of each stretch nearly black).
	 */
	static void torchIfDark(BlockPos at, BlockPos check, int kind) {
		add((mc, p) -> {
			if (mc.level != null && mc.level.getRawBrightness(check, 0) < DARK) pushFront(new PlaceAction(at, kind, SKIP));
			return DONE;
		});
	}

	/** Leave for the chests and come back to where we were. */
	static void storageTrip(boolean forTorches) {
		Loc back = here;
		status(com.seolwoni.assistcore.i18n.Tr.t("상자로 보관하러 가는 중"));
		add(new RouteAction(geo.routeToOrigin(here)));
		run(() -> here = Loc.origin());
		add(new Storage.TripAction(geo.origin, geo.d));
		status(com.seolwoni.assistcore.i18n.Tr.t("다시 파러 가는 중"));
		addRouteOut(back);
		run(() -> {
			here = back;
			if (forTorches) torchFetchTried = true;
		});
	}

	/** Walk from the origin out to {@code l}, putting up any stair torches that are missing on the way. */
	static void addRouteOut(Loc l) {
		MineConfig cfg = MineConfig.get();
		List<BlockPos> route = geo.routeFromOrigin(l);
		List<BlockPos> seg = new ArrayList<>();
		for (int idx = 0; idx < route.size(); idx++) {
			seg.add(route.get(idx));
			int stairNo = idx + 1;
			if (cfg.torches && stairNo <= geo.stairs && stairNo > 1 && (stairNo - 1) % cfg.torchInterval == 0
					&& !Blocks2.isTorch(geo.stair(stairNo - 1).above())) {
				// A stair torch is missing: stop here to put it up, then walk on.
				add(new RouteAction(seg));
				seg = new ArrayList<>();
				add(new PlaceAction(geo.stair(stairNo - 1).above(), PlaceAction.WALL_TORCH, SKIP));
			}
		}
		if (!seg.isEmpty()) add(new RouteAction(seg));
	}

	/** True for cells the miner walks through (stairs, main tunnel, branches): never fill these. */
	static boolean tunnelSpace(BlockPos q) {
		int dx = q.getX() - geo.origin.getX(), dz = q.getZ() - geo.origin.getZ();
		int along = dx * geo.d.getStepX() + dz * geo.d.getStepZ();
		int side = dx * geo.r.getStepX() + dz * geo.r.getStepZ();
		int n = geo.stairs;
		if (along >= 0 && along <= n) {
			int up = q.getY() - (geo.origin.getY() + geo.dy() * along);
			return side == 0 && up >= 0 && up <= 2;
		}
		int up = q.getY() - (geo.origin.getY() + geo.dy() * n);
		if (up < 0 || up > 1) return false;
		if (side == 0 || side == 1) return true;
		int k = along - n;
		return job.pattern == 0 && job.branchLength > 0 && job.branchSpacing > 0 && k % job.branchSpacing == 0;
	}

	/** Walls and roof around a freshly dug cross-section, so caves get closed off like a tunnel. */
	static void enclose(List<BlockPos> cells) {
		Set<BlockPos> seen = new HashSet<>();
		for (BlockPos c : cells) {
			for (Direction dir : Direction.values()) {
				if (dir == Direction.DOWN) continue;
				BlockPos q = c.relative(dir);
				if (tunnelSpace(q) || !seen.add(q)) continue;
				// Only where there really is a hole (a cave), and never in front of us: that is the next dig.
				if (dir == geo.d) continue;
				// A hole we dug for ore may still hold its drops: closing it would seal them in.
				if (minedOre.contains(q)) continue;
				add(new PlaceAction(q, PlaceAction.WALL, SKIP));
			}
		}
	}

	static boolean torchFetchTried() {
		return torchFetchTried;
	}

	static void clearTorchFetch() {
		torchFetchTried = false;
	}

	// ------------------------------------------------------------------ helpers

	static Direction faceToward(Vec3 eye, BlockPos pos) {
		double dx = eye.x - (pos.getX() + 0.5), dy = eye.y - (pos.getY() + 0.5), dz = eye.z - (pos.getZ() + 0.5);
		double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
		if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
		if (ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
		return dz > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	/** A point on the given face of a block, a little off-centre. */
	static Vec3 facePoint(BlockPos pos, Direction face, double spread) {
		double x = pos.getX() + 0.5 + face.getStepX() * 0.5;
		double y = pos.getY() + 0.5 + face.getStepY() * 0.5;
		double z = pos.getZ() + 0.5 + face.getStepZ() * 0.5;
		if (face.getAxis() != Direction.Axis.X) x += Human.jitter(spread);
		if (face.getAxis() != Direction.Axis.Y) y += Human.jitter(spread);
		if (face.getAxis() != Direction.Axis.Z) z += Human.jitter(spread);
		return new Vec3(x, y, z);
	}

	static boolean occupiedByPlayer(LocalPlayer p, BlockPos pos) {
		return p.getBoundingBox().intersects(new AABB(pos));
	}

	// ------------------------------------------------------------------ actions

	static final class WaitAction implements Action {
		private int t;

		WaitAction(int ticks) {
			this.t = ticks;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			return --t <= 0 ? DONE : RUN;
		}
	}

	/**
	 * Walks a known route in one go (no stop at every cell, runs on straight stretches). If something lies in
	 * the way, or the walk gets stuck, it falls back to careful cell-by-cell walking that clears the way.
	 */
	static final class RouteAction implements Action {
		private final List<BlockPos> cells;
		private com.seolwoni.assistcore.engine.RouteWalkAction walk;

		RouteAction(List<BlockPos> cells) {
			this.cells = new ArrayList<>(cells);
		}

		private static boolean free(BlockPos b) {
			return (Blocks2.isOpen(b) || Blocks2.isTorch(b)) && !Blocks2.isFluid(b);
		}

		private void fallback(int from) {
			List<Action> steps = new ArrayList<>();
			for (int i = from; i < cells.size(); i++) steps.add(new WalkAction(cells.get(i)));
			pushFront(steps);
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			if (cells.isEmpty()) return DONE;
			if (walk == null) {
				for (BlockPos b : cells) {
					if (!free(b) || !free(b.above())) {
						fallback(0);
						return DONE;
					}
				}
				walk = new com.seolwoni.assistcore.engine.RouteWalkAction(cells);
			}
			int r = walk.tick(mc, p);
			if (r == FAIL) {
				fallback(walk.index());
				return DONE;
			}
			return r;
		}
	}

	static final class WalkAction implements Action {
		private final BlockPos target;
		private final boolean skippable;
		private boolean started;
		private int pre, post = -1, clears, fallWait, stall;
		private Vec3 lastPos;

		WalkAction(BlockPos target) {
			this(target, false);
		}

		WalkAction(BlockPos target, boolean skippable) {
			this.target = target;
			this.skippable = skippable;
		}

		@Override
		public int failMode() {
			return skippable ? SKIP : STOP;
		}

		/** Blocks that must be open to step onto the target (gravel or sand may have fallen in). */
		private List<BlockPos> blockers(LocalPlayer p) {
			List<BlockPos> out = new ArrayList<>();
			List<BlockPos> need = new ArrayList<>(List.of(target, target.above()));
			if (target.getY() < p.blockPosition().getY()) need.add(target.above(2));
			if (target.getY() > p.blockPosition().getY()) need.add(p.blockPosition().above(2));
			for (BlockPos b : need) {
				if (!Blocks2.isTorch(b) && (!Blocks2.isOpen(b) || Blocks2.isFluid(b))) out.add(b);
			}
			return out;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			if (!started) {
				started = true;
				pre = Human.delay(0, 3);
				Body.resetWalk();
				Body.newTurn();
				List<BlockPos> blocked = blockers(p);
				if (!blocked.isEmpty() && clears++ < 20) {
					started = false;
					List<Action> digs = new ArrayList<>();
					for (BlockPos b : blocked) digs.add(new MineAction(b, skippable ? SKIP : END));
					pushFront(digs);
					return RUN;
				}
			}
			if (pre-- > 0) return RUN;
			if (post >= 0) return post-- <= 0 ? DONE : RUN;
			// Gravel or sand still falling nearby: wait for it to land, then look at the way again.
			if (fallWait < 60 && !mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class,
					new net.minecraft.world.phys.AABB(target).inflate(1.5, 4, 1.5)).isEmpty()) {
				fallWait++;
				Body.release(mc);
				if (fallWait % 10 == 0) started = false; // re-check (and clear) the way once it has landed
				return RUN;
			}
			if (Body.walkTo(p, target)) {
				post = Human.delay(0, 3);
				return RUN;
			}
			// Pressing on without getting anywhere: a block is in the way (one that came back or was never dug).
			// Do not keep running into it for seconds: look at the way right away and dig what blocks it.
			Vec3 now = p.position();
			if (lastPos != null && now.distanceToSqr(lastPos) < 0.0004 && p.onGround()) stall++;
			else stall = 0;
			lastPos = now;
			if (stall >= 10 && clears < 20) {
				stall = 0;
				List<BlockPos> blocked = blockers(p);
				// The cell right in front of us on the way, too (feet and head).
				double dx = target.getX() + 0.5 - now.x, dz = target.getZ() + 0.5 - now.z;
				double len = Math.sqrt(dx * dx + dz * dz);
				if (len > 0.3) {
					BlockPos ahead = BlockPos.containing(now.x + dx / len * 0.8, now.y + 0.1, now.z + dz / len * 0.8);
					for (BlockPos b : List.of(ahead, ahead.above())) {
						// Only cells at the target's own height or above: never the step we are about to climb, never a floor.
						if (b.getY() < target.getY() || b.getY() > target.getY() + 1) continue;
						if (!blocked.contains(b) && !Blocks2.isTorch(b) && !Blocks2.isOpen(b) && !Blocks2.isFluid(b) && !b.equals(p.blockPosition().below())) blocked.add(b);
					}
				}
				if (!blocked.isEmpty()) {
					Body.release(mc);
					clears++;
					List<Action> digs = new ArrayList<>();
					for (BlockPos b : blocked) digs.add(new MineAction(b, skippable ? SKIP : END));
					pushFront(digs);
					return RUN;
				}
			}
			if (Body.walkTicks() > 200) {
				Body.release(mc);
				// Something may have fallen in while walking: look again before giving up.
				if (!blockers(p).isEmpty() && clears < 20) {
					started = false;
					return RUN;
				}
				return FAIL;
			}
			return RUN;
		}

		@Override
		public String failReason() {
			return com.seolwoni.assistcore.i18n.Tr.t("길이 막혀서 움직일 수 없어요");
		}
	}

	/** Breaks one block safely: plugs fluids around it first, re-digs falling gravel/sand. */
	static final class MineAction implements Action {
		final BlockPos pos;
		private final int failMode;
		private int stage, ticks, settle, retries, delay, plugTries;
		private Vec3 aim;
		private Direction face;
		private String reason = "";
		private String oreId = "";
		boolean ore;
		/** Caves beside the block looked at (and closed) once. */
		private boolean caveChecked;

		MineAction(BlockPos pos, int failMode) {
			this.pos = pos;
			this.failMode = failMode;
		}

		@Override
		public int failMode() {
			return failMode;
		}

		@Override
		public String failReason() {
			return reason;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			switch (stage) {
				case 0 -> {
					if (Blocks2.isTorch(pos)) return DONE;
					if (Blocks2.isOpen(pos) && !Blocks2.isFluid(pos)) return DONE;
					if (Blocks2.isFluid(pos)) {
						// Water or lava where the tunnel goes: fill it first, then dig the filler.
						if (++plugTries > 4) {
							reason = com.seolwoni.assistcore.i18n.Tr.t("용암·물을 막지 못했어요");
							return FAIL;
						}
						pushFront(new PlaceAction(pos, PlaceAction.PLUG, failMode));
						return RUN;
					}
					if (Blocks2.unbreakable(pos)) {
						reason = com.seolwoni.assistcore.i18n.Tr.t("캘 수 없는 블록이 있어요");
						return FAIL;
					}
					List<Action> plugs = new ArrayList<>();
					for (Direction dir : Direction.values()) {
						BlockPos nb = pos.relative(dir);
						if (Blocks2.isFluid(nb) && !occupiedByPlayer(p, nb)) {
							plugs.add(new PlaceAction(nb, PlaceAction.PLUG, failMode));
						}
					}
					if (!plugs.isEmpty()) {
						if (++plugTries > 4) {
							reason = com.seolwoni.assistcore.i18n.Tr.t("용암·물을 막지 못했어요");
							return FAIL;
						}
						status(com.seolwoni.assistcore.i18n.Tr.t("용암·물 막는 중"));
						pushFront(plugs);
						return RUN;
					}
					// A cave right beside the block: close it first, the same way as water and lava, so no gap ever
					// opens onto the cave (mobs in it cannot see us or shoot, a knock cannot push us out into it).
					if (!caveChecked && geo != null && underground(pos)) {
						caveChecked = true;
						List<Action> walls = new ArrayList<>();
						for (Direction dir : Direction.values()) {
							BlockPos nb = pos.relative(dir);
							if (!(Blocks2.isOpen(nb) || Blocks2.isSoftCover(nb)) || Blocks2.isFluid(nb)) continue;
							if (tunnelSpace(nb) || minedOre.contains(nb) || dug.contains(nb) || occupiedByPlayer(p, nb)) continue;
							if (!underground(nb)) continue;
							// Once per cell: in a wide-open cave some cannot be built (nothing to build against); trying
							// them again every time the step is planned again kept the miner at it for minutes.
							// A second go only when there is now something to build against (a wall was built next to it).
							int tries = caveTries.getOrDefault(nb, 0);
							if (tries >= 2 || (tries == 1 && !hasSupport(nb))) continue;
							caveTries.put(nb.immutable(), tries + 1);
							caveTried.add(nb.immutable());
							walls.add(new PlaceAction(nb, PlaceAction.WALL, SKIP));
						}
						if (!walls.isEmpty()) {
							status(com.seolwoni.assistcore.i18n.Tr.t("동굴 쪽을 먼저 막는 중"));
							pushFront(walls);
							return RUN;
						}
					}
					if (!Inv.selectPickaxe(mc, p)) {
						reason = com.seolwoni.assistcore.i18n.Tr.t("쓸 수 있는 곡괭이가 없어요");
						stop(com.seolwoni.assistcore.i18n.Tr.t("곡괭이가 없어요"), com.seolwoni.assistcore.i18n.Tr.t("쓸 수 있는 곡괭이가 없어요"), true);
						return RUN;
					}
					oreId = Blocks2.id(pos);
					face = faceToward(p.getEyePosition(), pos);
					aim = facePoint(pos, face, 0.1);
					Body.newTurn();
					delay = Human.delay(0, 4);
					stage = 1;
					return RUN;
				}
				case 1 -> {
					boolean aligned = Body.lookAt(p, aim, 2.5f);
					if (aligned && delay-- <= 0) {
						stage = 2;
						ticks = 0;
					}
					return RUN;
				}
				case 2 -> {
					Body.lookAt(p, aim, 1.5f);
					mc.gameMode.continueDestroyBlock(pos, face);
					com.seolwoni.assistcore.util.Compat.swing(p, InteractionHand.MAIN_HAND);
					ticks++;
					if (Blocks2.isOpen(pos)) {
						dug.add(pos.immutable());
						job.blocksMined++;
						com.seolwoni.mineassist.hud.MineStats.block();
						if (ore || ores().contains(oreId)) {
							job.oresMined++;
							com.seolwoni.mineassist.hud.MineStats.ore(oreId);
						}
						settle = Human.delay(1, 5);
						stage = 3;
					} else if (ticks > 400) {
						mc.gameMode.stopDestroyBlock();
						reason = com.seolwoni.assistcore.i18n.Tr.t("블록이 캐지지 않아요");
						return FAIL;
					}
					return RUN;
				}
				default -> {
					if (!Blocks2.isOpen(pos) || Blocks2.isFluid(pos)) {
						// Gravel/sand fell in, or water flowed in: go again.
						if (++retries > 30) {
							reason = com.seolwoni.assistcore.i18n.Tr.t("떨어지는 블록이 너무 많아요");
							return FAIL;
						}
						stage = 0;
						return RUN;
					}
					return --settle <= 0 ? DONE : RUN;
				}
			}
		}
	}

	/** Places a filler block (cobblestone) or a torch at a position. */
	static final class PlaceAction implements Action {
		/** Vines or carpets cleared out of the spot first (lush caves). */
		private int softClears;
		static final int PLUG = 0, TORCH = 1, WALL_TORCH = 2, WALL = 3;
		/** Walls are nice to have: keep this many blocks back for floors. */
		static final int WALL_RESERVE = 16;
		private final BlockPos pos;
		private final int kind, failMode;
		private int stage, wait, attempts, delay, slot;
		private BlockPos support;
		private Direction face;
		private Vec3 hit;
		private String reason = "";
		private boolean wasFluid;
		private int bridges;

		PlaceAction(BlockPos pos, int kind, int failMode) {
			this.pos = pos;
			this.kind = kind;
			this.failMode = failMode;
		}

		@Override
		public int failMode() {
			return failMode;
		}

		@Override
		public String failReason() {
			return reason;
		}

		private boolean done() {
			return block() ? Blocks2.isSolid(pos) : Blocks2.isTorch(pos);
		}

		private boolean block() {
			return kind == PLUG || kind == WALL;
		}

		/** Torches and walls never stop the job when they cannot be placed. */
		private boolean torchKind() {
			return kind != PLUG;
		}

		/**
		 * Nothing to place against (open cave): a short chain of filler cells, from one that touches solid
		 * ground out to {@code pos}, that are all in reach and outside the tunnel.
		 */
		private List<BlockPos> bridgeChain(LocalPlayer p) {
			Vec3 eye = p.getEyePosition();
			java.util.Map<BlockPos, BlockPos> parent = new java.util.HashMap<>();
			Deque<BlockPos> open = new ArrayDeque<>();
			java.util.Map<BlockPos, Integer> depth = new java.util.HashMap<>();
			open.add(pos);
			depth.put(pos, 0);
			Direction[] order = {Direction.DOWN, geo.d.getOpposite(), geo.r, geo.r.getOpposite(), geo.d};
			while (!open.isEmpty()) {
				BlockPos c = open.poll();
				int dep = depth.get(c);
				if (dep >= 4) continue;
				for (Direction dir : order) {
					BlockPos q = c.relative(dir);
					if (depth.containsKey(q)) continue;
					depth.put(q, dep + 1);
					if (!(Blocks2.isOpen(q) || Blocks2.isFluid(q)) || tunnelSpace(q) || occupiedByPlayer(p, q)) continue;
					if (eye.distanceTo(Vec3.atCenterOf(q)) > 4.2) continue;
					parent.put(q, c);
					boolean touches = false;
					for (Direction t : Direction.values()) {
						BlockPos s2 = q.relative(t);
						if (Blocks2.isSolid(s2) && eye.distanceTo(Vec3.atCenterOf(s2)) <= 4.8) touches = true;
					}
					if (touches) {
						List<BlockPos> chain = new ArrayList<>();
						for (BlockPos x = q; x != null && !x.equals(pos); x = parent.get(x)) chain.add(x);
						return chain;
					}
					open.add(q);
				}
			}
			return List.of();
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			int r = step(mc, p);
			if (r != RUN || stage == 0) mc.options.keyShift.setDown(false);
			return r;
		}

		private int step(Minecraft mc, LocalPlayer p) {
			switch (stage) {
				case 0 -> {
					if (done()) return DONE;
					if (attempts == 0) wasFluid = Blocks2.isFluid(pos);
					if (block() && Blocks2.isSoftCover(pos) && !occupiedByPlayer(p, pos) && softClears++ < 3) {
						// Vines, a moss carpet, roots in the way: not a wall. Clear it, then put the block there.
						pushFront(new MineAction(pos, SKIP));
						return RUN;
					}
					if (block() && !Blocks2.isOpen(pos) && !Blocks2.isFluid(pos)) return DONE;
					if (kind == WALL && (Blocks2.isTorch(pos) || Inv.count(p, Inv::isPlug) <= WALL_RESERVE)) return DONE;
					// Never wall a drop in (an ore that dropped into a cave, a pocket we have not emptied yet).
					if (kind == WALL && !mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(pos),
							ie -> !Chores.isJunk(p, ie.getItem())).isEmpty()) return DONE;
					if (torchKind() && (!Blocks2.isOpen(pos) || Blocks2.isFluid(pos))) return DONE;
					if (block() && occupiedByPlayer(p, pos)) return DONE;
					if (!findSupport(p) && (kind == PLUG || kind == WALL) && bridges < 3 && Inv.count(p, Inv::isPlug) > 0) {
						List<BlockPos> chain = bridgeChain(p);
						if (!chain.isEmpty()) {
							bridges++;
							status(kind == PLUG ? com.seolwoni.assistcore.i18n.Tr.t("동굴 위에 다리 놓는 중") : com.seolwoni.assistcore.i18n.Tr.t("동굴 벽 쌓는 중"));
							List<Action> puts = new ArrayList<>();
							for (BlockPos c : chain) puts.add(new PlaceAction(c, kind, kind == PLUG ? failMode : SKIP));
							pushFront(puts);
							return RUN;
						}
					}
					if (support == null) {
						reason = kind == PLUG ? com.seolwoni.assistcore.i18n.Tr.t("막을 자리를 찾지 못했어요 (큰 동굴이나 용암 호수)") : "";
						return torchKind() ? DONE : FAIL;
					}
					slot = Inv.bringToHotbar(mc, p, block() ? Inv::isPlug : Inv::isTorch);
					if (slot == -1) {
						reason = kind == PLUG ? com.seolwoni.assistcore.i18n.Tr.t("막을 조약돌이 없어요") : "";
						return torchKind() ? DONE : FAIL;
					}
					delay = Human.delay(1, 5);
					Body.newTurn();
					stage = 1;
					return RUN;
				}
				case 1 -> {
					// Crouch at the edge while building out over a drop.
					if (kind == PLUG) mc.options.keyShift.setDown(true);
					if (delay-- > 0) return RUN;
					Inv.select(p, slot);
					stage = 2;
					return RUN;
				}
				case 2 -> {
					if (!Body.lookAt(p, hit, 3f)) return RUN;
					mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
					com.seolwoni.assistcore.util.Compat.swing(p, InteractionHand.MAIN_HAND);
					wait = Human.rand(2, 4);
					stage = 3;
					return RUN;
				}
				default -> {
					if (wait-- > 0) return RUN;
					if (done()) {
						if (kind == PLUG && wasFluid) com.seolwoni.mineassist.hud.MineStats.plug();
						return DONE;
					}
					if (++attempts >= 3) {
						reason = kind == PLUG ? com.seolwoni.assistcore.i18n.Tr.t("블록을 놓지 못했어요") : "";
						return torchKind() ? DONE : FAIL;
					}
					stage = 0;
					return RUN;
				}
			}
		}

		private boolean findSupport(LocalPlayer p) {
			Vec3 eye = p.getEyePosition();
			double best = Double.MAX_VALUE;
			support = null;
			Direction[] dirs = kind == TORCH ? new Direction[] {Direction.DOWN}
					: kind == WALL_TORCH ? new Direction[] {geo.r, geo.r.getOpposite()} : Direction.values();
			for (Direction dir : dirs) {
				BlockPos q = pos.relative(dir);
				if (!Blocks2.isSolid(q)) continue;
				Direction f = dir.getOpposite();
				Vec3 h = facePoint(q, f, 0.08);
				double dist = eye.distanceTo(h);
				if (dist > 4.4 || dist >= best) continue;
				best = dist;
				support = q;
				face = f;
				hit = h;
			}
			return support != null;
		}
	}

	/** Picks up drops lying in the tunnel just out of pickup range, then steps back. */
	/** Steps towards an item without leaving {@code cell}, until it is picked up or a short time is over. */
	static final class LeanAction implements Action {
		private final BlockPos cell;
		private final net.minecraft.world.entity.item.ItemEntity item;
		private int ticks;

		LeanAction(BlockPos cell, net.minecraft.world.entity.item.ItemEntity item) {
			this.cell = cell;
			this.item = item;
		}

		@Override
		public int failMode() {
			return SKIP;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			if (!item.isAlive() || ticks++ > Human.delay(16, 24)) {
				Body.release(mc);
				return DONE;
			}
			double cx = cell.getX() + 0.5, cz = cell.getZ() + 0.5;
			double tx = cx + Math.max(-0.42, Math.min(0.42, item.getX() - cx));
			double tz = cz + Math.max(-0.42, Math.min(0.42, item.getZ() - cz));
			Vec3 pos = p.position();
			double dx = tx - pos.x, dz = tz - pos.z;
			if (Math.sqrt(dx * dx + dz * dz) < 0.08) {
				mc.options.keyUp.setDown(false);
				return RUN;
			}
			float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
			Body.turnTo(p, yaw, Math.max(-10f, Math.min(35f, p.getXRot())), 1f);
			mc.options.keyUp.setDown(ticks % 2 == 0);
			return RUN;
		}
	}

	/** Drops we went for and could not get (entity id -> tries): after two, left where they are. */
	static final java.util.Map<Integer, Integer> collectTries = new java.util.HashMap<>();
	/** Drops given up on (shown to the player once, written to the usage log). */
	public static final Set<BlockPos> leftDrops = new java.util.LinkedHashSet<>();

	/** The job is in the Nether (set at start): no "up top", everything is underground; netherrack plugs. */
	/**
	 * Make the floor cell solid: plug it if open. Magma (it burns whoever walks on it) is dug out first and
	 * replaced (the lava under it is plugged first, as for any dig).
	 */
	static void floor(BlockPos f, int failMode) {
		// Looked at when we get there (the floor shows only once the cell above it is dug).
		add((mc, p) -> {
			if (Blocks2.state(f).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)) {
				status(com.seolwoni.assistcore.i18n.Tr.t("마그마 블록 바꾸는 중"));
				pushFront(new MineAction(f, failMode));
			}
			return DONE;
		});
		add(new PlaceAction(f, PlaceAction.PLUG, failMode));
	}

	static boolean inNether;
	private static int drinkTicks;

	static boolean isFireRes(net.minecraft.world.item.ItemStack st) {
		if (st.isEmpty() || !st.is(net.minecraft.world.item.Items.POTION)) return false;
		var pc = st.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
		if (pc == null) return false;
		for (var e : pc.getAllEffects()) if (e.getEffect().value() == net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE.value()) return true;
		return false;
	}

	/** Ores to dig: the Nether list in the Nether. */
	static java.util.List<String> ores() {
		MineConfig c = MineConfig.get();
		return inNether ? c.netherOres : c.ores;
	}

	/** Plug blocks to keep. */
	public static int plugKeep() {
		MineConfig c = MineConfig.get();
		return inNether ? c.netherPlugKeep : c.plugKeep;
	}

	static boolean wearsGold(net.minecraft.world.entity.player.Player p) {
		for (net.minecraft.world.entity.EquipmentSlot sl : new net.minecraft.world.entity.EquipmentSlot[] {
				net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
				net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
			net.minecraft.world.item.ItemStack st = p.getItemBySlot(sl);
			if (st.is(net.minecraft.world.item.Items.GOLDEN_HELMET) || st.is(net.minecraft.world.item.Items.GOLDEN_CHESTPLATE)
					|| st.is(net.minecraft.world.item.Items.GOLDEN_LEGGINGS) || st.is(net.minecraft.world.item.Items.GOLDEN_BOOTS)) return true;
		}
		return false;
	}

	/**
	 * Below the start, in the ground (where caves are closed off and Defense keeps to the tunnels). In the Nether
	 * everywhere counts: there is no safe open ground up top.
	 */
	static boolean underground(BlockPos q) {
		// Nether: everything is underground (caves above and below), except the room the player started in (a
		// shelter: its air is not a cave to wall off).
		if (inNether) return !startRoom(q);
		return q.getY() < geo.origin.getY() - 1;
	}

	/** Around the start: within 5 blocks sideways, from the floor to 5 above it. */
	static boolean startRoom(BlockPos q) {
		BlockPos o = geo.origin;
		return Math.abs(q.getX() - o.getX()) <= 5 && Math.abs(q.getZ() - o.getZ()) <= 5 && q.getY() >= o.getY() - 1 && q.getY() <= o.getY() + 5;
	}

	/** We hold sneak because we stand at an open drop (see {@link #edgeSneak}). */
	private static boolean edgeSneaking;

	/**
	 * Like a player on a bridge over a cave: crouch while standing next to an open drop (3 or more blocks down, or
	 * lava), so a knock (a fireball, an arrow) cannot push us over the edge. Crouching keeps the feet on the block.
	 */
	private static void edgeSneak(Minecraft mc, LocalPlayer p) {
		boolean edge = false;
		// Not while walking: crouching stops a step down onto the next stair (it holds us at every edge), so the
		// walk would stall. The knocks that throw us off come while we stand and work (dig, build a bridge).
		boolean walking = mc.options.keyUp.isDown() || mc.options.keyDown.isDown() || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
		if (!walking && p.onGround() && !p.isInWater()) edge = edgeAt(p.blockPosition());
		if (edge) {
			mc.options.keyShift.setDown(true);
			edgeSneaking = true;
		} else if (edgeSneaking) {
			mc.options.keyShift.setDown(false);
			edgeSneaking = false;
		}
	}

	/** Standing at {@code f}: is there an open drop (3 or more down, or lava) or an opening into a cave beside us? */
	static boolean edgeAt(BlockPos f) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = f.relative(d);
			if (!Blocks2.isOpen(n) || Blocks2.isFluid(n)) continue;
			int drop = 0;
			boolean lava = false;
			for (int k = 1; k <= 4; k++) {
				BlockPos b = n.below(k);
				if (Blocks2.isLava(b)) {
					lava = true;
					break;
				}
				if (!Blocks2.isOpen(b)) break;
				drop++;
			}
			if (drop >= 3 || lava) return true;
			// An opening from our tunnel into a cave, however shallow its floor: a knock pushes us into the cave
			// and further down from there (night: an arrow while digging a branch, then an 8-block fall).
			if (geo != null && underground(n) && !tunnelSpace(n) && !dug.contains(n) && !minedOre.contains(n)) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ ghasts

	/** The last place we stood that had no drop or cave opening beside it (inside our own walled tunnel). */
	private static BlockPos lastSafe;
	private static boolean sheltering;
	public static int ghastShelters;

	/** A ghast that can see us (we can see it too: it is in plain sight), within 64 blocks. */
	static net.minecraft.world.entity.Entity watchingGhast(Minecraft mc, LocalPlayer p) {
		for (net.minecraft.world.entity.Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(64))) {
			if (!e.isAlive() || !isGhast(e)) continue;
			if (p.hasLineOfSight(e)) return e;
		}
		return null;
	}

	private static boolean isGhast(net.minecraft.world.entity.Entity e) {
		return "ghast".equals(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
	}

	/**
	 * A ghast to keep away from an edge for: one that can see us within 64 blocks, or any ghast within 32 (hidden
	 * behind our own walls it is still right there, and comes round in a moment).
	 */
	static net.minecraft.world.entity.Entity dangerGhast(Minecraft mc, LocalPlayer p) {
		net.minecraft.world.entity.Entity seen = watchingGhast(mc, p);
		if (seen != null) return seen;
		for (net.minecraft.world.entity.Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(32))) {
			if (e.isAlive() && isGhast(e)) return e;
		}
		return null;
	}

	/** Edge spots where we already tried to wall off the drop (once each). */
	private static final Set<BlockPos> edgeWalled = new HashSet<>();

	/**
	 * In the Nether, a ghast's fireball blast throws us further than crouching can hold (nether-b: off a bridge, 18
	 * blocks down towards lava). So while a ghast is about, we never stand at an edge: back into the walled tunnel,
	 * crouch out of its sight, and go on once it has been gone for a while. And before working at an edge, the open
	 * side gets a wall (feet and head) where one can be built, so a blast pushes us into stone, not over the drop.
	 * True when this took over the tick.
	 */
	private static boolean ghastWatch(Minecraft mc, LocalPlayer p) {
		if (!inNether || geo == null) return false;
		BlockPos feet = p.blockPosition();
		if (p.onGround() && !edgeAt(feet) && onDugGround(feet)) lastSafe = feet.immutable();
		if (sheltering || lastSafe == null || !edgeAt(feet)) return false;
		if (dangerGhast(mc, p) != null) {
			shelter(mc, p);
			return true;
		}
		// Not while walking (a stair step down, the walk back): only where we stand and work.
		boolean walking = mc.options.keyUp.isDown() || mc.options.keyDown.isDown() || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
		if (!walking && p.onGround() && edgeWalled.add(feet.immutable()) && Inv.count(p, Inv::isPlug) > 16) {
			List<Action> walls = new ArrayList<>();
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos n = feet.relative(d);
				if (!dropBeside(n)) continue;
				for (BlockPos c : new BlockPos[] {n, n.above()}) {
					if (!Blocks2.isOpen(c) || Blocks2.isFluid(c) || tunnelSpace(c) || dug.contains(c) || minedOre.contains(c)) continue;
					walls.add(new PlaceAction(c, PlaceAction.WALL, SKIP));
				}
			}
			if (!walls.isEmpty()) {
				status(com.seolwoni.assistcore.i18n.Tr.t("동굴 벽 쌓는 중"));
				queue.pushFront(walls);
			}
		}
		return false;
	}

	/** Beside us at {@code n}: an open drop of 3 or more, or lava (the same test as {@link #edgeAt}, one side). */
	private static boolean dropBeside(BlockPos n) {
		if (!Blocks2.isOpen(n) || Blocks2.isFluid(n)) return false;
		int drop = 0;
		for (int k = 1; k <= 4; k++) {
			BlockPos b = n.below(k);
			if (Blocks2.isLava(b)) return true;
			if (!Blocks2.isOpen(b)) break;
			drop++;
		}
		return drop >= 3;
	}

	/** Back to the last safe spot in our tunnel, wait there until no ghast is about, then back to the work. */
	private static void shelter(Minecraft mc, LocalPlayer p) {
		if (ghastShelters >= 8) {
			// Ghasts keep coming back over this cave: no more work at its edge today.
			stop(com.seolwoni.assistcore.i18n.Tr.t("가스트가 계속 나타나서 굴 안에서 멈췄어요"), com.seolwoni.assistcore.i18n.Tr.t("가스트를 처리하거나 자리를 옮긴 뒤 다시 켜주세요"), true);
			return;
		}
		sheltering = true;
		ghastShelters++;
		queue.clear();
		Body.release(mc);
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		var box = com.seolwoni.assistcore.storage.PortableStorage.interrupted(mc);
		if (box != null) queue.add(box);
		status(com.seolwoni.assistcore.i18n.Tr.t("가스트를 피해 굴 안으로 들어가는 중"));
		Alerts.chat("Mine Assist", com.seolwoni.assistcore.i18n.Tr.t("가스트가 보고 있어서 낭떠러지에서 물러나 굴 안에서 기다려요"));
		final BlockPos to = lastSafe;
		queue.add(new com.seolwoni.assistpath.walk.PathWalkAction(new com.seolwoni.assistpath.goals.GoalBlock(to), com.seolwoni.assistpath.PathOptions.WALK,
				com.seolwoni.assistcore.engine.Action.SKIP));
		queue.add(new GhastWaitAction());
		// Watching again from here on: the walk back to the edge is where a ghast that came back would hit us.
		add((mc2, p2) -> {
			sheltering = false;
			return DONE;
		});
		BlockPos back = geo.pos(here);
		queue.add(new com.seolwoni.assistpath.walk.PathWalkAction(new com.seolwoni.assistpath.goals.GoalBlock(back), com.seolwoni.assistpath.PathOptions.WALK,
				com.seolwoni.assistcore.engine.Action.SKIP));
	}

	/**
	 * Crouched in the tunnel until no ghast has been about (none in sight, none within 32 blocks) for 15 seconds;
	 * a minute at most, then the job stops. (Out of its sight in the tunnel used to count as gone after 5 s.)
	 */
	static final class GhastWaitAction implements Action {
		private int clear, total;

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			mc.options.keyShift.setDown(true);
			if (total == 0) status(com.seolwoni.assistcore.i18n.Tr.t("가스트가 지나가길 기다리는 중"));
			total++;
			if (dangerGhast(mc, p) == null) clear++;
			else clear = 0;
			if (clear >= 300) {
				mc.options.keyShift.setDown(false);
				return DONE;
			}
			if (total > 1200) {
				mc.options.keyShift.setDown(false);
				sheltering = false;
				stop(com.seolwoni.assistcore.i18n.Tr.t("가스트가 1분 넘게 지켜보고 있어서 굴 안에서 멈췄어요"), com.seolwoni.assistcore.i18n.Tr.t("가스트를 처리하거나 자리를 옮긴 뒤 다시 켜주세요"), true);
				return DONE;
			}
			return RUN;
		}
	}

	/** Times in a row we ended up off the dug tunnels after Defense (see the tick). */
	private static int lostCount, lostEpisodes;

	/** Walls and roof around where we stand (feet and head level), then stop and tell the player where. */
	static void shelterAndStop(LocalPlayer p) {
		queue.clear();
		BlockPos f = p.blockPosition();
		status(com.seolwoni.assistcore.i18n.Tr.t("블록으로 몸을 막는 중"));
		List<Action> walls = new ArrayList<>();
		for (Direction d : Direction.Plane.HORIZONTAL) {
			walls.add(new PlaceAction(f.relative(d), PlaceAction.PLUG, SKIP));
			walls.add(new PlaceAction(f.above().relative(d), PlaceAction.PLUG, SKIP));
		}
		walls.add(new PlaceAction(f.above(2), PlaceAction.PLUG, SKIP));
		pushFront(walls);
		add((mc, pl) -> {
			lostCount = 0;
			lostEpisodes = 0;
			com.seolwoni.assistcore.log.UsageLog.event("mine", com.seolwoni.assistcore.i18n.Tr.t("굴 밖에서 돌아가지 못해 몸을 막고 멈춤: ") + f.toShortString());
			Alerts.chat("Mine Assist", com.seolwoni.assistcore.i18n.Tr.t("굴 밖 동굴(") + f.toShortString() + com.seolwoni.assistcore.i18n.Tr.t(")에서 돌아가는 길을 못 찾아, 블록으로 몸을 막고 멈췄어요."));
			stop(com.seolwoni.assistcore.i18n.Tr.t("굴 밖에서 길을 잃었어요"), com.seolwoni.assistcore.i18n.Tr.t("블록으로 몸을 막고 멈췄어요: ") + f.toShortString(), true);
			return DONE;
		});
	}

	/** For the tests: is the player where the job dug (tunnels, ore pockets, one step beside), or up top? */
	public static boolean onDugGround(BlockPos feet) {
		if (geo == null) return true;
		if (!underground(feet)) return true;
		return nearDug(feet) || nearDug(feet.below()) || nearDug(feet.above());
	}

	/** A cell we may step onto to pick something up: the tunnel itself, a dug-out ore pocket, or one step beside them. */
	static boolean nearDug(BlockPos c) {
		if (tunnelSpace(c) || minedOre.contains(c)) return true;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = c.relative(d);
			if (tunnelSpace(n) || minedOre.contains(n)) return true;
		}
		return false;
	}

	static final class CollectAction implements Action {
		private int wait = -1;
		private final int pass;

		CollectAction() {
			this(0);
		}

		CollectAction(int pass) {
			this.pass = pass;
		}

		@Override
		public int failMode() {
			return SKIP;
		}

		private static boolean standable(BlockPos b) {
			return Blocks2.isOpen(b) && !Blocks2.isFluid(b) && Blocks2.isOpen(b.above()) && Blocks2.isSolid(b.below());
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			// Fresh drops cannot be picked up for a moment, but only wait if something is lying around.
			if (wait < 0) {
				boolean any = false;
				for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(3.5, 2.5, 3.5))) {
					if (e instanceof net.minecraft.world.entity.item.ItemEntity ie && ie.isAlive() && !Chores.isJunk(p, ie.getItem())) any = true;
				}
				if (!any) return DONE;
				wait = Human.delay(8, 14);
			}
			if (wait-- > 0) return RUN;
			BlockPos home = p.blockPosition();
			AABB area = p.getBoundingBox().inflate(3.5, 2.5, 3.5);
			AABB reach = p.getBoundingBox().inflate(1.0, 0.5, 1.0);
			List<Action> walks = new ArrayList<>();
			BlockPos last = home;
			for (Entity e : mc.level.getEntities(p, area)) {
				if (!(e instanceof net.minecraft.world.entity.item.ItemEntity ie) || !e.isAlive()) continue;
				if (Chores.isJunk(p, ie.getItem())) continue;
				if (reach.intersects(e.getBoundingBox())) continue;
				BlockPos at = e.blockPosition();
				// Tried twice already and it is still lying there (behind a wall, in a dungeon, down a crack): like a
				// player, let it be instead of going for it over and over.
				int tries = collectTries.getOrDefault(e.getId(), 0);
				if (tries >= 2) {
					if (leftDrops.add(at.immutable())) {
						Alerts.actionBar(com.seolwoni.assistcore.i18n.Tr.t("줍지 못한 아이템을 두고 가요: ") + at.toShortString(), 0xFFFFAA00);
						com.seolwoni.assistcore.log.UsageLog.event("mine", com.seolwoni.assistcore.i18n.Tr.t("줍지 못한 아이템 두고 감: ") + ie.getItem().getCount() + " " + Inv.id(ie.getItem()) + " at " + at.toShortString());
					}
					continue;
				}
				// A drop in a small hole below our floor (a dug-out floor ore): stepping in means running at the edge
				// and climbing out again. Filling the hole pushes the drop up to us instead, and leaves the floor whole.
				if (!tunnelSpace(at) && at.getY() < home.getY() && Blocks2.isSolid(at.below()) && Blocks2.isOpen(at.above())
						&& !Blocks2.isFluid(at) && !occupiedByPlayer(p, at) && at.distManhattan(home) <= 3) {
					walks.add(new PlaceAction(at, PlaceAction.PLUG, SKIP));
					walks.add(new WaitAction(Human.delay(6, 10)));
					continue;
				}
				BlockPos best = null;
				double bestD = Double.MAX_VALUE;
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						for (int dy = -1; dy <= 0; dy++) {
							BlockPos c = at.offset(dx, dy, dz);
							if (!standable(c) || Math.abs(c.getY() - home.getY()) > 1) continue;
							double d = c.distSqr(at);
							if (d < bestD) {
								bestD = d;
								best = c;
							}
						}
					}
				}
				if (best == null || best.distManhattan(home) > 4) continue;
				// Never go off into a cave or a dungeon after it: only where we dug (or one step beside).
				if (!nearDug(best)) {
					collectTries.merge(e.getId(), 2, Integer::sum);
					continue;
				}
				collectTries.merge(e.getId(), 1, Integer::sum);
				if (collectTries.size() > 256) collectTries.clear();
				if (!best.equals(last)) walks.add(new WalkAction(best, true));
				// Standing on the right cell is not always close enough (an item tucked into a hole):
				// lean over towards it inside the cell.
				walks.add(new LeanAction(best, ie));
				last = best;
			}
			if (walks.isEmpty()) return DONE;
			status(com.seolwoni.assistcore.i18n.Tr.t("떨어진 아이템 줍는 중"));
			walks.add(new WalkAction(home, false));
			// Look once more: something may have rolled away while walking.
			if (pass < 2) walks.add(new CollectAction(pass + 1));
			pushFront(walks);
			wait = 0;
			return DONE;
		}
	}

	/** After stepping into a new column, mines the target ores showing in its walls, floor and ceiling. */
	/**
	 * After digging out an ore vein: if drops are lying in the dug-out pockets (out of reach from the tunnel),
	 * fill the pockets with filler blocks from the farthest cell to the nearest. An item inside a block is pushed
	 * to the nearest free space, so cell by cell the drops are moved out into the tunnel, where we pick them up.
	 */
	/** What the pocket filling did (for the tests and the usage log). */
	public static final List<String> pocketLog = new ArrayList<>();

	static void pocketNote(String s) {
		if (pocketLog.size() < 40) pocketLog.add(s);
	}

	/** Waits until no drop is inside the given (just filled) cell any more, at most a second and a half. */
	static final class ItemsOutAction implements Action {
		private final BlockPos cell;
		private int t;

		ItemsOutAction(BlockPos cell) {
			this.cell = cell;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			if (++t < 4) return RUN;
			AABB box = new AABB(cell);
			boolean inside = !mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box).isEmpty();
			return inside && t < 30 ? RUN : DONE;
		}
	}

	static final class PocketFillAction implements Action {
		private final List<BlockPos> cells;
		private int wait = -1, rounds, fetches, nearWait;

		/** Cells to walk (at our feet height, L-shaped) to stand on the drop's column, or null if not safe or too far. */
		private static List<BlockPos> walkway(BlockPos home, BlockPos drop) {
			int dy = drop.getY() - home.getY();
			if (dy < 0 || dy > 2) return null;
			int dx = drop.getX() - home.getX(), dz = drop.getZ() - home.getZ();
			if (Math.abs(dx) + Math.abs(dz) > 5 || (dx == 0 && dz == 0)) return null;
			List<BlockPos> best = null;
			int bestDig = Integer.MAX_VALUE;
			for (int order = 0; order < 2; order++) {
				List<BlockPos> way = new ArrayList<>();
				BlockPos c = home;
				int dig = 0;
				boolean ok = true;
				for (int leg = 0; leg < 2 && ok; leg++) {
					boolean alongX = (leg == 0) == (order == 0);
					int n = alongX ? dx : dz;
					for (int i = 0; i < Math.abs(n) && ok; i++) {
						c = alongX ? c.offset(Integer.signum(n), 0, 0) : c.offset(0, 0, Integer.signum(n));
						way.add(c);
						for (BlockPos q : List.of(c, c.above())) {
							if (tunnelSpace(q)) continue;
							if (!Blocks2.isOpen(q)) dig++;
							// Never open up next to lava or water.
							for (Direction d : Direction.values()) if (Blocks2.isFluid(q.relative(d))) ok = false;
							if (Blocks2.isFluid(q)) ok = false;
						}
						BlockPos floor = c.below();
						if (Blocks2.isFluid(floor) || (!Blocks2.isSolid(floor) && !Blocks2.isOpen(floor))) ok = false;
					}
				}
				if (ok && dig < bestDig) {
					bestDig = dig;
					best = way;
				}
			}
			return best;
		}

		PocketFillAction(List<BlockPos> cells) {
			this.cells = cells;
		}

		@Override
		public int failMode() {
			return SKIP;
		}

		private static boolean open(BlockPos c) {
			return Blocks2.isOpen(c) && !Blocks2.isFluid(c);
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			if (wait < 0) wait = Human.delay(8, 14); // let the drops settle first
			if (wait-- > 0) return RUN;
			// Drops lying off the tunnel (in the dug-out pockets or a crack next to them).
			List<BlockPos> stuck = new ArrayList<>();
			boolean near = false;
			for (Entity e : mc.level.getEntities(p, p.getBoundingBox().inflate(6))) {
				if (!(e instanceof net.minecraft.world.entity.item.ItemEntity ie) || !ie.isAlive() || Chores.isJunk(p, ie.getItem())) continue;
				BlockPos at = ie.blockPosition();
				if (tunnelSpace(at) || tunnelSpace(at.below())) continue;
				// Close enough to just pick up: no need to move it, but stay until it is really picked up
				// (fresh drops cannot be picked up for a moment, and walking on would leave them behind).
				if (nearWait < 40 && p.getBoundingBox().inflate(1.0, 0.5, 1.0).intersects(ie.getBoundingBox())) {
					near = true;
					continue;
				}
				stuck.add(at);
			}
			if (stuck.isEmpty() && near && nearWait++ < 40) return RUN;
			if (stuck.isEmpty() || rounds++ >= 2) return DONE;
			// First choice: a drop lying at our height (or on top of a block at head height) a few steps away is
			// fetched by digging a short walkway to it and back, like a player would. Filling is for the rest.
			BlockPos home = p.blockPosition();
			for (BlockPos s : fetches < 6 ? stuck : List.<BlockPos>of()) {
				List<BlockPos> way = walkway(home, s);
				if (way == null) continue;
				List<Action> todo = new ArrayList<>();
				for (BlockPos c : way) {
					if (!Blocks2.isSolid(c.below()) && !tunnelSpace(c.below())) todo.add(new PlaceAction(c.below(), PlaceAction.PLUG, SKIP));
					todo.add(new WalkAction(c, true));
				}
				todo.add(new CollectAction(1));
				for (int i = way.size() - 2; i >= 0; i--) todo.add(new WalkAction(way.get(i), true));
				todo.add(new WalkAction(home, true));
				// Ore dug on the way leaves drops of its own: look around once more before re-checking.
				todo.add(new CollectAction());
				todo.add(this);
				pocketNote("round " + rounds + ": dig a walkway of " + way.size() + " to the drop at " + s.toShortString());
				status(com.seolwoni.assistcore.i18n.Tr.t("광석 구멍에서 아이템 꺼내는 중"));
				wait = 0;
				rounds--; // fetching does not count as a filling round
				fetches++;
				pushFront(todo);
				return DONE;
			}
			// From each drop, find the open way back to the tunnel; fill that way from the far end inwards.
			java.util.Map<BlockPos, Integer> depth = new java.util.HashMap<>();
			// A filled cell pushes its items sideways or up, never down; where the way out goes down, gravity does it.
			Set<BlockPos> drains = new HashSet<>();
			for (BlockPos start : stuck) {
				java.util.Map<BlockPos, BlockPos> parent = new java.util.HashMap<>();
				Deque<BlockPos> q = new ArrayDeque<>();
				q.add(start);
				parent.put(start, start);
				BlockPos exit = null;
				while (!q.isEmpty() && parent.size() < 60 && exit == null) {
					BlockPos c = q.poll();
					for (Direction d : Direction.values()) {
						BlockPos n = c.relative(d);
						if (parent.containsKey(n)) continue;
						if (tunnelSpace(n)) {
							exit = c;
							if (d == Direction.DOWN) drains.add(c);
							break;
						}
						if (!open(n)) continue;
						parent.put(n, c);
						q.add(n);
					}
				}
				if (exit == null) continue;
				int k = 0;
				BlockPos prev = null;
				for (BlockPos c = exit; ; c = parent.get(c)) {
					depth.merge(c, k++, Math::max);
					if (prev != null && prev.equals(c.below())) drains.add(c);
					prev = c;
					if (c.equals(start)) break;
				}
			}
			Vec3 eye = p.getEyePosition();
			List<BlockPos> order = new ArrayList<>(depth.keySet());
			order.removeIf(c -> drains.contains(c) || !open(c) || tunnelSpace(c) || occupiedByPlayer(p, c) || eye.distanceTo(Vec3.atCenterOf(c)) > 4.4);
			// Farthest from the tunnel first: each filled cell pushes the drops one cell further out.
			order.sort((a, b) -> Integer.compare(depth.get(b), depth.get(a)));
			pocketNote("round " + rounds + ": " + stuck.size() + " drops off the tunnel at " + stuck + ", way out found for " + depth.size()
					+ " cells (" + drains.size() + " drop down by themselves), " + order.size() + " to fill, filler " + Inv.count(p, Inv::isPlug));
			if (order.isEmpty() || Inv.count(p, Inv::isPlug) < order.size()) return DONE;
			List<Action> todo = new ArrayList<>();
			for (BlockPos c : order) {
				todo.add(new PlaceAction(c, PlaceAction.PLUG, SKIP));
				// Give the pushed item time to drift into the next cell before that one is filled too.
				todo.add(new ItemsOutAction(c));
			}
			todo.add(new WaitAction(Human.delay(16, 24))); // pushed-out items drift slowly
			todo.add(new CollectAction());
			todo.add(this); // look once more (a drop may need a second push)
			wait = 0;
			status(com.seolwoni.assistcore.i18n.Tr.t("광석 구멍 메우며 아이템 꺼내는 중"));
			pushFront(todo);
			return DONE;
		}
	}

	static final class OreScanAction implements Action {
		private final List<BlockPos> column;
		private final boolean allSides;
		private boolean scanned;

		OreScanAction(List<BlockPos> column) {
			this(column, false);
		}

		/** allSides: also above and below the cells (poke holes: that is the point of them). */
		OreScanAction(List<BlockPos> column, boolean allSides) {
			this.column = column;
			this.allSides = allSides;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			// Scan only once: ores we could not reach are left alone instead of retried forever.
			if (scanned) return DONE;
			scanned = true;
			MineConfig cfg = MineConfig.get();
			Set<BlockPos> tunnel = new HashSet<>(column);
			Set<BlockPos> found = new HashSet<>();
			int top = Integer.MIN_VALUE, bottom = Integer.MAX_VALUE;
			for (BlockPos b : column) {
				top = Math.max(top, b.getY());
				bottom = Math.min(bottom, b.getY());
			}
			for (BlockPos b : column) {
				for (Direction dir : Direction.values()) {
					BlockPos n = b.relative(dir);
					if (tunnel.contains(n) || found.contains(n)) continue;
					if (!allSides && !cfg.mineFloorCeiling && (n.getY() > top || n.getY() < bottom)) continue;
					if (ores().contains(Blocks2.id(n))) vein(n, found, p);
				}
			}
			if (found.isEmpty()) return DONE;
			List<BlockPos> sorted = new ArrayList<>(found);
			Vec3 eye = p.getEyePosition();
			sorted.sort((a, b) -> Double.compare(eye.distanceToSqr(Vec3.atCenterOf(a)), eye.distanceToSqr(Vec3.atCenterOf(b))));
			boolean alerted = false;
			List<Action> todo = new ArrayList<>();
			int feetY = p.blockPosition().getY();
			net.minecraft.world.phys.AABB body = p.getBoundingBox();
			for (BlockPos o : sorted) {
				String id = Blocks2.id(o);
				// Never dig the floor out from under our own feet (we would drop into the hole and get stuck there).
				if (o.getY() < feetY && body.maxX > o.getX() && body.minX < o.getX() + 1 && body.maxZ > o.getZ() && body.minZ < o.getZ() + 1) continue;
				// Nothing under the ore (a cave, a drop, lava): its drops would fall where we cannot follow safely.
				// Leave it in place and say where it is, so the player can take it by hand.
				// A floor ore is plugged right after digging, and the plug pushes its drops to the nearest open side:
				// with a cave beside it, that is into the cave. So for floor ores the sides count as well.
				boolean escape = false;
				for (Direction dir : o.getY() < feetY ? new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST} : new Direction[] {Direction.DOWN}) {
					BlockPos n = o.relative(dir);
					if (!tunnelSpace(n) && !found.contains(n) && (Blocks2.isOpen(n) || Blocks2.isFluid(n))) escape = true;
				}
				// Nether: lava on any side of the ore (above above all): never dig it, a plug may not hold and a flow
				// from above lands on us. Leave it for the player, with its place.
				boolean byLava = false;
				if (inNether) for (Direction dir : Direction.values()) {
					if (Blocks2.isLava(o.relative(dir))) byLava = true;
				}
				if (byLava) {
					if (leftOverVoid.add(o.immutable())) {
						String where = o.getX() + ", " + o.getY() + ", " + o.getZ();
						pocketNote("left " + Blocks2.id(o) + " at " + where + ": lava next to it");
						Alerts.actionBar(com.seolwoni.assistcore.i18n.Tr.t("용암 옆이라 남겨둔 광석: ") + where, 0xFFFFAA55);
						Alerts.chat("Mine Assist", com.seolwoni.assistcore.i18n.Tr.t("용암 옆이라 남겨둔 ") + Blocks2.state(o).getBlock().getName().getString() + ": " + where);
						com.seolwoni.assistcore.log.UsageLog.event("mine", com.seolwoni.assistcore.i18n.Tr.t("용암 옆이라 남겨둔 광석 ") + Blocks2.id(o) + " (" + where + ")");
					}
					continue;
				}
				if (escape) {
					if (leftOverVoid.add(o.immutable())) {
						String where = o.getX() + ", " + o.getY() + ", " + o.getZ();
						pocketNote("left " + Blocks2.id(o) + " at " + where + ": open below or beside");
						Alerts.actionBar(com.seolwoni.assistcore.i18n.Tr.t("옆·아래가 비어 있어 남겨둔 광석: ") + where, 0xFFFFAA55);
						com.seolwoni.assistcore.log.UsageLog.event("mine", com.seolwoni.assistcore.i18n.Tr.t("옆·아래가 비어 있어 남겨둔 광석 ") + Blocks2.id(o) + " (" + where + ")");
					}
					continue;
				}
				if (!alerted && cfg.alertOres.contains(id)) {
					alerted = true;
					String name = Blocks2.state(o).getBlock().getName().getString();
					Alerts.title(com.seolwoni.assistcore.i18n.Tr.t("광석 발견!"), name, 0xFF55FFFF);
					Alerts.ding();
				}
				MineAction m = new MineAction(o, SKIP);
				m.ore = true;
				minedOre.add(o.immutable());
				todo.add(m);
				// Ore dug out of the floor leaves a hole: put a block back so we never step into it.
				if (o.getY() < feetY) todo.add(new PlaceAction(o, PlaceAction.PLUG, SKIP));
			}
			// Drops that land in the ore pockets beside the tunnel are out of reach: fill the pockets from the far
			// end inwards, which pushes the items out towards us, then pick them up.
			// Pick up what is in reach first; only what is left after that gets pushed out by filling.
			todo.add(new CollectAction());
			todo.add(new PocketFillAction(new ArrayList<>(found)));
			status(com.seolwoni.assistcore.i18n.Tr.t("광석 캐는 중"));
			pushFront(todo);
			return RUN;
		}

		private void vein(BlockPos start, Set<BlockPos> found, LocalPlayer p) {
			MineConfig cfg = MineConfig.get();
			Deque<BlockPos> open = new ArrayDeque<>();
			open.add(start);
			Vec3 eye = p.getEyePosition();
			while (!open.isEmpty() && found.size() < 48) {
				BlockPos b = open.poll();
				if (found.contains(b)) continue;
				if (!ores().contains(Blocks2.id(b))) continue;
				if (eye.distanceTo(Vec3.atCenterOf(b)) > 4.5) continue;
				found.add(b);
				for (Direction dir : Direction.values()) open.add(b.relative(dir));
			}
		}
	}
}
