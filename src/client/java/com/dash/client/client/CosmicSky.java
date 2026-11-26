package com.dash.client.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.SkyRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.dimension.DimensionType;

public final class CosmicSky {
    private static final int SKY_COLOR = 0xFF160A2D;
    private static final int HORIZON_COLOR = 0xFF7938AA;
    private static final int GLOW_COLOR = 0x00EF83FF;
    private static final int GRASS_SHADE = 0xFF26243D;

    private CosmicSky() {}

    public static boolean isEnabled() {
        return HudSettings.cosmicSky();
    }

    public static void toggle() {
        HudSettings.toggleCosmicSky();
    }

    public static boolean isActive(ClientLevel level) {
        return isEnabled() && !SaveMode.isPaused() && level != null
                && level.dimensionType().skybox() == DimensionType.Skybox.OVERWORLD;
    }

    public static void apply(ClientLevel level, SkyRenderState state) {
        if (!isActive(level)) {
            return;
        }

        // Reuse the cached sky, horizon and star buffers in both vanilla and VulkanMod.
        state.skyColor = SKY_COLOR;
        state.starBrightness = 0.88F * state.rainBrightness;
        int glowAlpha = Math.round(185.0F * state.rainBrightness);
        state.sunriseAndSunsetColor = glowAlpha << 24 | GLOW_COLOR;
    }

    public static int horizonColor() {
        return HORIZON_COLOR;
    }

    public static int grassColor(int original) {
        if (!TerrainVisuals.isGrassTintActive()) {
            return original;
        }
        return ARGB.srgbLerp(0.55F, original | 0xFF000000, GRASS_SHADE) & 0xFFFFFF;
    }

}
