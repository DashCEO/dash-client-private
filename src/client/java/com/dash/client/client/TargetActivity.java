package com.dash.client.client;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

final class TargetActivity {
    private static final long RECENT_USE_NANOS = 2_500_000_000L;
    private static final Map<LivingEntity, ObservedUse> recentUses = new IdentityHashMap<>();

    private TargetActivity() {
    }

    static void tick(Minecraft client, LivingEntity target) {
        if (SaveMode.isPaused() || client.level == null || client.player == null) {
            reset();
            return;
        }

        long now = System.nanoTime();
        recentUses.entrySet().removeIf(entry -> entry.getKey().level() != client.level
                || !entry.getKey().isAlive() || now - entry.getValue().observedAt() >= RECENT_USE_NANOS);

        for (AbstractClientPlayer player : client.level.players()) {
            if (player != client.player && player.isAlive()) {
                observe(player, now);
            }
        }
        if (target != null && !(target instanceof AbstractClientPlayer)
                && target.level() == client.level && target.isAlive()) {
            observe(target, now);
        }
    }

    static void reset() {
        recentUses.clear();
    }

    static ItemStack activeItem(LivingEntity entity) {
        return entity.isUsingItem() ? entity.getUseItem() : ItemStack.EMPTY;
    }

    static ItemStack recentItem(LivingEntity entity) {
        ObservedUse use = recentUses.get(entity);
        if (use == null || System.nanoTime() - use.observedAt() >= RECENT_USE_NANOS) {
            return ItemStack.EMPTY;
        }
        return use.item();
    }

    private static void observe(LivingEntity entity, long now) {
        ItemStack item = activeItem(entity);
        if (item.isEmpty()) {
            return;
        }

        ObservedUse previous = recentUses.get(entity);
        ItemStack snapshot = previous != null && ItemStack.isSameItemSameComponents(previous.item(), item)
                ? previous.item() : item.copyWithCount(1);
        recentUses.put(entity, new ObservedUse(snapshot, now));
    }

    private record ObservedUse(ItemStack item, long observedAt) {
    }
}
