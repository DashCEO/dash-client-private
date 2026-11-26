package com.dash.client.client.mixin;

import com.dash.client.client.NameProtect;
import com.dash.client.client.Friends;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(SubmitNodeCollection.class)
abstract class NameProtectNametagMixin {
    @ModifyVariable(method = "submitNameTag", at = @At("HEAD"), argsOnly = true)
    private Component dash$maskNametag(Component name) {
        return name == Friends.nameTag() ? name : NameProtect.mask(name);
    }
}
