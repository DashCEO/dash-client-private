package com.dash.client.client;

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

final class PlayerGps {
    private static final String COMMAND = "gps";

    private PlayerGps() {}

    static void register() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(PlayerGps::allowCommand);
    }

    private static boolean allowCommand(String command) {
        if (SaveMode.isPaused()) return true;
        if (!command.startsWith(COMMAND)
                || (command.length() > COMMAND.length() && !Character.isWhitespace(command.charAt(COMMAND.length())))) {
            return true;
        }
        showCoordinates(Minecraft.getInstance(), command.substring(COMMAND.length()).strip());
        return false;
    }

    private static void showCoordinates(Minecraft client, String name) {
        if (name.isEmpty() || name.chars().anyMatch(Character::isWhitespace)) {
            showMessage(client, "Введи /gps <нік>.");
            return;
        }
        if (client.level != null) {
            for (var player : client.level.players()) {
                String playerName = player.getGameProfile().name();
                if (playerName.equalsIgnoreCase(name)) {
                    BlockPos position = player.blockPosition();
                    showMessage(client, "%s  X %d  Y %d  Z %d".formatted(
                            playerName, position.getX(), position.getY(), position.getZ()));
                    return;
                }
            }
        }
        showMessage(client, "Гравця " + name + " немає серед завантажених у поточному світі.");
    }

    private static void showMessage(Minecraft client, String message) {
        client.gui.getChat().addMessage(Component.literal("[GPS] " + message));
    }
}
