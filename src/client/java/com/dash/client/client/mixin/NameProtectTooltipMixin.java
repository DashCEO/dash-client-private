package com.dash.client.client.mixin;

import com.dash.client.client.NameProtect;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GuiGraphics.class)
abstract class NameProtectTooltipMixin {
    @ModifyExpressionValue(method = "renderComponentHoverEffect", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/network/chat/HoverEvent$ShowText;value()Lnet/minecraft/network/chat/Component;"))
    private Component dash$maskHoverText(Component text) {
        return dash$shouldMaskChatTooltip() ? NameProtect.mask(text) : text;
    }

    @ModifyExpressionValue(method = "renderComponentHoverEffect", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/network/chat/HoverEvent$EntityTooltipInfo;getTooltipLines()Ljava/util/List;"))
    private List<Component> dash$maskHoverEntity(List<Component> lines) {
        return dash$shouldMaskChatTooltip() ? lines.stream().map(NameProtect::mask).toList() : lines;
    }

    @Unique
    private static boolean dash$shouldMaskChatTooltip() {
        return NameProtect.isEnabled() && Minecraft.getInstance().screen instanceof ChatScreen;
    }
}
