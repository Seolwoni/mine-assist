package com.seolwoni.mineassist.mining;

import java.util.ArrayList;
import java.util.List;

import com.seolwoni.mineassist.config.JobState;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The fixed shape of a job, all relative to the origin O and facing D (R = right of D):
 * <pre>
 *  stairs  S(i) = O + D*i - up*i            i = 1..n, 1 wide, 3 high while descending
 *  main    M(k) = S(n) + D*k                k = 1..len, 2 wide (lane 0 = M, lane 1 = M + R), 2 high
 *  left    L(k,d) = M(k) - R*d              every 'spacing' steps, 1 wide, 2 high
 *  right   Rt(k,d) = M(k) + R*(1+d)
 * </pre>
 */
public final class Geo {
	public final BlockPos origin;
	public final Direction d;
	public final Direction r;
	public final int stairs;
	/** Stairs go up (started below the target height, as in the Nether under the ancient debris level). */
	public final boolean up;

	public Geo(JobState s) {
		this.origin = new BlockPos(s.ox, s.oy, s.oz);
		this.d = Direction.from2DDataValue(s.dir);
		this.r = d.getClockWise();
		this.up = s.targetY > s.oy;
		this.stairs = Math.abs(s.oy - s.targetY);
	}

	/** +1 when the stairs climb, -1 when they go down. */
	public int dy() {
		return up ? 1 : -1;
	}

	public BlockPos stair(int i) {
		return origin.relative(d, i).above(dy() * i);
	}

	public BlockPos main0() {
		return stairs > 0 ? stair(stairs) : origin;
	}

	public BlockPos main(int k) {
		return main0().relative(d, k);
	}

	public BlockPos lane1(int k) {
		return main(k).relative(r);
	}

	public BlockPos left(int k, int dist) {
		return main(k).relative(r.getOpposite(), dist);
	}

	public BlockPos right(int k, int dist) {
		return main(k).relative(r, 1 + dist);
	}

	public BlockPos branch(int k, int side, int dist) {
		return side == 0 ? left(k, dist) : right(k, dist);
	}

	/** Where the player stands: on the stairs, in the main tunnel (lane 0/1), or in a branch. */
	public record Loc(int kind, int a, int b, int c) {
		public static final int ORIGIN = 0, STAIR = 1, MAIN = 2, BRANCH = 3;

		public static Loc origin() {
			return new Loc(ORIGIN, 0, 0, 0);
		}

		public static Loc stair(int i) {
			return i <= 0 ? origin() : new Loc(STAIR, i, 0, 0);
		}

		/** k, lane */
		public static Loc main(int k, int lane) {
			return new Loc(MAIN, k, lane, 0);
		}

		/** k, side, dist */
		public static Loc branch(int k, int side, int dist) {
			return dist <= 0 ? main(k, side == 0 ? 0 : 1) : new Loc(BRANCH, k, side, dist);
		}
	}

	public BlockPos pos(Loc l) {
		return switch (l.kind()) {
			case Loc.STAIR -> stair(l.a());
			case Loc.MAIN -> l.a() <= 0 ? main0() : (l.b() == 0 ? main(l.a()) : lane1(l.a()));
			case Loc.BRANCH -> branch(l.a(), l.b(), l.c());
			default -> origin;
		};
	}

	/** Every standing position from (not including) the origin out to {@code l}. */
	public List<BlockPos> routeFromOrigin(Loc l) {
		List<BlockPos> out = new ArrayList<>();
		int stairTo = l.kind() == Loc.STAIR ? l.a() : (l.kind() == Loc.ORIGIN ? 0 : stairs);
		for (int i = 1; i <= stairTo; i++) out.add(stair(i));
		if (l.kind() == Loc.MAIN || l.kind() == Loc.BRANCH) {
			int k = l.a();
			for (int j = 1; j <= k; j++) out.add(main(j));
			boolean viaLane1 = (l.kind() == Loc.MAIN && l.b() == 1) || (l.kind() == Loc.BRANCH && l.b() == 1);
			if (viaLane1 && k > 0) out.add(lane1(k));
			if (l.kind() == Loc.BRANCH) {
				for (int j = 1; j <= l.c(); j++) out.add(branch(k, l.b(), j));
			}
		}
		return out;
	}

	/** Standing positions from {@code l} back to the origin, ending at the origin. */
	public List<BlockPos> routeToOrigin(Loc l) {
		List<BlockPos> fwd = routeFromOrigin(l);
		List<BlockPos> out = new ArrayList<>();
		for (int i = fwd.size() - 2; i >= 0; i--) out.add(fwd.get(i));
		out.add(origin);
		return out;
	}
}
