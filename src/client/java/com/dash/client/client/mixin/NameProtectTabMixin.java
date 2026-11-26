package com.dash.client.client.mixin;

import com.dash.client.client.NameProtect;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(PlayerTabOverlay.class)
abstract class NameProtectTabMixin {
    @ModifyArg(method = "getNameForDisplay", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/scores/PlayerTeam;formatNameForTeam(Lnet/minecraft/world/scores/Team;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/MutableComponent;"), index = 1)
    private Component dash$maskTabProfileName(Component name) {
        return NameProtect.mask(name);
    }

    @ModifyExpressionValue(method = "render", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/components/PlayerTabOverlay;getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;"))
    private Component dash$maskTabName(Component name) {
        return NameProtect.mask(name);
    }

    @ModifyArg(method = "render", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/Font;split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;"), index = 0, require = 2)
    private FormattedText dash$maskTabHeaderAndFooter(FormattedText text) {
        return text instanceof Component component ? NameProtect.mask(component) : text;
    }

    @ModifyExpressionValue(method = "render", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/scores/ReadOnlyScoreInfo;safeFormatValue(Lnet/minecraft/world/scores/ReadOnlyScoreInfo;Lnet/minecraft/network/chat/numbers/NumberFormat;)Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent dash$maskTabScore(MutableComponent score) {
        return NameProtect.mask(score);
    }
}
