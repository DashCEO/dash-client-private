package com.dash.client.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import static java.util.Map.entry;

final class OreHighlight {
    private static final int RANGE = 40;
    private static final int RAY_HALF_COLUMNS = 24;
    private static final int RAY_HALF_ROWS = 16;
    private static final int RAY_COLUMNS = RAY_HALF_COLUMNS * 2 + 1;
    private static final int GRID_SIZE = RAY_COLUMNS * (RAY_HALF_ROWS * 2 + 1);
    // Coprime with the grid size, so batches spread across the view and visit every point.
    private static final int GRID_STRIDE = 53;
    private static final int GRID_RAYS_PER_TICK = 41;
    private static final int FACE_AGE_TICKS = (GRID_SIZE + GRID_RAYS_PER_TICK - 1) / GRID_RAYS_PER_TICK + 1;
    private static final int MAX_FACES = 96;
    private static final float EDGE = .48F;
    private static final float INNER_EDGE = .425F;
    private static final Quaternionf[] FACE_ROTATIONS = new Quaternionf[Direction.values().length];
    private static final Map<Block, Integer> COLORS = Map.ofEntries(
            entry(Blocks.DIAMOND_ORE, 0x72EDFF), entry(Blocks.DEEPSLATE_DIAMOND_ORE, 0x72EDFF),
            entry(Blocks.EMERALD_ORE, 0x65F2AA), entry(Blocks.DEEPSLATE_EMERALD_ORE, 0x65F2AA),
            entry(Blocks.GOLD_ORE, 0xFFD270), entry(Blocks.DEEPSLATE_GOLD_ORE, 0xFFD270),
            entry(Blocks.NETHER_GOLD_ORE, 0xFFD270),
            entry(Blocks.IRON_ORE, 0xFFC6A4), entry(Blocks.DEEPSLATE_IRON_ORE, 0xFFC6A4),
            entry(Blocks.COPPER_ORE, 0xFFA06E), entry(Blocks.DEEPSLATE_COPPER_ORE, 0xFFA06E),
            entry(Blocks.LAPIS_ORE, 0x8198FF), entry(Blocks.DEEPSLATE_LAPIS_ORE, 0x8198FF),
            entry(Blocks.REDSTONE_ORE, 0xFF727E), entry(Blocks.DEEPSLATE_REDSTONE_ORE, 0xFF727E),
            entry(Blocks.COAL_ORE, 0xC4C8DC), entry(Blocks.DEEPSLATE_COAL_ORE, 0xC4C8DC),
            entry(Blocks.NETHER_QUARTZ_ORE, 0xFFF4DD), entry(Blocks.ANCIENT_DEBRIS, 0xF2AD9B)
    );

    static {
        for (Direction direction : Direction.values()) {
            FACE_ROTATIONS[direction.ordinal()] = direction.getRotation();
        }
    }

    private static boolean enabled;
    private static ClientLevel scannedLevel;
    private static int nextRay;
    private static long scanTick;
    private static final LinkedHashMap<Face, Long> lastSeen = new LinkedHashMap<>(MAX_FACES, .75F, true);
    private static List<Face> faces = List.of();

    private OreHighlight() {}

    static boolean isEnabled() {
        return enabled;
    }

    static boolean isOre(BlockState state) {
        return COLORS.containsKey(state.getBlock());
    }

    static void toggle() {
        enabled = !enabled;
        reset();
    }

    static void tick(Minecraft client) {
        if (SaveMode.isPaused()) return;
        Camera camera = client.gameRenderer.getMainCamera();
        if (!canShow(client, camera)) {
            reset();
            return;
        }
        if (scannedLevel != client.level) {
            reset();
            scannedLevel = client.level;
        }

        scanTick++;
        sampleVisibleFaces(scannedLevel, camera);
        lastSeen.values().removeIf(seenAt -> scanTick - seenAt >= FACE_AGE_TICKS);
        faces = List.copyOf(lastSeen.keySet());
    }

