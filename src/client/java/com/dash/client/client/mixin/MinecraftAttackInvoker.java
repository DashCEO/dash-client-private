package com.dash.client.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftAttackInvoker {
    @Invoker("startAttack")
    boolean dash$startAttack();
}
