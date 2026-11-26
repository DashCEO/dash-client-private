package com.dash.client.ipconfig.mixin;

import com.dash.client.ipconfig.IpConfigScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JoinMultiplayerScreen.class)
abstract class JoinMultiplayerScreenMixin extends Screen {
    @Unique
    private Button dash$ipConfigButton;

    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init()V", at = @At("TAIL"))
    private void dash$addIpConfig(CallbackInfo callback) {
        if (dash$ipConfigButton == null || !children().contains(dash$ipConfigButton)) {
            dash$ipConfigButton = addRenderableWidget(Button.builder(Component.literal("IP Config"),
                    button -> minecraft.setScreen(new IpConfigScreen(this)))
                    .bounds(Math.max(6, width - 78), 6, 72, 20).build());
        }
    }

    @Inject(method = "repositionElements()V", at = @At("TAIL"))
    private void dash$repositionIpConfig(CallbackInfo callback) {
        if (dash$ipConfigButton != null) {
            dash$ipConfigButton.setX(Math.max(6, width - 78));
        }
    }
}
