package com.dash.client.client.mixin;

import com.dash.client.client.AutoBuy;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public abstract class AutoBuyInventoryClickMixin {
    @Inject(method = "handleInventoryMouseClick", at = @At("HEAD"))
    private void dashTrackManualConfirmation(int containerId, int slot, int button, ClickType clickType,
                                             Player player, CallbackInfo callback) {
        AutoBuy.onInventoryClick(containerId, slot, button, clickType);
    }
}
