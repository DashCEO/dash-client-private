package com.dash.client.client.mixin;

import com.dash.client.client.DashClientClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityMixin {

    // Player ESP (примусове світіння)
    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
    private void dashForceGlow(CallbackInfoReturnable<Boolean> cir) {
        if (!DashClientClient.isPlayerEspEnabled()) return;

        Entity self = (Entity) (Object) this;
        if (self instanceof Player player && player != Minecraft.getInstance().player) {
            cir.setReturnValue(true); // Примусово світиться білим
        }
    }
}
