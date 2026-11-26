package com.dash.client.client.mixin;

import com.dash.client.client.DashTelemetry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class TelemetryTimeMixin {
    @Inject(method = "handleSetTime", at = @At("TAIL"))
    private void dashSampleServerTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
        DashTelemetry.onServerTime(Minecraft.getInstance(), packet.gameTime());
    }
}
