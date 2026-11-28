package com.dash.client.client;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

public final class FunTimeAuction {
    private static final Pattern FORMATTING = Pattern.compile("(?i)§[0-9A-FK-ORX]");
    private static final Pattern CONTROL_DECORATION = Pattern.compile("^[^\\p{L}]+|[^\\p{L}]+$");
    private static final Pattern PACK_SEARCH_TITLE = Pattern.compile(
            "^0A2z\\x{CFF98}\\d+/\\d+\\x{CFFAE}\\s+漢:\\s*\\S.*$");
    private static final Pattern PURCHASE_RECEIPT = Pattern.compile(
            "^\\[☃\\] Вы успешно купили (.+?)(?:\\s+[xх×]([1-9]\\d*))? за \\$([0-9][0-9,. ]*)!$");
    private static final Pattern PRICE = Pattern.compile(
            "^[^\\p{L}\\d+\\-]*(?:цена|ціна|стоимость|вартість|price)(?:\\s+лота)?[\\s:$»|]*(.*?)\\s*$");
    private static final Pattern AMOUNT = Pattern.compile(
            "^[$₽⛃]?\\s*(\\d[\\d\\s,.]*?)(?:\\s*(k{1,2}|к{1,2}|тыс\\.?|тис\\.?|млн\\.?))?\\s*(?:[$₽⛃]|монет(?:а|и|ы)?|coins?)?$");
    private static final Pattern UNIT_PRICE = Pattern.compile(
            ".*(?:(?:цена|ціна|вартість)\\s+за\\s*(?:1\\s*(?:шт|ед|од)|штуку|единицу|одиницю)|price\\s+per\\s+(?:item|unit)).*");
    private static final Pattern UNIT_PRICE_LINE = Pattern.compile(
            "^[^\\p{L}\\d]*(?:(?:цена|ціна|вартість)\\s+за\\s*(?:1\\s*(?:шт|ед|од)\\.?|штуку|единицу|одиницю)"
                    + "|price\\s+per\\s+(?:item|unit))[\\s:$»|]*(.*?)\\s*$");
    private static final Pattern BUY_PROMPT = Pattern.compile(
            "^[^\\p{L}\\d]*(?:нажмите,?\\s+чтобы\\s+купить|натисніть,?\\s+щоб\\s+купити|click\\s+to\\s+buy)[.!]?$");
    private static final Pattern EXPIRY = Pattern.compile(
            "^[^\\p{L}\\d]*(?:истекает|спливає|expires(?:\\s+in)?)\\s*:\\s*"
                    + "(?:\\d+\\s*(?:д\\.|ч\\.|г\\.|год\\.|мин\\.|хв\\.|сек\\.|с\\.|[dhms])\\s*)+$");

    private FunTimeAuction() {}

    public record Lot(int slotIndex, ItemStack stack, long totalPrice, String seller) {}

    private record AuctionTerms(long price, String seller, int priceLine, int amountLine, int sellerLine) {}

