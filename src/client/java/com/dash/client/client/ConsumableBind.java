package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import com.dash.client.client.InventoryBindSupport.ItemChoice;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

public final class ConsumableBind {
    static final int MAX_DELAY_MS = 2000;
    private static final int DEFAULT_DELAY_MS = 10;
    private static final long NANOS_PER_MS = 1_000_000L;
    private static final long MIN_SETTLE_NANOS = 100_000_000L;
    private static final long INSTANT_RESPONSE_NANOS = 250_000_000L;
    private static final long ITEM_LOOKUP_WINDOW_NANOS = 1_000_000_000L;
    private static final long RECOVERY_WINDOW_NANOS = 2_000_000_000L;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Rule EMPTY = new Rule(null, DEFAULT_DELAY_MS);
    private static final Rule[] rules = new Rule[InventoryBindSupport.RULE_COUNT];
    private static KeyMapping[] keys;
    private static boolean enabled = true;
    private static Request request;
    private static Use pending;

    static {
        Arrays.fill(rules, EMPTY);
    }

    private ConsumableBind() {}

    static void register() {
        keys = InventoryBindSupport.registerKeys("consumable-bind", (client, index) -> start(client, rules[index]));
        load();
    }

    static void tick(Minecraft client) {
        if (pending != null) {
            advance(client, pending);
        } else if (request != null) {
            long now = System.nanoTime();
            if (client.player != request.owner() || !enabled || !InventoryBindSupport.canInteract(client)
                    || now >= request.expiresAt()) {
                request = null;
            } else if (now >= request.readyAt() && !InventoryBindSupport.reservationActive(client)
                    && !client.player.isHandsBusy()) {
                int source = findUsableSlot(client, request.item());
                if (source >= 0) {
                    ItemChoice item = request.item();
                    request = null;
                    beginUse(client, item, source, now);
                }
            }
        }
    }

    static boolean busy(Minecraft client) {
        return pending != null && pending.owner == client.player || request != null && request.owner() == client.player;
    }

    static boolean hasPendingPauseCleanup() {
        return pending != null;
    }

    static boolean hasStartedUse(Minecraft client) {
        return pending != null && pending.owner == client.player && !pending.restoring;
    }

    static void reset() {
        request = null;
        pending = null;
    }

    static void abort(Minecraft client) {
        request = null;
        Use use = pending;
        if (use == null) return;
        if (!isCurrentPlayer(client, use)) {
            reset();
            return;
        }
        long now = System.nanoTime();
        if (!use.restoring && ownsUse(use)) client.gameMode.releaseUsingItem(use.owner);
        beginRestore(client, use, now);
    }

    static void finishPause(Minecraft client) {
        try {
            if (pending != null && pending.restoring) {
                advance(client, pending);
            } else {
                reset();
            }
        } catch (RuntimeException exception) {
            reset();
            throw exception;
        } finally {
            if (pending == null) InventoryBindSupport.clearReservation();
        }
    }

    public static boolean holdsUse(Minecraft client, Player player) {
        Use use = pending;
        return use != null && use.owner == player && !use.restoring && enabled && isCurrentPlayer(client, use)
                && InventoryBindSupport.canInteract(client) && selected(use) && ownsUse(use)
                && System.nanoTime() < use.finishAt;
    }

    public static boolean blocksHandActions(Minecraft client) {
        Use use = pending;
        return use != null && !use.restoring && enabled && isCurrentPlayer(client, use)
                && InventoryBindSupport.canInteract(client) && selected(use) && (use.swapped() || ownsUse(use));
    }

    private static boolean start(Minecraft client, Rule rule) {
        if (!enabled || rule.item() == null || !InventoryBindSupport.canInteract(client)
                || busy(client)) return false;
        long readyAt = System.nanoTime() + rule.delayMs() * NANOS_PER_MS;
        request = new Request(client.player, rule.item(), readyAt, readyAt + ITEM_LOOKUP_WINDOW_NANOS);
        return true;
    }

