package com.dash.client.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

public final class Friends {
    public static final int COLOR = 0xFF78DFF6;
    private static final Component NAME_TAG = Component.literal("Dash Friend")
            .withStyle(style -> style.withColor(COLOR & 0xFFFFFF));
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static volatile Map<String, String> friends = Map.of();

    private Friends() {}

    static void register() {
        load();
        ClientSendMessageEvents.ALLOW_COMMAND.register(Friends::allowCommand);
    }

    public static boolean isFriend(String name) {
        return !SaveMode.isPaused() && name != null && friends.containsKey(name.toLowerCase(Locale.ROOT));
    }

    static boolean isFriendPlayer(Entity entity) {
        return entity instanceof Player player && isFriend(player.getGameProfile().name());
    }

    public static Component nameTag() {
        return NAME_TAG;
    }

    static void load() {
        Map<String, String> loaded = new LinkedHashMap<>();
        try (var reader = Files.newBufferedReader(file())) {
            JsonArray entries = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement entry : entries) {
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) continue;
                String name = entry.getAsString();
                if (USERNAME.matcher(name).matches()) {
                    loaded.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
                }
            }
            friends = Map.copyOf(loaded);
        } catch (NoSuchFileException missing) {
            friends = Map.of();
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not load Dash friends", exception);
        }
    }

    static boolean allowCommand(String command) {
        if (SaveMode.isPaused()) return true;
        String[] parts = command.strip().split("\\s+", 3);
        if (!parts[0].equalsIgnoreCase("friend")) return true;

        Minecraft client = Minecraft.getInstance();
        if (parts.length == 2 && parts[1].equalsIgnoreCase("list")) {
            String names = String.join(", ", names());
            message(client, names.isEmpty() ? "Список друзів порожній." : "У списку друзів — " + names);
            return false;
        }
        if (parts.length != 3 || !USERNAME.matcher(parts[2]).matches()) {
            message(client, "Використовуй /friend add <нік> · /friend remove <нік> · /friend list");
            return false;
        }

        message(client, manage(parts[1].toLowerCase(Locale.ROOT), parts[2]));
        return false;
    }

    static java.util.List<String> names() {
        return friends.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    static String manage(String action, String name) {
        if (SaveMode.isPaused()) return "Dash Client зараз на паузі.";
        if (!USERNAME.matcher(name).matches()) return "Введи нік Minecraft від 1 до 16 символів.";
        String key = name.toLowerCase(Locale.ROOT);
        Map<String, String> updated = new LinkedHashMap<>(friends);
        String result;
        switch (action) {
            case "add" -> {
                if (updated.putIfAbsent(key, name) != null) {
                    return name + " уже є у списку друзів.";
                }
                result = "Додано " + name + ". Мітка над гравцем — Dash Friend.";
            }
            case "remove" -> {
                if (updated.remove(key) == null) {
                    return name + " відсутній у списку друзів.";
                }
                result = "Видалено " + name + " зі списку друзів.";
            }
            default -> {
                return "Використовуй /friend add <нік> · /friend remove <нік> · /friend list";
            }
        }
        try {
            save(updated);
            friends = Map.copyOf(updated);
            return result;
        } catch (IOException | JsonIOException exception) {
            LOGGER.warn("Could not save Dash friends", exception);
            return "Не вдалося зберегти список друзів. Перевір доступ до папки config.";
        }
    }

    private static void save(Map<String, String> updated) throws IOException {
        Path target = file();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "dash-friends-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) {
                GSON.toJson(updated.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(), writer);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("dash-friends.json");
    }

    private static void message(Minecraft client, String text) {
        client.gui.getChat().addMessage(Component.literal("[Dash Friends] " + text));
    }
}
