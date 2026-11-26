package com.dash.client.client;

import net.minecraft.client.Minecraft;

final class TerrainVisuals {
    // Chunk workers read this snapshot. Change both flags before invalidating meshes.
    private static volatile TerrainState state = new TerrainState(false, false);

    private TerrainVisuals() {}

    static void refresh(Minecraft client) {
        boolean xray = client.level != null && !SaveMode.isPaused() && XRay.isEnabled();
        boolean grassTint = CosmicSky.isActive(client.level);
        TerrainState previous = state;
        if (previous.xray() == xray && previous.grassTint() == grassTint) return;

        state = new TerrainState(xray, grassTint);
        if (client.level != null) client.levelRenderer.allChanged();
    }

    static boolean isXRayRendering() { return !SaveMode.isPaused() && state.xray(); }
    static boolean isGrassTintActive() { return !SaveMode.isPaused() && state.grassTint(); }

    private record TerrainState(boolean xray, boolean grassTint) {}
}
