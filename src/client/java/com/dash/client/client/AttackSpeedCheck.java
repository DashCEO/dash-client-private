package com.dash.client.client;

import com.mojang.logging.LogUtils;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

public final class AttackSpeedCheck {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static LocalPlayer sampledPlayer;
    private static double previousSpeed;
    private static double previousBase;
    private static ItemStack previousMainHand = ItemStack.EMPTY;
    private static ItemStack previousOffHand = ItemStack.EMPTY;

    private AttackSpeedCheck() {}

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> sample(client, false));
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            if (SaveMode.isPaused() || !command.strip().equalsIgnoreCase("dashspeed")) return true;
            show(Minecraft.getInstance());
            return false;
        });
    }

    public static void onServerAttributes(Minecraft client, ClientboundUpdateAttributesPacket packet, boolean applied) {
        if (SaveMode.isPaused() || !client.isSameThread() || client.player == null
                || packet.getEntityId() != client.player.getId()) return;
        for (var snapshot : packet.getValues()) {
            if (snapshot.attribute().equals(Attributes.ATTACK_SPEED)) {
                sample(client, applied);
                return;
            }
        }
    }

    private static void sample(Minecraft client, boolean serverUpdate) {
        if (SaveMode.isPaused()) return;
        var player = client.player;
        var attribute = player == null ? null : player.getAttribute(Attributes.ATTACK_SPEED);
        if (attribute == null) {
            reset();
            return;
        }
        double speed = attribute.getValue();
        double base = attribute.getBaseValue();
        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        if (sampledPlayer == player && Math.round((previousSpeed - speed) * 100.0) >= 10) {
            LOGGER.info("[Dash Attack Speed] before={} after={} source={} base_before={} base_after={}"
                            + " main_hand={} off_hand={} main_hand_before={} off_hand_before={}"
                            + " main_name_before=\"{}\" main_name=\"{}\" off_name_before=\"{}\" off_name=\"{}\"",
                    format(previousSpeed), format(speed), serverUpdate ? "server_attribute_update" : "client_observation",
                    format(previousBase), format(base), itemId(mainHand), itemId(offHand),
                    itemId(previousMainHand), itemId(previousOffHand),
                    logName(previousMainHand), logName(mainHand), logName(previousOffHand), logName(offHand));
        }
        sampledPlayer = player;
        previousSpeed = speed;
        previousBase = base;
        previousMainHand = mainHand;
        previousOffHand = offHand;
    }

    static void reset() {
        sampledPlayer = null;
        previousSpeed = 0;
        previousBase = 0;
        previousMainHand = ItemStack.EMPTY;
        previousOffHand = ItemStack.EMPTY;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String logName(ItemStack stack) {
        return heldItem(stack).replace('\r', ' ').replace('\n', ' ').replace('"', '\'');
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static void show(Minecraft client) {
        var player = client.player;
        if (player == null) {
            message(client, "Відкрий світ, щоб перевірити швидкість атаки.");
            return;
        }

        double speed = player.getAttributeValue(Attributes.ATTACK_SPEED);
        float seconds = player.getCurrentItemAttackStrengthDelay() / 20.0F;
        String recharge = Float.isFinite(seconds)
                ? String.format(Locale.ROOT, "повний заряд %.2f с за 20 TPS", seconds)
                : "повний заряд недоступний";
        message(client, String.format(Locale.ROOT, "Швидкість атаки %.2f/с · %s", speed, recharge));
        message(client, "Основна рука · " + heldItem(player.getMainHandItem())
                + "  |  Друга рука · " + heldItem(player.getOffhandItem()));
    }

    private static String heldItem(ItemStack stack) {
        return stack.isEmpty() ? "порожньо" : stack.getHoverName().getString();
    }

    private static void message(Minecraft client, String text) {
        client.gui.getChat().addMessage(Component.literal("[Dash] " + text));
    }
}
