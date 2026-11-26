package com.dash.client.client;

import com.dash.client.DashClient;
import com.google.common.net.HostAndPort;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

public final class DashWaypoints {
    private static final String COMMAND = "mark";
    private static final int DEFAULT_RANGE = 1024;
    private static final int MIN_RANGE = 16;
    private static final int MAX_RANGE = 10000;
    private static final int MAX_NAME_LENGTH = 48;
    private static final int MAX_WAYPOINTS = 64;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Profile EMPTY_PROFILE = new Profile(DEFAULT_RANGE, List.of());

    private static boolean enabled = true;
    private static ClientLevel activeLevel;
    private static Path activeFile;
    private static Profile activeProfile;

    private DashWaypoints() {}

    public static void register() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(DashWaypoints::allowCommand);
        ClientTickEvents.END_CLIENT_TICK.register(DashWaypoints::tick);
        HudElementRegistry.attachElementBefore(VanillaHudElements.CROSSHAIR, DashClient.id("waypoints"),
                DashWaypoints::render);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    private static String commandArguments(String command) {
        if (!command.startsWith(COMMAND)
                || (command.length() > COMMAND.length() && !Character.isWhitespace(command.charAt(COMMAND.length())))) {
            return null;
        }
        return command.substring(COMMAND.length()).strip();
    }

    private static boolean allowCommand(String command) {
        if (SaveMode.isPaused()) return true;
        String arguments = commandArguments(command);
        if (arguments == null) {
            return true;
        }
        runCommand(Minecraft.getInstance(), arguments);
        return false;
    }

    private static void runCommand(Minecraft client, String arguments) {
        if (arguments.isEmpty()) {
            message(client, "/mark <назва> · /mark list · /mark remove <назва> · /mark range <блоки>");
            return;
        }
        if (client.level == null || client.player == null) {
            message(client, "Спочатку зайди у світ.");
            return;
        }
        loadActiveProfile(client);
        if (activeProfile == null) {
            message(client, "Не вдалося завантажити точки цього світу.");
            return;
        }

        int end = 0;
        while (end < arguments.length() && !Character.isWhitespace(arguments.charAt(end))) {
            end++;
        }
        String action = arguments.substring(0, end).toLowerCase(Locale.ROOT);
        String value = arguments.substring(end).strip();
        switch (action) {
            case "list" -> {
                if (value.isEmpty()) {
                    list(client);
                } else {
                    message(client, "Введи /mark list.");
                }
            }
            case "remove" -> message(client, remove(value));
            case "range" -> message(client, changeRange(value));
            default -> message(client, mark(client, arguments));
        }
    }

    static List<Waypoint> current(Minecraft client) {
        if (client.level == null || client.player == null) return List.of();
        loadActiveProfile(client);
        return activeProfile == null ? List.of() : activeProfile.waypoints();
    }

    static String manage(Minecraft client, String action, String value) {
        if (SaveMode.isPaused()) return "Dash Client зараз на паузі.";
        if (client.level == null || client.player == null) return "Спочатку зайди у світ.";
        loadActiveProfile(client);
        if (activeProfile == null) return "Не вдалося завантажити точки цього світу.";
        return switch (action) {
            case "remove" -> remove(value);
            case "range" -> changeRange(value);
            default -> mark(client, value);
        };
    }

    static String hudSummary(Minecraft client) {
        if (client.player == null || client.level != activeLevel || activeProfile == null) return null;
        Waypoint nearest = null;
        double closest = Double.POSITIVE_INFINITY;
        for (Waypoint waypoint : activeProfile.waypoints()) {
            double distance = client.player.distanceToSqr(waypoint.x() + .5, waypoint.y(), waypoint.z() + .5);
            if (distance >= closest) continue;
            nearest = waypoint;
            closest = distance;
        }
        return nearest == null ? null : nearest.name() + " · " + Math.round(Math.sqrt(closest)) + " m";
    }

    private static String mark(Minecraft client, String name) {
        if (!validName(name)) {
            return "Введи назву довжиною до " + MAX_NAME_LENGTH + " символів.";
        }
        List<Waypoint> waypoints = new ArrayList<>(activeProfile.waypoints());
        int index = findWaypoint(waypoints, name);
        if (index < 0 && waypoints.size() == MAX_WAYPOINTS) {
            return "У цьому вимірі вже збережено " + MAX_WAYPOINTS + " точок.";
        }

        BlockPos position = client.player.blockPosition();
        Waypoint waypoint = new Waypoint(name, position.getX(), position.getY(), position.getZ());
        if (index < 0) {
            waypoints.add(waypoint);
        } else {
            waypoints.set(index, waypoint);
        }
        return save(new Profile(activeProfile.range(), waypoints))
                ? (index < 0 ? "Збережено: " : "Оновлено: ") + describe(waypoint) : "Не вдалося зберегти точки.";
    }

