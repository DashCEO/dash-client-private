package com.dash.client.client;

import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class AimCritTiming {
    private static final int FORECAST_TICKS = 12;
    private static final double VERTICAL_DRAG = 0.98F;
    private static final double HORIZONTAL_DRAG = 0.91F;
    private static final double COLLISION_EPSILON = 1.0e-7;
    private static LocalPlayer tickPlayer;
    private static long tickStarted;
    private static Sample sample;

    private AimCritTiming() {}

    static void markTick(LocalPlayer player) {
        tickPlayer = player;
        tickStarted = System.nanoTime();
        sample = null;
    }

    static boolean isReady(Player player) {
        return !player.onGround() && player.fallDistance > 0.0 && permitsCritical(player) && charged(player, 0);
    }

    private static boolean permitsCritical(Player player) {
        // Player.canCriticalAttack in Minecraft 1.21.11, for a living target.
        return !player.onClimbable() && !player.isInWater() && !player.isMobilityRestricted()
                && !player.isPassenger() && !player.isSprinting();
    }

    private static boolean charged(Player player, int ticksAhead) {
        return player.getAttackStrengthScale(0.5F + ticksAhead) > 0.9F
                && !player.cannotAttackWithItem(player.getMainHandItem(), ticksAhead);
    }

    static Window assess(LocalPlayer player, int horizonMs) {
        if (tickPlayer != player) markTick(player);
        if (sample == null) sample = forecast(player);
        long tickNanos = sample.tickNanos();
        long elapsed = Math.clamp(System.nanoTime() - tickStarted, 0L, tickNanos - 1L);
        int untilCritical = remainingMs(sample.criticalInTicks(), tickNanos, elapsed);
        return new Window(sample.onGround(), sample.velocityY(), sample.fallDistance(), sample.falling(),
                sample.cooldown(), sample.criticalInTicks() == 0,
                untilCritical <= horizonMs ? untilCritical : -1, remainingMs(sample.landInTicks(), tickNanos, elapsed));
    }

    private static Sample forecast(LocalPlayer player) {
        var level = player.level();
        long tickNanos = Math.max(1L, level.tickRateManager().nanosecondsPerTick());
        boolean onGround = player.onGround();
        Vec3 motion = player.getDeltaMovement();
        double fallDistance = player.fallDistance;
        float cooldown = player.getAttackStrengthScale(0.5F);
        boolean falling = !onGround && motion.y < 0.0;
        boolean critAllowed = permitsCritical(player);
        int critTick = !onGround && fallDistance > 0.0 && critAllowed && charged(player, 0) ? 0 : -1;
        int landTick = onGround ? 0 : -1;
        double gravity = player.getGravity();
        if (!onGround && gravity > 0.0 && !player.onClimbable()
                && !player.isInWater() && !player.isInLava() && !player.isPassenger()
                && !player.getAbilities().flying && !player.isFallFlying()
                && !player.hasEffect(MobEffects.LEVITATION) && !player.hasEffect(MobEffects.SLOW_FALLING)) {
            AABB box = player.getBoundingBox();
            Vec3 velocity = motion;
            double estimatedFall = fallDistance;
            // Sweep the current body along short extrapolated movement. Without future input this is an estimate.
            for (int tick = 1; tick <= FORECAST_TICKS; tick++) {
                Vec3 step = Entity.collideBoundingBox(player, velocity, box, level, List.of());
                if (velocity.y < 0.0 && step.y - velocity.y > COLLISION_EPSILON) {
                    landTick = tick;
                    break;
                }
                box = box.move(step);
                if (step.y < 0.0) estimatedFall -= step.y;
                if (critTick < 0 && critAllowed && estimatedFall > 0.0 && step.y < 0.0 && charged(player, tick)) {
                    critTick = tick;
                }
                velocity = new Vec3(step.x * HORIZONTAL_DRAG, (step.y - gravity) * VERTICAL_DRAG,
                        step.z * HORIZONTAL_DRAG);
            }
        }
        return new Sample(onGround, motion.y, fallDistance, falling, cooldown, critTick, landTick, tickNanos);
    }

    private static int remainingMs(int ticks, long tickNanos, long elapsed) {
        if (ticks <= 0) return ticks;
        long remaining = ticks * tickNanos - elapsed;
        return (int) Math.max(1L, (remaining + 999_999L) / 1_000_000L);
    }

    record Window(boolean onGround, double velocityY, double fallDistance, boolean falling, float cooldown,
                  boolean criticalNow, int untilCriticalMs, int untilLandingMs) {}

    private record Sample(boolean onGround, double velocityY, double fallDistance, boolean falling, float cooldown,
                          int criticalInTicks, int landInTicks, long tickNanos) {}
}
