package com.dash.client.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

final class ChinaHat {
    private static final int SEGMENTS = 40;
    private static final float[] CIRCLE_X = new float[SEGMENTS];
    private static final float[] CIRCLE_Z = new float[SEGMENTS];
    private static final Ring[] CONE = {
            new Ring(0.000F, 0.282F, 0xFFF3FDFF),
            new Ring(0.390F, 0.119F, 0xF4A2DAFA),
            new Ring(0.626F, 0.021F, 0xF252A6DD),
            new Ring(0.675F, 0.000F, 0xFFDAF7FF),
            new Ring(0.675F, -0.015F, 0xE78BCFFF)
    };
    private static final Ring GLOW_INNER = new Ring(0.675F, -0.006F, 0x7848B7FF);
    private static final Ring GLOW_OUTER = new Ring(0.716F, -0.006F, 0x0048B7FF);

    private static boolean enabled;

    static {
        for (int segment = 0; segment < SEGMENTS; segment++) {
            double angle = Math.TAU * segment / SEGMENTS;
            CIRCLE_X[segment] = (float) Math.cos(angle);
            CIRCLE_Z[segment] = (float) Math.sin(angle);
        }
    }

    private ChinaHat() {
    }

    static boolean isEnabled() {
        return enabled;
    }

    static void toggle() {
        enabled = !enabled;
    }

    static void render(WorldRenderContext context) {
        if (!enabled || SaveMode.isPaused()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        var player = client.player;
        if (player == null || client.level == null
                || !player.isAlive() || player.isSpectator() || player.isInvisible() || player.isSleeping()
                || player.isVisuallySwimming() || player.isFallFlying() || player.isAutoSpinAttack()
                || context.consumers() == null) {
            return;
        }

        var camera = client.gameRenderer.getMainCamera();
        if (!camera.isInitialized() || !camera.isDetached() || camera.entity() != player) {
            return;
        }

        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(
                !client.level.tickRateManager().isEntityFrozen(player));
        float entityScale = player.getScale();
        float modelScale = entityScale * 0.9375F;
        float neckHeight = modelScale * 1.501F;
        if (player.isCrouching()) {
            // Match the avatar render offset and the crouching head pivot in the humanoid model.
            neckHeight -= entityScale * 0.125F + modelScale * (4.2F / 16F);
        }

        var cameraPosition = camera.position();
        float headYaw = Mth.rotLerp(partialTick, player.yHeadRotO, player.getYHeadRot());
        float headPitch = player.getViewXRot(partialTick);
        Matrix4f pose = new Matrix4f().translation(
                        (float) (Mth.lerp(partialTick, player.xOld, player.getX()) - cameraPosition.x),
                        (float) (Mth.lerp(partialTick, player.yOld, player.getY()) - cameraPosition.y) + neckHeight,
                        (float) (Mth.lerp(partialTick, player.zOld, player.getZ()) - cameraPosition.z))
                .rotateY(-headYaw * Mth.DEG_TO_RAD)
                .rotateX(headPitch * Mth.DEG_TO_RAD)
                .scale(modelScale)
                .translate(0.0F, 0.575F, 0.0F);

        VertexConsumer vertices = context.consumers().getBuffer(RenderTypes.debugQuads());
        for (int ring = 0; ring < CONE.length - 1; ring++) {
            drawBand(vertices, pose, CONE[ring], CONE[ring + 1]);
        }
        drawBand(vertices, pose, GLOW_INNER, GLOW_OUTER);
    }

    private static void drawBand(VertexConsumer vertices, Matrix4f pose, Ring inner, Ring outer) {
        for (int segment = 0; segment < SEGMENTS; segment++) {
            int next = (segment + 1) % SEGMENTS;
            addVertex(vertices, pose, inner, segment);
            addVertex(vertices, pose, outer, segment);
            addVertex(vertices, pose, outer, next);
            addVertex(vertices, pose, inner, next);
        }
    }

    private static void addVertex(VertexConsumer vertices, Matrix4f pose, Ring ring, int segment) {
        vertices.addVertex(pose, CIRCLE_X[segment] * ring.radius, ring.height, CIRCLE_Z[segment] * ring.radius)
                .setColor(ring.color);
    }

    private record Ring(float radius, float height, int color) {
    }
}