    private static void list(Minecraft client) {
        if (activeProfile.waypoints().isEmpty()) {
            message(client, "У цьому вимірі ще немає точок.");
            return;
        }
        for (Waypoint waypoint : activeProfile.waypoints()) {
            message(client, describe(waypoint));
        }
    }

    private static String remove(String name) {
        if (name.isEmpty()) {
            return "Введи /mark remove <назва>.";
        }
        List<Waypoint> waypoints = new ArrayList<>(activeProfile.waypoints());
        int index = findWaypoint(waypoints, name);
        if (index < 0) {
            return "Точку «" + name + "» не знайдено.";
        }
        Waypoint removed = waypoints.remove(index);
        return save(new Profile(activeProfile.range(), waypoints))
                ? "Видалено точку «" + removed.name() + "»." : "Не вдалося зберегти точки.";
    }

    private static String changeRange(String value) {
        if (value.isEmpty()) {
            return "Поточна дальність: " + activeProfile.range() + " блоків.";
        }
        int range;
        try {
            range = Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            range = 0;
        }
        if (!validRange(range)) {
            return "Введи ціле число від " + MIN_RANGE + " до " + MAX_RANGE + ".";
        }
        return save(new Profile(range, activeProfile.waypoints()))
                ? "Дальність показу: " + range + " блоків." : "Не вдалося зберегти точки.";
    }

    private static int findWaypoint(List<Waypoint> waypoints, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        for (int index = 0; index < waypoints.size(); index++) {
            if (waypoints.get(index).name().toLowerCase(Locale.ROOT).equals(key)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean validName(String name) {
        return name != null && !name.isBlank() && name.equals(name.strip())
                && name.codePointCount(0, name.length()) <= MAX_NAME_LENGTH
                && name.codePoints().noneMatch(codePoint -> Character.isISOControl(codePoint) || codePoint == '§');
    }

    private static boolean validRange(int range) {
        return range >= MIN_RANGE && range <= MAX_RANGE;
    }

    private static String describe(Waypoint waypoint) {
        return "%s  X %d  Y %d  Z %d".formatted(waypoint.name(), waypoint.x(), waypoint.y(), waypoint.z());
    }

    private static void message(Minecraft client, String text) {
        client.gui.getChat().addMessage(Component.literal("[Waypoints] " + text));
    }

    private static void tick(Minecraft client) {
        if (SaveMode.isPaused()) return;
        if (client.level == null || client.player == null) {
            clearTransient();
        } else if (enabled) {
            loadActiveProfile(client);
        }
    }

    static void clearTransient() {
        activeLevel = null;
        activeFile = null;
        activeProfile = null;
    }

    private static void loadActiveProfile(Minecraft client) {
        if (activeLevel == client.level) {
            return;
        }
        clearTransient();
        String scope = worldScope(client);
        if (scope == null) {
            return;
        }

        activeLevel = client.level;
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("dash-client").resolve("waypoints");
        activeFile = profileFile(directory, scope, activeLevel.dimension().identifier().toString());
        try {
            activeProfile = readProfile(activeFile);
        } catch (IOException failure) {
            DashClient.LOGGER.warn("Could not load the current waypoint profile", failure);
        }
    }

    static String worldScope(Minecraft client) {
        var integratedServer = client.getSingleplayerServer();
        if (integratedServer != null) {
            return "local\0" + integratedServer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        }
        var server = client.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            try {
                return "remote\0" + HostAndPort.fromString(server.ip.strip().toLowerCase(Locale.ROOT))
                        .withDefaultPort(25565);
            } catch (IllegalArgumentException invalid) {
                // Use the actual connection endpoint when no usable saved address is available.
            }
        }
        var connection = client.getConnection();
        if (connection != null && connection.getConnection().getRemoteAddress() instanceof InetSocketAddress address) {
            return "remote\0" + HostAndPort.fromParts(address.getHostString().toLowerCase(Locale.ROOT), address.getPort());
        }
        return null;
    }

    static Path profileFile(Path directory, String scope, String dimension) {
        try {
            byte[] key = (scope + '\0' + dimension).getBytes(StandardCharsets.UTF_8);
            String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));
            return directory.resolve(id + ".json");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static Profile readProfile(Path file) throws IOException {
        try (var reader = Files.newBufferedReader(file)) {
            Profile profile = GSON.fromJson(reader, Profile.class);
            if (profile == null || !validRange(profile.range()) || profile.waypoints() == null
                    || profile.waypoints().size() > MAX_WAYPOINTS) {
                throw new IOException("Invalid waypoint profile");
            }
            var names = new HashSet<String>();
            for (Waypoint waypoint : profile.waypoints()) {
                if (waypoint == null || !validName(waypoint.name()) || !names.add(waypoint.name().toLowerCase(Locale.ROOT))) {
                    throw new IOException("Invalid waypoint in profile");
                }
            }
            return new Profile(profile.range(), List.copyOf(profile.waypoints()));
        } catch (NoSuchFileException missing) {
            return EMPTY_PROFILE;
        } catch (JsonParseException invalid) {
            throw new IOException("Invalid waypoint profile", invalid);
        }
    }

    private static void writeProfile(Path file, Profile profile) throws IOException {
        writeJson(file, GSON.toJson(profile));
    }

    static void writeJson(Path file, String json) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "dash-point-", ".tmp");
        try {
            Files.writeString(temporary, json);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean save(Profile profile) {
        try {
            writeProfile(activeFile, profile);
            activeProfile = profile;
            return true;
        } catch (IOException failure) {
            DashClient.LOGGER.warn("Could not save the current waypoint profile", failure);
            return false;
        }
    }

    private static void render(GuiGraphics graphics, DeltaTracker tickCounter) {
        if (!enabled || !DashHudLayout.enabled(DashHudLayout.Widget.WAYPOINTS)
                || SaveMode.isPaused() || activeProfile == null || activeProfile.waypoints().isEmpty()
                || graphics.guiHeight() < 18) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof DashDashboardScreen || client.level != activeLevel
                || client.player == null || client.options.hideGui) {
            return;
        }
        var camera = client.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }

        Vec3 player = client.player.position();
        double rangeSquared = (double) activeProfile.range() * activeProfile.range();
        for (Waypoint waypoint : activeProfile.waypoints()) {
            Vec3 position = new Vec3(waypoint.x() + 0.5, waypoint.y(), waypoint.z() + 0.5);
            double distanceSquared = player.distanceToSqr(position);
            if (distanceSquared > rangeSquared) continue;
            Vec3 projected = projectAnchor(client, camera, position.add(0.0, 1.5, 0.0));
            if (projected == null) continue;
            int x = (int) Math.round((projected.x + 1.0) * graphics.guiWidth() * 0.5);
            int y = (int) Math.round((1.0 - projected.y) * graphics.guiHeight() * 0.5);
            drawLabel(graphics, client, x, y, waypoint.name(), Math.round(Math.sqrt(distanceSquared)));
        }
    }

