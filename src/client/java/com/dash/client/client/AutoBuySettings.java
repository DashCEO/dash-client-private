package com.dash.client.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AutoBuySettings {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long LEGACY_MAX_TOLERANCE = 20_000L;
    private static List<Rule> rules = List.of();
    private static long revision;

    private AutoBuySettings() {}

    public enum PriceMode {
        PER_ITEM,
        PER_LOT
    }

    public enum QuantityMode {
        ANY,
        SINGLE,
        FULL_STACK;

        public boolean matches(ItemStack stack) {
            return switch (this) {
                case ANY -> !stack.isEmpty();
                case SINGLE -> stack.getCount() == 1;
                case FULL_STACK -> stack.getMaxStackSize() > 1 && stack.getCount() == stack.getMaxStackSize();
            };
        }
    }

    public record Rule(String id, String itemId, String label, String stackJson,
                       long minPrice, long maxPrice, PriceMode priceMode,
                       QuantityMode quantityMode, boolean enabled) {
        public Rule(String id, String itemId, String label, String stackJson,
                    long minPrice, long maxPrice, PriceMode priceMode, boolean enabled) {
            this(id, itemId, label, stackJson, minPrice, maxPrice, priceMode, QuantityMode.ANY, enabled);
        }

        public Rule {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Правило має містити ID.");
            if (itemId == null || Identifier.tryParse(itemId) == null) {
                throw new IllegalArgumentException("Невідомий ID предмета.");
            }
            if (label == null || label.isBlank()) label = itemId;
            if (stackJson != null && stackJson.isBlank()) stackJson = null;
            if (minPrice < 0) {
                throw new IllegalArgumentException("Мінімальна ціна має бути від 0.");
            }
            if (maxPrice < 1 || minPrice > maxPrice) {
                throw new IllegalArgumentException("Максимальна ціна має бути додатною та не меншою за мінімальну.");
            }
            if (priceMode == null) throw new IllegalArgumentException("Вибери спосіб розрахунку ціни.");
            if (quantityMode == null) quantityMode = QuantityMode.ANY;
        }
    }

    public static List<Rule> rules() {
        return rules;
    }

    public static long revision() {
        return revision;
    }

    public static void load() {
        Path file = file();
        if (!Files.isRegularFile(file)) return;
        try (var reader = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            int version = root.has("version") ? root.get("version").getAsInt() : 0;
            if (version != 1 && version != 2) {
                throw new IllegalArgumentException("Unsupported AutoBuy settings version");
            }
            List<Rule> loaded = new ArrayList<>();
            JsonArray entries = root.getAsJsonArray("rules");
            if (entries == null) throw new IllegalArgumentException("Missing AutoBuy rules");
            for (JsonElement entry : entries) {
                try {
                    Rule rule = readRule(entry, version);
                    if (rule == null || loaded.stream().anyMatch(existing -> existing.id().equals(rule.id()))) {
                        throw new IllegalArgumentException("Invalid or duplicated AutoBuy rule");
                    }
                    loaded.add(rule);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Could not read an AutoBuy rule", exception);
                }
            }
            rules = List.copyOf(loaded);
            revision++;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not read Dash AutoBuy settings", exception);
        }
    }

    private static Rule readRule(JsonElement entry, int version) {
        JsonObject object = entry.getAsJsonObject();
        if (version == 1) {
            long price = object.get("price").getAsBigDecimal().longValueExact();
            long tolerance = object.has("tolerance") && !object.get("tolerance").isJsonNull()
                    ? object.get("tolerance").getAsBigDecimal().longValueExact() : 0;
            if (price < 1 || tolerance < 0 || tolerance > LEGACY_MAX_TOLERANCE) {
                throw new IllegalArgumentException("Invalid legacy AutoBuy price or tolerance");
            }
            object = object.deepCopy();
            object.remove("price");
            object.remove("tolerance");
            // Old rules had only an upper limit; retain that behavior until the user sets a minimum.
            object.addProperty("minPrice", 0);
            object.addProperty("maxPrice", Math.addExact(price, tolerance));
        } else {
            if (object.has("minPrice") && !object.get("minPrice").isJsonNull()) {
                object.get("minPrice").getAsBigDecimal().longValueExact();
            }
            object.get("maxPrice").getAsBigDecimal().longValueExact();
        }
        if (object.has("quantityMode")) QuantityMode.valueOf(object.get("quantityMode").getAsString());
        return GSON.fromJson(object, Rule.class);
    }

    public static void put(Rule rule) throws IOException {
        if (rule == null) throw new IllegalArgumentException("Правило відсутнє.");
        List<Rule> updated = new ArrayList<>(rules);
        int index = -1;
        for (int i = 0; i < updated.size(); i++) {
            if (updated.get(i).id().equals(rule.id())) {
                index = i;
                break;
            }
        }
        if (index < 0) updated.add(rule);
        else updated.set(index, rule);
        write(updated);
        rules = List.copyOf(updated);
        revision++;
    }

    public static void remove(String id) throws IOException {
        List<Rule> updated = new ArrayList<>(rules);
        if (!updated.removeIf(rule -> rule.id().equals(id))) return;
        write(updated);
        rules = List.copyOf(updated);
        revision++;
    }

    public static void save() throws IOException {
        write(rules);
    }

    public static Rule createRangeRule(ItemStack stack, long minPrice, long maxPrice, PriceMode mode, boolean exact) {
        return createRangeRule(stack, minPrice, maxPrice, mode, QuantityMode.ANY, exact);
    }

    public static Rule createRangeRule(ItemStack stack, long minPrice, long maxPrice, PriceMode mode,
                                       QuantityMode quantityMode, boolean exact) {
        if (stack == null || stack.isEmpty()) throw new IllegalArgumentException("Спочатку вибери предмет.");
        String snapshot = null;
        if (exact) {
            Minecraft client = Minecraft.getInstance();
            if (client.level == null) throw new IllegalArgumentException("Додай предмет із руки після входу у світ.");
            ItemStack template = stack.copy();
            template.setCount(1);
            template.remove(DataComponents.LORE);
            snapshot = ItemStack.CODEC.encodeStart(client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE),
                    template).getOrThrow().toString();
        }
        return new Rule(UUID.randomUUID().toString(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getHoverName().getString(), snapshot, minPrice, maxPrice, mode, quantityMode, true);
    }

    private static void write(List<Rule> value) throws IOException {
        Path file = file();
        Files.createDirectories(file.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
        root.add("rules", GSON.toJsonTree(value));
        Path temporary = Files.createTempFile(file.getParent(), "dash-autobuy-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException exception) {
                LOGGER.debug("Could not remove temporary AutoBuy settings", exception);
            }
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("dash-autobuy.json");
    }
}
