package com.dash.client.client.mixin;

import com.dash.client.client.NameProtect;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Gui.class)
abstract class NameProtectSidebarMixin {
    @ModifyExpressionValue(method = "displayScoreboardSidebar", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/scores/Objective;getDisplayName()Lnet/minecraft/network/chat/Component;"))
    private Component dash$maskSidebarTitle(Component title) {
        return NameProtect.mask(title);
    }

    // This method constructs the sidebar's render-only entries before measuring them.
    @ModifyArg(method = "method_55439", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/scores/PlayerTeam;formatNameForTeam(Lnet/minecraft/world/scores/Team;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/MutableComponent;"), index = 1)
    private Component dash$maskSidebarOwner(Component owner) {
        return NameProtect.mask(owner);
    }

    @ModifyExpressionValue(method = "method_55439", at = {
            @At(value = "INVOKE", target =
                    "Lnet/minecraft/world/scores/PlayerTeam;formatNameForTeam(Lnet/minecraft/world/scores/Team;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/MutableComponent;"),
            @At(value = "INVOKE", target =
                    "Lnet/minecraft/world/scores/PlayerScoreEntry;formatValue(Lnet/minecraft/network/chat/numbers/NumberFormat;)Lnet/minecraft/network/chat/MutableComponent;")
    }, require = 2)
    private MutableComponent dash$maskSidebarEntry(MutableComponent text) {
        return NameProtect.mask(text);
    }
}
