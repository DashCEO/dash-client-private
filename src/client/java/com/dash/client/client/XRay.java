package com.dash.client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class XRay {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static boolean enabled;

    private XRay() {}

    public static boolean isEnabled() {
        return enabled;
    }

    static void toggle() {
        enabled = !enabled;
        TerrainVisuals.refresh(Minecraft.getInstance());
    }

    public static boolean isRendering() {
        return TerrainVisuals.isXRayRendering();
    }

    public static BlockState terrainState(BlockState state) {
        return state.isAir() || OreHighlight.isOre(state) ? state : AIR;
    }
}
