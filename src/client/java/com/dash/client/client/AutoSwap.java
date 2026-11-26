package com.dash.client.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Chooses an offhand accessory after confirmed combat events; never generates attacks. */
public final class AutoSwap {
    private static final long DEFENSE_NANOS = 1_500_000_000L;
    private static final long OFFENSE_NANOS = 3_000_000_000L;
    private static final long SWAP_NANOS = 350_000_000L;
    private static final long HIT_UPDATE_NANOS = 350_000_000L;
    private static final Identifier ABSORPTION_EFFECT = Identifier.withDefaultNamespace("effect.absorption");
    private static final double ABSORPTION_IV_HEALTH = 16.0;
    private static boolean enabled = false;
    private static String attackItem = "";
    private static String defenseItem = "";
    private static LocalPlayer owner;
    private static AbsorptionHit absorptionHit;
    private static Player offenseTarget;
    private static long defenseUntil;
    private static long offenseUntil;
    private static long nextSwapAt;
    private static long nextScanAt;
    private static boolean lastSwapWasDefense;
    private static String status = "Очікування бою";

    private AutoSwap() {}

    static boolean isEnabled() { return enabled; }

    static void toggle() {
        enabled = !enabled;
        reset();
    }

    static void disable() {
        enabled = false;
        reset();
    }

    static void reset() {
        owner = null;
        absorptionHit = null;
        clearOffense();
        defenseUntil = nextSwapAt = nextScanAt = 0;
        lastSwapWasDefense = false;
        status = "Очікування бою";
    }

    public static void onDamage(ClientboundDamageEventPacket packet) {
        Minecraft client = Minecraft.getInstance();
        if (!active(client)) return;
        bind(client.player);
        long now = System.nanoTime();
        boolean anonymous = packet.sourceType().is(DamageTypes.GENERIC)
                && packet.sourceCauseId() < 0 && packet.sourceDirectId() < 0
                && packet.sourcePosition().isEmpty();
        if (packet.entityId() == owner.getId()) {
            Entity attacker = client.level.getEntity(packet.sourceCauseId());
            if (attacker instanceof Player && attacker != owner || anonymous
                    || packet.sourceType().is(DamageTypeTags.IS_PLAYER_ATTACK)
                    || packet.sourceType().is(DamageTypeTags.IS_PROJECTILE)) {
                if (now >= defenseUntil || !lastSwapWasDefense) nextScanAt = 0;
                // A hit can interrupt the offensive swap cooldown. Repeated defensive
                // swaps still respect the cooldown if a server rejects a click.
                if (!lastSwapWasDefense) nextSwapAt = 0;
                defenseUntil = now + DEFENSE_NANOS;
                absorptionHit = null;
                clearOffense();
            }
            return;
        }
        if (!(client.level.getEntity(packet.entityId()) instanceof Player target)) return;
        // Metadata may combine several hits. A later hit from another source
        // invalidates attribution to the previous hit.
        if (absorptionHit != null && absorptionHit.target() == target) absorptionHit = null;
        float absorption = target.getAbsorptionAmount();
        if (packet.sourceCauseId() == owner.getId() && absorption > 0
                && !Friends.isFriendPlayer(target) && hasAbsorptionIV(target)) {
            absorptionHit = new AbsorptionHit(target, absorption, now);
        }
    }

    private static boolean hasAbsorptionIV(Player target) {
        var attribute = target.getAttribute(Attributes.MAX_ABSORPTION);
        var modifier = attribute == null ? null : attribute.getModifier(ABSORPTION_EFFECT);
        return modifier != null && modifier.operation() == AttributeModifier.Operation.ADD_VALUE
                && modifier.amount() == ABSORPTION_IV_HEALTH;
    }

    private static void checkAbsorptionHit(Minecraft client, long now) {
        if (absorptionHit == null) return;
        Player target = absorptionHit.target();
        if (now - absorptionHit.time() > HIT_UPDATE_NANOS || !tracksTarget(client, target)
                || !hasAbsorptionIV(target)) {
            absorptionHit = null;
            return;
        }
        // The damage event arrives before the server's absorption metadata.
        // Check after processing packets so an expired effect does not look like a hit.
        float absorption = target.getAbsorptionAmount();
        if (absorption == absorptionHit.amount()) return;
        absorptionHit = null;
        if (absorption <= 0) {
            offenseTarget = target;
            offenseUntil = now + OFFENSE_NANOS;
            nextScanAt = 0;
        }
    }

    private static boolean tracksTarget(Minecraft client, Player target) {
        return target.isAlive() && client.level.getEntity(target.getId()) == target
                && !Friends.isFriendPlayer(target);
    }

    private static void clearOffense() {
        offenseTarget = null;
        offenseUntil = 0;
    }