    public static String plain(String text) {
        return FORMATTING.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC))
                .replaceAll("").replace('\u00A0', ' ').replace('\u202F', ' ')
                .replaceAll("\\p{Cf}", "").trim();
    }

    public static String label(String text) {
        String value = plain(text).toLowerCase(Locale.ROOT);
        if (value.codePoints().noneMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC)) {
            return value;
        }
        // The auction uses visually identical Latin letters in some Russian labels.
        return value.replace('a', 'а').replace('c', 'с').replace('e', 'е')
                .replace('o', 'о').replace('p', 'р').replace('x', 'х')
                .replace('y', 'у').replace('k', 'к').replace('m', 'м')
                .replace('t', 'т').replace('b', 'в').replace('h', 'н').replace('i', 'і');
    }

    public static boolean isAuctionTitle(String title) {
        String value = label(title);
        return value.contains("аукцион") || value.startsWith("поиск")
                || value.startsWith("auction") || value.startsWith("search");
    }

    static boolean isAuctionPickerScreen(String title, ChestMenu menu) {
        if (isConfirmationTitle(title)) return false;
        if (isAuctionTitle(title)) return true;

        // Resource packs can change the title glyphs and move the controls within the footer.
        int size = menu.getContainer().getContainerSize();
        if (size != 54) return false;
        boolean refresh = false;
        boolean storage = false;
        boolean rental = false;
        for (int slot = size - 9; slot < size; slot++) {
            ItemStack item = menu.getSlot(slot).getItem();
            if (!item.is(Items.PAPER)) continue;
            String control = CONTROL_DECORATION.matcher(label(item.getHoverName().getString())).replaceAll("");
            switch (control) {
                case "обновить" -> refresh = true;
                case "хранилище" -> storage = true;
                case "аренда слотов" -> rental = true;
            }
        }
        return refresh && storage && rental;
    }

    public static boolean isAuctionScreen(String title, ChestMenu menu) {
        if (isAuctionTitle(title)) return true;
        if (menu.getContainer().getContainerSize() != 54
                || !PACK_SEARCH_TITLE.matcher(plain(title)).matches()) return false;
        // The search header is drawn by the server pack, so verify its footer too.
        return (paperControl(menu, 45, "[⟲] обновить") || paperControl(menu, 49, "[⟲] обновить"))
                && paperControl(menu, 46, "[☃] хранилище")
                && paperControl(menu, 53, "[☃] аренда слотов");
    }

    private static boolean paperControl(ChestMenu menu, int slot, String label) {
        ItemStack item = menu.getSlot(slot).getItem();
        return item.is(Items.PAPER) && label(item.getHoverName().getString()).equals(label);
    }

    public static boolean isConfirmationTitle(String title) {
        String value = label(title);
        return value.contains("подтверждение покупки") || value.contains("подозрительная цена")
                || value.contains("confirm purchase");
    }

    public static boolean isBuyAction(String name) {
        String value = label(name);
        if (isCancelAction(value)) return false;
        return value.contains("купить") || value.contains("купити") || value.contains("подтверд")
                || value.contains("підтверд") || value.contains("приобрести")
                || value.equals("buy") || value.equals("confirm")
                || value.matches("[\\s✓✔✅]+") && !value.isBlank();
    }

    public static boolean isCancelAction(String name) {
        String value = label(name);
        return value.contains("отмен") || value.contains("скасув") || value.contains("cancel")
                || value.matches("[\\s✕✖✗✘❌×]+") && !value.isBlank();
    }

    public static boolean matchesPurchaseReceipt(String message, Lot lot) {
        Matcher receipt = PURCHASE_RECEIPT.matcher(plain(message));
        if (!receipt.matches()) return false;
        String count = receipt.group(2);
        if (count == null ? lot.stack().getCount() != 1 : !count.equals(Integer.toString(lot.stack().getCount()))) {
            return false;
        }
        if (parsePrice(receipt.group(3)).orElse(-1) != lot.totalPrice()) return false;
        String name = label(receipt.group(1));
        if (name.equals(label(lot.stack().getHoverName().getString()))) return true;
        // The server writes receipts in Russian regardless of the client locale.
        return lot.stack().is(Items.TOTEM_OF_UNDYING) && name.equals("тотем бессмертия")
                || lot.stack().is(Items.GOLD_INGOT) && name.equals("золотой слиток")
                || lot.stack().is(Items.NETHERITE_INGOT) && name.equals("незеритовый слиток");
    }

    public static Optional<Lot> parseLot(int slot, ItemStack stack) {
        if (stack.isEmpty() || stack.getCount() <= 0) return Optional.empty();
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) return Optional.empty();
        AuctionTerms terms = auctionTerms(lore.lines());
        return terms == null ? Optional.empty() : Optional.of(new Lot(slot, stack.copy(), terms.price(), terms.seller()));
    }

    private static AuctionTerms auctionTerms(List<Component> lines) {
        Long price = null;
        String seller = "";
        int priceIndex = -1;
        int amountIndex = -1;
        int sellerIndex = -1;
        for (int index = 0; index < lines.size(); index++) {
            String raw = plain(lines.get(index).getString());
            String normalized = label(raw);
            if (UNIT_PRICE.matcher(normalized).matches()) continue;
            Matcher priceLine = PRICE.matcher(normalized);
            if (priceLine.matches()) {
                if (price != null) return null;
                priceIndex = index;
                String amount = priceLine.group(1);
                if (amount.isBlank() && index + 1 < lines.size()) {
                    amount = plain(lines.get(++index).getString());
                }
                amountIndex = index;
                OptionalLong parsed = parsePrice(amount);
                if (parsed.isEmpty()) return null;
                price = parsed.getAsLong();
            }
            // Keep the seller's actual spelling while normalizing only the label.
            int colon = raw.lastIndexOf(':');
            if (colon >= 0 && label(raw.substring(0, colon)).replaceAll("[^\\p{L}]", "")
                    .matches("продавец|продавець|seller")) {
                String candidate = raw.substring(colon + 1).trim().replaceFirst("\\s*\\$\\s*$", "").trim();
                if (!candidate.matches("[A-Za-z0-9_]{1,32}") || !seller.isEmpty()) return null;
                seller = candidate;
                sellerIndex = index;
            }
        }
        return price == null ? null : new AuctionTerms(price, seller, priceIndex, amountIndex, sellerIndex);
    }

    public static OptionalLong parsePrice(String text) {
        String value = plain(text).toLowerCase(Locale.ROOT);
        Matcher matcher = AMOUNT.matcher(value);
        if (!matcher.matches()) return OptionalLong.empty();
        String number = matcher.group(1).trim();
        String suffix = matcher.group(2);
        try {
            BigDecimal amount;
            if (suffix == null) {
                if (!number.matches("\\d+") && !number.matches("\\d{1,3}(?:[ ,. ]\\d{3})+")) {
                    return OptionalLong.empty();
                }
                amount = new BigDecimal(number.replaceAll("[\\s,.]", ""));
            } else {
                number = number.replace(" ", "").replace(',', '.');
                amount = new BigDecimal(number);
                long multiplier = suffix.equals("kk") || suffix.equals("кк") || suffix.startsWith("млн")
                        ? 1_000_000L : 1_000L;
                amount = amount.multiply(BigDecimal.valueOf(multiplier));
            }
            long result = amount.longValueExact();
            return result > 0 ? OptionalLong.of(result) : OptionalLong.empty();
        } catch (NumberFormatException | ArithmeticException exception) {
            return OptionalLong.empty();
        }
    }

    public static ItemStack merchandise(ItemStack stack) {
        return merchandise(stack, false);
    }

    static ItemStack merchandise(ItemStack stack, boolean preserveItemLore) {
        ItemStack result = stack.copy();
        if (preserveItemLore) {
            removeAuctionLore(result);
        } else {
            result.remove(DataComponents.LORE);
        }
        CustomData customData = result.get(DataComponents.CUSTOM_DATA);
        if (customData != null && !customData.isEmpty()) {
            CompoundTag data = customData.copyTag();
            data.getCompound("PublicBukkitValues").ifPresent(values -> {
                // FunTime regenerates this menu UUID between listing and confirmation.
                if (values.getByteArray("auctions:if-uuid").filter(bytes -> bytes.length == 16).isEmpty()) return;
                values.remove("auctions:if-uuid");
                if (values.isEmpty()) data.remove("PublicBukkitValues");
                if (data.isEmpty()) result.remove(DataComponents.CUSTOM_DATA);
                else result.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
            });
        }
        return result;
    }

    private static void removeAuctionLore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null || lore.lines().isEmpty()) return;
        List<Component> lines = lore.lines();
        AuctionTerms terms = auctionTerms(lines);
        if (terms == null) return;

        // Only a trailing block anchored by a valid total and seller or auction prompt
        // can be removed. The original item text and its component styling stay intact.
        int start = terms.priceLine();
        int end = terms.amountLine() + 1;
        while (start > 0 && isAuctionDecoration(lines, start - 1, terms.sellerLine())) start--;
        while (end < lines.size() && isAuctionDecoration(lines, end, terms.sellerLine())) end++;
        if (end != lines.size()) return;
        if (terms.sellerLine() >= 0) {
            if (terms.sellerLine() < start) return;
        } else if (lines.subList(start, terms.priceLine()).stream()
                .noneMatch(line -> BUY_PROMPT.matcher(label(line.getString())).matches())) {
            return;
        }

        if (start == 0) {
            stack.remove(DataComponents.LORE);
        } else {
            stack.set(DataComponents.LORE, new ItemLore(List.copyOf(lines.subList(0, start))));
        }
    }

    private static boolean isAuctionDecoration(List<Component> lines, int index, int sellerLine) {
        if (index == sellerLine) return true;
        String value = label(lines.get(index).getString());
        if (value.isBlank() || BUY_PROMPT.matcher(value).matches() || EXPIRY.matcher(value).matches()) return true;
        Matcher unitPrice = UNIT_PRICE_LINE.matcher(value);
        return unitPrice.matches() && parsePrice(unitPrice.group(1)).isPresent();
    }
}
