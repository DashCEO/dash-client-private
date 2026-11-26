package com.dash.client.ipconfig;

import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ProxySettings {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir()
            .resolve("dash-client").resolve("ip-config.txt");
    private static final String AUTH = "auth";
    private static final Route DIRECT = new Route(null, false, null, null);
    private static volatile Route route = load();

    private ProxySettings() {}

    public record Route(String address, boolean authenticationRequired, String username, String password) {
        public boolean needsCredentials() {
            return authenticationRequired && password == null;
        }

        @Override
        public String toString() {
            return "Route[address=" + address + ", authenticationRequired=" + authenticationRequired + "]";
        }
    }

    public static Route route() {
        return route;
    }

    public static String address() {
        return route.address();
    }

    static void useProxy(String value, String username, String password) throws IOException {
        String selected = ProxyEndpoint.parse(value).address();
        boolean authentication = Socks5Route.usesAuthentication(username, password);
        String newline = System.lineSeparator();
        String saved = selected + newline + (authentication ? AUTH + newline : "");
        Files.createDirectories(CONFIG.getParent());
        Path temporary = Files.createTempFile(CONFIG.getParent(), "ip-config-", ".tmp");
        try {
            Files.writeString(temporary, saved);
            try {
                Files.move(temporary, CONFIG, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, CONFIG, StandardCopyOption.REPLACE_EXISTING);
            }
            route = new Route(selected, authentication, authentication ? username : null, authentication ? password : null);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void useDirect() throws IOException {
        Files.deleteIfExists(CONFIG);
        route = DIRECT;
    }

    private static Route load() {
        try {
            var lines = Files.readString(CONFIG).strip().lines().toList();
            boolean authentication = lines.size() == 2 && AUTH.equals(lines.get(1));
            if (lines.size() != 1 && !authentication) {
                throw new IllegalArgumentException("Invalid proxy settings");
            }
            return new Route(ProxyEndpoint.parse(lines.getFirst()).address(), authentication, null, null);
        } catch (NoSuchFileException exception) {
            return DIRECT;
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.warn("Could not read Dash IP Config", exception);
            // Invalid saved proxy settings must fail instead of connecting directly.
            return new Route("", false, null, null);
        }
    }
}
