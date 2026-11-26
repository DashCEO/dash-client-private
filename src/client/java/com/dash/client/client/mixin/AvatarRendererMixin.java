package com.dash.client.client.mixin;

import com.dash.client.client.DashClientClient;
import com.dash.client.client.Friends;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public class AvatarRendererMixin {

    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL")
    )
    private void dashCustomizePlayer(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        if (!(avatar instanceof Player player) || player == Minecraft.getInstance().player) {
            return;
        }
        if (state.nameTag != null && Friends.isFriend(player.getGameProfile().name())) {
            state.nameTag = Friends.nameTag();
        }
        if (DashClientClient.isUnInvisibilityEnabled() && state.isInvisible && !state.isSpectator) {
            state.isInvisibleToPlayer = false;
            if (state.outlineColor == AvatarRenderState.NO_OUTLINE) {
                state.outlineColor = 0xFFF2F2F2;
            }
        }
    }
}
