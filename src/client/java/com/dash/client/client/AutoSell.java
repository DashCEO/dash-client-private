package com.dash.client.client;

import com.google.common.net.HostAndPort;
import com.mojang.logging.LogUtils;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.slf4j.Logger;

public final class AutoSell {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long SECOND = 1_000_000_000L;
    private static final long LIST_INTERVAL = 10 * SECOND;
    private static final long COMMAND_INTERVAL = 5 * SECOND;
    private static final long RETRY_INTERVAL = 15 * SECOND;
    private static final long REPLY_TIMEOUT = 12 * SECOND;
    private static final long INVENTORY_DELAY = 200_000_000L;
    private static final long QUOTE_LIFETIME = 60 * SECOND;
    private static final long MARKET_SETTLE_DELAY = 250_000_000L;
    private static final long MARKET_REFRESH_DELAY = 5 * SECOND;
    private static final int MAX_SEARCH_ATTEMPTS = 3;
    private static final Pattern WAIT_SECONDS = Pattern.compile("подождите\\s+(\\d{1,3})\\s*сек");
    private static final int HOTBAR_SIZE = 9;
    private static final int INVENTORY_SIZE = 36;
    private static final String EMPTY_SHULKER_QUERY = "шалкер пустой";
    private static final Pattern LISTED = Pattern.compile(
            "^\\[☃\\] Предмет (.+?)(?:\\s+[xх×]([1-9]\\d*))? выставлен на продажу за \\$([0-9][0-9,. ]*)!$");

    private static String itemInput = "";
    private static String searchInput = "";
    private static String status = "Вимкнено";
    private static Session session;
    private static long nextAuctionCommandAt;

    private AutoSell() {}

    static boolean isEnabled() { return session != null; }
    static String status() { return status; }
    static String itemInput() { return itemInput; }
    static String searchInput() { return searchInput; }

    static void toggle() {
        if (SaveMode.isPaused()) return;
        if (isEnabled()) {
            stop("Зупинено");
        } else {
            Minecraft client = Minecraft.getInstance();
            client.setScreen(new AutoSellScreen(client.screen));
        }
    }

    static boolean start(Minecraft client, String itemText, String searchText) {
        if (SaveMode.isPaused()) return false;
        itemInput = itemText.strip();
        searchInput = searchText.strip();
        if (AutoBuy.isEnabled()) return invalid("Спочатку вимкни AutoBuy");
        if (client.player == null || client.level == null || client.gameMode == null
                || client.getConnection() == null || !isSupportedServer(client)) {
            return invalid("Ця версія працює з форматом аукціону FunTime");
        }
        ItemStack sample = findItem(client, itemInput);
        if (sample.isEmpty()) {
            ItemStack held = client.player.getMainHandItem();
            if (isShulker(held.getItem())) {
                var components = held.getComponents().keySet().stream()
                        .map(type -> BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString()).sorted().toList();
                LOGGER.info("AutoSell rejected held shulker: components={}, containerItems={}, loot={}",
                        components, held.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                                .nonEmptyStream().count(), held.has(DataComponents.CONTAINER_LOOT));
            }
            return invalid(isShulker(resolveItem(itemInput)) || isDefaultShulkerName(itemInput)
                    ? "Потрібен порожній шалкер указаного кольору"
                    : "Вкажи ID або точну назву звичайного предмета в інвентарі");
        }
        String query = searchInput.isEmpty() || isShulker(sample.getItem())
                && (isDefaultShulkerName(searchInput)
                || FunTimeAuction.label(searchInput).equals(FunTimeAuction.label(sample.getHoverName().getString())))
                ? defaultSearch(sample) : searchInput;
        if (query.isBlank() || query.length() > 96 || query.codePoints().anyMatch(Character::isISOControl)) {
            return invalid("Вкажи назву для /ah search (до 96 символів)");
        }
        client.setScreen(null);
        session = new Session(client.level, client.getConnection(), sellIdentity(sample).copyWithCount(1), query);
        search(session, System.nanoTime());
        return true;
    }