    static void tick(Minecraft client) {
        if (!active(client)) {
            reset();
            return;
        }
        bind(client.player);
        if (owner.getMainArm() != HumanoidArm.RIGHT) {
            status = "Для свапу лівої руки вибери основну руку «Права».";
            return;
        }
        long now = System.nanoTime();
        checkAbsorptionHit(client, now);
        if (offenseTarget != null && (now >= offenseUntil || !tracksTarget(client, offenseTarget)
                || offenseTarget.getAbsorptionAmount() > 0)) {
            clearOffense();
        }
        if (client.screen != null || client.gameMode == null || client.getConnection() == null
                || owner.containerMenu != owner.inventoryMenu || !owner.inventoryMenu.getCarried().isEmpty()
                || owner.isUsingItem() || AutoBuy.isEnabled() || AutoSell.isEnabled()
                || InventoryBindSupport.busy(client) || SwapBind.reservesOffhand(client)) return;
        if (DashClientClient.reserveOffhandForTotem(owner)) {
            status = "Пріоритет Auto Totem";
            return;
        }
        ItemStack current = owner.getOffhandItem();
        if (current.is(Items.TOTEM_OF_UNDYING) && !AutoSwapItems.isAccessory(current)) {
            status = "Тотем у другій руці";
            return;
        }
        if (now >= Math.max(defenseUntil, offenseUntil)) {
            status = "Очікування бою";
            return;
        }
        if (now < nextSwapAt || now < nextScanAt) return;
        nextScanAt = now + 250_000_000L;
        boolean defense = now < defenseUntil || owner.getHealth() + owner.getAbsorptionAmount() <= 8;
        if (!defense) clearOffense();
        String preferred = defense ? defenseItem : attackItem;
        String mode = defense ? "Захист" : "Атака";
        if (!current.isEmpty() && !AutoSwapItems.isAccessory(current)) {
            status = "У лівій руці має бути сфера або талісман";
            return;
        }
        if (!preferred.isEmpty() && key(current).equals(preferred)) {
            status = mode + ": " + current.getHoverName().getString();
            return;
        }
        double best = preferred.isEmpty() ? AutoSwapItems.score(owner, current, defense) : Double.NaN;
        if (preferred.isEmpty() && !Double.isFinite(best)) {
            status = mode + ": бонуси поточного предмета невідомі — обери пару";
            return;
        }
        Inventory inventory = owner.getInventory();
        int selectedSlot = inventory.getSelectedSlot();
        int slot = -1;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (i == selectedSlot) continue;
            ItemStack item = inventory.getItem(i);
            if (!AutoSwapItems.isAccessory(item)) continue;
            if (!preferred.isEmpty()) {
                if (key(item).equals(preferred)) { slot = i; break; }
            } else {
                double score = AutoSwapItems.score(owner, item, defense);
                if (Double.isFinite(score) && (!Double.isFinite(best) || score > best + .001)) {
                    best = score;
                    slot = i;
                }
            }
        }
        if (slot < 0) {
            status = preferred.isEmpty() ? mode + ": поточний предмет або вибери пару вручну"
                    : mode + ": обраного предмета немає в інвентарі";
            return;
        }
        ItemStack chosen = inventory.getItem(slot);
        if (ItemStack.isSameItemSameComponents(current, chosen)) return;
        int menuSlot = Inventory.isHotbarSlot(slot) ? slot + 36 : slot;
        client.gameMode.handleInventoryMouseClick(owner.inventoryMenu.containerId, menuSlot,
                Inventory.SLOT_OFFHAND, ClickType.SWAP, owner);
        nextSwapAt = now + SWAP_NANOS;
        lastSwapWasDefense = defense;
        status = mode + ": " + chosen.getHoverName().getString();
    }

    private static boolean active(Minecraft client) {
        return enabled && !SaveMode.isPaused() && client.player != null && client.level != null
                && client.player.isAlive() && !client.player.isSpectator();
    }

    private static void bind(LocalPlayer player) {
        if (owner != player) {
            reset();
            owner = player;
        }
    }

    static String status() { return status; }

    static String preference(boolean defense) { return defense ? defenseItem : attackItem; }

    static String key(ItemStack item) {
        if (item.isEmpty()) return "";
        return BuiltInRegistries.ITEM.getKey(item.getItem()) + "|" + FunTimeAuction.plain(item.getHoverName().getString());
    }

    static List<Choice> choices(Minecraft client) {
        Map<String, Choice> options = new LinkedHashMap<>();
        options.put("", new Choice("", "Автоматично за бонусами"));
        for (String saved : List.of(attackItem, defenseItem)) {
            if (!saved.isEmpty()) options.put(saved, new Choice(saved, saved.substring(saved.indexOf('|') + 1)));
        }
        if (client.player != null) {
            Inventory inventory = client.player.getInventory();
            for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
                if (i != inventory.getSelectedSlot()) addChoice(options, inventory.getItem(i));
            }
            addChoice(options, client.player.getOffhandItem());
        }
        return List.copyOf(options.values());
    }

    private static void addChoice(Map<String, Choice> options, ItemStack item) {
        if (AutoSwapItems.isAccessory(item)) {
            String key = key(item);
            options.put(key, new Choice(key, item.getHoverName().getString()));
        }
    }

    static void select(boolean defense, String key) {
        key = accessoryPreference(key);
        if (defense) defenseItem = key;
        else attackItem = key;
        nextScanAt = 0;
        Properties settings = new Properties();
        settings.setProperty("attack", attackItem);
        settings.setProperty("defense", defenseItem);
        try {
            Files.createDirectories(file().getParent());
            try (Writer writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                settings.store(writer, "Dash AutoSwap offhand choices");
            }
        } catch (IOException error) {
            Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[Dash] Не вдалося зберегти пару AutoSwap."));
        }
    }

    static void load() {
        if (!Files.isRegularFile(file())) return;
        try (Reader reader = Files.newBufferedReader(file(), StandardCharsets.UTF_8)) {
            Properties settings = new Properties();
            settings.load(reader);
            attackItem = accessoryPreference(settings.getProperty("attack", ""));
            defenseItem = accessoryPreference(settings.getProperty("defense", ""));
        } catch (IOException | IllegalArgumentException ignored) {
            attackItem = defenseItem = "";
        }
    }

    private static String accessoryPreference(String key) {
        int separator = key.indexOf('|');
        return separator > 0 && AutoSwapItems.isAccessoryName(key.substring(separator + 1)) ? key : "";
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("dash-autoswap.properties");
    }

    record Choice(String key, String label) {}
    private record AbsorptionHit(Player target, float amount, long time) {}
}
