package com.dash.client.client;

import com.google.common.net.HostAndPort;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

public final class AutoBuy {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long SECOND = 1_000_000_000L;
    private static final long REFRESH_DELAY = 1_500_000_000L;
    private static final long CONFIRM_DELAY = 200_000_000L;
    private static final long RETURN_DELAY = 600_000_000L;
    private static final long RATE_LIMIT_DELAY = 2_500_000_000L;
    private static boolean enabled;
    private static String status = "Вимкнено";
    private static String lastScan = "";
    private static AbstractContainerMenu observedMenu;
    private static long serverVersion;
    private static long lastServerUpdate;
    private static long nextAction;
    private static long nextRefresh;
    private static long scannedVersion = -1;
    private static AbstractContainerMenu refreshSource;
    private static long returnAt;
    private static long waitingRefreshVersion = -1;
    private static long refreshDeadline;
    private static int missedRefreshes;
    private static int rateLimits;
    private static long lastClick;
    private static ClickAction lastClickAction;
    private static boolean refreshRequired;
    private static Purchase pending;
    private static ClientLevel rulesLevel;
    private static long rulesRevision = -1;
    private static List<Matcher> matchers = List.of();
    private static int purchases;

    private AutoBuy() {}

    public static boolean isEnabled() { return enabled; }
    public static String status() { return lastScan.isEmpty() ? status : status + " · " + lastScan; }
    public static void toggle() { setEnabled(!enabled); }

    public static void setEnabled(boolean value) {
        if (value && SaveMode.isPaused()) return;
        reset();
        enabled = value;
        status = value ? "Очікую аукціон /ah" : "Вимкнено";
        Minecraft client = Minecraft.getInstance();
        if (value && client.player != null && client.getConnection() != null
                && !(client.screen instanceof AbstractContainerScreen<?> screen
                && screen.getMenu() instanceof ChestMenu menu
                && FunTimeAuction.isAuctionScreen(screen.getTitle().getString(), menu))) {
            client.setScreen(null);
            client.getConnection().sendCommand("ah");
        }
    }

    public static void reset() {
        pending = null;
        lastScan = "";
        nextAction = 0;
        nextRefresh = 0;
        scannedVersion = -1;
        refreshSource = null;
        returnAt = 0;
        waitingRefreshVersion = -1;
        missedRefreshes = 0;
        rateLimits = 0;
        lastClick = 0;
        lastClickAction = null;
        refreshRequired = false;
    }

    public static void disconnect() {
        pause();
    }

    static void pause() {
        setEnabled(false);
        observedMenu = null;
        rulesLevel = null;
        matchers = List.of();
    }

    public static void onContainerUpdate(int containerId) {
        if (SaveMode.isPaused()) return;
        var player = Minecraft.getInstance().player;
        if (player != null && player.containerMenu.containerId == containerId) {
            observedMenu = player.containerMenu;
            serverVersion++;
            lastServerUpdate = System.nanoTime();
        }
    }

    public static void onServerMessage(String message) {
        if (!enabled || SaveMode.isPaused()) return;
        String text = FunTimeAuction.label(message)
                .replaceFirst("^(?:\\s*\\[[^\\]]*]\\s*)+", "")
                .replaceFirst("^[^\\p{L}]+", "")
                .replaceFirst("^(?:ошибка|аукцион)\\s*[:!|».-]+\\s*", "");
        // FunTime prefixes responses with icons and "Ошибка!"; the result is not at offset zero.
        if (text.contains("не так быстро") || text.contains("подождите")
                || text.contains("слишком часто") && text.contains("обнов")) {
            if (lastClickAction == null || pending != null && pending.serverSuccess) return;
            if (++rateLimits > 3) {
                stop("Сервер обмежує частоту дій. Спробуй пізніше");
                return;
            }
            long retryAt = System.nanoTime() + RATE_LIMIT_DELAY * (1L << (rateLimits - 1));
            waitingRefreshVersion = -1;
            refreshRequired = true;
            nextAction = retryAt;
            if (pending != null && lastClickAction != ClickAction.REFRESH) {
                pending.retryAt = retryAt;
                pending.deadline = retryAt + 5 * SECOND;
                pending.rateLimited = true;
            }
            status = "Пауза після обмеження сервера";
            return;
        }
        if (pending == null) {
            return;
        }
        if (text.matches("(?U)^(?:вы\\s+)?(?:успешно\\s+)?(?:купили|приобрели)\\b.*")
                || text.startsWith("успешная покупка")) {
            pending.serverSuccess = true;
            if (pending.confirmed && isFunTimeConnection(Minecraft.getInstance())
                    && FunTimeAuction.matchesPurchaseReceipt(message, pending.lot)) {
                pending.receiptConfirmed = true;
            }
        } else if (text.contains("недостаточно") || text.contains("не хватает монет")) {
            stop("Недостатньо монет для цього лота");
        } else if (text.matches("(?U)^(?:этот\\s+)?(?:товар|предмет|лот) уже (?:куплен|продан|купили)\\b.*")) {
            pending.sold = true;
        } else if (text.contains("этот товар уже") || text.contains("этот предмет уже")
                || text.contains("вы не можете купить")) {
            stop("Покупку відхилено сервером");
        }
    }

