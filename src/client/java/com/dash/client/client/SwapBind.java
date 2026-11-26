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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

public final class SwapBind {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long RETRY_WINDOW_NANOS = 2_000_000_000L;
    private static final long TOTEM_SCAN_INTERVAL_NANOS = 500_000_000L;
    private static final long SYNC_OBSERVATION_NANOS = 1_500_000_000L;
    private static final Rule EMPTY = new Rule(null, null, Hand.LEFT);
    private static final Rule[] rules = new Rule[InventoryBindSupport.RULE_COUNT];
    private static final ReturnSwap[] returns = new ReturnSwap[rules.length];
    private static final HandOwnership[] held = new HandOwnership[Hand.values().length];
    private static final boolean[] synchronizedHand = new boolean[held.length];
    private static KeyMapping[] keys;
    private static LocalPlayer player;
    private static boolean enabled = true;
    private static long nextTotemScan;
    private static long clientTicks;
    private static long lastClickTick = Long.MIN_VALUE;
    private static int revision;
    private static SwapRequest queued;

    static {
        Arrays.fill(rules, EMPTY);
    }

    private SwapBind() {}

    static void register() {
        keys = InventoryBindSupport.registerKeys("swap-bind", SwapBind::apply);
        load();
    }

    static void tick(Minecraft client) {
        clientTicks++;
        syncPlayer(client);
        reconcileHands(client);
        retry(client);
        if (!enabled || SaveMode.isPaused() || player == null || !player.isAlive() || player.isSpectator()) return;
        long now = System.nanoTime();
        if (now < nextTotemScan) return;
        nextTotemScan = now + TOTEM_SCAN_INTERVAL_NANOS;
        refreshTotems();
    }

    private static void syncPlayer(Minecraft client) {
        if (player != client.player || SaveMode.isPaused()) {
            reset();
            player = client.player;
        }
    }

    static void reset() {
        player = null;
        nextTotemScan = 0;
        lastClickTick = Long.MIN_VALUE;
        queued = null;
        Arrays.fill(returns, null);
        Arrays.fill(held, null);
        Arrays.fill(synchronizedHand, false);
    }

    static boolean isEnabled() {
        return enabled;
    }

    static void toggle() {
        enabled = !enabled;
        if (!save()) {
            enabled = !enabled;
            message(Minecraft.getInstance(), text("Не вдалося зберегти налаштування.",
                    "Не удалось сохранить настройки.", "Could not save settings."));
        }
        if (enabled) {
            nextTotemScan = 0;
            tick(Minecraft.getInstance());
        } else {
            reset();
        }
    }

    static Rule rule(int index) {
        return rules[index];
    }

    static int revision() {
        return revision;
    }

    static KeyMapping key(int index) {
        return keys[index];
    }

    static boolean update(int index, Rule rule) {
        Rule previous = rules[index];
        rules[index] = rule;
        if (!save()) {
            rules[index] = previous;
            return false;
        }
        returns[index] = null;
        int arm = previous.hand().ordinal();
        if (held[arm] != null && held[arm].index() == index) held[arm] = null;
        if (queued != null && queued.index() == index) queued = null;
        revision++;
        return true;
    }

    static boolean delete(Minecraft client, int index) {
        if (!update(index, EMPTY)) return false;
        InventoryBindSupport.unbind(client, keys[index]);
        return true;
    }

    static boolean reservesOffhand(Minecraft client) {
        return reservesSlot(client, Inventory.SLOT_OFFHAND);
    }

    public static void onInventoryMenuSync(Minecraft client, int containerId, int menuSlot) {
        if (SaveMode.isPaused()) return;
        if (player == null || player != client.player || player.inventoryMenu.containerId != containerId) return;
        for (int i = 0; i < held.length; i++) {
            HandOwnership ownership = held[i];
            if (ownership != null && (menuSlot == -1 || menuSlot == InventoryBindSupport.menuSlot(ownership.destination()))) {
                synchronizedHand[i] = true;
            }
        }
    }

    public static void onPlayerInventorySync(Minecraft client, int slot) {
        if (SaveMode.isPaused()) return;
        if (player == null || player != client.player || !(Inventory.isHotbarSlot(slot) || slot == Inventory.SLOT_OFFHAND)) {
            return;
        }
        onInventoryMenuSync(client, player.inventoryMenu.containerId, InventoryBindSupport.menuSlot(slot));
    }