    static Vec3 projectAnchor(Minecraft client, Camera camera, Vec3 anchor) {
        Vec3 origin = camera.position();
        var forward = camera.forwardVector();
        double depth = (anchor.x - origin.x) * forward.x() + (anchor.y - origin.y) * forward.y()
                + (anchor.z - origin.z) * forward.z();
        if (!(depth > 0.05)) return null;
        Vec3 projected = client.gameRenderer.projectPointToScreen(anchor);
        return Math.abs(projected.x) <= 1.0 && Math.abs(projected.y) <= 1.0 ? projected : null;
    }

    private static void drawLabel(GuiGraphics graphics, Minecraft client, int x, int y, String name, long distance) {
        String suffix = " · " + distance + " м";
        int available = graphics.guiWidth() - client.font.width(suffix) - 14;
        if (available < client.font.width("…")) {
            return;
        }
        if (client.font.width(name) > available) {
            name = client.font.plainSubstrByWidth(name, available - client.font.width("…")) + "…";
        }

        int nameWidth = client.font.width(name);
        int width = nameWidth + client.font.width(suffix);
        int left = Math.clamp(x - width / 2, 7, graphics.guiWidth() - width - 7);
        int top = Math.clamp(y - 17, 3, graphics.guiHeight() - 15);
        int accent = HudSettings.color(HudSettings.accent());
        graphics.fill(left - 4, top - 3, left + width + 4, top + 11,
                HudSettings.color(0xDF000000 | (HudSettings.shade(.05F) & 0xFFFFFF)));
        graphics.fill(left - 4, top - 3, left - 3, top + 11, accent);
        graphics.drawString(client.font, name, left, top, HudSettings.color(0xFFF2EDF9), false);
        graphics.drawString(client.font, suffix, left + nameWidth, top, accent, false);
        graphics.fill(x - 1, y - 1, x + 2, y + 2, accent);
    }

    record Waypoint(String name, int x, int y, int z) {}

    private record Profile(int range, List<Waypoint> waypoints) {}
}
