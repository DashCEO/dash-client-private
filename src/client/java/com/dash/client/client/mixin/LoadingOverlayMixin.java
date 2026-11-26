package com.dash.client.client.mixin;

import com.dash.client.client.DashIntroVideo;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LoadingOverlay.class)
public class LoadingOverlayMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void dash$renderIntro(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (DashIntroVideo.render(graphics)) {
            ci.cancel();
        }
    }
}
