package com.dash.client.client.mixin;

import com.dash.client.client.NameProtect;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ChatComponent.class)
abstract class NameProtectChatMixin {
    @ModifyVariable(method = "addMessageToDisplayQueue", at = @At("HEAD"), argsOnly = true)
    private GuiMessage dash$maskChat(GuiMessage message) {
        Component masked = NameProtect.mask(message.content());
        return masked == message.content()
                ? message
                : new GuiMessage(message.addedTime(), masked, message.signature(), message.tag());
    }
}
