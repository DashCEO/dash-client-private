package com.dash.client.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;
import java.util.function.BiPredicate;
import java.util.function.IntPredicate;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

final class InventoryBindSupport {
    static final int RULE_COUNT = 8;
    private static LocalPlayer reservedPlayer;
    private static long reserveUntil;

    private InventoryBindSupport() {}

    static KeyMapping[] registerKeys(String group, BiPredicate<Minecraft, Integer> onPress) {
        KeyMapping[] result = new KeyMapping[RULE_COUNT];
        for (int i = 0; i < result.length; i++) {
            int index = i;
            result[i] = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                    "key.dash-client." + group + "." + (i + 1), InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_UNKNOWN, KeyMapping.Category.MISC));
            DashBindInput.register(result[i], client -> onPress.test(client, index));
        }
        return result;
    }

    static boolean assign(Minecraft client, KeyMapping mapping, KeyEvent event) {
        return assign(client, mapping, InputConstants.getKey(event));
    }

    static boolean assign(Minecraft client, KeyMapping mapping, InputConstants.Key key) {
        if (InputConstants.UNKNOWN.equals(key) || hasConflict(client, mapping, key)) return false;
        setKey(client, mapping, key);
        return true;
    }

    static void unbind(Minecraft client, KeyMapping mapping) {
        setKey(client, mapping, InputConstants.UNKNOWN);
    }

    private static boolean hasConflict(Minecraft client, KeyMapping own, InputConstants.Key key) {
        for (KeyMapping mapping : client.options.keyMappings) {
            if (mapping != own && SaveMode.isManagedBinding(mapping) && !mapping.isUnbound()
                    && KeyBindingHelper.getBoundKeyOf(mapping).equals(key)) {
                return true;
            }
        }
        return false;
    }

    static boolean conflicts(Minecraft client, KeyMapping mapping) {
        return !mapping.isUnbound() && hasConflict(client, mapping, KeyBindingHelper.getBoundKeyOf(mapping));
    }

    private static void setKey(Minecraft client, KeyMapping mapping, InputConstants.Key key) {
        mapping.setKey(key);
        mapping.setDown(false);
        while (mapping.consumeClick()) {
            // A queued press belongs to the old key.
        }
        KeyMapping.resetMapping();
        client.options.save();
    }

    static boolean canInteract(Minecraft client) {
        LocalPlayer player = client.player;
        return player != null && player.isAlive() && !player.isSpectator() && client.level != null
                && client.gameMode != null && client.getConnection() != null && client.screen == null
                && client.getOverlay() == null && !client.isPaused() && !SaveMode.isPaused()
                && client.isWindowActive() && player.containerMenu == player.inventoryMenu
                && player.inventoryMenu.getCarried().isEmpty() && !AutoBuy.isEnabled() && !AutoSell.isEnabled();
    }

    static boolean busy(Minecraft client) {
        return ConsumableBind.busy(client) || reservationActive(client);
    }

    static boolean reservationActive(Minecraft client) {
        return SwapBind.serializing(client)
                || reservedPlayer != null && reservedPlayer == client.player && System.nanoTime() < reserveUntil;
    }

    static void reserve(Minecraft client, long nanos) {
        reservedPlayer = client.player;
        reserveUntil = System.nanoTime() + nanos;
    }

    static void clearReservation() {
        reservedPlayer = null;
        reserveUntil = 0;
    }

    static boolean swap(Minecraft client, int inventorySlot, int button) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null || client.getConnection() == null
                || !canSwap(player, inventorySlot, button)) return false;
        client.gameMode.handleInventoryMouseClick(
                player.inventoryMenu.containerId, menuSlot(inventorySlot), button, ClickType.SWAP, player);
        return true;
    }

    static boolean canSwap(LocalPlayer player, int source, int destination) {
        InventoryMenu menu = player.inventoryMenu;
        if (player.containerMenu != menu || !menu.getCarried().isEmpty() || source == destination
                || source < 0 || source >= Inventory.INVENTORY_SIZE
                || !(Inventory.isHotbarSlot(destination) || destination == Inventory.SLOT_OFFHAND)) return false;

        int sourceId = menuSlot(source);
        int destinationId = menuSlot(destination);
        if (sourceId >= menu.slots.size() || destinationId >= menu.slots.size()) return false;
        Inventory inventory = player.getInventory();
        Slot sourceSlot = menu.getSlot(sourceId);
        Slot destinationSlot = menu.getSlot(destinationId);
        if (sourceSlot.container != inventory || sourceSlot.getContainerSlot() != source
                || destinationSlot.container != inventory || destinationSlot.getContainerSlot() != destination) return false;

        ItemStack from = sourceSlot.getItem();
        ItemStack to = destinationSlot.getItem();
        return (!from.isEmpty() || !to.isEmpty())
                && canTransfer(player, sourceSlot, destinationSlot, from)
                && canTransfer(player, destinationSlot, sourceSlot, to);
    }

    private static boolean canTransfer(LocalPlayer player, Slot source, Slot destination, ItemStack stack) {
        return stack.isEmpty() || source.mayPickup(player) && destination.mayPlace(stack)
                && stack.getCount() <= destination.getMaxStackSize(stack);
    }

    static int menuSlot(int inventorySlot) {
        if (inventorySlot == Inventory.SLOT_OFFHAND) return InventoryMenu.SHIELD_SLOT;
        return Inventory.isHotbarSlot(inventorySlot) ? inventorySlot + InventoryMenu.USE_ROW_SLOT_START : inventorySlot;
    }

    static int find(Inventory inventory, ItemChoice item, IntPredicate eligible) {
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (item.matches(inventory.getItem(slot)) && eligible.test(slot)) return slot;
        }
        return -1;
    }

    static Path configFile(String name) {
        return FabricLoader.getInstance().getConfigDir().resolve(name);
    }

    static Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        if (Files.isRegularFile(path)) {
            try (var reader = Files.newBufferedReader(path)) {
                properties.load(reader);
            }
        }
        return properties;
    }

    static void save(Path path, Properties properties, String comment) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, comment);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static final class ItemChoice {
        private final Identifier id;
        private final String customName;
        private final JsonElement variantJson;
        private RegistryAccess resolvedIn;
        private DataComponentPatch resolved;

        private ItemChoice(Identifier id, String customName, JsonElement variantJson) {
            this.id = id;
            this.customName = customName;
            this.variantJson = variantJson;
        }

        static ItemChoice of(ItemStack stack) {
            if (stack.isEmpty()) return null;
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String name = explicitName(stack);
            DataComponentPatch variant = variant(stack);
            if (variant.isEmpty()) return new ItemChoice(id, name, JsonNull.INSTANCE);

            var level = Minecraft.getInstance().level;
            if (level == null) return null;
            var registries = level.registryAccess();
            JsonElement encoded = DataComponentPatch.CODEC
                    .encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), variant).result().orElse(null);
            if (encoded == null) return null;
            ItemChoice choice = new ItemChoice(id, name, encoded);
            choice.resolvedIn = registries;
            choice.resolved = variant;
            return choice;
        }

        private static String explicitName(ItemStack stack) {
            Component name = stack.get(DataComponents.CUSTOM_NAME);
            if (name == null) {
                var patchedName = stack.getComponentsPatch().get(DataComponents.ITEM_NAME);
                if (patchedName != null) name = patchedName.orElse(null);
            }
            return name == null ? "" : name.getString();
        }

        boolean matches(ItemStack stack) {
            if (stack.isEmpty() || !id.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                    || !customName.equals(explicitName(stack))) return false;
            DataComponentPatch expected = resolve();
            return expected != null && expected.equals(variant(stack));
        }

        boolean matchesNamedStack(ItemStack stack) {
            return !customName.isEmpty() && matches(stack);
        }

        private static DataComponentPatch variant(ItemStack stack) {
            return stack.getComponentsPatch().forget(type -> type != DataComponents.POTION_CONTENTS
                    && type != DataComponents.SUSPICIOUS_STEW_EFFECTS && type != DataComponents.FIREWORKS);
        }

        private DataComponentPatch resolve() {
            if (variantJson == null || variantJson.isJsonNull()) return DataComponentPatch.EMPTY;
            var level = Minecraft.getInstance().level;
            if (level == null) return null;
            var registries = level.registryAccess();
            if (resolvedIn != registries) {
                resolved = DataComponentPatch.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), variantJson)
                        .result().orElse(null);
                resolvedIn = registries;
            }
            return resolved;
        }

        String label() {
            if (!customName.isEmpty()) return customName;
            ItemStack preview = BuiltInRegistries.ITEM.getValue(id).getDefaultInstance();
            DataComponentPatch variant = resolve();
            if (variant != null && !variant.isEmpty()) preview.applyComponents(variant);
            return preview.getHoverName().getString();
        }

        static ItemChoice load(Properties properties, String prefix) {
            String value = properties.getProperty(prefix + ".item", "");
            Identifier key = Identifier.tryParse(value);
            if (value.isEmpty() || key == null || !BuiltInRegistries.ITEM.containsKey(key)) return null;
            String storedVariant = properties.getProperty(prefix + ".variant");
            try {
                JsonElement variant = storedVariant == null ? JsonNull.INSTANCE : JsonParser.parseString(storedVariant);
                if (!variant.isJsonNull() && !variant.isJsonObject()) return null;
                return new ItemChoice(key, properties.getProperty(prefix + ".name", ""), variant);
            } catch (JsonParseException ignored) {
                return null;
            }
        }

        void save(Properties properties, String prefix) {
            properties.setProperty(prefix + ".item", id.toString());
            properties.setProperty(prefix + ".name", customName);
            properties.setProperty(prefix + ".variant", variantJson.toString());
        }

        @Override public boolean equals(Object other) {
            return other instanceof ItemChoice choice && id.equals(choice.id) && customName.equals(choice.customName)
                    && variantJson.equals(choice.variantJson);
        }

        @Override public int hashCode() {
            return Objects.hash(id, customName, variantJson);
        }
    }
}
