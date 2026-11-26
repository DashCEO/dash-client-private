package com.dash.client.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/** Reads the item's supplied stats instead of assigning strength by accessory name. */
final class AutoSwapItems {
    private static final Pattern ACCESSORY = Pattern.compile(
            "(?:^|[^\\p{L}])(?:сфера|талисман|талісман|sphere|talisman)(?:$|[^\\p{L}])");
    private static final Pattern AMOUNT = Pattern.compile("([+\\-])\\s*(\\d+(?:[.,]\\d+)?)\\s*(%)?");
    private static final double REFERENCE_HIT_DAMAGE = 8.0;

    private AutoSwapItems() {}

    static boolean isAccessory(ItemStack stack) {
        return !stack.isEmpty() && !stack.is(ItemTags.SWORDS)
                && isAccessoryName(stack.getHoverName().getString());
    }

    static boolean isAccessoryName(String name) {
        return ACCESSORY.matcher(FunTimeAuction.label(name)).find();
    }

    static double score(Player player, ItemStack stack, boolean defense) {
        if (!stack.isEmpty() && !isAccessory(stack)) return Double.NaN;
        boolean attributes = hasAttributes(stack);
        Map<Stat, List<Adjustment>> lore = attributes ? Map.of() : loreStats(stack);
        if (!stack.isEmpty() && !attributes && lore.isEmpty()) return Double.NaN;

        double damage = value(player, stack, Stat.DAMAGE, lore);
        double armor = value(player, stack, Stat.ARMOR, lore);
        double toughness = value(player, stack, Stat.TOUGHNESS, lore);
        double maxHealth = value(player, stack, Stat.HEALTH, lore);
        if (!Double.isFinite(damage) || !Double.isFinite(armor) || !Double.isFinite(toughness)
                || !Double.isFinite(maxHealth) || maxHealth <= 0) return Double.NaN;

        if (!defense) {
            // Damage is primary. Attack speed only breaks effectively equal damage scores.
            double speed = value(player, stack, Stat.SPEED, lore);
            return Math.max(0, damage) + Math.max(0, Math.min(20, speed)) * 0.0001;
        }

        armor = Math.max(0, Math.min(30, armor));
        toughness = Math.max(0, Math.min(20, toughness));
        // Vanilla armor reduction for a representative four-heart hit. Current HP is
        // capped when a candidate lowers max health; extra max HP does not heal us.
        double protectedArmor = Math.max(armor * 0.2,
                Math.min(20, armor - REFERENCE_HIT_DAMAGE / (2 + toughness / 4)));
        double takenFraction = Math.max(0.2, 1 - protectedArmor / 25);
        double availableHealth = Math.min(Math.max(0, player.getHealth()), maxHealth)
                + Math.max(0, player.getAbsorptionAmount());
        return availableHealth / takenFraction + maxHealth * 0.001;
    }

