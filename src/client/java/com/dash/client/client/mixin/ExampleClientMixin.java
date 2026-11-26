package com.dash.client.client.mixin;

import com.dash.client.client.DashIntroVideo;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.Music;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class ExampleClientMixin {
    @Inject(method = "getSituationalMusic", at = @At("HEAD"), cancellable = true)
    private void dash$muteMenuMusicDuringStartup(CallbackInfoReturnable<Music> cir) {
        if (DashIntroVideo.isPlayingStartup()) {
            cir.setReturnValue(null);
        }
    }

	@Inject(at = @At("HEAD"), method = "run")
	private void init(CallbackInfo info) {
		// This code is injected into the start of Minecraft.run()V
	}
}