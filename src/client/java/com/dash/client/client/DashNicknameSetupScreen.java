package com.dash.client.client;

import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

import static com.dash.client.client.DashLanguage.text;

final class DashNicknameSetupScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern NICKNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final int PANEL_HEIGHT = 110;

    private final Path nicknameFile;
    private final Screen parent;
    private final String initialNickname;
    private String draft;
    private boolean saveFailed;
    private int panelWidth;
    private int left;
    private int top;

    private DashNicknameSetupScreen(Path nicknameFile, Screen parent, String initialNickname) {
        super(Component.literal(text("Профіль Dash", "Профиль Dash", "Dash profile")));
        this.nicknameFile = nicknameFile;
        this.parent = parent;
        this.initialNickname = initialNickname;
        this.draft = initialNickname;
    }

    static void register() {
        String setupFile = System.getProperty("dash.launcherNicknameFile");
        if (setupFile == null || setupFile.isBlank()) return;

        Path nicknameFile;
        try {
            nicknameFile = Path.of(setupFile);
            if (!nicknameFile.isAbsolute() || nicknameFile.getParent() == null) {
                LOGGER.warn("Dash nickname setup requires an absolute file path");
                return;
            }
        } catch (InvalidPathException exception) {
            LOGGER.warn("Invalid Dash nickname setup path", exception);
            return;
        }

        if (Boolean.getBoolean("dash.launcherNicknameSetup")) {
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (!SaveMode.isPaused() && client.getOverlay() == null && client.screen instanceof TitleScreen) {
                    client.setScreen(new DashNicknameSetupScreen(nicknameFile, null, ""));
                }
            });
            return;
        }

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!SaveMode.isPaused() && screen instanceof TitleScreen) {
                Component label = Component.literal(text("Змінити нік", "Изменить ник", "Change nickname"));
                Screens.getButtons(screen).add(Button.builder(label, button ->
                                client.setScreen(new DashNicknameSetupScreen(nicknameFile, screen, client.getUser().getName())))
                        .bounds(8, 8, 110, 20).build());
            }
        });
    }

    @Override
    protected void init() {
        panelWidth = Math.min(280, width - 24);
        left = (width - panelWidth) / 2;
        top = (height - PANEL_HEIGHT) / 2;
        int contentX = left + 10;
        int contentWidth = panelWidth - 20;

        Component label = Component.literal(text("Твій нік Minecraft", "Твой ник Minecraft", "Your Minecraft username"));
        EditBox input = addRenderableWidget(new EditBox(font, contentX, top + 34, contentWidth, 20, label));
        input.setValue(draft);
        input.setHint(label);

        int saveWidth = (contentWidth - 6) * 2 / 3;
        Button save = addRenderableWidget(Button.builder(Component.literal(text(
                        "Зберегти й перезапустити", "Сохранить и перезапустить", "Save and restart")),
                        button -> saveNickname())
                .bounds(contentX, top + 80, saveWidth, 20).build());
        save.active = canSave();
        input.setResponder(value -> {
            draft = value;
            save.active = canSave();
            saveFailed = false;
        });

        String dismissLabel = parent == null
                ? text("Вийти", "Выйти", "Quit")
                : text("Скасувати", "Отмена", "Cancel");
        addRenderableWidget(Button.builder(Component.literal(dismissLabel), button -> onClose())
                .bounds(contentX + saveWidth + 6, top + 80, contentWidth - saveWidth - 6, 20).build());
        setInitialFocus(input);
    }

    private boolean canSave() {
        return !initialNickname.equals(draft) && NICKNAME.matcher(draft).matches();
    }

    private void saveNickname() {
        if (!canSave()) return;

        try {
            Path directory = nicknameFile.getParent();
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "dash-nickname-", ".tmp");
            try {
                Files.writeString(temporary, draft);
                Files.move(temporary, nicknameFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | SecurityException exception) {
            LOGGER.warn("Could not save Dash nickname", exception);
            saveFailed = true;
            return;
        }
        minecraft.stop();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xFF0C1219);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        DashAppearance.drawPanel(graphics, left, top, left + panelWidth, top + PANEL_HEIGHT,
                DashAppearance.background(), true);
        graphics.drawCenteredString(font, title, width / 2, top + 12, 0xFFFFFFFF);
        String status = saveFailed
                ? text("Не вдалося зберегти нік", "Не удалось сохранить ник", "Could not save the nickname")
                : text("3–16 символів · A–Z, 0–9, _", "3–16 символов · A–Z, 0–9, _", "3–16 characters · A–Z, 0–9, _");
        graphics.drawCenteredString(font, status, width / 2, top + 63, saveFailed ? 0xFFFF9292 : 0xFFA6B8CE);
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        if (parent == null) {
            minecraft.stop();
        } else {
            minecraft.setScreen(parent);
        }
    }
}