    private static int findUsableSlot(Minecraft client, ItemChoice item) {
        LocalPlayer owner = client.player;
        Inventory inventory = owner.getInventory();
        int selected = inventory.getSelectedSlot();
        ItemStack held = inventory.getItem(selected);
        if (item.matches(held) && canUse(owner, held)) return selected;
        ItemStack offhand = owner.getOffhandItem();
        if (item.matches(offhand) && canUse(owner, offhand)) {
            return Inventory.SLOT_OFFHAND;
        }
        return InventoryBindSupport.find(inventory, item, slot -> canUse(owner, inventory.getItem(slot)));
    }

    private static boolean canUse(LocalPlayer owner, ItemStack stack) {
        if (stack.isEmpty() || !stack.isItemEnabled(owner.level().enabledFeatures())
                || owner.getCooldowns().isOnCooldown(stack)) return false;
        var consumable = stack.get(DataComponents.CONSUMABLE);
        // A single bind may hold food or drink to completion. Charge-up weapons and shields are separate inputs.
        if (consumable == null) return stack.getUseDuration(owner) <= 0;
        var patch = stack.getComponentsPatch();
        boolean custom = stack.has(DataComponents.CUSTOM_NAME) || stack.has(DataComponents.CUSTOM_DATA)
                || patch.get(DataComponents.ITEM_NAME) != null || patch.get(DataComponents.CUSTOM_MODEL_DATA) != null;
        return custom || consumable.canConsume(owner, stack);
    }

