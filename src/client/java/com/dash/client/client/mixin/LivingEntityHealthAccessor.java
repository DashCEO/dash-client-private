package com.dash.client.client.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface LivingEntityHealthAccessor {
    @Accessor("DATA_HEALTH_ID")
    static EntityDataAccessor<Float> dash$healthData() {
        throw new AssertionError();
    }
}
