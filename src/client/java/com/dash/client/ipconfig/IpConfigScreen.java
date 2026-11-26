package com.dash.client.ipconfig;

import static com.dash.client.client.DashLanguage.text;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.util.function.Consumer;

public final class IpConfigScreen extends Screen {
    private final Component endpointLabel = Component.literal(
            text("Проксі SOCKS5 · IP:порт", "Прокси SOCKS5 · IP:порт", "SOCKS5 proxy · IP:port"));
    private final Component loginLabel = Component.literal(text("Логін", "Логин", "Login"));
    private final Component passwordLabel = Component.literal(text("Пароль", "Пароль", "Password"));

    private final Screen parent;
    private String addressDraft;
    private String loginDraft;
    private String passwordDraft;
    private Component error;
    private int top;
    private int fieldX;
    private int fieldWidth;

    public IpConfigScreen(Screen parent) {
        super(Component.literal("IP Config"));
        this.parent = parent;
        var saved = ProxySettings.route();
        addressDraft = orEmpty(saved.address());
        loginDraft = orEmpty(saved.username());
        passwordDraft = orEmpty(saved.password());
        if (saved.address() != null && saved.address().isEmpty()) {
            error = Component.literal(text("Хибна адреса проксі. Введи адресу або підключись напряму.",
                    "Неверный адрес прокси. Введи адрес или подключись напрямую.", "Invalid proxy address. Enter an address or connect directly."));
        }
    }

    @Override
    protected void init() {
        fieldWidth = Math.min(326, width - 28);
        fieldX = (width - fieldWidth) / 2;
        top = (height - 182) / 2;
        int half = (fieldWidth - 8) / 2;
        int rightX = fieldX + half + 8;
        int rightWidth = fieldWidth - half - 8;

        EditBox endpoint = new EditBox(font, fieldX, top + 48, fieldWidth, 20, endpointLabel);
        endpoint.setHint(Component.literal("127.0.0.1:1080"));
        addField(endpoint, addressDraft, 270, value -> addressDraft = value);
        addField(new EditBox(font, fieldX, top + 88, half, 20, loginLabel),
                loginDraft, 255, value -> loginDraft = value);
        addField(new PasswordBox(font, rightX, top + 88, rightWidth, passwordLabel),
                passwordDraft, 255, value -> passwordDraft = value);
        setInitialFocus(endpoint);

        addRenderableWidget(Button.builder(Component.literal(text("Через SOCKS5", "Через SOCKS5", "Use SOCKS5")), button -> apply(true))
                .bounds(fieldX, top + 136, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal(text("Напряму", "Напрямую", "Connect directly")), button -> apply(false))
                .bounds(rightX, top + 136, rightWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal(text("Скасувати", "Отмена", "Cancel")), button -> onClose())
                .bounds(width / 2 - 50, top + 162, 100, 20).build());
    }

    private void addField(EditBox field, String initialValue, int maxLength, Consumer<String> update) {
        field.setMaxLength(maxLength);
        field.setValue(initialValue);
        field.setResponder(value -> {
            update.accept(value);
            error = null;
        });
        addRenderableWidget(field);
    }

    private void apply(boolean useProxy) {
        try {
            if (useProxy) {
                ProxySettings.useProxy(addressDraft, loginDraft, passwordDraft);
            } else {
                ProxySettings.useDirect();
            }
            onClose();
        } catch (IllegalArgumentException exception) {
            error = Component.literal(exception.getMessage());
        } catch (IOException exception) {
            error = Component.literal(text("Не вдалося зберегти IP Config.", "Не удалось сохранить IP Config.",
                    "Could not save IP Config."));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, top, 0xFFFFFFFF);
        String prompt = text("Без авторизації залиш логін і пароль порожніми.",
                "Без авторизации оставь логин и пароль пустыми.", "Leave login and password blank to connect without authentication.");
        graphics.drawCenteredString(font, prompt, width / 2, top + 19, 0xFFA6B8CE);
        graphics.drawString(font, endpointLabel, fieldX, top + 36, 0xFFD7C9EB);
        graphics.drawString(font, loginLabel, fieldX, top + 76, 0xFFD7C9EB);
        graphics.drawString(font, passwordLabel, fieldX + (fieldWidth - 8) / 2 + 8, top + 76, 0xFFD7C9EB);

        var selected = ProxySettings.route();
        String status = selected.address() == null ? text("Активно · напряму", "Активно · напрямую", "Active · Direct")
                : text("Активно · SOCKS5", "Активно · SOCKS5", "Active · SOCKS5");
        if (selected.authenticationRequired()) {
            status = selected.needsCredentials()
                    ? text("Введи логін і пароль для цього сеансу.", "Введи логин и пароль для этого сеанса.",
                            "Enter your login and password for this session.")
                    : text("SOCKS5 із логіном для цього сеансу", "SOCKS5 с логином для этого сеанса",
                            "SOCKS5 with login for this session");
        }
        graphics.drawCenteredString(font, error == null ? Component.literal(status) : error,
                width / 2, top + 119, error == null ? 0xFFA6B8CE : 0xFFFF9292);
    }

    @Override
    public void onClose() {
        loginDraft = "";
        passwordDraft = "";
        minecraft.setScreen(parent);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class PasswordBox extends EditBox {
        private final Component label;

        private PasswordBox(Font font, int x, int y, int width, Component label) {
            super(font, x, y, width, 20, label);
            this.label = label;
            addFormatter((text, index) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            return event.isCopy() || event.isCut() || super.keyPressed(event);
        }

        @Override
        protected MutableComponent createNarrationMessage() {
            return label.copy();
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.enableScissor(getX(), getY(), getX() + getWidth(), getY() + getHeight());
            try {
                super.renderWidget(graphics, mouseX, mouseY, partialTick);
            } finally {
                graphics.disableScissor();
            }
        }
    }
}