    private static void sampleVisibleFaces(ClientLevel level, Camera camera) {
        Vec3 origin = camera.position();
        var plane = camera.getNearPlane();
        var collision = CollisionContext.of(camera.entity());
        sampleRay(level, origin, plane.getPointOnPlane(0, 0), collision);
        for (int i = 0; i < GRID_RAYS_PER_TICK; i++) {
            int x = nextRay % RAY_COLUMNS - RAY_HALF_COLUMNS;
            int y = nextRay / RAY_COLUMNS - RAY_HALF_ROWS;
            nextRay = (nextRay + GRID_STRIDE) % GRID_SIZE;
            Vec3 ray = plane.getPointOnPlane(x / (float) RAY_HALF_COLUMNS, y / (float) RAY_HALF_ROWS);
            sampleRay(level, origin, ray, collision);
        }
    }

    private static void sampleRay(ClientLevel level, Vec3 origin, Vec3 ray, CollisionContext collision) {
        Vec3 end = origin.add(ray.scale(RANGE / ray.length()));
        var hit = level.clip(new ClipContext(origin, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, collision));
        if (hit.getType() != HitResult.Type.BLOCK || hit.isInside()) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        Direction direction = hit.getDirection();
        BlockPos neighbor = pos.relative(direction);
        Integer color = COLORS.get(level.getBlockState(pos).getBlock());
        if (color == null || !level.hasChunkAt(pos) || !level.hasChunkAt(neighbor)
                || !level.getBlockState(neighbor).isAir()) {
            return;
        }

        lastSeen.put(new Face(pos.immutable(), direction, color), scanTick);
        if (lastSeen.size() > MAX_FACES) {
            lastSeen.pollFirstEntry();
        }
    }

    static void render(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        Camera camera = client.gameRenderer.getMainCamera();
        if (faces.isEmpty() || !canShow(client, camera) || client.level != scannedLevel || context.consumers() == null) {
            return;
        }

        Vec3 origin = camera.position();
        Matrix4f pose = new Matrix4f();
        VertexConsumer out = context.consumers().getBuffer(RenderTypes.debugQuads());
        for (Face face : faces) {
            BlockPos pos = face.pos();
            Direction direction = face.direction();
            Integer currentColor = COLORS.get(scannedLevel.getBlockState(pos).getBlock());
            if (currentColor == null || currentColor != face.color()
                    || !scannedLevel.getBlockState(pos.relative(direction)).isAir()) {
                continue;
            }
            double x = pos.getX() + .5 - origin.x + .501 * direction.getStepX();
            double y = pos.getY() + .5 - origin.y + .501 * direction.getStepY();
            double z = pos.getZ() + .5 - origin.z + .501 * direction.getStepZ();
            if (x * x + y * y + z * z > RANGE * RANGE
                    || x * direction.getStepX() + y * direction.getStepY() + z * direction.getStepZ() >= 0) {
                continue;
            }
            pose.translation((float) x, (float) y, (float) z)
                    .rotate(FACE_ROTATIONS[direction.ordinal()]);
            drawQuad(out, pose, -EDGE, -EDGE, EDGE, EDGE, 0x40000000 | face.color());
            int border = 0xD0000000 | face.color();
            drawQuad(out, pose, -EDGE, -EDGE, EDGE, -INNER_EDGE, border);
            drawQuad(out, pose, -EDGE, INNER_EDGE, EDGE, EDGE, border);
            drawQuad(out, pose, -EDGE, -INNER_EDGE, -INNER_EDGE, INNER_EDGE, border);
            drawQuad(out, pose, INNER_EDGE, -INNER_EDGE, EDGE, INNER_EDGE, border);
        }
    }

    private static void drawQuad(VertexConsumer out, Matrix4f pose, float x0, float z0, float x1, float z1, int color) {
        out.addVertex(pose, x0, 0, z0).setColor(color);
        out.addVertex(pose, x0, 0, z1).setColor(color);
        out.addVertex(pose, x1, 0, z1).setColor(color);
        out.addVertex(pose, x1, 0, z0).setColor(color);
    }

    private static boolean canShow(Minecraft client, Camera camera) {
        return enabled && !XRay.isRendering() && !SaveMode.isPaused() && !client.options.hideGui && client.level != null
                && client.player != null && !client.player.isSpectator()
                && camera.isInitialized() && camera.entity() == client.player;
    }

    static void reset() {
        scannedLevel = null;
        nextRay = 0;
        scanTick = 0;
        lastSeen.clear();
        faces = List.of();
    }

    private record Face(BlockPos pos, Direction direction, int color) {}
}
