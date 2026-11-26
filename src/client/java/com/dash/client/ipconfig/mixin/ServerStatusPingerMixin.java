package com.dash.client.ipconfig.mixin;

import com.dash.client.ipconfig.ProxySettings;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerStatusPinger.class)
abstract class ServerStatusPingerMixin {
    @Inject(method = "pingLegacyServer", at = @At("HEAD"), cancellable = true)
    private void dash$blockDirectLegacyPing(CallbackInfo callback) {
        if (ProxySettings.address() != null) {
            callback.cancel();
        }
    }
}
