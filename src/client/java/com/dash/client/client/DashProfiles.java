package com.dash.client.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

final class DashProfiles {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern PROFILE_NAME = Pattern.compile("[\\p{L}\\p{M}\\p{N} _-]{1,48}");
    private static final String FILE_PREFIX = "profile-";
    private static final String FILE_SUFFIX = ".properties";
    private static final String VERSION = "1";
    private static final String NAME_HINT = "Назва має містити до 48 літер, цифр, пробілів, символів _ або -.";
    private static final String RENAME_USAGE = "/config rename <стара назва> -> <нова назва>";
    private static final String USAGE = "Використовуй /config save <назва>, /config load <назва>, "
            + "/config delete <назва>, /config list або " + RENAME_USAGE + ".";

    private DashProfiles() {}

    static void register() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(DashProfiles::allowCommand);
    }

    private static boolean allowCommand(String command) {
        if (SaveMode.isPaused()) return true;
        String[] parts = command.strip().split("\\s+", 3);
        if (!parts[0].equalsIgnoreCase("config") && !parts[0].equalsIgnoreCase("cfg")) return true;

        Minecraft client = Minecraft.getInstance();
        if (parts.length == 2 && parts[1].equalsIgnoreCase("list")) {
            listProfiles(client);
            return false;
        }
        if (parts.length != 3) {
            message(client, USAGE);
            return false;
        }

        String action = parts[1].toLowerCase(Locale.ROOT);
        if (action.equals("rename")) {
            renameProfile(client, parts[2].strip());
            return false;
        }
        message(client, manage(client, action, parts[2].strip()));
        return false;
    }

    static String manage(Minecraft client, String action, String name) {
        if (SaveMode.isPaused()) return "Dash Client зараз на паузі.";
        if (!PROFILE_NAME.matcher(name).matches()) {
            return NAME_HINT;
        }

        Path file = profileFile(name);
        try {
            return switch (action) {
                case "save" -> {
                    write(file, capture(client));
                    yield "Збережено профіль «" + name + "».";
                }
                case "load" -> {
                    Properties profile = new Properties();
                    try (var reader = Files.newBufferedReader(file)) {
                        profile.load(reader);
                    }
                    apply(client, profile);
                    yield "Завантажено профіль «" + name + "».";
                }
                case "delete" -> {
                    if (!Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                        yield "Профіль «" + name + "» не знайдено.";
                    }
                    Files.delete(file);
                    yield "Видалено профіль «" + name + "».";
                }
                default -> USAGE;
            };
        } catch (NoSuchFileException exception) {
            return "Профіль «" + name + "» не знайдено.";
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Invalid Dash profile {}", file, exception);
            return "Профіль «" + name + "» пошкоджений або має несумісний формат.";
        } catch (IOException exception) {
            LOGGER.warn("Could not access Dash profile {}", file, exception);
            return action.equals("delete") ? "Не вдалося видалити профіль."
                    : "Не вдалося прочитати або зберегти профіль.";
        }
    }

    private static Path profileDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve("dash-client").resolve("profiles");
    }

    private static Path profileFile(String name) {
        return profileDirectory().resolve(FILE_PREFIX + name + FILE_SUFFIX);
    }

    private static void renameProfile(Minecraft client, String arguments) {
        String[] names = arguments.contains("->") ? arguments.split("->", -1) : arguments.split("\\s+");
        if (names.length != 2) {
            message(client, "Використовуй " + RENAME_USAGE + ".");
            return;
        }
        String oldName = names[0].strip();
        String newName = names[1].strip();
        if (!PROFILE_NAME.matcher(oldName).matches() || !PROFILE_NAME.matcher(newName).matches()) {
            message(client, NAME_HINT);
            return;
        }

        Path source = profileFile(oldName);
        Path destination = profileFile(newName);
        try {
            var attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new NoSuchFileException(source.toString());
            if (oldName.equals(newName)) {
                message(client, "Профіль уже має назву «" + newName + "».");
                return;
            }
            if (source.equals(destination)) {
                // Windows' ordinary move treats a change of case as a no-op.
                try (var files = Files.list(source.getParent())) {
                    if (files.filter(source::equals).limit(2).count() != 1) {
                        throw new FileAlreadyExistsException(destination.toString());
                    }
                }
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            } else {
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    throw new FileAlreadyExistsException(destination.toString());
                }
                Files.move(source, destination);
            }
            message(client, "Профіль «" + oldName + "» перейменовано на «" + newName + "».");
        } catch (NoSuchFileException exception) {
            message(client, "Профіль «" + oldName + "» не знайдено.");
        } catch (FileAlreadyExistsException exception) {
            message(client, "Назва «" + newName + "» вже зайнята.");
        } catch (IOException | UncheckedIOException exception) {
            LOGGER.warn("Could not rename Dash profile {} to {}", source, destination, exception);
            message(client, "Не вдалося перейменувати профіль.");
        }
    }

    private static void listProfiles(Minecraft client) {
        try {
            var names = names();
            if (names.isEmpty()) {
                message(client, "Збережених профілів поки немає.");
            } else {
                message(client, "Збережені профілі (" + names.size() + ")");
                names.forEach(name -> client.gui.getChat().addMessage(Component.literal("• " + name)));
            }
        } catch (IOException | UncheckedIOException exception) {
            LOGGER.warn("Could not list Dash profiles", exception);
            message(client, "Не вдалося прочитати список профілів.");
        }
    }

    static java.util.List<String> names() throws IOException {
        try (var files = Files.list(profileDirectory())) {
            return files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .filter(file -> file.startsWith(FILE_PREFIX) && file.endsWith(FILE_SUFFIX))
                    .map(file -> file.substring(FILE_PREFIX.length(), file.length() - FILE_SUFFIX.length()))
                    .filter(name -> name.equals(name.strip()) && PROFILE_NAME.matcher(name).matches())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (NoSuchFileException exception) {
            return java.util.List.of();
        }
    }

    private static Properties capture(Minecraft client) {
        Properties profile = new Properties();
        profile.setProperty("format", VERSION);
        AimAssist.Settings aim = DashClientClient.profileAimSettings();
        profile.setProperty("aim.speed", Float.toString(aim.strength()));
        profile.setProperty("aim.smoothness", Float.toString(aim.smoothness()));
        profile.setProperty("aim.radius", Float.toString(aim.radius()));
        profile.setProperty("aim.durationMs", Integer.toString(aim.durationMs()));
        profile.setProperty("aim.mode", aim.mode().name());
        profile.setProperty("aim.auraTurnSpeed", Integer.toString(aim.auraTurnSpeed()));
        profile.setProperty("aim.auraPriority", aim.auraPriority().name());
        profile.setProperty("aim.auraCritTiming", Boolean.toString(aim.auraCritTiming()));
        profile.setProperty("velocity.force", Float.toString(Velocity.force()));
        HudSettings.Profile hud = HudSettings.profile();
        profile.setProperty("hud.scale", Float.toString(hud.scale()));
        profile.setProperty("hud.opacity", Float.toString(hud.opacity()));
        profile.setProperty("hud.hideEmpty", Boolean.toString(hud.hideEmpty()));
        profile.setProperty("hud.blur", Boolean.toString(hud.blur()));
        profile.setProperty("hud.cosmicSky", Boolean.toString(hud.cosmicSky()));
        profile.setProperty("hud.autoChunkReload", Boolean.toString(hud.autoChunkReload()));
        profile.setProperty("hud.theme", hud.theme());
        DashAppearance.writeProfile(profile);
        DashHudLayout.capture(profile);
        DashClientClient.profileModules().forEach((label, enabled) ->
                profile.setProperty("module." + label, Boolean.toString(enabled)));
        for (KeyMapping mapping : client.options.keyMappings) {
            if (isDashBinding(mapping)) {
                profile.setProperty("bind." + mapping.getName(), mapping.saveString());
            }
        }
        return profile;
    }

    private static void apply(Minecraft client, Properties profile) {
        if (!VERSION.equals(profile.getProperty("format"))) {
            throw new IllegalArgumentException("Unsupported profile format");
        }
        float speed = readFloat(profile, "aim.speed", 0.0F, 1.0F);
        float smoothness = readFloat(profile, "aim.smoothness", 0.0F, 1.0F);
        float aimRadius = profile.containsKey("aim.radius")
                ? readFloat(profile, "aim.radius", AimAssist.MIN_RADIUS, AimAssist.MAX_RADIUS)
                : AimAssist.DEFAULT_RADIUS;
        int aimDuration = Integer.parseInt(profile.getProperty("aim.durationMs",
                Integer.toString(AimAssist.DEFAULT_DURATION_MS)));
        String storedMode = profile.getProperty("aim.mode");
        AimAssist.Mode aimMode = storedMode == null ? AimAssist.Mode.SMART : AimAssist.Mode.valueOf(storedMode);
        if (storedMode == null && aimDuration > AimAssist.MAX_DURATION_MS) aimDuration = AimAssist.DEFAULT_DURATION_MS;
        if (aimDuration < AimAssist.MIN_DURATION_MS || aimDuration > AimAssist.MAX_DURATION_MS) {
            throw new IllegalArgumentException("Aim Assist duration out of range");
        }
        int auraTurnSpeed = Integer.parseInt(profile.getProperty("aim.auraTurnSpeed",
                Integer.toString(AimAssist.DEFAULT_AURA_TURN_SPEED)));
        if (auraTurnSpeed < AimAssist.MIN_AURA_TURN_SPEED || auraTurnSpeed > AimAssist.MAX_AURA_TURN_SPEED) {
            throw new IllegalArgumentException("Aura turn speed out of range");
        }
        var auraPriority = AimAssist.Priority.valueOf(profile.getProperty("aim.auraPriority",
                AimAssist.DEFAULT_AURA_PRIORITY.name()));
        boolean auraCritTiming = profile.containsKey("aim.auraCritTiming")
                ? readBoolean(profile, "aim.auraCritTiming") : AimAssist.DEFAULT_AURA_CRIT_TIMING;
        var aim = new AimAssist.Settings(speed, smoothness, aimRadius, aimDuration, aimMode, auraTurnSpeed,
                auraPriority, auraCritTiming);
        float force = readFloat(profile, "velocity.force", 0.0F, 1.0F);
        HudSettings.Profile hud = new HudSettings.Profile(
                Float.parseFloat(profile.getProperty("hud.scale", "")),
                Float.parseFloat(profile.getProperty("hud.opacity", "")),
                readBoolean(profile, "hud.hideEmpty"), readBoolean(profile, "hud.blur"),
                readBoolean(profile, "hud.cosmicSky"), readBoolean(profile, "hud.autoChunkReload"),
                profile.getProperty("hud.theme"));
        if (!HudSettings.validProfile(hud)) throw new IllegalArgumentException("Invalid HUD settings");

        Map<String, Boolean> modules = new LinkedHashMap<>();
        for (String label : DashClientClient.profileModules().keySet()) {
            String key = "module." + label;
            if (profile.containsKey(key)) modules.put(label, readBoolean(profile, key));
        }
        Map<KeyMapping, InputConstants.Key> bindings = new LinkedHashMap<>();
        for (KeyMapping mapping : client.options.keyMappings) {
            if (!isDashBinding(mapping)) continue;
            String value = profile.getProperty("bind." + mapping.getName());
            if (value != null) {
                InputConstants.Key key = readBinding(value);
                if (!mapping.saveString().equals(value)) bindings.put(mapping, key);
            }
        }

        DashClientClient.applyProfileAim(aim);
        HudSettings.applyProfile(hud);
        Velocity.setForce(force);
        DashClientClient.applyProfileModules(modules);
        DashAppearance.applyProfile(profile);
        DashHudLayout.apply(profile);
        if (!bindings.isEmpty()) {
            bindings.forEach((mapping, key) -> {
                mapping.setDown(false);
                while (mapping.consumeClick()) {
                    // Clear inputs queued on the previous binding.
                }
                mapping.setKey(key);
            });
            KeyMapping.resetMapping();
            client.options.save();
        }
    }

    private static boolean isDashBinding(KeyMapping mapping) {
        return mapping.getName().startsWith("key.dash-client.");
    }

    private static InputConstants.Key readBinding(String value) {
        try {
            InputConstants.Key key = InputConstants.getKey(value);
            int code = key.getValue();
            boolean validCode = switch (key.getType()) {
                case KEYSYM -> key.equals(InputConstants.UNKNOWN)
                        || code >= GLFW.GLFW_KEY_SPACE && code <= GLFW.GLFW_KEY_LAST;
                case SCANCODE -> code >= 0;
                case MOUSE -> code >= 0 && code <= GLFW.GLFW_MOUSE_BUTTON_LAST;
            };
            if (!validCode || !key.getName().equals(value)) throw new IllegalArgumentException("Invalid key binding");
            return key;
        } catch (IndexOutOfBoundsException exception) {
            throw new IllegalArgumentException("Invalid key binding", exception);
        }
    }

    private static boolean readBoolean(Properties profile, String key) {
        String value = profile.getProperty(key, "");
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Invalid flag " + key);
        };
    }

    private static float readFloat(Properties profile, String key, float min, float max) {
        float value = Float.parseFloat(profile.getProperty(key, ""));
        if (!Float.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException("Invalid value for " + key);
        }
        return value;
    }

    private static void write(Path file, Properties profile) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "dash-profile-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) {
                profile.store(writer, "Dash Client profile");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void message(Minecraft client, String text) {
        client.gui.getChat().addMessage(Component.literal("[Dash Config] " + text));
    }
}
