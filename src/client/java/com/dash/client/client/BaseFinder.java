package com.dash.client.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class BaseFinder {
    private static final int RANGE = 64;
    private static final int MAX_MARKERS = 32;
    private static final int SCAN_INTERVAL = 10;
    private static final GizmoStyle CHEST_OUTLINE = GizmoStyle.stroke(0xFFFFBF58, 2.0F);
    private static final GizmoStyle SHULKER_OUTLINE = GizmoStyle.stroke(0xFFC68BFF, 2.0F);
    private static final int CHEST_TRACER = 0xB0FFBF58;
    private static final int SHULKER_TRACER = 0xB0C68BFF;

    private static boolean enabled;
    private static ClientLevel scannedLevel;
    private static int ticksUntilScan;
    private static List<Marker> markers = List.of();

    private BaseFinder() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            reset();
        }
    }

    public static void tick(Minecraft client) {
        if (SaveMode.isPaused()) return;
        if (!enabled || client.level == null || client.player == null || client.options.hideGui) {
            reset();
            return;
        }

        if (scannedLevel != client.level) {
            reset();
            scannedLevel = client.level;
        }
        if (ticksUntilScan > 0 && --ticksUntilScan > 0) {
            return;
        }

        ticksUntilScan = SCAN_INTERVAL;
        markers = scanContainers(scannedLevel, client.player.position());
    }

    public static void render(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (SaveMode.isPaused() || !enabled || level == null || level != scannedLevel || client.player == null
                || client.options.hideGui || markers.isEmpty()) {
            return;
        }

        Camera camera = client.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }
        // Start beyond the near plane and just below the crosshair.
        Vec3 tracerStart = camera.position().add(camera.getNearPlane().getPointOnPlane(0.0F, -0.45F).scale(4.0));
        Vec3 playerPos = client.player.position();
        ClientChunkCache chunks = level.getChunkSource();

        // The main pass collects these, then vanilla draws them over the world in its late pass.
        try (var ignored = client.levelRenderer.collectPerFrameGizmos()) {
            for (Marker marker : markers) {
                // View distance changes can discard a chunk without marking its entities removed.
                if (marker.entity().isRemoved() || marker.pos().distToCenterSqr(playerPos) > RANGE * RANGE
                        || loadedChunk(chunks, marker.chunkX(), marker.chunkZ()) != marker.chunk()) {
                    continue;
                }

                Gizmos.cuboid(marker.bounds(), marker.outline()).setAlwaysOnTop();
                Gizmos.line(tracerStart, marker.center(), marker.tracerColor(), 1.0F).setAlwaysOnTop();
            }
        }
    }

    private static List<Marker> scanContainers(ClientLevel level, Vec3 origin) {
        ClientChunkCache chunks = level.getChunkSource();
        Comparator<BlockPos> farthestFirst = Comparator.comparingDouble((BlockPos pos) -> pos.distToCenterSqr(origin))
                .reversed();
        PriorityQueue<BlockPos> closest = new PriorityQueue<>(MAX_MARKERS, farthestFirst);
        int minX = SectionPos.blockToSectionCoord(origin.x - RANGE);
        int maxX = SectionPos.blockToSectionCoord(origin.x + RANGE);
        int minZ = SectionPos.blockToSectionCoord(origin.z - RANGE);
        int maxZ = SectionPos.blockToSectionCoord(origin.z + RANGE);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                LevelChunk chunk = loadedChunk(chunks, x, z);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (isContainer(blockEntity)) {
                        considerContainer(closest, blockEntity.getBlockPos(), origin);
                    }
                }
            }
        }

        List<Marker> found = new ArrayList<>(closest.size());
        for (BlockPos pos : closest) {
            int chunkX = SectionPos.blockToSectionCoord(pos.getX());
            int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
            LevelChunk chunk = loadedChunk(chunks, chunkX, chunkZ);
            if (chunk == null) {
                continue;
            }
            BlockEntity entity = chunk.getBlockEntities().get(pos);
            if (!isContainer(entity) || !entity.getType().isValid(entity.getBlockState())) {
                continue;
            }

            boolean shulker = entity.getType() == BlockEntityType.SHULKER_BOX;
            AABB bounds = shulker
                    ? new AABB(pos)
                    : entity.getBlockState().getShape(level, pos).bounds().move(pos);
            bounds = bounds.inflate(0.01);
            found.add(new Marker(pos, chunkX, chunkZ, chunk, entity, bounds, bounds.getCenter(),
                    shulker ? SHULKER_OUTLINE : CHEST_OUTLINE, shulker ? SHULKER_TRACER : CHEST_TRACER));
        }
        return List.copyOf(found);
    }

    private static void considerContainer(PriorityQueue<BlockPos> closest, BlockPos pos, Vec3 origin) {
        double distance = pos.distToCenterSqr(origin);
        if (distance > RANGE * RANGE) {
            return;
        }
        if (closest.size() == MAX_MARKERS) {
            if (distance >= closest.element().distToCenterSqr(origin)) {
                return;
            }
            closest.remove();
        }
        closest.add(pos);
    }

    private static LevelChunk loadedChunk(ClientChunkCache chunks, int x, int z) {
        return chunks.getChunk(x, z, ChunkStatus.FULL, false);
    }

    private static boolean isContainer(BlockEntity blockEntity) {
        if (blockEntity == null || blockEntity.isRemoved()) {
            return false;
        }
        BlockEntityType<?> type = blockEntity.getType();
        return type == BlockEntityType.CHEST || type == BlockEntityType.TRAPPED_CHEST || type == BlockEntityType.SHULKER_BOX;
    }

    static void reset() {
        scannedLevel = null;
        ticksUntilScan = 0;
        markers = List.of();
    }

    private record Marker(BlockPos pos, int chunkX, int chunkZ, LevelChunk chunk, BlockEntity entity,
                          AABB bounds, Vec3 center, GizmoStyle outline, int tracerColor) {
    }
}