    private static boolean hasAttributes(ItemStack stack) {
        for (var entry : stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).modifiers()) {
            if (entry.slot().test(EquipmentSlot.OFFHAND) && stat(entry.attribute()) != null) return true;
        }
        return false;
    }

    private static double value(Player player, ItemStack candidate, Stat stat, Map<Stat, List<Adjustment>> lore) {
        var instance = player.getAttribute(stat.attribute);
        double base = instance == null ? stat.fallback : instance.getBaseValue();
        Set<Identifier> replaced = new HashSet<>();
        collectIds(player.getOffhandItem(), stat, replaced);
        collectIds(candidate, stat, replaced);
        List<AttributeModifier> modifiers = new ArrayList<>();
        if (instance != null) {
            for (var modifier : instance.getModifiers()) {
                if (!replaced.contains(modifier.id())) modifiers.add(modifier);
            }
        }
        candidate.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                .forEach(EquipmentSlot.OFFHAND, (attribute, modifier) -> {
                    if (attribute.equals(stat.attribute)) modifiers.add(modifier);
                });

        double additive = base;
        for (var modifier : modifiers) {
            if (modifier.operation() == AttributeModifier.Operation.ADD_VALUE) additive += modifier.amount();
        }
        double result = additive;
        for (var modifier : modifiers) {
            if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) {
                result += additive * modifier.amount();
            }
        }
        for (var modifier : modifiers) {
            if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) result *= 1 + modifier.amount();
        }
        var additions = lore.get(stat);
        if (additions != null) {
            double flat = 0;
            double percent = 0;
            for (Adjustment adjustment : additions) {
                if (adjustment.percent) percent += adjustment.amount;
                else flat += adjustment.amount;
            }
            result = (result + flat) * (1 + percent / 100);
        }
        return result;
    }

    private static void collectIds(ItemStack stack, Stat stat, Set<Identifier> ids) {
        stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                .forEach(EquipmentSlot.OFFHAND, (attribute, modifier) -> {
                    if (attribute.equals(stat.attribute)) ids.add(modifier.id());
                });
    }

    private static Map<Stat, List<Adjustment>> loreStats(ItemStack stack) {
        Map<Stat, List<Adjustment>> result = new EnumMap<>(Stat.class);
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) return result;
        for (var component : lore.lines()) {
            String line = FunTimeAuction.label(component.getString()).replace('\u2212', '-');
            Stat stat = loreStat(line);
            if (stat == null) continue;
            var amount = AMOUNT.matcher(line);
            // An unsigned number or a line with several amounts is ambiguous.
            if (!amount.find()) continue;
            double value;
            try {
                value = Double.parseDouble(amount.group(2).replace(',', '.'));
            } catch (NumberFormatException exception) {
                continue;
            }
            if (amount.group(1).equals("-")) value = -value;
            boolean percent = amount.group(3) != null;
            if (amount.find() || !Double.isFinite(value)) continue;
            result.computeIfAbsent(stat, ignored -> new ArrayList<>()).add(new Adjustment(value, percent));
        }
        return result;
    }

    private static Stat loreStat(String line) {
        // Server effects such as critical damage, regeneration or armor penetration
        // are not ordinary attributes. Only accept an unambiguous attribute label.
        String label = AMOUNT.matcher(line).replaceAll("").replace("'", "").replace("’", "")
                .replaceAll("[^\\p{L} ]", " ").strip().replaceAll("\\s+", " ")
                .replaceFirst("^(?:к|до) ", "");
        if (label.matches("скорость атаки|скорости атаки|швидкість атаки|швидкості атаки|attack speed")) return Stat.SPEED;
        if (label.matches("твердость брони|твёрдость брони|твердості броні|твердість броні|armor toughness|toughness")) return Stat.TOUGHNESS;
        if (label.matches("(?:максимальное |максимальному )?здоровь[ею]|(?:максимальне |максимального )?здоровя|(?:max |maximum )?health")) return Stat.HEALTH;
        if (label.matches("броня|броне|броні|armor|armour")) return Stat.ARMOR;
        if (label.matches("урон|урону|шкода|шкоди|шкоді|attack damage|damage|сила атаки|силе атаки")) return Stat.DAMAGE;
        return null;
    }

    private static Stat stat(Holder<Attribute> attribute) {
        for (Stat stat : Stat.values()) if (stat.attribute.equals(attribute)) return stat;
        return null;
    }

    private enum Stat {
        DAMAGE(Attributes.ATTACK_DAMAGE, 1),
        SPEED(Attributes.ATTACK_SPEED, 4),
        ARMOR(Attributes.ARMOR, 0),
        TOUGHNESS(Attributes.ARMOR_TOUGHNESS, 0),
        HEALTH(Attributes.MAX_HEALTH, 20);

        final Holder<Attribute> attribute;
        final double fallback;

        Stat(Holder<Attribute> attribute, double fallback) {
            this.attribute = attribute;
            this.fallback = fallback;
        }
    }

    private record Adjustment(double amount, boolean percent) {}
}
