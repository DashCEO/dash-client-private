package com.dash.client.client;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;

public final class AutoChunkReload {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long CHECK_INTERVAL_MS = 1_000;
    private static final long STALL_MS = 3_000;
    private static final long RETRY_INTERVAL_MS = 12_000;
    private static final int NEARBY_RADIUS = 1;
    private static final int VERTICAL_RADIUS = 2;
    private static final int MAX_REBUILDS = 4;

    private static final Map<SectionPos, Long> retryAfter = new HashMap<>();
    private static VulkanSections vulkan;
    private static boolean lookedUpVulkan;
    private static ClientLevel level;
    private static Object lastGrid;
    private static SectionPos lastCenter;
    private static long settledSince;
    private static long nextCheck;
    private static long loadingSince;
    private static boolean loggedLoadingDelay;

    private AutoChunkReload() {}

    static void reset() {
        level = null;
        lastGrid = null;
        lastCenter = null;
        retryAfter.clear();
        settledSince = 0;
        nextCheck = 0;
        loadingSince = 0;
        loggedLoadingDelay = false;
    }

    static void tick(Minecraft client) {
        if (SaveMode.isPaused()) return;
        boolean loading = client.screen instanceof LevelLoadingScreen;
        if (level != client.level) {
            reset();
            level = client.level;
        }
        if (!loading) {
            loadingSince = 0;
            loggedLoadingDelay = false;
        }
        if (!active(client) || (!loading && client.screen != null) || client.getOverlay() != null
                || client.isPaused() || !client.isWindowActive()) {
            settledSince = 0;
            return;
        }

        long now = Util.getMillis();
        if (now < nextCheck) return;
        nextCheck = now + CHECK_INTERVAL_MS;
        if (loading) logLoadingDelay(client, now);

        VulkanSections access = vulkanSections();
        if (access == null || client.getFps() < 35 || !client.levelRenderer.hasRenderedAllSections()) {
            settledSince = 0;
            return;
        }

        try {
            Object renderer = access.getRenderer().invoke(null);
            if (renderer == null) return;
            Object grid = access.getGrid().invoke(renderer);
            if (grid == null) return;
            SectionPos center = SectionPos.of(client.player.blockPosition());
            if (lastGrid != grid) {
                lastGrid = grid;
                retryAfter.clear();
                settledSince = 0;
            }
            if (settledSince == 0 || !center.equals(lastCenter)) {
                lastCenter = center;
                settledSince = now;
                return;
            }
            if (now - settledSince < STALL_MS) return;

            retryAfter.values().removeIf(time -> time <= now);
            List<SectionPos> sections = pendingSections(access, grid, center);
            if (sections.isEmpty()) return;

            // The renderer's cache can still hold empty boundary columns from a previous build.
            access.clearRegionCache().invoke(access.regionCache().get(renderer));
            for (SectionPos position : sections) {
                client.levelRenderer.setSectionDirty(position.x(), position.y(), position.z());
                retryAfter.put(position, now + RETRY_INTERVAL_MS);
            }
            LOGGER.debug("Queued {} nearby Vulkan terrain sections for rebuild", sections.size());
        } catch (ReflectiveOperationException | LinkageError exception) {
            vulkan = null;
            LOGGER.debug("Auto Chunk Reload could not read VulkanMod section state", exception);
        }
    }

    private static List<SectionPos> pendingSections(VulkanSections access, Object grid, SectionPos center)
            throws ReflectiveOperationException {
        List<SectionPos> sections = new ArrayList<>(MAX_REBUILDS);
        addPendingSection(sections, access, grid, center, true);
        for (int layer = 0; layer <= VERTICAL_RADIUS * 2; layer++) {
            int dy = (layer + 1) / 2 * (layer % 2 == 0 ? 1 : -1);
            for (int dx = -NEARBY_RADIUS; dx <= NEARBY_RADIUS; dx++) {
                for (int dz = -NEARBY_RADIUS; dz <= NEARBY_RADIUS; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    addPendingSection(sections, access, grid, center.offset(dx, dy, dz), false);
                    if (sections.size() == MAX_REBUILDS) return sections;
                }
            }
        }
        return sections;
    }