    public static void onAuctionContents(int containerId) {
        if (SaveMode.isPaused()) return;
        Session run = session;
        if (run == null || run.phase != Phase.SEARCHING) return;
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof AbstractContainerScreen<?> screen && screen.getMenu() instanceof ChestMenu menu
                && menu.containerId == containerId) {
            if (run.results != menu) {
                LOGGER.debug("AutoSell market response: title={}, slots={}",
                        screen.getTitle().getString(), menu.getContainer().getContainerSize());
            }
            run.results = menu;
            run.marketUpdatedAt = System.nanoTime();
        }
    }

    static void onServerMessage(String message) {
        if (SaveMode.isPaused()) return;
        Session run = session;
        if (run == null || (run.phase != Phase.SEARCHING && run.phase != Phase.WAITING_FOR_SEARCH
                && run.phase != Phase.WAITING_FOR_LISTING)) return;
        String plain = FunTimeAuction.plain(message);
        if (run.phase == Phase.WAITING_FOR_LISTING && matchesReceipt(plain, run.count, run.price)) {
            run.receivedListing = true;
            return;
        }
        String text = plain.toLowerCase(Locale.ROOT);
        boolean serverError = text.matches("(?s)^\\s*\\[[^\\]]*]\\s*ошибка[!:. ].*") || text.startsWith("ошибка!");
        if (text.equals("данная команда недоступна в режиме afk")) {
            stop("Аукціон недоступний у режимі AFK. Вийди з AFK і запусти AutoSell повторно");
            return;
        }
        if ((text.startsWith("[☃]") || serverError) && (text.contains("перед использованием аукциона")
                || text.contains("не так быстро") || text.contains("подождите"))) {
            if (run.phase == Phase.WAITING_FOR_LISTING) {
                stop("Сервер обмежив частоту дій. Перевір свої лоти перед повтором");
                return;
            }
            var seconds = WAIT_SECONDS.matcher(text);
            long delay = seconds.find() ? Long.parseLong(seconds.group(1)) + 1 : 0;
            retrySearch(run, System.nanoTime(), delay * SECOND);
            return;
        }
        if (serverError
                || text.equals("[⚠] данной команды не существует!")
                || text.startsWith("[☃]") && (text.contains("повтор") || text.contains("слишком")
                || text.contains("не можете") || text.contains("нет места") || text.contains("подтверд"))) {
            stop("Сервер відхилив дію або просить підтвердження. Перевір чат");
        }
    }

    static void tick(Minecraft client) {
        if (SaveMode.isPaused()) return;
        Session run = session;
        if (run == null) return;
        var player = client.player;
        if (AutoBuy.isEnabled() || player == null || !player.isAlive() || player.isSpectator()
                || client.level != run.level || client.getConnection() != run.connection || client.gameMode == null) {
            stop("Зупинено");
            return;
        }
        long now = System.nanoTime();
        if (run.phase == Phase.WAITING_FOR_SEARCH) {
            if (client.screen != null && !(client.screen instanceof AbstractContainerScreen<?> screen
                    && screen.getMenu() instanceof ChestMenu)) {
                stop("Пошук перервано");
                return;
            }
            if (now >= run.nextAction && now >= nextAuctionCommandAt) {
                run.searchAttempts++;
                run.phase = Phase.SEARCHING;
                run.deadline = now + REPLY_TIMEOUT;
                nextAuctionCommandAt = now + COMMAND_INTERVAL;
                run.connection.sendCommand("ah search " + run.query);
            }
            return;
        }
        if (run.phase == Phase.SEARCHING) {
            readSearch(client, run, now);
            return;
        }
        if (run.phase == Phase.WAITING_FOR_LISTING) {
            if (run.receivedListing && player.getInventory().getItem(run.hotbarSlot).isEmpty()) {
                run.phase = Phase.READY;
                run.listed++;
                status = "Виставлено стаків: " + run.listed;
            } else if (now >= run.deadline) {
                stop("Продаж не підтверджено. Перевір свої лоти перед повтором");
            }
            return;
        }
        if (client.screen != null || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()) {
            stop("Зупинено через відкрите меню");
            return;
        }
        if (now < run.nextAction || now < nextAuctionCommandAt) return;
        if (run.phase == Phase.SELECTED) {
            listSelected(client, run, now);
            return;
        }
        if (!run.displacedItem.isEmpty()) {
            restoreHotbar(client, run, now);
            return;
        }
        prepareStack(client, run, now);
    }

    private static void search(Session run, long earliestAt) {
        if (run.searchAttempts >= MAX_SEARCH_ATTEMPTS) {
            stop("Не знайдено порівнюваних лотів після оновлень ринку");
            return;
        }
        run.results = null;
        run.phase = Phase.WAITING_FOR_SEARCH;
        run.nextAction = Math.max(earliestAt, nextAuctionCommandAt);
        status = run.marketSamples == 0 ? "Читаю ринок" : "Оновлюю ринок і перевіряю ціни";
    }

    private static void readSearch(Minecraft client, Session run, long now) {
        if (now >= run.deadline) {
            retrySearch(run, now, 0);
            return;
        }
        if (client.screen == null) return;
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)
                || !(screen.getMenu() instanceof ChestMenu menu)) {
            stop("Пошук перервано");
            return;
        }
        if (run.results != menu || now - run.marketUpdatedAt < MARKET_SETTLE_DELAY) return;
        Optional<BigDecimal> estimate = quote(menu, run.sample, client.getUser().getName());
        if (estimate.isEmpty()) {
            return;
        }
        if (!menu.getCarried().isEmpty()) {
            stop("Спочатку звільни курсор від предмета");
            return;
        }
        if (++run.marketSamples < 2) {
            search(run, now + MARKET_REFRESH_DELAY);
            return;
        }
        run.unitPrice = estimate.get();
        status = "Середня ціна: " + run.unitPrice.setScale(2, RoundingMode.HALF_UP).toPlainString() + " за шт.";
        run.quoteExpires = now + QUOTE_LIFETIME;
        run.phase = Phase.READY;
        run.nextAction = Math.max(run.nextAction, now + INVENTORY_DELAY);
        client.player.closeContainer();
    }

    private static void retrySearch(Session run, long now, long serverDelay) {
        if (run.searchAttempts >= MAX_SEARCH_ATTEMPTS) {
            stop("Не знайдено порівнюваних лотів після оновлень ринку");
            return;
        }
        long delay = Math.max(serverDelay, RETRY_INTERVAL * run.searchAttempts);
        nextAuctionCommandAt = Math.max(nextAuctionCommandAt, now + delay);
        search(run, nextAuctionCommandAt);
        status = "Повторний пошук після паузи " + (delay / SECOND) + " с";
    }

    private static void prepareStack(Minecraft client, Session run, long now) {
        var player = client.player;
        var inventory = player.getInventory();
        int source = -1;
        int empty = -1;
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (matchesStock(stack, run.sample)) {
                source = slot;
                break;
            }
            if (empty < 0 && slot < HOTBAR_SIZE && stack.isEmpty()) empty = slot;
        }
        if (source < 0) {
            stop("Готово. Виставлено стаків: " + run.listed);
            return;
        }
        if (now + INVENTORY_DELAY >= run.quoteExpires) {
            run.marketSamples = 0;
            run.searchAttempts = 0;
            search(run, now);
            return;
        }
        ItemStack stack = inventory.getItem(source);
        run.count = stack.getCount();
        run.price = stackPrice(run.unitPrice, run.count);
        if (run.price <= 0) {
            stop("Ціна лота поза допустимим діапазоном");
            return;
        }
        run.sourceSlot = -1;
        run.displacedItem = ItemStack.EMPTY;
        run.hotbarSlot = source < HOTBAR_SIZE ? source
                : empty >= 0 ? empty : inventory.getSelectedSlot();
        if (source >= HOTBAR_SIZE) {
            run.sourceSlot = source;
            run.displacedItem = inventory.getItem(run.hotbarSlot).copy();
            client.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, source, run.hotbarSlot,
                    ClickType.SWAP, player);
        }
        inventory.setSelectedSlot(run.hotbarSlot);
        run.connection.send(new ServerboundSetCarriedItemPacket(run.hotbarSlot));
        run.nextAction = now + INVENTORY_DELAY;
        run.phase = Phase.SELECTED;
    }

    private static void restoreHotbar(Minecraft client, Session run, long now) {
        var player = client.player;
        var inventory = player.getInventory();
        if (!inventory.getItem(run.hotbarSlot).isEmpty()
                || !ItemStack.matches(inventory.getItem(run.sourceSlot), run.displacedItem)) {
            stop("Інвентар змінився. Перевір предмет у рюкзаку");
            return;
        }
        client.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, run.sourceSlot, run.hotbarSlot,
                ClickType.SWAP, player);
        run.displacedItem = ItemStack.EMPTY;
        run.sourceSlot = -1;
        run.nextAction = now + INVENTORY_DELAY;
    }

    private static void listSelected(Minecraft client, Session run, long now) {
        var inventory = client.player.getInventory();
        ItemStack held = inventory.getSelectedItem();
        if (inventory.getSelectedSlot() != run.hotbarSlot || held.getCount() != run.count
                || !matchesStock(held, run.sample)) {
            stop("Предмет у руці змінився. Продаж зупинено");
            return;
        }
        if (now >= run.quoteExpires) {
            stop("Ціна застаріла. Запусти AutoSell повторно для нового пошуку");
            return;
        }
        run.phase = Phase.WAITING_FOR_LISTING;
        run.receivedListing = false;
        run.deadline = now + REPLY_TIMEOUT;
        run.nextAction = now + LIST_INTERVAL;
        nextAuctionCommandAt = run.nextAction;
        status = "Виставляю " + run.count + " шт. за " + run.price;
        run.connection.sendCommand("ah sell " + run.price);
    }

    static Optional<BigDecimal> quote(ChestMenu menu, ItemStack sample, String playerName) {
        Map<String, BigDecimal> bySeller = new HashMap<>();
        ItemStack identity = sellIdentity(sample);
        if (identity.isEmpty()) return Optional.empty();
        int size = menu.getContainer().getContainerSize();
        int lotSlots = size == 54 ? 45 : size;
        for (int slot = 0; slot < lotSlots; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack.isEmpty() || !ItemStack.isSameItem(stack, sample)) continue;
            var lot = FunTimeAuction.parseLot(slot, stack).orElse(null);
            if (lot == null || lot.seller().isEmpty() || lot.seller().equalsIgnoreCase(playerName)
                    || !serverListing(stack)) continue;
            ItemStack merchandise = FunTimeAuction.merchandise(stack);
            // Vanilla items have a default empty lore component.
            merchandise.set(DataComponents.LORE, ItemLore.EMPTY);
            if (!matchesStock(merchandise, identity)) continue;
            BigDecimal unit = BigDecimal.valueOf(lot.totalPrice())
                    .divide(BigDecimal.valueOf(stack.getCount()), MathContext.DECIMAL128);
            bySeller.merge(lot.seller().toLowerCase(Locale.ROOT), unit, BigDecimal::min);
        }
        var prices = bySeller.values().stream().sorted().toList();
        if (prices.isEmpty()) return Optional.empty();
        var comparable = prices;
        if (prices.size() >= 3) {
            BigDecimal median = prices.get((prices.size() - 1) / 2);
            BigDecimal lower = median.divide(BigDecimal.valueOf(3), MathContext.DECIMAL128);
            BigDecimal upper = median.multiply(BigDecimal.valueOf(3));
            comparable = prices.stream().filter(price -> price.compareTo(lower) >= 0 && price.compareTo(upper) <= 0).toList();
        }
        BigDecimal sum = comparable.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(sum.divide(BigDecimal.valueOf(comparable.size()), MathContext.DECIMAL128));
    }

    private static boolean serverListing(ItemStack stack) {
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        var lines = lore.lines().stream().map(line -> FunTimeAuction.label(line.getString()))
                .filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) return false;
        if (lines.getFirst().equals("➥ нажмите, чтобы купить")) return true;
        return isShulker(stack.getItem()) && (lines.size() == 4 || lines.size() == 5)
                && lines.getFirst().startsWith("$ цена:")
                && lines.get(3).equals("[лкм] посмотреть содержимое")
                && (lines.size() == 4
                    ? lines.get(1).startsWith("⭐ продавец:") && lines.get(2).startsWith("⟲ истекает:")
                    : lines.getLast().equals("[шифт+лкм] купить шалкер"));
    }

    private static boolean matchesStock(ItemStack stack, ItemStack identity) {
        return !stack.isEmpty() && ItemStack.isSameItem(stack, identity)
                && ItemStack.isSameItemSameComponents(sellIdentity(stack), identity);
    }

    private static ItemStack sellIdentity(ItemStack stack) {
        if (!isShulker(stack.getItem())) return stack;
        if (stack.has(DataComponents.CONTAINER_LOOT) || stack.getOrDefault(DataComponents.CONTAINER,
                ItemContainerContents.EMPTY).nonEmptyStream().findAny().isPresent()) return ItemStack.EMPTY;

        var blockEntity = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (blockEntity != null) {
            var tag = blockEntity.copyTagWithoutId();
            boolean empty = tag.isEmpty() || tag.size() == 1 && tag.getList("Items").filter(list -> list.isEmpty()).isPresent();
            if (blockEntity.type() != BlockEntityType.SHULKER_BOX || !empty) return ItemStack.EMPTY;
        }

        ItemStack identity = stack.copy();
        identity.set(DataComponents.CONTAINER, identity.getPrototype().get(DataComponents.CONTAINER));
        if (blockEntity != null) identity.remove(DataComponents.BLOCK_ENTITY_DATA);
        var name = stack.get(DataComponents.CUSTOM_NAME);
        if (name != null && isEmptyShulkerName(name.getString())) identity.remove(DataComponents.CUSTOM_NAME);
        CustomData customData = identity.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            var data = customData.copyTag();
            data.getCompound("PublicBukkitValues").ifPresent(values -> {
                // FunTime gives otherwise identical items their own serial number.
                if (values.getByteArray("minecraft:s").filter(serial -> serial.length == 16).isEmpty()) return;
                values.remove("minecraft:s");
                if (values.isEmpty()) data.remove("PublicBukkitValues");
                if (data.isEmpty()) identity.remove(DataComponents.CUSTOM_DATA);
                else identity.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
            });
        }
        return identity;
    }

    static String defaultSearch(ItemStack stack) {
        return isShulker(stack.getItem()) ? EMPTY_SHULKER_QUERY : stack.getHoverName().getString();
    }

    private static boolean isShulker(Item item) {
        return item instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock;
    }

    private static boolean isEmptyShulkerName(String name) {
        String label = FunTimeAuction.label(name);
        return label.equals(EMPTY_SHULKER_QUERY) || label.equals("пустой шалкер");
    }

    private static boolean isDefaultShulkerName(String name) {
        return isEmptyShulkerName(name) || switch (FunTimeAuction.label(name)) {
            case "шалкерова коробка", "шалкеровый ящик", "shulker box" -> true;
            default -> false;
        };
    }

    static long stackPrice(BigDecimal unitPrice, int count) {
        try {
            return unitPrice.multiply(BigDecimal.valueOf(count)).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException tooLarge) {
            return -1;
        }
    }

    static boolean matchesReceipt(String message, int count, long price) {
        var receipt = LISTED.matcher(message);
        return receipt.matches() && (receipt.group(2) == null ? count == 1 : receipt.group(2).equals(Integer.toString(count)))
                && FunTimeAuction.parsePrice(receipt.group(3)).orElse(-1) == price;
    }

    private static ItemStack findItem(Minecraft client, String input) {
        if (input.isBlank()) return ItemStack.EMPTY;
        Item item = resolveItem(input);
        ItemStack held = client.player.getMainHandItem();
        if (selectable(held, item, input)) return held;
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (selectable(stack, item, input)) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static boolean selectable(ItemStack stack, Item item, String input) {
        if (stack.isEmpty() || !(stack.getItem() == item || stack.getHoverName().getString().equalsIgnoreCase(input)
                || isShulker(stack.getItem()) && isDefaultShulkerName(input))) return false;
        return isShulker(stack.getItem()) ? !sellIdentity(stack).isEmpty()
                : matchesStock(stack, new ItemStack(stack.getItem()));
    }

    private static Item resolveItem(String input) {
        Identifier id = Identifier.tryParse(input.toLowerCase(Locale.ROOT));
        return id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    private static boolean isSupportedServer(Minecraft client) {
        var server = client.getCurrentServer();
        if (server == null || server.ip == null) return false;
        try {
            String host = HostAndPort.fromString(server.ip.strip()).getHost().toLowerCase(Locale.ROOT);
            return host.equals("funtime.su") || host.endsWith(".funtime.su");
        } catch (IllegalArgumentException invalidAddress) {
            return false;
        }
    }

    static void stop(String reason) {
        if (session == null) return;
        session = null;
        status = reason;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.gui.getChat().addMessage(Component.literal("[Dash AutoSell] " + reason));
        }
    }

    private static boolean invalid(String reason) {
        status = reason;
        return false;
    }

    private enum Phase { WAITING_FOR_SEARCH, SEARCHING, READY, SELECTED, WAITING_FOR_LISTING }

    private static final class Session {
        final ClientLevel level;
        final ClientPacketListener connection;
        final ItemStack sample;
        final String query;
        Phase phase;
        ChestMenu results;
        long marketUpdatedAt;
        int searchAttempts;
        int marketSamples;
        BigDecimal unitPrice;
        long quoteExpires;
        long nextAction;
        long deadline;
        boolean receivedListing;
        int hotbarSlot;
        int sourceSlot = -1;
        ItemStack displacedItem = ItemStack.EMPTY;
        int count;
        long price;
        int listed;

        Session(ClientLevel level, ClientPacketListener connection, ItemStack sample, String query) {
            this.level = level;
            this.connection = connection;
            this.sample = sample;
            this.query = query;
        }
    }
}
