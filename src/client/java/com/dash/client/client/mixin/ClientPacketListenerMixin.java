package com.dash.client.client.mixin;

import com.dash.client.client.AttackSpeedCheck;
import com.dash.client.client.DashClientClient;
import com.dash.client.client.AutoBuy;
import com.dash.client.client.AutoSell;
import com.dash.client.client.AutoSwap;
import com.dash.client.client.ManualHitFeedback;
import com.dash.client.client.SwapBind;
import com.dash.client.client.Velocity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void dashAuctionContents(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        AutoBuy.onContainerUpdate(packet.containerId());
        AutoSell.onAuctionContents(packet.containerId());
        SwapBind.onInventoryMenuSync(Minecraft.getInstance(), packet.containerId(), -1);
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void dashAuctionSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        AutoBuy.onContainerUpdate(packet.getContainerId());
        AutoSell.onAuctionContents(packet.getContainerId());
        SwapBind.onInventoryMenuSync(Minecraft.getInstance(), packet.getContainerId(), packet.getSlot());
    }

    @Inject(method = "handleSetPlayerInventory", at = @At("TAIL"))
    private void dashObservePlayerInventorySync(ClientboundSetPlayerInventoryPacket packet, CallbackInfo ci) {
        SwapBind.onPlayerInventorySync(Minecraft.getInstance(), packet.slot());
    }

    @Inject(method = "handleDamageEvent", at = @At("TAIL"))
    private void dashOnDamage(ClientboundDamageEventPacket packet, CallbackInfo ci) {
        ManualHitFeedback.onDamage(Minecraft.getInstance(), packet);
        Velocity.onDamage(packet);
        AutoSwap.onDamage(packet);
        DashClientClient.onDamageEvent(packet);
    }

    @Inject(method = "handleAnimate", at = @At("TAIL"))
    private void dashObserveCritSignal(ClientboundAnimatePacket packet, CallbackInfo ci) {
        ManualHitFeedback.onAnimate(Minecraft.getInstance(), packet);
    }

    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    private void dashObserveHealth(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        ManualHitFeedback.onEntityData(Minecraft.getInstance(), packet);
    }

    @Inject(method = "handleUpdateAttributes", at = @At("HEAD"))
    private void dashObserveAttackSpeedBeforeUpdate(ClientboundUpdateAttributesPacket packet, CallbackInfo ci) {
        AttackSpeedCheck.onServerAttributes(Minecraft.getInstance(), packet, false);
    }

    @Inject(method = "handleUpdateAttributes", at = @At("TAIL"))
    private void dashObserveAttackSpeedAfterUpdate(ClientboundUpdateAttributesPacket packet, CallbackInfo ci) {
        AttackSpeedCheck.onServerAttributes(Minecraft.getInstance(), packet, true);
    }

    @Redirect(method = "handleSetEntityMotion", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;lerpMotion(Lnet/minecraft/world/phys/Vec3;)V"))
    private void dashScaleKnockback(Entity entity, Vec3 received) {
        entity.lerpMotion(Velocity.adjustMotion(entity, received));
    }

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;setValuesFromPositionPacket(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;Lnet/minecraft/world/entity/Entity;Z)Z"))
    private void dashObservePositionCorrection(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        Velocity.onPositionCorrection(packet);
    }

}
