package com.dash.client.client.mixin;

import com.dash.client.client.Velocity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Redirect(method = "bobHurt", at = @At(value = "INVOKE",
            target = "Ljava/lang/Double;doubleValue()D", remap = false))
    private double dashDamageTilt(Double vanillaTilt) {
        Minecraft client = Minecraft.getInstance();
        return Velocity.isActive() && client.player != null && client.getCameraEntity() == client.player
                ? 0.0 : vanillaTilt.doubleValue();
    }
}
