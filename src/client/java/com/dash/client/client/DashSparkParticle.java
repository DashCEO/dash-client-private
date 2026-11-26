package com.dash.client.client;

import com.dash.client.DashClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

final class DashSparkParticle extends SingleQuadParticle {
    private static final Identifier SPRITE_ID = DashClient.id("dash_spark");
    private static final int SPARK_COUNT = 5;
    private static final float FADE_TICKS = 6.0F;
    private final float spin;

    private DashSparkParticle(ClientLevel level, TextureAtlasSprite sprite,
                              double x, double y, double z, double vx, double vy, double vz) {
        super(level, x, y, z, sprite);
        setParticleSpeed(vx, vy, vz);
        quadSize = .13F + random.nextFloat() * .045F;
        lifetime = 13 + random.nextInt(5);
        friction = .82F;
        gravity = .07F;
        hasPhysics = false;
        roll = oRoll = (random.nextFloat() - .5F) * .52F;
        spin = (random.nextFloat() - .5F) * .08F;
    }

    static boolean spawn(Minecraft client, Player target) {
        var atlas = client.getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES);
        TextureAtlasSprite sprite = atlas.getSprite(SPRITE_ID);
        if (!SPRITE_ID.equals(sprite.contents().name())) return false;

        ClientLevel level = client.level;
        var random = level.random;
        double radius = target.getBbWidth() * .52;
        double start = random.nextDouble() * Math.TAU;
        for (int i = 0; i < SPARK_COUNT; i++) {
            double angle = start + Math.TAU * i / SPARK_COUNT + (random.nextDouble() - .5) * .35;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            double speed = .13 + random.nextDouble() * .08;
            double y = target.getY() + target.getBbHeight() * (.42 + random.nextDouble() * .30);
            client.particleEngine.add(new DashSparkParticle(level, sprite,
                    target.getX() + dx * radius, y, target.getZ() + dz * radius,
                    dx * speed, .07 + random.nextDouble() * .09, dz * speed));
        }
        return true;
    }

    @Override
    public void tick() {
        if (SaveMode.isPaused()) {
            remove();
            return;
        }
        super.tick();
        oRoll = roll;
        roll += spin;
        setAlpha(Math.max(0.0F, Math.min(1.0F, (lifetime - age) / FADE_TICKS)));
    }

    @Override
    protected int getLightColor(float partialTick) {
        return LightTexture.FULL_BRIGHT;
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }
}