    static boolean serializing(Minecraft client) {
        return player != null && player == client.player && lastClickTick >= clientTicks - 1;
    }

    static boolean reservesSlot(Minecraft client, int slot) {
        if (!enabled || SaveMode.isPaused() || player == null || player != client.player) return false;
        for (HandOwnership ownership : held) {
            if (ownership != null && ownership.destination() == slot && validOwnership(ownership)) return true;
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < returns.length; i++) {
            ReturnSwap swap = returns[i];
            if (swap != null && (swap.destination() == slot || swap.source() == slot)
                    && validReturn(inventory, swap, destination(player, rules[i].hand()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean apply(Minecraft client, int index) {
        if (!enabled || !InventoryBindSupport.canInteract(client)) return false;
        syncPlayer(client);
        long now = System.nanoTime();
        if (queued != null) {
            if (canRetry(client, queued, now)) return queued.index() == index;
            queued = null;
        }
        Rule rule = rules[index];
        if (!rule.configured()) {
            message(client, text("Обери два різні предмети для пари.",
                    "Выбери два разных предмета для пары.", "Choose two different items for the pair."));
            return false;
        }
        LocalPlayer owner = client.player;
        int destination = destination(owner, rule.hand());
        if (handReady(client, owner, destination) && trySwap(client, index, rule, destination)) {
            return true;
        }
        queued = new SwapRequest(index, rule, destination, now + RETRY_WINDOW_NANOS);
        return true;
    }

    private static void retry(Minecraft client) {
        SwapRequest request = queued;
        if (request == null) return;
        long now = System.nanoTime();
        if (!canRetry(client, request, now)) {
            queued = null;
            return;
        }
        if (!handReady(client, player, request.destination())) return;
        if (trySwap(client, request.index(), request.rule(), request.destination())) queued = null;
    }

    private static boolean canRetry(Minecraft client, SwapRequest request, long now) {
        return now < request.expiresAt() && InventoryBindSupport.canInteract(client)
                && rules[request.index()] == request.rule()
                && destination(player, request.rule().hand()) == request.destination();
    }

    private static boolean handReady(Minecraft client, LocalPlayer owner, int destination) {
        InteractionHand targetHand = destination == Inventory.SLOT_OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        return !InventoryBindSupport.busy(client) && (!owner.isUsingItem() || owner.getUsedItemHand() != targetHand);
    }

    private static boolean trySwap(Minecraft client, int index, Rule rule, int destination) {
        Inventory inventory = player.getInventory();
        ReturnSwap pending = returns[index];
        if (pending != null && validReturn(inventory, pending, destination)
                && swap(client, index, pending.source(), destination, null)) {
            return true;
        }

        ItemStack current = inventory.getItem(destination);
        boolean holdsFirst = rule.first().matches(current);
        boolean holdsPairItem = holdsFirst || rule.second().matches(current);
        ItemChoice next = holdsFirst ? rule.second() : rule.first();
        int source = findSource(inventory, next, destination, index, -1);
        if (source < 0 || !holdsPairItem && findSource(inventory, rule.second(), destination, index, source) < 0) return false;

        ReturnSwap restore = holdsPairItem ? new ReturnSwap(source, destination, current.copy(), next) : null;
        return swap(client, index, source, destination, restore);
    }

    private static int findSource(Inventory inventory, ItemChoice choice, int destination, int ruleIndex, int excluded) {
        return InventoryBindSupport.find(inventory, choice, slot -> slot != inventory.getSelectedSlot()
                && slot != excluded && !conflicts(inventory, slot, destination, ruleIndex)
                && InventoryBindSupport.canSwap(player, slot, destination));
    }

    private static boolean swap(Minecraft client, int index, int source, int destination, ReturnSwap restore) {
        Inventory inventory = player.getInventory();
        ItemStack incoming = inventory.getItem(source).copy();
        ItemStack outgoing = inventory.getItem(destination).copy();
        int revision = player.inventoryMenu.getStateId();
        if (!InventoryBindSupport.swap(client, source, destination)) return false;
        lastClickTick = clientTicks;
        if (ItemStack.matches(inventory.getItem(source), outgoing)
                && ItemStack.matches(inventory.getItem(destination), incoming)) {
            Rule rule = rules[index];
            returns[index] = restore;
            int arm = rule.hand().ordinal();
            held[arm] = new HandOwnership(index, rule, destination, incoming, ItemChoice.of(incoming),
                    outgoing, ItemChoice.of(outgoing), revision, System.nanoTime());
            synchronizedHand[arm] = false;
        } else {
            LOGGER.debug("Swap Bind click did not predict a complete exchange (menu {}, revision {}, source {}, target {})",
                    player.inventoryMenu.containerId, revision, source, destination);
        }
        // A valid native click is sent exactly once. Minecraft applies any later server correction.
        return true;
    }

    private static void reconcileHands(Minecraft client) {
        if (player == null || ConsumableBind.busy(client)) return;
        for (int i = 0; i < held.length; i++) {
            HandOwnership ownership = held[i];
            if (ownership != null && !validOwnership(ownership)) {
                if (synchronizedHand[i] && System.nanoTime() - ownership.issuedAt() <= SYNC_OBSERVATION_NANOS) {
                    logHandSynchronization(client, ownership);
                }
                held[i] = null;
                returns[ownership.index()] = null;
                LOGGER.debug("Swap Bind released slot {} after an inventory change (issued revision {}, current {})",
                        ownership.destination(), ownership.menuRevision(), player.inventoryMenu.getStateId());
            }
            synchronizedHand[i] = false;
        }
    }

    private static void logHandSynchronization(Minecraft client, HandOwnership ownership) {
        ItemStack current = player.getInventory().getItem(ownership.destination());
        boolean restoredPrevious = sameHandItem(current, ownership.previous(), ownership.previousItem());
        LOGGER.info("[Dash Swap] result={} slot={} revision_before={} revision_now={} intended=\"{}\" actual=\"{}\"",
                restoredPrevious ? "server_restored_previous" : "server_changed_slot", ownership.destination(),
                ownership.menuRevision(), player.inventoryMenu.getStateId(), ownership.stack().getHoverName().getString(),
                current.getHoverName().getString());
        if (restoredPrevious && !current.isEmpty()) {
            message(client, text("Сервер повернув ", "Сервер вернул ", "Server restored ") + current.getHoverName().getString());
        }
    }

    private static boolean validOwnership(HandOwnership ownership) {
        return rules[ownership.index()] == ownership.rule()
                && destination(player, ownership.rule().hand()) == ownership.destination()
                && sameHandItem(player.getInventory().getItem(ownership.destination()), ownership.stack(), ownership.item());
    }

    private static boolean sameHandItem(ItemStack current, ItemStack stored, ItemChoice item) {
        // Servers can refresh attributes or lore on a named accessory without changing the chosen item.
        return ItemStack.matches(current, stored) || item != null && current.getCount() == stored.getCount()
                && item.matchesNamedStack(current);
    }

    private static void refreshTotems() {
        int index = autoRuleIndex();
        if (index < 0 || queued != null && queued.index() == index) return;
        Rule previous = rules[index];
        Hand hand = previous.autoTotems() ? previous.hand() : player.getMainArm() == HumanoidArm.LEFT ? Hand.RIGHT : Hand.LEFT;
        int destination = destination(player, hand);
        Inventory inventory = player.getInventory();
        ReturnSwap pending = returns[index];
        if (previous.configured() && (pending != null && validReturn(inventory, pending, destination)
                || hasPair(inventory, previous, destination))) return;
        Rule found = findTotemPair(inventory, destination, hand);
        if (found != null && !found.equals(previous)) update(index, found);
    }

    private static int autoRuleIndex() {
        int firstEmpty = -1;
        int boundEmpty = -1;
        for (int i = 0; i < rules.length; i++) {
            if (rules[i].autoTotems()) return i;
            if (!rules[i].empty()) continue;
            if (firstEmpty < 0) firstEmpty = i;
            if (boundEmpty < 0 && !keys[i].isUnbound()) boundEmpty = i;
        }
        return boundEmpty >= 0 ? boundEmpty : firstEmpty;
    }

    private static boolean hasPair(Inventory inventory, Rule rule, int destination) {
        ItemStack held = inventory.getItem(destination);
        int heldSlot = inventory.getSelectedSlot();
        int first = rule.first().matches(held) ? destination
                : InventoryBindSupport.find(inventory, rule.first(), slot -> slot != heldSlot);
        return first >= 0 && (first != destination && rule.second().matches(held)
                || InventoryBindSupport.find(inventory, rule.second(), slot -> slot != heldSlot && slot != first) >= 0);
    }

    private static Rule findTotemPair(Inventory inventory, int destination, Hand hand) {
        ItemChoice first = totemChoice(inventory.getItem(destination));
        ItemChoice matching = null;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (slot == inventory.getSelectedSlot()) continue;
            ItemChoice candidate = totemChoice(inventory.getItem(slot));
            if (candidate == null) continue;
            if (first == null) first = candidate;
            else if (!first.equals(candidate)) return new Rule(first, candidate, hand, true);
            else matching = candidate;
        }
        return matching == null ? null : new Rule(first, matching, hand, true);
    }

    private static ItemChoice totemChoice(ItemStack stack) {
        return stack.is(Items.TOTEM_OF_UNDYING) ? ItemChoice.of(stack) : null;
    }

    private static boolean conflicts(Inventory inventory, int source, int destination, int index) {
        for (int i = 0; i < returns.length; i++) {
            ReturnSwap other = returns[i];
            if (i != index && other != null
                    && validReturn(inventory, other, destination(player, rules[i].hand()))
                    && (other.source() == source || other.source() == destination
                            || other.destination() == source || other.destination() == destination)) {
                return true;
            }
        }
        return false;
    }

    private static boolean validReturn(Inventory inventory, ReturnSwap swap, int destination) {
        return swap.destination() == destination && swap.source() != inventory.getSelectedSlot()
                && ItemStack.matches(inventory.getItem(swap.source()), swap.displaced())
                && swap.heldItem().matches(inventory.getItem(destination));
    }

    private static int destination(LocalPlayer owner, Hand hand) {
        HumanoidArm arm = hand == Hand.LEFT ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
        return owner.getMainArm() == arm ? owner.getInventory().getSelectedSlot() : Inventory.SLOT_OFFHAND;
    }

    private static void message(Minecraft client, String text) {
        client.gui.setOverlayMessage(Component.literal(text), false);
    }

    private static Path file() {
        return InventoryBindSupport.configFile("dash-swap-bind.properties");
    }

    private static void load() {
        try {
            Properties properties = InventoryBindSupport.load(file());
            enabled = Boolean.parseBoolean(properties.getProperty("enabled", "true"));
            for (int i = 0; i < rules.length; i++) {
                String prefix = "rule." + i;
                Hand hand = "RIGHT".equals(properties.getProperty(prefix + ".hand")) ? Hand.RIGHT : Hand.LEFT;
                rules[i] = new Rule(ItemChoice.load(properties, prefix + ".a"),
                        ItemChoice.load(properties, prefix + ".b"), hand,
                        Boolean.parseBoolean(properties.getProperty(prefix + ".autoTotems")));
            }
        } catch (IOException | IllegalArgumentException error) {
            LOGGER.warn("Unable to read Swap Bind settings", error);
        }
    }

    private static boolean save() {
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        for (int i = 0; i < rules.length; i++) {
            Rule rule = rules[i];
            String prefix = "rule." + i;
            properties.setProperty(prefix + ".hand", rule.hand().name());
            if (rule.autoTotems()) properties.setProperty(prefix + ".autoTotems", "true");
            if (rule.first() != null) rule.first().save(properties, prefix + ".a");
            if (rule.second() != null) rule.second().save(properties, prefix + ".b");
        }
        try {
            InventoryBindSupport.save(file(), properties, "Dash Swap Bind");
            return true;
        } catch (IOException error) {
            LOGGER.warn("Unable to write Swap Bind settings", error);
            return false;
        }
    }

    enum Hand { LEFT, RIGHT }

    record Rule(ItemChoice first, ItemChoice second, Hand hand, boolean autoTotems) {
        Rule(ItemChoice first, ItemChoice second, Hand hand) {
            this(first, second, hand, false);
        }

        boolean empty() {
            return first == null && second == null;
        }

        boolean configured() {
            return first != null && second != null && (autoTotems || !first.equals(second));
        }
    }

    private record ReturnSwap(int source, int destination, ItemStack displaced, ItemChoice heldItem) {}

    private record SwapRequest(int index, Rule rule, int destination, long expiresAt) {}

    private record HandOwnership(int index, Rule rule, int destination, ItemStack stack, ItemChoice item,
                                 ItemStack previous, ItemChoice previousItem, int menuRevision, long issuedAt) {}
}
