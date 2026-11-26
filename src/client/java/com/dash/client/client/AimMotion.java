package com.dash.client.client;

final class AimMotion {
    private static final long MAX_FRAME_GAP_NANOS = 100_000_000;
    private static final double MAX_DEGREES_PER_FRAME = 8.0;
    private static final double EASING_SPEED_MARGIN = 1.6;
    private long start;
    private long previousFrame;
    private double previousEase;

    void begin(long now) {
        start = now;
        previousFrame = now;
        previousEase = 0.0;
    }

    boolean isTimely(long now) {
        return now - previousFrame < MAX_FRAME_GAP_NANOS;
    }

    static int durationForAura(int requestedMs, double yawError, double pitchError, int maxDegreesPerSecond) {
        double angle = Math.hypot(yawError, pitchError);
        // Give a rear turn time to finish while keeping the same bounded frame steps as Smart.
        int turnMs = (int) Math.ceil(angle * 1_000.0 * EASING_SPEED_MARGIN / maxDegreesPerSecond);
        return Math.max(requestedMs, turnMs);
    }

    static double sweepStep(long elapsedNanos, int maxDegreesPerSecond) {
        if (elapsedNanos <= 0 || elapsedNanos >= MAX_FRAME_GAP_NANOS) return 0.0;
        return Math.min(MAX_DEGREES_PER_FRAME, elapsedNanos * 1.0e-9 * maxDegreesPerSecond);
    }

    Turn step(long now, double yawError, double pitchError, int durationMs, float strength, float smoothness,
              int maxDegreesPerSecond) {
        double progress = Math.min(1.0, (now - start) / (durationMs * 1_000_000.0));
        double accelerated = progress + (strength - 0.5) * progress * (1.0 - progress);
        double smooth = accelerated * accelerated * (3.0 - 2.0 * accelerated);
        double ease = accelerated + (smooth - accelerated) * smoothness;
        // Consume this frame's share of the angle remaining after the player's own movement.
        double blend = previousEase < 1.0 ? (ease - previousEase) / (1.0 - previousEase) : 0.0;
        if (maxDegreesPerSecond > 0) {
            double magnitude = Math.hypot(yawError, pitchError);
            double limit = sweepStep(now - previousFrame, maxDegreesPerSecond);
            if (magnitude > 0.0) blend = Math.min(blend, limit / magnitude);
        }
        previousFrame = now;
        previousEase = ease;
        return new Turn(yawError * blend, pitchError * blend, progress >= 1.0);
    }

    record Turn(double yaw, double pitch, boolean finished) {}
}