    private static boolean isFunTimeConnection(Minecraft client) {
        var server = client.getCurrentServer();
        if (server == null || server.ip == null) return false;
        try {
            String host = HostAndPort.fromString(server.ip.strip()).getHost().toLowerCase(Locale.ROOT);
            return host.equals("funtime.su") || host.endsWith(".funtime.su");
        } catch (IllegalArgumentException invalidAddress) {
            return false;
        }
    }

    public static void onInventoryClick(int containerId, int slot, int button, ClickType clickType) {
        Purchase purchase = pending;
        if (!enabled || SaveMode.isPaused() || purchase == null || purchase.confirmed || button != 0
                || clickType != ClickType.PICKUP) return;
        Minecraft client = Minecraft.getInstance();
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)
                || !(screen.getMenu() instanceof ChestMenu menu)
                || menu == purchase.source || menu.containerId != containerId
                || slot < 0 || slot >= menu.getContainer().getContainerSize()
                || !isConfirmationScreen(client)) return;
        ItemStack action = menu.getSlot(slot).getItem();
        if (!FunTimeAuction.isBuyAction(action.getHoverName().getString())) {
            var lot = FunTimeAuction.parseLot(slot, action);
            if (slot != 0 || lot.isEmpty() || !samePurchase(purchase.lot, lot.get())) return;
        }
        purchase.confirmed = true;
        purchase.beforeCount = inventoryCount(client, FunTimeAuction.merchandise(purchase.lot.stack()));
        long now = System.nanoTime();
        purchase.deadline = now + 5 * SECOND;
        lastClick = now;
        lastClickAction = ClickAction.CONFIRMATION;
    }

    public static void tick(Minecraft client) {
        if (!enabled || SaveMode.isPaused()) return;
        if (client.player == null || client.level == null || client.gameMode == null) {
            setEnabled(false);
            return;
        }
        if (!client.player.isAlive() || client.player.isSpectator()) {
            stop("Покупки зупинено");
            return;
        }
        updateMatchers(client);
        long now = System.nanoTime();
        if (pending != null) {
            processPurchase(client, now);
            return;
        }
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)
                || !(screen.getMenu() instanceof ChestMenu menu)
                || !FunTimeAuction.isAuctionScreen(screen.getTitle().getString(), menu)) {
            if (returnAt != 0 && now >= returnAt && now >= nextAction
                    && (client.screen == null || isConfirmationScreen(client))) {
                if (client.screen != null) {
                    client.player.closeContainer();
                    returnAt = now + 100_000_000L;
                } else {
                    returnAt = 0;
                    client.getConnection().sendCommand("ah");
                }
            }
            status = "Очікую аукціон /ah";
            return;
        }
        returnAt = 0;
        if (menu != observedMenu || now < nextAction) return;
        if (waitingRefreshVersion >= 0) {
            if (serverVersion <= waitingRefreshVersion) {
                if (now >= refreshDeadline) {
                    waitingRefreshVersion = -1;
                    refreshRequired = true;
                    nextAction = now + SECOND;
                    if (++missedRefreshes >= 3) stop("Аукціон не відповідає на оновлення");
                }
                return;
            }
            waitingRefreshVersion = -1;
            missedRefreshes = 0;
        }
        if (!menu.getCarried().isEmpty()) {
            if (refreshRequired) stop("Сервер не підтвердив оновлення аукціону");
            return;
        }
        if (refreshRequired || refreshSource == menu) {
            if (now >= nextRefresh) {
                refreshRequired = false;
                refreshSource = null;
                refresh(client, menu, now);
            }
            return;
        }
        refreshSource = null;
        if (matchers.isEmpty()) {
            status = "Додай й увімкни правило AutoBuy";
            return;
        }
        if (scannedVersion == serverVersion) {
            if (now >= nextRefresh) refresh(client, menu, now);
            return;
        }
        scannedVersion = serverVersion;

        Candidate best = null;
        int visibleLots = 0;
        int recognizedLots = 0;
        int matchingLots = 0;
        int lotSlots = menu.getContainer().getContainerSize() - 9;
        for (int slot = 0; slot < lotSlots; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!stack.isEmpty()) visibleLots++;
            var parsed = FunTimeAuction.parseLot(slot, stack);
            if (parsed.isEmpty() || parsed.get().seller().isEmpty()) continue;
            recognizedLots++;
            var lot = parsed.get();
            if (lot.seller().equalsIgnoreCase(client.player.getGameProfile().name())) continue;
            boolean matchesItem = false;
            for (Matcher matcher : matchers) {
                if (!matcher.matches(lot.stack())) continue;
                matchesItem = true;
                if (matcher.rule.quantityMode().matches(lot.stack())
                        && withinRange(matcher.rule, lot.totalPrice(), lot.stack().getCount())) {
                    Candidate candidate = new Candidate(matcher.rule, lot);
                    if (best == null || cheaper(candidate.lot, best.lot)) best = candidate;
                }
            }
            if (matchesItem) matchingLots++;
        }
        lastScan = "Розпізнано " + recognizedLots + "/" + visibleLots + " · потрібних " + matchingLots;
        if (best == null) {
            status = "Лотів у діапазоні немає";
            if (now >= nextRefresh) refresh(client, menu, now);
            return;
        }
        ItemStack merchandise = FunTimeAuction.merchandise(best.lot.stack());
        if (!hasSpace(client, merchandise)) {
            stop("Звільни місце в інвентарі");
            return;
        }
        pending = new Purchase(best.rule, best.lot, menu, serverVersion,
                inventoryCount(client, merchandise), now + 5 * SECOND);
        status = "Перевіряю покупку · " + best.lot.totalPrice();
        click(client, menu, best.lot.slotIndex(), ClickAction.LISTING);
    }

    private static void processPurchase(Minecraft client, long now) {
        Purchase purchase = pending;
        if (AutoBuySettings.rules().stream().noneMatch(purchase.rule::equals)) {
            stop("Правило змінено під час покупки");
            return;
        }
        ItemStack expected = FunTimeAuction.merchandise(purchase.lot.stack());
        boolean mayHavePurchased = purchase.manualMenu == null || purchase.confirmed || purchase.serverSuccess;
        boolean delivered = mayHavePurchased
                && inventoryCount(client, expected) >= purchase.beforeCount + expected.getCount();
        // Give the usual inventory update its full window before falling back to the receipt.
        if (delivered || purchase.receiptConfirmed && now >= purchase.deadline) {
            purchases++;
            rateLimits = 0;
            pending = null;
            refreshSource = purchase.source;
            returnAt = now + RETURN_DELAY;
            status = "Куплено · " + purchases;
            return;
        }
        if (purchase.sold && !purchase.serverSuccess) {
            pending = null;
            refreshSource = purchase.source;
            returnAt = now + RETURN_DELAY;
            status = "Лот уже продано. Шукаю далі";
            return;
        }
        if (purchase.manualMenu != null && purchase.manualVersion == serverVersion
                && !purchase.confirmed && !purchase.serverSuccess
                && client.screen instanceof AbstractContainerScreen<?> screen
                && screen.getMenu() == purchase.manualMenu) {
            purchase.deadline = now + 5 * SECOND;
            return;
        }
        if (now >= purchase.deadline) {
            if ((purchase.manualMenu != null || purchase.sawUnexpectedMenu)
                    && !purchase.confirmed && !purchase.serverSuccess) {
                pending = null;
                refreshSource = purchase.source;
                returnAt = now + RETURN_DELAY;
                refreshRequired = true;
                status = "Підтвердження не відкрито. Чекаю аукціон";
                return;
            }
            stop("Сервер не підтвердив покупку. Перевір інвентар");
            return;
        }
        if (purchase.serverSuccess) {
            status = "Очікую предмет після підтвердження сервера";
            return;
        }
        if (purchase.rateLimited) {
            if (now < purchase.retryAt) return;
            // A rejected click can be retried only after waiting and checking for delivery first.
            if (client.screen instanceof AbstractContainerScreen<?> retryScreen
                    && retryScreen.getMenu() instanceof ChestMenu retryMenu
                    && (retryMenu == purchase.source
                    || FunTimeAuction.isAuctionScreen(retryScreen.getTitle().getString(), retryMenu))) {
                pending = null;
                refreshRequired = true;
                nextAction = now;
                return;
            }
            if (client.screen == null && client.getConnection() != null) {
                pending = null;
                refreshRequired = false;
                nextAction = now + CONFIRM_DELAY;
                client.getConnection().sendCommand("ah");
                return;
            }
            purchase.rateLimited = false;
            purchase.confirmed = false;
        }
        if (purchase.confirmed) return;
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)
                || !(screen.getMenu() instanceof ChestMenu menu)) {
            // Close/open packets can arrive on adjacent frames. Keep tracking delivery/rejection.
            status = "Очікую вікно підтвердження";
            return;
        }
        if (menu == purchase.source) return;
        if (observedMenu != menu || serverVersion <= purchase.sourceVersion
                || now - lastServerUpdate < 100_000_000L || !menu.getCarried().isEmpty()) return;
        if (now - lastClick < CONFIRM_DELAY) return;
        if (FunTimeAuction.isAuctionScreen(screen.getTitle().getString(), menu) && refreshSlot(menu) >= 0) {
            // Give the original click time to return its confirmation or purchase result.
            if (now - lastClick < RETURN_DELAY) return;
            pending = null;
            refreshRequired = true;
            status = "Аукціон повернувся. Оновлюю лоти";
            return;
        }
        boolean titledConfirmation = FunTimeAuction.isConfirmationTitle(screen.getTitle().getString());
        boolean structuredConfirmation = menu.getContainer().getContainerSize() <= 27
                && hasCancelButton(menu) && namedConfirmationSlot(menu, false) >= 0;
        if (!titledConfirmation && !structuredConfirmation) {
            if (!purchase.sawUnexpectedMenu) {
                LOGGER.info("AutoBuy waiting for confirmation after menu '{}' ({} slots)",
                        screen.getTitle().getString(), menu.getContainer().getContainerSize());
                purchase.sawUnexpectedMenu = true;
            }
            status = "Очікую вікно підтвердження";
            return;
        }
        FunTimeAuction.Lot preview = null;
        for (int slot = 0; slot < menu.getContainer().getContainerSize(); slot++) {
            var parsed = FunTimeAuction.parseLot(slot, menu.getSlot(slot).getItem());
            if (parsed.isPresent() && ItemStack.isSameItem(parsed.get().stack(), purchase.lot.stack())) {
                if (preview != null) {
                    awaitManualConfirmation(purchase, menu, "Кілька предметів у підтвердженні");
                    return;
                }
                preview = parsed.get();
            }
        }
        if (preview == null || !samePurchase(purchase.lot, preview)
                || !purchase.rule.quantityMode().matches(preview.stack())
                || !withinRange(purchase.rule, preview.totalPrice(), preview.stack().getCount())) {
            awaitManualConfirmation(purchase, menu, "Не вдалося звірити предмет або ціну");
            return;
        }
        int confirm = confirmationSlot(menu, preview);
        if (confirm < 0) {
            awaitManualConfirmation(purchase, menu, "Кнопку підтвердження не знайдено");
            return;
        }
        purchase.confirmed = true;
        if (purchase.manualMenu != null) purchase.beforeCount = inventoryCount(client, expected);
        purchase.deadline = now + 5 * SECOND;
        click(client, menu, confirm, ClickAction.CONFIRMATION);
    }

    private static void awaitManualConfirmation(Purchase purchase, ChestMenu menu, String reason) {
        purchase.manualMenu = menu;
        purchase.manualVersion = serverVersion;
        status = reason + " · Очікую ручне рішення";
    }

    private static boolean samePurchase(FunTimeAuction.Lot first, FunTimeAuction.Lot second) {
        return first.totalPrice() == second.totalPrice()
                && first.stack().getCount() == second.stack().getCount()
                && (second.seller().isEmpty() || first.seller().equalsIgnoreCase(second.seller()))
                && ItemStack.isSameItemSameComponents(FunTimeAuction.merchandise(first.stack()),
                FunTimeAuction.merchandise(second.stack()));
    }

    private static boolean isConfirmationScreen(Minecraft client) {
        return client.screen instanceof AbstractContainerScreen<?> screen
                && screen.getMenu() instanceof ChestMenu menu
                && (FunTimeAuction.isConfirmationTitle(screen.getTitle().getString())
                || menu.getContainer().getContainerSize() <= 27
                && hasCancelButton(menu) && namedConfirmationSlot(menu, false) >= 0);
    }

    private static int confirmationSlot(ChestMenu menu, FunTimeAuction.Lot preview) {
        int result = namedConfirmationSlot(menu, !hasCancelButton(menu));
        // Older FunTime menus confirm by clicking the verified product in the first slot.
        return result >= 0 ? result : result == -1 && preview.slotIndex() == 0 ? 0 : -1;
    }

    private static boolean hasCancelButton(ChestMenu menu) {
        for (int slot = 0; slot < menu.getContainer().getContainerSize(); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            String name = stack.getHoverName().getString();
            if (FunTimeAuction.isCancelAction(name)) return true;
            if (stack.is(Items.PAPER) && FunTimeAuction.label(name).equals("[вернуться]")) {
                var lore = stack.get(DataComponents.LORE);
                if (lore != null && lore.lines().stream().anyMatch(line ->
                        FunTimeAuction.label(line.getString()).equals("отказаться от покупки"))) return true;
            }
        }
        return false;
    }

    private static int namedConfirmationSlot(ChestMenu menu, boolean requireGreen) {
        int result = -1;
        for (int slot = 0; slot < menu.getContainer().getContainerSize(); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            String name = FunTimeAuction.label(stack.getHoverName().getString());
            boolean green = stack.is(Items.LIME_STAINED_GLASS_PANE) || stack.is(Items.GREEN_STAINED_GLASS_PANE)
                    || stack.is(Items.LIME_STAINED_GLASS) || stack.is(Items.GREEN_STAINED_GLASS);
            boolean named = FunTimeAuction.isBuyAction(name);
            if (named && (!requireGreen || green)) {
                if (result >= 0 && !sameAction(menu.getSlot(result).getItem(), stack)) return -2;
                if (result < 0) result = slot;
            }
        }
        return result;
    }

    private static boolean sameAction(ItemStack first, ItemStack second) {
        // Resource-pack button tiles may differ in model data but must describe the same action.
        return FunTimeAuction.label(first.getHoverName().getString()).equals(
                FunTimeAuction.label(second.getHoverName().getString()))
                && java.util.Objects.equals(first.get(net.minecraft.core.component.DataComponents.LORE),
                second.get(net.minecraft.core.component.DataComponents.LORE));
    }

    private static void refresh(Minecraft client, ChestMenu menu, long now) {
        int slot = refreshSlot(menu);
        if (slot < 0) {
            stop("Кнопку оновлення аукціону не знайдено");
            return;
        }
        waitingRefreshVersion = serverVersion;
        refreshDeadline = now + 3 * SECOND;
        nextRefresh = now + REFRESH_DELAY;
        click(client, menu, slot, ClickAction.REFRESH);
    }

    private static int refreshSlot(ChestMenu menu) {
        int size = menu.getContainer().getContainerSize();
        for (int slot = Math.max(0, size - 9); slot < size; slot++) {
            String name = FunTimeAuction.label(menu.getSlot(slot).getItem().getHoverName().getString());
            if (name.contains("обнов") || name.contains("refresh")) {
                return slot;
            }
        }
        return -1;
    }

    private static void click(Minecraft client, AbstractContainerMenu menu, int slot, ClickAction action) {
        lastClick = System.nanoTime();
        lastClickAction = action;
        client.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.PICKUP, client.player);
    }

    private static boolean withinRange(AutoBuySettings.Rule rule, long total, int count) {
        if (count <= 0 || total < 0) return false;
        if (rule.priceMode() == AutoBuySettings.PriceMode.PER_LOT) {
            return total >= rule.minPrice() && total <= rule.maxPrice();
        }
        long quotient = total / count;
        return quotient >= rule.minPrice()
                && (quotient < rule.maxPrice() || quotient == rule.maxPrice() && total % count == 0);
    }

    private static boolean cheaper(FunTimeAuction.Lot first, FunTimeAuction.Lot second) {
        return BigInteger.valueOf(first.totalPrice()).multiply(BigInteger.valueOf(second.stack().getCount()))
                .compareTo(BigInteger.valueOf(second.totalPrice()).multiply(BigInteger.valueOf(first.stack().getCount()))) < 0;
    }

    private static int inventoryCount(Minecraft client, ItemStack expected) {
        int count = 0;
        for (int slot = 0; slot < client.player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (ItemStack.isSameItemSameComponents(FunTimeAuction.merchandise(stack), expected)) count += stack.getCount();
        }
        return count;
    }

    private static boolean hasSpace(Minecraft client, ItemStack expected) {
        int remaining = expected.getCount();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack.isEmpty()) remaining -= expected.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(FunTimeAuction.merchandise(stack), expected)) {
                remaining -= Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
            if (remaining <= 0) return true;
        }
        return false;
    }

    private static void updateMatchers(Minecraft client) {
        if (rulesRevision == AutoBuySettings.revision() && rulesLevel == client.level) return;
        List<Matcher> updated = new ArrayList<>();
        for (AutoBuySettings.Rule rule : AutoBuySettings.rules()) {
            if (!rule.enabled()) continue;
            try {
                ItemStack template = rule.stackJson() == null ? null : ItemStack.CODEC.parse(
                        client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE),
                        JsonParser.parseString(rule.stackJson())).getOrThrow();
                updated.add(new Matcher(rule, template == null ? null : FunTimeAuction.merchandise(template)));
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not decode AutoBuy rule {}", rule.id(), exception);
            }
        }
        matchers = List.copyOf(updated);
        scannedVersion = -1;
        rulesRevision = AutoBuySettings.revision();
        rulesLevel = client.level;
    }

    private static void stop(String reason) {
        enabled = false;
        reset();
        status = reason;
        var client = Minecraft.getInstance();
        if (client.player != null) client.gui.getChat().addMessage(Component.literal("[Dash AutoBuy] " + reason));
    }

    private record Candidate(AutoBuySettings.Rule rule, FunTimeAuction.Lot lot) {}

    private enum ClickAction { REFRESH, LISTING, CONFIRMATION }

    private record Matcher(AutoBuySettings.Rule rule, ItemStack template) {
        boolean matches(ItemStack stack) {
            if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(rule.itemId())) return false;
            return template == null || ItemStack.isSameItemSameComponents(template, FunTimeAuction.merchandise(stack));
        }
    }

    private static final class Purchase {
        final AutoBuySettings.Rule rule;
        final FunTimeAuction.Lot lot;
        final AbstractContainerMenu source;
        final long sourceVersion;
        int beforeCount;
        ChestMenu manualMenu;
        long manualVersion;
        long deadline;
        boolean confirmed;
        boolean sawUnexpectedMenu;
        boolean serverSuccess;
        boolean receiptConfirmed;
        boolean sold;
        boolean rateLimited;
        long retryAt;

        Purchase(AutoBuySettings.Rule rule, FunTimeAuction.Lot lot, AbstractContainerMenu source,
                 long sourceVersion, int beforeCount, long deadline) {
            this.rule = rule;
            this.lot = lot;
            this.source = source;
            this.sourceVersion = sourceVersion;
            this.beforeCount = beforeCount;
            this.deadline = deadline;
        }
    }
}