    private static void addPendingSection(List<SectionPos> sections, VulkanSections access, Object grid,
                                          SectionPos position, boolean includeAir)
            throws ReflectiveOperationException {
        BlockPos origin = position.origin();
        if (level.isOutsideBuildHeight(origin) || retryAfter.containsKey(position)) return;
        LevelChunk chunk = litChunk(level, position.x(), position.z());
        if (chunk == null || (!includeAir && chunk.getSection(level.getSectionIndex(origin.getY())).hasOnlyAir())) {
            return;
        }

        Object section = access.getSection().invoke(grid, origin.getX(), origin.getY(), origin.getZ());
        if (section == null || (int) access.sectionX().invoke(section) != origin.getX()
                || (int) access.sectionY().invoke(section) != origin.getY()
                || (int) access.sectionZ().invoke(section) != origin.getZ()
                || (boolean) access.isCompiled().invoke(section) || (boolean) access.isDirty().invoke(section)) {
            return;
        }
        sections.add(position);
    }

    public static boolean canBuildNearbySection(int blockX, int blockY, int blockZ) {
        Minecraft client = Minecraft.getInstance();
        if (!active(client)) return false;
        int chunkX = SectionPos.blockToSectionCoord(blockX);
        int chunkZ = SectionPos.blockToSectionCoord(blockZ);
        var center = client.player.chunkPosition();
        int playerSectionY = SectionPos.blockToSectionCoord(client.player.getBlockY());
        if (Math.abs(chunkX - center.x) > NEARBY_RADIUS || Math.abs(chunkZ - center.z) > NEARBY_RADIUS
                || Math.abs(SectionPos.blockToSectionCoord(blockY) - playerSectionY) > VERTICAL_RADIUS) {
            return false;
        }
        return litChunk(client.level, chunkX, chunkZ) != null;
    }

    private static boolean active(Minecraft client) {
        return HudSettings.autoChunkReload() && client.level != null && client.player != null
                && !SaveMode.isPaused() && !XRay.isRendering() && !client.player.isSpectator();
    }

    private static LevelChunk litChunk(ClientLevel world, int x, int z) {
        var chunks = world.getChunkSource();
        LevelChunk chunk = chunks.getChunk(x, z, ChunkStatus.FULL, false);
        return chunk != null && chunks.getLightEngine().lightOnInColumn(SectionPos.getZeroNode(x, z)) ? chunk : null;
    }

    private static void logLoadingDelay(Minecraft client, long now) {
        if (loadingSince == 0) loadingSince = now;
        if (loggedLoadingDelay || now - loadingSince < 10_000) return;
        loggedLoadingDelay = true;
        BlockPos spawn = client.player.blockPosition();
        var center = client.player.chunkPosition();
        var chunks = level.getChunkSource();
        int cached = 0;
        for (int dx = -NEARBY_RADIUS; dx <= NEARBY_RADIUS; dx++) {
            for (int dz = -NEARBY_RADIUS; dz <= NEARBY_RADIUS; dz++) {
                if (chunks.hasChunk(center.x + dx, center.z + dz)) cached++;
            }
        }
        boolean spawnCached = chunks.hasChunk(center.x, center.z);
        LOGGER.info("Terrain loading still waiting: nearby cached chunks {}/9, spawn cached={}, spawn light ready={}, spawn section compiled={}",
                cached, spawnCached, litChunk(level, center.x, center.z) != null,
                spawnCached && !level.isOutsideBuildHeight(spawn)
                        && client.levelRenderer.isSectionCompiledAndVisible(spawn));
    }

    private static VulkanSections vulkanSections() {
        if (!lookedUpVulkan) {
            lookedUpVulkan = true;
            if (FabricLoader.getInstance().isModLoaded("vulkanmod")) {
                try {
                    Class<?> renderer = Class.forName("net.vulkanmod.render.chunk.WorldRenderer");
                    Class<?> grid = Class.forName("net.vulkanmod.render.chunk.SectionGrid");
                    Class<?> section = Class.forName("net.vulkanmod.render.chunk.RenderSection");
                    Field regionCache = renderer.getField("renderRegionCache");
                    vulkan = new VulkanSections(renderer.getMethod("getInstance"), renderer.getMethod("getSectionGrid"),
                            grid.getMethod("getSectionAtBlockPos", int.class, int.class, int.class),
                            section.getMethod("xOffset"), section.getMethod("yOffset"), section.getMethod("zOffset"),
                            section.getMethod("isCompiled"), section.getMethod("isDirty"), regionCache,
                            regionCache.getType().getMethod("clear"));
                } catch (ReflectiveOperationException | LinkageError exception) {
                    LOGGER.debug("Auto Chunk Reload is unavailable with this VulkanMod build", exception);
                }
            }
        }
        return vulkan;
    }

    private record VulkanSections(Method getRenderer, Method getGrid, Method getSection, Method sectionX,
                                  Method sectionY, Method sectionZ, Method isCompiled, Method isDirty,
                                  Field regionCache, Method clearRegionCache) {}
}
