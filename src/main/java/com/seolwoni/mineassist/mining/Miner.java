package com.seolwoni.mineassist.mining;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.seolwoni.mineassist.config.JobState;
import com.seolwoni.mineassist.config.MineConfig;
import com.seolwoni.mineassist.mining.Geo.Loc;
import com.seolwoni.mineassist.util.Alerts;
import com.seolwoni.mineassist.util.Blocks2;
import com.seolwoni.mineassist.util.Body;
import com.seolwoni.mineassist.util.Human;
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
	public static final int RUN = 0, DONE = 1, FAIL = 2;
	/** What a failure means: skip it, give up this branch, finish the job, or stop right away. */
	public static final int SKIP = 0, ABANDON = 1, END = 2, STOP = 3;

	public interface Action {
		int tick(Minecraft mc, LocalPlayer p);

		default int failMode() {
			return STOP;
		}

		default String failReason() {
			return "";
		}
	}

	static JobState job = new JobState();
	static Geo geo;
	static Loc here = Loc.origin();
	private static final Deque<Action> queue = new ArrayDeque<>();
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
		for (int i = list.size() - 1; i >= 0; i--) queue.addFirst(list.get(i));
	}

	static void pushFront(Action a) {
		queue.addFirst(a);
	}

	static void add(Action a) {
		queue.addLast(a);
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
				Alerts.chat("이어서 파려면 시작 지점 " + o.toShortString() + "으로 가서 켜주세요. 새로 시작하려면 설정 → 기본 → 진행 초기화.");
				Alerts.warning();
				return false;
			}
			geo = new Geo(job);
			here = Loc.origin();
			Loc frontier = frontier();
			status("이어서 파러 가는 중");
			for (BlockPos b : geo.routeFromOrigin(frontier)) add(new WalkAction(b));
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
		nextPause = Human.rand(1500, 4000);
		cfg.running = true;
		MineConfig.save();
		return true;
	}

	public static void stop(String title, String sub, boolean alert) {
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

		Action a = queue.peekFirst();
		if (a != null) {
			int r = a.tick(mc, p);
			if (r == DONE) {
				if (queue.peekFirst() == a) queue.pollFirst();
				else queue.remove(a);
			} else if (r == FAIL) {
				queue.remove(a);
				onFail(a);
			}
			return;
		}
		plan(mc, p, cfg);
	}

	private static boolean safety(Minecraft mc, LocalPlayer p, MineConfig cfg) {
		if (p.isDeadOrDying()) {
			BlockPos b = p.blockPosition();
			Alerts.chat("사망 위치: " + b.getX() + ", " + b.getY() + ", " + b.getZ());
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

	private static void onFail(Action a) {
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
		for (BlockPos b : geo.routeToOrigin(here)) add(new WalkAction(b));
		run(() -> {
			here = Loc.origin();
			job.active = false;
			job.save();
			stop("채광 완료!", "시작 지점으로 돌아왔어요", true);
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
			for (int j = reached - 1; j >= 1; j--) add(new WalkAction(geo.branch(k, side, j)));
			if (side == 1) add(new WalkAction(geo.lane1(k)));
			add(new WalkAction(geo.main(k)));
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
		if (cfg.torches && nd % cfg.torchInterval == 0 && nd > 1) {
			add(new PlaceAction(geo.branch(k, side, nd - 1), PlaceAction.TORCH, SKIP));
		}
	}

	/** Leave for the chests and come back to where we were. */
	static void storageTrip(boolean forTorches) {
		Loc back = here;
		status("상자로 보관하러 가는 중");
		for (BlockPos b : geo.routeToOrigin(here)) add(new WalkAction(b));
		run(() -> here = Loc.origin());
		add(new Storage.TripAction(geo.origin, geo.d));
		status("다시 파러 가는 중");
		for (BlockPos b : geo.routeFromOrigin(back)) add(new WalkAction(b));
		run(() -> {
			here = back;
			if (forTorches) torchFetchTried = true;
		});
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

	static final class WalkAction implements Action {
		private final BlockPos target;
		private boolean started;
		private int pre, post = -1;

		WalkAction(BlockPos target) {
			this.target = target;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			if (!started) {
				started = true;
				pre = Human.delay(0, 4);
				Body.resetWalk();
				Body.newTurn();
			}
			if (pre-- > 0) return RUN;
			if (post >= 0) return post-- <= 0 ? DONE : RUN;
			if (Body.walkTo(p, target)) {
				post = Human.delay(0, 3);
				return RUN;
			}
			if (Body.walkTicks() > 200) {
				Body.release(mc);
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
					face = faceToward(p.getEyePosition(), pos);
					aim = facePoint(pos, face, 0.28);
					Body.newTurn();
					delay = Human.delay(1, 6);
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
						if (ore) job.oresMined++;
						settle = Human.rand(3, 6);
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
		static final int PLUG = 0, TORCH = 1;
		private final BlockPos pos;
		private final int kind, failMode;
		private int stage, wait, attempts, delay, slot;
		private BlockPos support;
		private Direction face;
		private Vec3 hit;
		private String reason = "";

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
			return kind == PLUG ? Blocks2.isSolid(pos) : Blocks2.isTorch(pos);
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			switch (stage) {
				case 0 -> {
					if (done()) return DONE;
					if (kind == PLUG && !Blocks2.isOpen(pos) && !Blocks2.isFluid(pos)) return DONE;
					if (kind == TORCH && (!Blocks2.isOpen(pos) || Blocks2.isFluid(pos))) return DONE;
					if (kind == PLUG && occupiedByPlayer(p, pos)) return DONE;
					if (!findSupport(p)) {
						reason = kind == PLUG ? "막을 자리를 찾지 못했어요 (큰 동굴이나 용암 호수)" : "";
						return kind == TORCH ? DONE : FAIL;
					}
					slot = Inv.bringToHotbar(mc, p, kind == PLUG ? Inv::isPlug : Inv::isTorch);
					if (slot == -1) {
						reason = kind == PLUG ? "막을 조약돌이 없어요" : "";
						return kind == TORCH ? DONE : FAIL;
					}
					delay = Human.delay(1, 5);
					Body.newTurn();
					stage = 1;
					return RUN;
				}
				case 1 -> {
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
					if (done()) return DONE;
					if (++attempts >= 3) {
						reason = kind == PLUG ? "블록을 놓지 못했어요" : "";
						return kind == TORCH ? DONE : FAIL;
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
			Direction[] dirs = kind == TORCH ? new Direction[] {Direction.DOWN} : Direction.values();
			for (Direction dir : dirs) {
				BlockPos q = pos.relative(dir);
				if (!Blocks2.isSolid(q)) continue;
				Direction f = dir.getOpposite();
				Vec3 h = facePoint(q, f, kind == TORCH ? 0.15 : 0.2);
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
			for (BlockPos o : sorted) {
				String id = Blocks2.id(o);
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
