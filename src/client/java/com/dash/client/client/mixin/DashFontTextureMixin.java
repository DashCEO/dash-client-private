package com.dash.client.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.util.function.Supplier;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FontTexture.class)
public abstract class DashFontTextureMixin extends AbstractTexture {
    @Inject(method = "<init>(Ljava/util/function/Supplier;Lnet/minecraft/client/gui/font/GlyphRenderTypes;Z)V",
            at = @At("RETURN"))
    private void dash$interpolateUiGlyphs(Supplier<String> textureLabel, GlyphRenderTypes renderTypes,
                                         boolean colored, CallbackInfo callback) {
        // GlyphStitcher labels each atlas page as the font resource identifier plus "/<page>".
        if (textureLabel.get().startsWith("dash-client:ui/")) {
            sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
        }
    }
}
