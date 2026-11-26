package com.dash.client.client.mixin;

import com.dash.client.client.DashClientClient;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class PlayerEspMixin {

    @Inject(
            method = "isCurrentlyGlowing()Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void dashPlayerEsp(CallbackInfoReturnable<Boolean> cir) {
        Entity entity = (Entity) (Object) this;

        if (entity instanceof Player player
                && DashClientClient.shouldGlowPlayer(player)) {
            cir.setReturnValue(true);
        }
    }
}