package com.dash.client.client;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import static com.dash.client.client.DashLanguage.text;

/** Pauses Dash independently of the Minecraft session. */
final class PauseManager {
    enum State { ACTIVE, PAUSING, PAUSED }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicReference<State> STATE = new AtomicReference<>(State.ACTIVE);
    // These settings are explicitly disabled in onPause. Other module settings stay in place.
    private static final Set<String> DISABLED_BY_PAUSE = Set.of("AutoSwap", "Trigger Bot", "KillAura (Crits)",
            "PvP 1.8", "Auto Totem", "Auto Farm", "Packet Fly", "Velocity", "Sprint");
    private static KeyMapping[] dashBindings = {};
    private static Connection resumeConnection;
    private static List<DashClientClient.DashboardModule> resumeModules = List.of();

    private PauseManager() {}

    static State state() {
        return STATE.get();
    }

    static void register() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(PauseManager::allowCommand);
    }

    static void pause(Minecraft client) {
        if (state() != State.ACTIVE) return;
        if (!client.isSameThread()) {
            client.execute(() -> pause(client));
            return;
        }
        var listener = client.getConnection();
        if (client.level == null || client.player == null || listener == null
                || !listener.getConnection().isConnected()) {
            message(client, text("Пауза Dash доступна після входу у світ.",
                    "Пауза Dash доступна после входа в мир.", "Enter a world to pause Dash."));
            return;
        }
        if (isPrivateWorld(client, listener)) {
            step("capture module states", () -> {
                resumeModules = DashClientClient.dashboardModules().stream()
                        .filter(module -> module.enabled().getAsBoolean()).toList();
                resumeConnection = listener.getConnection();
            });
        }
        if (!STATE.compareAndSet(State.ACTIVE, State.PAUSING)) return;

        try {
            step("input", () -> {
                dashBindings = Arrays.stream(client.options.keyMappings)
                        .filter(SaveMode::isManagedBinding).toArray(KeyMapping[]::new);
                discardBindingInput();
                DashBindInput.reset();
            });
            step("Dash menu", () -> {
                if (client.screen instanceof DashDashboardScreen || client.screen instanceof DashClientClient.DashClickGui) {
                    client.setScreen(null);
                }
            });
            step("module cleanup", () -> DashClientClient.onPause(client));
            step("terrain renderer", () -> TerrainVisuals.refresh(client));
            step("chat layout", () -> client.gui.getChat().rescaleChat());
            step("pause notice", () -> message(client, resumeConnection != null
                    ? text("PAUSED. Для відновлення введіть /resume.",
                            "PAUSED. Для восстановления введите /resume.", "PAUSED. Use /resume to restore modules.")
                    : text("PAUSED. Модулі Dash зупинено.", "PAUSED. Модули Dash остановлены.", "PAUSED. Dash modules are stopped.")));
            LOGGER.info("Dash Pause completed in the current {} world",
                    listener.getConnection().isMemoryConnection() ? "local" : "multiplayer");
        } finally {
            STATE.set(State.PAUSED);
        }
    }

    private static boolean allowCommand(String command) {
        String alias = command.strip();
        boolean save = alias.equals("save");
        if (!save && !alias.equals("resume")) return true;

        Minecraft client = Minecraft.getInstance();
        ClientPacketListener listener = client.getConnection();
        boolean privateWorld = isPrivateWorld(client, listener);
        // /save always belongs to the server outside a private world. A registered command
        // also takes precedence over either alias inside a private world.
        if (save && (!privateWorld || state() != State.PAUSED)) return true;
        var local = ClientCommandManager.getActiveDispatcher();
        if ((listener != null && listener.getCommands().getRoot().getChild(alias) != null)
                || (local != null && local.getRoot().getChild(alias) != null)) return true;
        if (!privateWorld && state() == State.ACTIVE) return true;

        resume(client);
        return false;
    }

    private static boolean isPrivateWorld(Minecraft client, ClientPacketListener listener) {
        var server = client.getSingleplayerServer();
        return listener != null && listener.getConnection().isConnected()
                && listener.getConnection().isMemoryConnection()
                && client.level != null && client.player != null && server != null && !server.isPublished();
    }

    private static void resume(Minecraft client) {
        if (!client.isSameThread()) {
            client.execute(() -> resume(client));
            return;
        }
        if (state() == State.ACTIVE) {
            message(client, text("Dash уже ACTIVE.", "Dash уже ACTIVE.", "Dash is already ACTIVE."));
            return;
        }
        var listener = client.getConnection();
        if (state() != State.PAUSED || !isPrivateWorld(client, listener)
                || listener.getConnection() != resumeConnection) {
            message(client, text("Dash залишається PAUSED. Відновлення доступне лише в тому самому закритому одиночному світі.",
                    "Dash остаётся PAUSED. Восстановление доступно только в том же закрытом одиночном мире.",
                    "Dash stays PAUSED. Restore is available only in the same private singleplayer world."));
            return;
        }
        if (ConsumableBind.hasPendingPauseCleanup()) {
            message(client, text("Dash залишається PAUSED. Дочекайтеся завершення відновлення інвентарю.",
                    "Dash остаётся PAUSED. Дождитесь завершения восстановления инвентаря.",
                    "Dash stays PAUSED. Wait for inventory cleanup to finish."));
            return;
        }

        discardBindingInput();
        DashBindInput.reset();
        List<DashClientClient.DashboardModule> modules = resumeModules;
        clearResumeContext();
        STATE.set(State.ACTIVE);
        List<String> restored = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (var module : modules) {
            try {
                if (DISABLED_BY_PAUSE.contains(module.id()) && !module.enabled().getAsBoolean()) {
                    module.toggle().run();
                }
                if (module.enabled().getAsBoolean()) {
                    restored.add(module.label().get());
                } else {
                    errors.add(module.label().get() + text(" (недоступний)", " (недоступен)", " (unavailable)"));
                }
            } catch (RuntimeException exception) {
                errors.add(module.id() + text(" (помилка запуску)", " (ошибка запуска)", " (startup error)"));
                LOGGER.error("Error while restoring Dash module {}", module.id(), exception);
            }
        }
        step("terrain renderer", () -> TerrainVisuals.refresh(client));
        step("chat layout", () -> client.gui.getChat().rescaleChat());
        message(client, restored.isEmpty()
                ? text("ACTIVE. Немає відновлених модулів.", "ACTIVE. Восстановленных модулей нет.", "ACTIVE. No modules restored.")
                : text("ACTIVE. Відновлено ", "ACTIVE. Восстановлены ", "ACTIVE. Restored ") + String.join(", ", restored));
        if (!errors.isEmpty()) {
            message(client, text("Не відновлено ", "Не восстановлены ", "Could not restore ") + String.join(", ", errors));
        }
        LOGGER.info("Dash Resume restored {} module(s) in singleplayer with {} unavailable", restored.size(), errors.size());
    }

    static void clearResumeContext() {
        resumeConnection = null;
        resumeModules = List.of();
    }

    static void discardBindingInput() {
        for (KeyMapping key : dashBindings) {
            key.setDown(false);
            while (key.consumeClick()) {
                // Discard input for Dash bindings; vanilla bindings use Minecraft's normal input path.
            }
        }
    }

    static void step(String name, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            LOGGER.error("Error while pausing Dash component {}", name, exception);
        }
    }

    private static void message(Minecraft client, String message) {
        client.gui.getChat().addMessage(Component.literal("[Dash] " + message));
    }
}