    private static void beginUse(Minecraft client, ItemChoice item, int source, long now) {
        LocalPlayer owner = client.player;
        Inventory inventory = owner.getInventory();
        InteractionHand hand = source == Inventory.SLOT_OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        int target = hand == InteractionHand.OFF_HAND ? source : inventory.getSelectedSlot();
        ItemStack inserted = inventory.getItem(source).copy();
        Use use = new Use(owner, hand, item, source, target, inventory.getItem(target).copy(), inserted, now);
        boolean alreadyUsing = source == target && ownsUse(use);
        if (!alreadyUsing && owner.isUsingItem()) client.gameMode.releaseUsingItem(owner);
        if (client.gameMode.isDestroying()) client.gameMode.stopDestroyBlock();
        if (use.swapped()) {
            if (!InventoryBindSupport.swap(client, source, target)) return;
            if (!ItemStack.matches(inventory.getItem(source), use.displaced)
                    || !ItemStack.matches(inventory.getItem(target), inserted)) {
                pending = use;
                beginRestore(client, use, now);
                return;
            }
        }
        pending = use;
        if (!alreadyUsing) {
            InteractionResult result = client.gameMode.useItem(owner, hand);
            if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
                owner.swing(hand);
            }
        }
        use.wasUsing = ownsUse(use);
    }

    private static void advance(Minecraft client, Use use) {
        if (!isCurrentPlayer(client, use)) {
            reset();
            return;
        }
        long now = System.nanoTime();
        if (use.restoring) {
            restore(client, use, now);
            return;
        }
        if (!enabled || !InventoryBindSupport.canInteract(client) || !selected(use) || now >= use.finishAt) {
            abort(client);
            return;
        }
        ItemStack active = use.owner.getInventory().getItem(use.target);
        if (ownsUse(use)) {
            // A server update may move the displaced stack while the original use is still active.
            use.wasUsing = true;
            use.countBeforeFinish = use.owner.getUseItem().getCount();
            return;
        }
        if (!expectedActive(use, active)) {
            beginRestore(client, use, now);
            return;
        }
        if (use.wasUsing) {
            use.wasUsing = false;
            use.restoreAt = now + MIN_SETTLE_NANOS;
            use.responseAt = now + INSTANT_RESPONSE_NANOS;
        }
        boolean confirmed = active.isEmpty() || matchesRemainder(use, active)
                || active.getCount() < use.countBeforeFinish || use.owner.getCooldowns().isOnCooldown(active);
        if (!use.owner.isUsingItem() && now >= use.restoreAt && (confirmed || now >= use.responseAt)) {
            if (!confirmed) overlay(client, text("Використання предмета не підтвердилось.",
                    "Использование предмета не подтвердилось.", "Item use was not confirmed."));
            beginRestore(client, use, now);
        }
    }

    private static void beginRestore(Minecraft client, Use use, long now) {
        if (!use.restoring) {
            use.restoring = true;
            use.finishAt = now + RECOVERY_WINDOW_NANOS;
        }
        restore(client, use, now);
    }

    private static void restore(Minecraft client, Use use, long now) {
        if (!use.swapped()) {
            finish(client);
            return;
        }
        if (now >= use.finishAt) {
            LOGGER.debug("Consumable Bind could not safely restore the original active stack to slot {}", use.target);
            finish(client);
            return;
        }
        LocalPlayer owner = use.owner;
        if (owner.isUsingItem() || client.screen instanceof AbstractContainerScreen<?>
                || owner.containerMenu != owner.inventoryMenu || !owner.inventoryMenu.getCarried().isEmpty()) return;

        Inventory inventory = owner.getInventory();
        ItemStack active = inventory.getItem(use.target);
        if (ItemStack.matches(active, use.displaced) || !use.displaced.isEmpty()
                && ItemStack.matches(inventory.getSelectedItem(), use.displaced)) {
            finish(client);
            return;
        }
        if (!expectedActive(use, active)) return;

        int source = findRestoreSlot(use, inventory);
        if (source < 0) return;
        ItemStack remainder = active.copy();
        if (!InventoryBindSupport.swap(client, source, use.target)) return;
        if (!ItemStack.matches(inventory.getItem(use.target), use.displaced)
                || !ItemStack.matches(inventory.getItem(source), remainder)) {
            LOGGER.debug("Consumable Bind restore did not predict a complete exchange from {} to {}", source, use.target);
        }
        // Native inventory packets reconcile the exchange. Do not resend a click already issued.
        finish(client);
    }

    private static int findRestoreSlot(Use use, Inventory inventory) {
        if (ItemStack.matches(inventory.getItem(use.source), use.displaced)
                && InventoryBindSupport.canSwap(use.owner, use.source, use.target)) return use.source;
        if (use.displaced.isEmpty()) return -1;

        int found = -1;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (slot == use.target || !ItemStack.matches(inventory.getItem(slot), use.displaced)
                    || !InventoryBindSupport.canSwap(use.owner, slot, use.target)) continue;
            if (found >= 0) return -1;
            found = slot;
        }
        return found;
    }

    private static void finish(Minecraft client) {
        reset();
        InventoryBindSupport.reserve(client, MIN_SETTLE_NANOS);
    }

    private static void overlay(Minecraft client, String message) {
        client.gui.setOverlayMessage(Component.literal(message), false);
    }

    private static boolean expectedActive(Use use, ItemStack current) {
        return current.isEmpty() || use.item.matches(current) || matchesRemainder(use, current);
    }

    private static boolean matchesRemainder(Use use, ItemStack current) {
        return !use.remainder.isEmpty() && ItemStack.matches(current, use.remainder);
    }

    private static boolean selected(Use use) {
        return use.hand == InteractionHand.OFF_HAND || use.owner.getInventory().getSelectedSlot() == use.target;
    }

    private static boolean ownsUse(Use use) {
        return use.owner.isUsingItem() && use.owner.getUsedItemHand() == use.hand
                && ItemStack.isSameItemSameComponents(use.owner.getUseItem(), use.inserted);
    }

    private static boolean isCurrentPlayer(Minecraft client, Use use) {
        return client.player == use.owner && client.level == use.owner.level() && use.owner.isAlive()
                && !use.owner.isSpectator() && client.gameMode != null && client.getConnection() != null;
    }

    static Rule rule(int index) { return rules[index]; }
    static KeyMapping key(int index) { return keys[index]; }
    static boolean isEnabled() { return enabled; }

    static void toggle() {
        enabled = !enabled;
        if (!save()) {
            enabled = !enabled;
            Minecraft.getInstance().gui.setOverlayMessage(Component.literal(text(
                    "Не вдалося зберегти налаштування.", "Не удалось сохранить настройки.", "Could not save settings.")), false);
        }
    }

    static boolean update(int index, Rule rule) {
        Rule previous = rules[index];
        rules[index] = rule;
        if (save()) return true;
        rules[index] = previous;
        return false;
    }

    static boolean delete(Minecraft client, int index) {
        if (!update(index, EMPTY)) return false;
        InventoryBindSupport.unbind(client, keys[index]);
        return true;
    }

    private static Path file() {
        return InventoryBindSupport.configFile("dash-consumable-bind.properties");
    }

    private static void load() {
        try {
            Properties properties = InventoryBindSupport.load(file());
            enabled = Boolean.parseBoolean(properties.getProperty("enabled", "true"));
            for (int i = 0; i < rules.length; i++) {
                String prefix = "rule." + i;
                int delay = DEFAULT_DELAY_MS;
                try {
                    delay = Integer.parseInt(properties.getProperty(prefix + ".delay", Integer.toString(DEFAULT_DELAY_MS)));
                } catch (NumberFormatException ignored) {
                    // Keep the default for one invalid entry.
                }
                rules[i] = new Rule(ItemChoice.load(properties, prefix), delay);
            }
        } catch (IOException | IllegalArgumentException error) {
            LOGGER.warn("Unable to read Consumable Bind settings", error);
        }
    }

    private static boolean save() {
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        for (int i = 0; i < rules.length; i++) {
            Rule rule = rules[i];
            String prefix = "rule." + i;
            properties.setProperty(prefix + ".delay", Integer.toString(rule.delayMs()));
            if (rule.item() != null) rule.item().save(properties, prefix);
        }
        try {
            InventoryBindSupport.save(file(), properties, "Dash Consumable Bind");
            return true;
        } catch (IOException error) {
            LOGGER.warn("Unable to write Consumable Bind settings", error);
            return false;
        }
    }

    record Rule(ItemChoice item, int delayMs) {
        Rule {
            delayMs = Math.max(0, Math.min(delayMs, MAX_DELAY_MS));
        }
    }

    private record Request(LocalPlayer owner, ItemChoice item, long readyAt, long expiresAt) {}

    private static final class Use {
        final LocalPlayer owner;
        final InteractionHand hand;
        final ItemChoice item;
        final int source;
        final int target;
        final ItemStack displaced;
        final ItemStack inserted;
        final ItemStack remainder;
        long finishAt;
        long restoreAt;
        long responseAt;
        int countBeforeFinish;
        boolean wasUsing;
        boolean restoring;

        Use(LocalPlayer owner, InteractionHand hand, ItemChoice item, int source, int target,
            ItemStack displaced, ItemStack inserted, long now) {
            this.owner = owner;
            this.hand = hand;
            this.item = item;
            this.source = source;
            this.target = target;
            this.displaced = displaced;
            this.inserted = inserted;
            countBeforeFinish = inserted.getCount();
            var useRemainder = inserted.get(DataComponents.USE_REMAINDER);
            remainder = useRemainder == null ? ItemStack.EMPTY : useRemainder.convertInto().copy();
            int duration = Math.min(600, inserted.getUseDuration(owner));
            finishAt = now + (duration + 40) * 50L * NANOS_PER_MS;
            restoreAt = now + MIN_SETTLE_NANOS;
            responseAt = now + INSTANT_RESPONSE_NANOS;
        }

        boolean swapped() { return source != target; }
    }
}
