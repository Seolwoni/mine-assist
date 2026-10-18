package com.seolwoni.mineassist.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Smooth, human-looking looking and walking. */
public final class Body {
	private static float turnSpeed = 18f;
	private static int walkTicks;

	private Body() {
	}

	private static float wrap(float d) {
		d %= 360f;
		if (d >= 180f) d -= 360f;
		if (d < -180f) d += 360f;
		return d;
	}

	public static float yawTo(LocalPlayer p, Vec3 t) {
		Vec3 e = p.getEyePosition();
		return (float) (Math.toDegrees(Math.atan2(t.z - e.z, t.x - e.x)) - 90.0);
	}

	public static float pitchTo(LocalPlayer p, Vec3 t) {
		Vec3 e = p.getEyePosition();
		double dx = t.x - e.x, dy = t.y - e.y, dz = t.z - e.z;
		return (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
	}

	/** Start a new turn: picks a fresh turning speed so every movement looks a little different. */
	public static void newTurn() {
		turnSpeed = 11f + Human.RANDOM.nextFloat() * 12f;
	}

	/**
	 * Turns towards yaw/pitch with an ease-out. Returns true once within {@code tolerance} degrees.
	 */
	public static boolean turnTo(LocalPlayer p, float yaw, float pitch, float tolerance) {
		float dy = wrap(yaw - p.getYRot());
		float dp = pitch - p.getXRot();
		float dist = (float) Math.sqrt(dy * dy + dp * dp);
		if (dist <= tolerance) return true;
		float step = Math.min(dist, Math.max(1.5f, Math.min(turnSpeed, dist * 0.45f)));
		float f = step / dist;
		p.setYRot(p.getYRot() + dy * f);
		p.setXRot(Math.max(-90f, Math.min(90f, p.getXRot() + dp * f)));
		return false;
	}

	public static boolean lookAt(LocalPlayer p, Vec3 t, float tolerance) {
		return turnTo(p, yawTo(p, t), pitchTo(p, t), tolerance);
	}

	// ------------------------------------------------------------------ walking

	public static void resetWalk() {
		walkTicks = 0;
	}

	public static int walkTicks() {
		return walkTicks;
	}

	/**
	 * Walks to the centre of the given feet position. Returns true when arrived.
	 * Looks where it is going with a relaxed downward pitch, and eases off near the target.
	 */
	public static boolean walkTo(LocalPlayer p, BlockPos feet) {
		Minecraft mc = Minecraft.getInstance();
		walkTicks++;
		Vec3 target = new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
		Vec3 pos = p.position();
		double dx = target.x - pos.x, dz = target.z - pos.z;
		double dist = Math.sqrt(dx * dx + dz * dz);
		double dyFeet = feet.getY() - pos.y;
		boolean arrived = dist < 0.22 && Math.abs(dyFeet) < 0.6;
		if (arrived) {
			release(mc);
			return true;
		}
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		turnTo(p, yaw, 18f + (float) Human.jitter(4), 1f);
		float yawErr = Math.abs(wrap(yaw - p.getYRot()));
		// Ease off: only push forward while facing roughly the right way, and tap it when very close.
		boolean push = yawErr < 35f && (dist > 0.5 || walkTicks % 2 == 0);
		mc.options.keyUp.setDown(push);
		mc.options.keyJump.setDown(dyFeet > 0.5 && p.onGround());
		return false;
	}

	public static void release(Minecraft mc) {
		mc.options.keyUp.setDown(false);
		mc.options.keyJump.setDown(false);
		mc.options.keyAttack.setDown(false);
	}
}
