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
	static Loc here = Loc.origin();
	private static final ActionQueue queue = new ActionQueue(Miner::onFail);
	private static String status = "꺼짐";
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
		LocalPlayer p = mc.player;
		MineConfig cfg = MineConfig.get();
		if (p == null || mc.level == null) return false;
		queue.clear();
		finishing = false;
		torchFetchTried = false;
		BlockPos feet = p.blockPosition();

		if (cfg.resume && job.active && job.world.equals(worldKey(mc))) {
			BlockPos o = new BlockPos(job.ox, job.oy, job.oz);
			if (feet.distManhattan(o) > 1) {
				Alerts.title("시작 지점이 아니에요", "이어서 파려면 " + o.getX() + ", " + o.getY() + ", " + o.getZ() + "에서 켜주세요", 0xFFFF5555);
				Alerts.chat("Mine Assist", "이어서 파려면 시작 지점 " + o.toShortString() + "으로 가서 켜주세요. 새로 시작하려면 설정 → 기본 → 진행 초기화.");
				Alerts.warning();
				return false;
			}
			geo = new Geo(job);
			// Jobs saved by 1.2.3 and older started with mainK = 1 and would skip main step 1.
			if (job.stairsDone < geo.stairs && job.mainK == 1) job.mainK = 0;
			here = Loc.origin();
			Loc frontier = frontier();
			status("이어서 파러 가는 중");
			addRouteOut(frontier);
			run(() -> here = frontier);
			Alerts.actionBar("이어서 파기 시작", 0xFF55FF55);
		} else {
			if (p.blockPosition().getY() < cfg.targetY) {
				Alerts.title("목표 Y보다 아래에 있어요", "Y " + cfg.targetY + " 이상에서 시작해주세요", 0xFFFF5555);
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
			job.targetY = cfg.targetY;
			job.mainLength = cfg.mainLength;
			job.branchLength = cfg.branchLength;
			job.branchSpacing = cfg.branchSpacing;
			job.save();
			geo = new Geo(job);
			here = Loc.origin();
			Alerts.actionBar("채광 시작: " + geo.d.getName() + " 방향, Y " + job.targetY, 0xFF55FF55);
		}
		// Gear up at the chests before setting off (food, torches, a spare pickaxe).
		if (cfg.storeEnabled && !cfg.chests.isEmpty() && Storage.needsSupplies(p)) {
			pushFront(new Storage.TripAction(geo.origin, geo.d));
		}
		nextPause = Human.rand(1500, 4000);
		cfg.running = true;
		MineConfig.save();
		return true;
	}

	/** Why the miner last stopped (title / subtitle), for the debug log and automated tests. */
	public static String lastStop = "";

	public static void stop(String title, String sub, boolean alert) {
		lastStop = (title == null ? "" : title) + (sub == null ? "" : " / " + sub);
		Minecraft mc = Minecraft.getInstance();
		MineConfig cfg = MineConfig.get();
		cfg.running = false;
		MineConfig.save();
		queue.clear();
		Body.release(mc);
		mc.options.keyUse.setDown(false);
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		if (mc.player != null && mc.gui.screen() instanceof AbstractContainerScreen<?> s && !(s instanceof InventoryScreen)) {
			mc.player.closeContainer();
		}
		job.save();
		status("꺼짐");
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
		job.ticks++;
		com.seolwoni.mineassist.hud.MineStats.tick();

		Human.humanLike = cfg.humanDelay;
		Human.pace = cfg.pace;
		if (queue.tick(mc, p)) return;
		plan(mc, p, cfg);
	}

	private static boolean safety(Minecraft mc, LocalPlayer p, MineConfig cfg) {
		if (p.isDeadOrDying()) {
			BlockPos b = p.blockPosition();
			Alerts.chat("Mine Assist", "사망 위치: " + b.getX() + ", " + b.getY() + ", " + b.getZ());
			stop("죽었어요", "사망 위치를 채팅에 남겼어요", true);
			return false;
		}
		if (p.isInLava() || p.isOnFire()) {
			stop("위험! 불이나 용암", "채광을 멈췄어요", true);
			return false;
		}
		if (p.getHealth() <= cfg.minHealth) {
			stop("체력이 낮아요", "채광을 멈췄어요", true);
			return false;
		}
		if (cfg.stopOnMob && job.ticks % 10 == 0) {
			AABB box = p.getBoundingBox().inflate(cfg.mobRange);
			for (Entity e : mc.level.getEntities(p, box)) {
				if (e instanceof Enemy && e.isAlive()) {
					stop("몹이 다가와요", e.getName().getString() + " 근처에 있어요", true);
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
				Alerts.actionBar("가지굴을 더 팔 수 없어서 다음 가지굴로 넘어가요", 0xFFFFAA00);
			}
			case END -> {
				queue.clear();
				Alerts.actionBar("가운데 굴이 막혀서 마무리하고 돌아가요", 0xFFFFAA00);
				goHomeAndFinish();
			}
			default -> stop("채광을 멈췄어요", a.failReason().isEmpty() ? "진행할 수 없어요" : a.failReason(), true);
		}
	}

	// ------------------------------------------------------------------ planning

	private static void plan(Minecraft mc, LocalPlayer p, MineConfig cfg) {
		if (finishing) return;

		// Every so often, take a short breather like a person would.
		if (--nextPause <= 0) {
			nextPause = Human.rand(1500, 4000);
			add(new WaitAction(Human.rand(30, 100)));
			status("잠깐 쉬는 중");
			return;
		}
		if (Chores.planChores(mc, p, cfg)) return;
		planStep(cfg);
	}

	/** Leave the tunnel: walk back to the origin and end the job. */
	static void goHomeAndFinish() {
		finishing = true;
		status("시작 지점으로 돌아가는 중");
		add(new RouteAction(geo.routeToOrigin(here)));
		run(() -> here = Loc.origin());
		MineConfig cfg = MineConfig.get();
		if (cfg.storeEnabled && !cfg.chests.isEmpty()) {
			add(new Storage.TripAction(geo.origin, geo.d, true));
		}
		run(() -> {
			job.active = false;
			job.save();
			stop("채광 완료!", "정리까지 끝내고 시작 지점으로 돌아왔어요", true);
			Alerts.ding();
		});
	}

	private static void planStep(MineConfig cfg) {
		int n = geo.stairs;

		// 1) stairs down to the target Y
		if (job.stairsDone < n) {
			int i = job.stairsDone + 1;
			BlockPos s = geo.stair(i);
			status("계단 파는 중 (" + i + "/" + n + ")");
			List<BlockPos> col = List.of(s.above(2), s.above(), s);
			for (BlockPos b : col) add(new MineAction(b, END));
			add(new PlaceAction(s.below(), PlaceAction.PLUG, END));
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
			}
			return;
		}

		int k = job.mainK;
		// 2) branches at this main step
		if (k > 0 && job.branchLength > 0 && k % job.branchSpacing == 0 && job.branchSide < 2) {
			planBranch(k);
			return;
		}
		// 3) next main step, or done
		if (k >= job.mainLength) {
			goHomeAndFinish();
			return;
		}
		int nk = k + 1;
		status("가운데 굴 파는 중 (" + nk + "/" + job.mainLength + ")");
		BlockPos m = geo.main(nk), l = geo.lane1(nk);
		List<BlockPos> col = List.of(m.above(), m, l.above(), l);
		for (BlockPos b : col) add(new MineAction(b, END));
		add(new PlaceAction(m.below(), PlaceAction.PLUG, END));
		add(new PlaceAction(l.below(), PlaceAction.PLUG, END));
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
	}

	private static void planBranch(int k) {
		MineConfig cfg = MineConfig.get();
		int side = job.branchSide;
		int d = job.branchD;
		String sideName = side == 0 ? "왼쪽" : "오른쪽";

		// Finished (or abandoned) this side: walk back to lane 0 of the main tunnel.
		if (d >= job.branchLength) {
			status(sideName + " 가지굴에서 돌아오는 중");
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
		status(sideName + " 가지굴 " + (k / job.branchSpacing) + "번 (" + nd + "/" + job.branchLength + ")");
		List<BlockPos> col = List.of(b.above(), b);
		for (BlockPos x : col) add(new MineAction(x, ABANDON));
		add(new PlaceAction(b.below(), PlaceAction.PLUG, ABANDON));
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
		}
	}

	/** Leave for the chests and come back to where we were. */
	static void storageTrip(boolean forTorches) {
		Loc back = here;
		status("상자로 보관하러 가는 중");
		add(new RouteAction(geo.routeToOrigin(here)));
		run(() -> here = Loc.origin());
		add(new Storage.TripAction(geo.origin, geo.d));
		status("다시 파러 가는 중");
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
			int up = q.getY() - (geo.origin.getY() - along);
			return side == 0 && up >= 0 && up <= 2;
		}
		int up = q.getY() - (geo.origin.getY() - n);
		if (up < 0 || up > 1) return false;
		if (side == 0 || side == 1) return true;
		int k = along - n;
		return job.branchLength > 0 && job.branchSpacing > 0 && k % job.branchSpacing == 0;
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
		private int pre, post = -1, clears, fallWait;

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
			return "길이 막혀서 움직일 수 없어요";
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
							reason = "용암·물을 막지 못했어요";
							return FAIL;
						}
						pushFront(new PlaceAction(pos, PlaceAction.PLUG, failMode));
						return RUN;
					}
					if (Blocks2.unbreakable(pos)) {
						reason = "캘 수 없는 블록이 있어요";
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
							reason = "용암·물을 막지 못했어요";
							return FAIL;
						}
						status("용암·물 막는 중");
						pushFront(plugs);
						return RUN;
					}
					if (!Inv.selectPickaxe(mc, p)) {
						reason = "쓸 수 있는 곡괭이가 없어요";
						stop("곡괭이가 없어요", "쓸 수 있는 곡괭이가 없어요", true);
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
					p.swing(InteractionHand.MAIN_HAND);
					ticks++;
					if (Blocks2.isOpen(pos)) {
						job.blocksMined++;
						com.seolwoni.mineassist.hud.MineStats.block();
						if (ore || MineConfig.get().ores.contains(oreId)) {
							job.oresMined++;
							com.seolwoni.mineassist.hud.MineStats.ore(oreId);
						}
						settle = Human.delay(1, 5);
						stage = 3;
					} else if (ticks > 400) {
						mc.gameMode.stopDestroyBlock();
						reason = "블록이 캐지지 않아요";
						return FAIL;
					}
					return RUN;
				}
				default -> {
					if (!Blocks2.isOpen(pos) || Blocks2.isFluid(pos)) {
						// Gravel/sand fell in, or water flowed in: go again.
						if (++retries > 30) {
							reason = "떨어지는 블록이 너무 많아요";
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
					if (block() && !Blocks2.isOpen(pos) && !Blocks2.isFluid(pos)) return DONE;
					if (kind == WALL && (Blocks2.isTorch(pos) || Inv.count(p, Inv::isPlug) <= WALL_RESERVE)) return DONE;
					if (torchKind() && (!Blocks2.isOpen(pos) || Blocks2.isFluid(pos))) return DONE;
					if (block() && occupiedByPlayer(p, pos)) return DONE;
					if (!findSupport(p) && (kind == PLUG || kind == WALL) && bridges < 3 && Inv.count(p, Inv::isPlug) > 0) {
						List<BlockPos> chain = bridgeChain(p);
						if (!chain.isEmpty()) {
							bridges++;
							status(kind == PLUG ? "동굴 위에 다리 놓는 중" : "동굴 벽 쌓는 중");
							List<Action> puts = new ArrayList<>();
							for (BlockPos c : chain) puts.add(new PlaceAction(c, kind, kind == PLUG ? failMode : SKIP));
							pushFront(puts);
							return RUN;
						}
					}
					if (support == null) {
						reason = kind == PLUG ? "막을 자리를 찾지 못했어요 (큰 동굴이나 용암 호수)" : "";
						return torchKind() ? DONE : FAIL;
					}
					slot = Inv.bringToHotbar(mc, p, block() ? Inv::isPlug : Inv::isTorch);
					if (slot == -1) {
						reason = kind == PLUG ? "막을 조약돌이 없어요" : "";
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
					p.swing(InteractionHand.MAIN_HAND);
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
						reason = kind == PLUG ? "블록을 놓지 못했어요" : "";
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
				if (!best.equals(last)) walks.add(new WalkAction(best, true));
				// Standing on the right cell is not always close enough (an item tucked into a hole):
				// lean over towards it inside the cell.
				walks.add(new LeanAction(best, ie));
				last = best;
			}
			if (walks.isEmpty()) return DONE;
			status("떨어진 아이템 줍는 중");
			walks.add(new WalkAction(home, false));
			// Look once more: something may have rolled away while walking.
			if (pass < 2) walks.add(new CollectAction(pass + 1));
			pushFront(walks);
			wait = 0;
			return DONE;
		}
	}

	/** After stepping into a new column, mines the target ores showing in its walls, floor and ceiling. */
	static final class OreScanAction implements Action {
		private final List<BlockPos> column;
		private boolean scanned;

		OreScanAction(List<BlockPos> column) {
			this.column = column;
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
					if (!cfg.mineFloorCeiling && (n.getY() > top || n.getY() < bottom)) continue;
					if (cfg.ores.contains(Blocks2.id(n))) vein(n, found, p);
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
				if (!alerted && cfg.alertOres.contains(id)) {
					alerted = true;
					String name = Blocks2.state(o).getBlock().getName().getString();
					Alerts.title("광석 발견!", name, 0xFF55FFFF);
					Alerts.ding();
				}
				MineAction m = new MineAction(o, SKIP);
				m.ore = true;
				todo.add(m);
				// Ore dug out of the floor leaves a hole: put a block back so we never step into it.
				if (o.getY() < feetY) todo.add(new PlaceAction(o, PlaceAction.PLUG, SKIP));
			}
			status("광석 캐는 중");
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
				if (!cfg.ores.contains(Blocks2.id(b))) continue;
				if (eye.distanceTo(Vec3.atCenterOf(b)) > 4.5) continue;
				found.add(b);
				for (Direction dir : Direction.values()) open.add(b.relative(dir));
			}
		}
	}
}
