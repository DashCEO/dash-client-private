package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import com.dash.client.client.DashHudLayout.Widget;
import com.dash.client.client.mixin.MinecraftAttackInvoker;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarrotBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class DashClientClient implements ClientModInitializer {

    public static final String PLAYER_NAME = "Dash_client";

    private static KeyMapping openMenuKey;

    private static boolean hudEnabled = true;
    private static boolean fpsEnabled = true;
    private static boolean keysEnabled = true;
    private static boolean sprintEnabled = false;
    private static LocalPlayer dashSprintOwner;

    private static boolean targetHudEnabled = true;
    private static boolean hitParticlesEnabled = true;
    private static final int MAX_PENDING_HITS = 16;
    private static final long HIT_CONFIRMATION_NANOS = 750_000_000L;
    private static final ArrayDeque<PendingHit> pendingHits = new ArrayDeque<>();
    private static boolean totemCounterEnabled = true;
    private static boolean potionHudEnabled = true;
    private static boolean armorHudEnabled = true;
    private static boolean pingHudEnabled = true;
    private static boolean tpsHudEnabled = true;
    private static boolean radarHudEnabled = false;
    private static boolean keybindHudEnabled = false;
    private static boolean cooldownHudEnabled = true;

    private static boolean criticalAssistEnabled = true;
    private static boolean killAuraEnabled = false;
    private static boolean pvp18Enabled = false;

    private static boolean noFireEnabled = false;
    private static boolean autoTotemEnabled = true;
    private static boolean unInvisibilityEnabled = true;
    private static boolean targetRenderEnabled = true;
    private static boolean targetEspEnabled = false;

    private static boolean packetFlyEnabled = false;
    private static int packetFlyCounter = 0;

    private static boolean nightVisionEnabled = false;
    private static MobEffectInstance dashNightVisionEffect;

    // AIM ASSIST
    private static final AimAssist AIM_ASSIST = new AimAssist();
    private static boolean aimOwnsAttacksThisTick;
    private static String assistedAttackSource;
    private static boolean assistedAttackDispatched;

    // AUTO FARM
    private static boolean autoFarmEnabled = false;
    private static int autoFarmCooldown = 0;
    private static int boneMealAppliedCount = 0;

    private static int dashTickCounter = 0;

    private static final StringBuilder SB = new StringBuilder();
    private static final EquipmentSlot[] HUD_ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final float[] TARGET_CORNER = {
            .16F, 0, .88F, 0, .965F, .035F, 1, .12F, .965F, .205F, .88F, .24F,
            .37F, .24F, .28F, .28F, .24F, .37F,
            .24F, .88F, .205F, .965F, .12F, 1, .035F, .965F, 0, .88F,
            0, .16F, .045F, .045F
    };
    private static final float[] TARGET_CORNER_GLOW = {
            .16F, -.13F, .88F, -.13F, 1.06F, -.06F, 1.13F, .12F, 1.06F, .30F, .88F, .37F,
            .37F, .37F, .37F, .37F, .37F, .37F,
            .37F, .88F, .30F, 1.06F, .12F, 1.13F, -.06F, 1.06F, -.13F, .88F,
            -.13F, .16F, -.05F, -.05F
    };

    // ==================== GETTERS ====================

    public static boolean isNoFireEnabled() {
        return noFireEnabled && !SaveMode.isPaused();
    }

    public static boolean isUnInvisibilityEnabled() {
        return unInvisibilityEnabled && !SaveMode.isPaused();
    }

    public static boolean isNightVisionEnabled() {
        return nightVisionEnabled && !SaveMode.isPaused();
    }

    // ==================== PLAYER ESP STUBS (для сумісності з міксинами) ====================

    public static boolean isPlayerEspEnabled() {
        return false;
    }

    public static boolean shouldGlowPlayer(Player target) {
        return false;
    }

    // ==================== NIGHT VISION ====================

    private static void tickNightVision(Minecraft client) {
        LocalPlayer player = client.player;

        if (player == null) {
            dashNightVisionEffect = null;
            return;
        }

        MobEffectInstance current = player.getEffect(MobEffects.NIGHT_VISION);
        if (!isNightVisionEnabled()) {
            if (current != null && current == dashNightVisionEffect
                    && current.getAmplifier() == 0 && !current.isVisible() && !current.showIcon()) {
                player.removeEffect(MobEffects.NIGHT_VISION);
            }
            dashNightVisionEffect = null;
            return;
        }

        if (current == null || (current == dashNightVisionEffect && current.getDuration() < 40)) {
            player.addEffect(new MobEffectInstance(
                    MobEffects.NIGHT_VISION,
                    1000000,
                    0,
                    false,
                    false,
                    false
            ));
            dashNightVisionEffect = player.getEffect(MobEffects.NIGHT_VISION);
        }
    }

    static void onPause(Minecraft client) {
        PauseManager.step("AutoBuy", AutoBuy::pause);
        PauseManager.step("AutoSell", () -> AutoSell.stop(text("Зупинено", "Остановлено", "Stopped")));
        PauseManager.step("AutoSwap", AutoSwap::disable);
        PauseManager.step("Swap Bind", SwapBind::reset);
        PauseManager.step("Consumable Bind", () -> ConsumableBind.abort(client));
        InventoryBindSupport.clearReservation();
        PauseManager.step("Aim Assist", () -> {
            AIM_ASSIST.reset();
            AimCritTiming.markTick(null);
        });
        aimOwnsAttacksThisTick = false;
        assistedAttackSource = null;
        assistedAttackDispatched = false;
        killAuraEnabled = false;
        pvp18Enabled = false;
        autoTotemEnabled = false;
        autoFarmEnabled = false;
        packetFlyEnabled = false;
        autoFarmCooldown = boneMealAppliedCount = packetFlyCounter = dashTickCounter = 0;
        PauseManager.step("Trigger Bot", TriggerBot::disable);
        PauseManager.step("Freelook", Freelook::reset);
        PauseManager.step("Zoom", Zoom::reset);
        PauseManager.step("Target HUD", TargetActivity::reset);
        PauseManager.step("Velocity", Velocity::disable);
        pendingHits.clear();
        PauseManager.step("Hit Feedback", ManualHitFeedback::clear);
        PauseManager.step("Night Vision", () -> tickNightVision(client));
        PauseManager.step("Sprint", () -> releaseDashSprint(client));
        sprintEnabled = false;
        PauseManager.step("Auto Chunk Reload", AutoChunkReload::reset);
        PauseManager.step("Ore Highlight", OreHighlight::reset);
        PauseManager.step("Base Finder", BaseFinder::reset);
        PauseManager.step("Staff List", StaffList::reset);
        PauseManager.step("HUD telemetry", DashTelemetry::reset);
        PauseManager.step("Attack Speed diagnostics", AttackSpeedCheck::reset);
        PauseManager.step("HUD session", DashHudLayout::clearTransient);
        PauseManager.step("marks session", Marks::clearTransient);
        PauseManager.step("waypoints session", DashWaypoints::clearTransient);
        PauseManager.step("intro playback", () -> DashIntroVideo.finish(client));
    }

    private static void tickSprint(Minecraft client) {
        LocalPlayer player = client.player;
        if (client.options.keySprint.isDown()) {
            dashSprintOwner = null;
        } else if (!player.isSprinting()) {
            player.setSprinting(true);
            dashSprintOwner = player;
        }
    }

    private static void releaseDashSprint(Minecraft client) {
        LocalPlayer player = dashSprintOwner;
        dashSprintOwner = null;
        if (player != null && player == client.player && !client.options.keySprint.isDown()) {
            player.setSprinting(false);
        }
    }

    private static void registerModuleTick(ClientTickEvents.EndTick callback) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!SaveMode.isPaused()) callback.onEndTick(client);
        });
    }

    static Map<String, Boolean> profileModules() {
        Map<String, Boolean> states = new LinkedHashMap<>();
        for (var tab : DashClickGui.Tab.values()) {
            for (var module : tab.modules) {
                if (isProfileModule(module.label())) states.put(module.label(), module.isEnabled());
            }
        }
        return states;
    }

    record DashboardModule(String id, String category, Supplier<String> label, BooleanSupplier enabled,
                           Runnable toggle, Runnable configure) {}

    static List<DashboardModule> dashboardModules() {
        return DashboardModules.ALL;
    }

    private static final class DashboardModules {
        private static final List<DashboardModule> ALL = collect();

        private static List<DashboardModule> collect() {
            List<DashboardModule> choices = new ArrayList<>();
            for (var tab : DashClickGui.Tab.values()) {
                for (var module : tab.modules) {
                    String id = module.label();
                    String category = switch (tab) {
                        case COMBAT -> "Combat";
                        case MOVEMENT -> "Movement";
                        case VISUALS -> switch (id) {
                            case "Auto Chunk Reload", "Ore Highlight", "XRay", "Night Vision", "Background Blur" -> "Render";
                            default -> "Visual";
                        };
                        case UTILITY -> switch (id) {
                            case "Waypoints", "Marks" -> "Client";
                            default -> "Player";
                        };
                        default -> "Client";
                    };
                    choices.add(new DashboardModule(id, category, module::displayName, module.enabledState(),
                            module.activate(), module.configure()));
                }
            }
            return List.copyOf(choices);
        }
    }

    static boolean dashboardKey(KeyEvent event) {
        return openMenuKey != null && openMenuKey.matches(event);
    }

    static boolean hudWidgetFeatureEnabled(DashHudLayout.Widget widget) {
        return switch (widget) {
            case WATERMARK -> hudEnabled;
            case FPS -> fpsEnabled;
            case PING -> pingHudEnabled;
            case KEYSTROKES -> keysEnabled;
            case ARMOR -> armorHudEnabled;
            case POTIONS -> potionHudEnabled;
            case TARGET -> targetHudEnabled;
            case WAYPOINTS -> DashWaypoints.isEnabled();
            case STAFF -> StaffList.isEnabled();
            case KEYBINDS -> keybindHudEnabled;
            case RADAR -> radarHudEnabled;
            case SERVER_TPS -> tpsHudEnabled;
            case COOLDOWNS -> cooldownHudEnabled;
            case TOTEMS -> totemCounterEnabled;
            case HIT_FEEDBACK -> HudSettings.hitFeedback();
            default -> true;
        };
    }

    static void setHudWidgetEnabled(DashHudLayout.Widget widget, boolean enabled) {
        switch (widget) {
            case WATERMARK -> hudEnabled = enabled;
            case FPS -> fpsEnabled = enabled;
            case PING -> pingHudEnabled = enabled;
            case KEYSTROKES -> keysEnabled = enabled;
            case ARMOR -> armorHudEnabled = enabled;
            case POTIONS -> potionHudEnabled = enabled;
            case TARGET -> targetHudEnabled = enabled;
            case WAYPOINTS -> DashWaypoints.setEnabled(enabled);
            case STAFF -> {
                if (StaffList.isEnabled() != enabled) StaffList.toggle();
            }
            case KEYBINDS -> keybindHudEnabled = enabled;
            case RADAR -> radarHudEnabled = enabled;
            case SERVER_TPS -> tpsHudEnabled = enabled;
            case COOLDOWNS -> cooldownHudEnabled = enabled;
            case TOTEMS -> totemCounterEnabled = enabled;
            case HIT_FEEDBACK -> {
                if (HudSettings.hitFeedback() != enabled) HudSettings.toggleHitFeedback();
            }
            default -> {}
        }
    }

    static boolean hasUntrackedAttacks() {
        return !SaveMode.isPaused() && !aimOwnsAttacksThisTick && !TriggerBot.isEnabled()
                && (killAuraEnabled || pvp18Enabled);
    }

    static void applyProfileModules(Map<String, Boolean> states) {
        for (var tab : DashClickGui.Tab.values()) {
            for (var module : tab.modules) {
                Boolean enabled = states.get(module.label());
                if (enabled != null && isProfileModule(module.label()) && enabled != module.isEnabled()) {
                    module.activate().run();
                }
            }
        }
    }

    private static boolean isProfileModule(String label) {
        return switch (label) {
            // Auction sessions use dedicated controls. HUD settings have their own profile data.
            case "AutoBuy", "AutoSell", "Auto Chunk Reload", "Cosmic Sky", "Background Blur", "Hide Empty" -> false;
            default -> true;
        };
    }

    static AimAssist.Settings profileAimSettings() { return AIM_ASSIST.settings(); }

    static void applyProfileAim(AimAssist.Settings settings) {
        if (!AIM_ASSIST.update(settings)) {
            throw new IllegalArgumentException("Could not save Aim Assist settings");
        }
    }

    // ==================== INIT ====================

    @Override
    public void onInitializeClient() {
        HudSettings.load();
        DashAppearance.load();
        DashNicknameSetupScreen.register();
        DashHudLayout.load();
        AutoBuySettings.load();
        Freelook.register();
        Zoom.register();
        TriggerBot.register();
        SwapBind.register();
        ConsumableBind.register();
        AIM_ASSIST.register();
        PauseManager.register();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            PauseManager.clearResumeContext();
            dashSprintOwner = null;
            AIM_ASSIST.reset();
            AimCritTiming.markTick(null);
            SwapBind.reset();
            ConsumableBind.reset();
            InventoryBindSupport.clearReservation();
            ManualHitFeedback.clear();
            killAuraEnabled = false;
            pvp18Enabled = false;
            TriggerBot.disable();
            Velocity.disable();
            AutoBuy.disconnect();
            AutoSell.stop("З’єднання закрито");
            AutoSwap.disable();
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (SaveMode.isPaused()) return;
            String text = message.getString();
            AutoBuy.onServerMessage(text);
            AutoSell.onServerMessage(text);
        });
        PlayerGps.register();
        AttackSpeedCheck.register();
        Friends.register();
        DashProfiles.register();
        AutoSwap.load();
        DashWaypoints.register();
        Marks.register();
        if (Boolean.getBoolean("dash.rtxBorderless")) {
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
                if (!client.getWindow().isFullscreen()) {
                    client.getWindow().toggleFullScreen();
                }
                client.options.fullscreen().set(true);
            });
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            ManualHitFeedback.clear();
            HudSettings.save();
            DashAppearance.save();
            DashHudLayout.save();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> TriggerBot.saveSettings());
        ClientLifecycleEvents.CLIENT_STOPPING.register(DashIntroVideo::finish);
        AttackEntityCallback.EVENT.register((player, level, hand, target, hitResult) -> {
            recordLocalAttack(player, level, target);
            return InteractionResult.PASS;
        });

        openMenuKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping(
                        "key.dash-client.open_menu",
                        InputConstants.Type.KEYSYM,
                        GLFW.GLFW_KEY_RIGHT_SHIFT,
                        KeyMapping.Category.MISC
                )
        );

        registerModuleTick(TriggerBot::handleInput);
        registerModuleTick(DashBindInput::tick);
        registerModuleTick(SwapBind::tick);
        registerModuleTick(ConsumableBind::tick);
        registerModuleTick(DashClientClient::tickAim);
        registerModuleTick(DashHudLayout::tick);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (SaveMode.isPaused()) {
                PauseManager.discardBindingInput();
                if (ConsumableBind.hasPendingPauseCleanup()) {
                    PauseManager.step("inventory recovery", () -> ConsumableBind.finishPause(client));
                }
                return;
            }
            ManualHitFeedback.tick(client);
            if (!hitParticlesEnabled || client.player == null || client.level == null) {
                pendingHits.clear();
            } else if (!pendingHits.isEmpty()) {
                expirePendingHits(System.nanoTime());
            }
            DashTelemetry.tick(client, radarHudEnabled, cooldownHudEnabled);
            StaffList.tick(client);
            if (targetHudEnabled) {
                TargetActivity.tick(client, getAimedTarget(client));
            } else {
                TargetActivity.reset();
            }
            while (openMenuKey.consumeClick()) {
                if (client.screen instanceof DashDashboardScreen dashboard) {
                    dashboard.onClose();
                } else if (client.screen == null) {
                    client.setScreen(new DashDashboardScreen());
                }
            }

            if (client.player == null || client.level == null) {
                Velocity.reset();
                return;
            }

            dashTickCounter++;
            tickNightVision(client);

            if (sprintEnabled) {
                tickSprint(client);
            }
            if (autoTotemEnabled) {
                tickAutoTotem(client);
            }
            if (autoFarmEnabled && !InventoryBindSupport.busy(client)) {
                tickAutoFarm(client);
            }
            if (!aimOwnsAttacksThisTick && !TriggerBot.isEnabled() && !ConsumableBind.busy(client)) {
                if (killAuraEnabled) {
                    tickKillAura(client);
                }
                if (pvp18Enabled) {
                    tickPvP18(client);
                }
            }
            if (packetFlyEnabled) {
                tickPacketFly(client);
            }
        });

        registerModuleTick(client -> {
            if (!aimOwnsAttacksThisTick) TriggerBot.tick(client);
        });
        registerModuleTick(AutoSwap::tick);
        registerModuleTick(AutoBuy::tick);
        registerModuleTick(AutoSell::tick);
        registerModuleTick(TerrainVisuals::refresh);
        WorldRenderEvents.AFTER_ENTITIES.register(DashClientClient::renderTargetEsp);
        WorldRenderEvents.AFTER_ENTITIES.register(ChinaHat::render);
        registerModuleTick(AutoChunkReload::tick);
        WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, outline) -> !XRay.isRendering());
        registerModuleTick(OreHighlight::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(OreHighlight::render);
        registerModuleTick(BaseFinder::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(BaseFinder::render);

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("dash-client", "hud"),
                (graphics, tickCounterObj) -> {

                    Minecraft client = Minecraft.getInstance();

                    if (client.screen instanceof DashDashboardScreen || client.player == null
                            || client.level == null || client.options.hideGui) {
                        return;
                    }
                    boolean paused = SaveMode.isPaused();
                    boolean showPauseStatus = client.screen == null && isMultiplayerWorld(client);
                    if (showPauseStatus) drawPauseStatus(graphics, client, paused);
                    if (paused) return;

                    if (targetRenderEnabled) {
                        LivingEntity target = getAimedTarget(client);

                        if (target != null
                                && client.player.distanceTo(target) <= 3.0F) {

                            int centerX = client.getWindow().getGuiScaledWidth() / 2;
                            int centerY = client.getWindow().getGuiScaledHeight() / 2;

                            float time = (System.currentTimeMillis() % 2000) / 2000.0F;
                            float angleOffset = time * (float) Math.PI * 2;
                            float radius = 30.0F;
                            int segments = 20;
                            int ringColor = targetEspEnabled ? 0xFFC0EDFF : 0xFFBB86FC;

                            int x1 = (int) (centerX + Math.cos(angleOffset) * radius);
                            int y1 = (int) (centerY + Math.sin(angleOffset) * radius);
                            for (int i = 1; i <= segments; i++) {
                                float angle = angleOffset + (float) (i * Math.PI * 2 / segments);
                                int x2 = (int) (centerX + Math.cos(angle) * radius);
                                int y2 = (int) (centerY + Math.sin(angle) * radius);

                                drawLine(graphics, x1, y1, x2, y2, ringColor);
                                x1 = x2;
                                y1 = y2;
                            }
                        }
                    }

                    drawHud(graphics, client, showPauseStatus);
                }
        );
    }

    private static boolean isMultiplayerWorld(Minecraft client) {
        var listener = client.getConnection();
        if (listener == null || !listener.getConnection().isConnected()) return false;
        // The LAN host still uses the integrated server's memory connection.
        var server = client.getSingleplayerServer();
        return !listener.getConnection().isMemoryConnection() || (server != null && server.isPublished());
    }

    private static void drawPauseStatus(GuiGraphics graphics, Minecraft client, boolean paused) {
        String label = paused ? "Dash Client · PAUSED" : "Dash Client · ACTIVE";
        var pose = graphics.pose();
        pose.pushMatrix();
        try {
            pose.translate(5, 3).scale(0.75F);
            graphics.fill(0, 0, client.font.width(label) + 6, 12, 0x40000000);
            graphics.drawString(client.font, label, 3, 2, 0xFFBFBFBF, true);
        } finally {
            pose.popMatrix();
        }
    }

    private static void drawHud(GuiGraphics graphics, Minecraft client, boolean reserveStatusSpace) {
        boolean information = pingHudEnabled || tpsHudEnabled || radarHudEnabled || keybindHudEnabled
                || cooldownHudEnabled || StaffList.isEnabled();
        LivingEntity target = criticalAssistEnabled || targetHudEnabled ? getAimedTarget(client) : null;
        int infoOffset = client.player.getActiveEffects().isEmpty() ? 0 : 46;
        float scale = Math.min(HudSettings.scale(), Math.min(
                graphics.guiHeight() / 224.0F, graphics.guiWidth() / (information ? 236.0F : 120.0F)));
        if (information) {
            scale = Math.min(scale, Math.max(1, graphics.guiHeight() - infoOffset) / 240.0F);
        }
        var pose = graphics.pose();
        pose.pushMatrix();
        try {
            pose.scale(scale);
            int x = 8;
            int y = reserveStatusSpace ? Math.max(8, (int) Math.ceil(15 / scale)) : 8;
            int gap = DashAppearance.hudSpacing();

            if (hudEnabled && DashHudLayout.begin(graphics, Widget.WATERMARK, x, y, 104, 20, scale)) {
                try { DashHud.drawBrand(graphics, client.font, x, y); }
                finally { DashHudLayout.end(graphics); }
                y += 20 + gap;
            }
            if (fpsEnabled && DashHudLayout.begin(graphics, Widget.FPS, x, y, 104, 13, scale)) {
                try {
                    int fps = client.getFps();
                    int color = fps >= 120 ? HudSettings.secondary() : fps >= 60 ? HudSettings.accent() : 0xFFFFBE87;
                    DashHud.drawMetric(graphics, client.font, x, y, "FPS", Integer.toString(fps), color);
                } finally { DashHudLayout.end(graphics); }
                y += 13 + gap;
            }
            if (keysEnabled && DashHudLayout.begin(graphics, Widget.KEYSTROKES, x, y, 104, 13, scale)) {
                try { DashHud.drawKeys(graphics, client, x, y); }
                finally { DashHudLayout.end(graphics); }
                y += 13 + gap;
            }
            if (HudSettings.hitFeedback() && DashHudLayout.begin(graphics, Widget.HIT_FEEDBACK, x, y, 104, 13, scale)) {
                try { y = ManualHitFeedback.draw(graphics, client, x, y); }
                finally { DashHudLayout.end(graphics); }
            }
            if (totemCounterEnabled && DashHudLayout.enabled(Widget.TOTEMS)) {
                int totems = countItem(client.player, Items.TOTEM_OF_UNDYING);
                if ((totems > 0 || !HudSettings.hideEmpty())
                        && DashHudLayout.begin(graphics, Widget.TOTEMS, x, y, 104, 13, scale)) {
                    try {
                        DashHud.drawMetric(graphics, client.font, x, y, text("ТОТЕМИ", "ТОТЕМЫ", "TOTEMS"),
                                Integer.toString(totems), HudSettings.accent());
                    } finally { DashHudLayout.end(graphics); }
                    y += 13 + gap;
                }
            }

            if (criticalAssistEnabled && (target != null || !HudSettings.hideEmpty())) {
                var window = target == null ? null : AimCritTiming.assess(client.player, AimAssist.MAX_DURATION_MS);
                boolean ready = window != null && window.criticalNow();
                String timing = ready ? text("ГОТОВО", "ГОТОВО", "READY") : text("ЧЕКАЙ", "ЖДИ", "WAIT");
                if (!ready && window != null && window.untilCriticalMs() > 0) timing = "~" + window.untilCriticalMs() + " ms";
                DashHud.drawMetric(graphics, client.font, x, y, text("КРИТ", "КРИТ", "CRIT"),
                        timing,
                        ready ? HudSettings.secondary() : DashHud.MUTED);
                y += 13 + gap;
            }
            y = drawAuraStatus(graphics, client, x, y);
            if (armorHudEnabled && DashHudLayout.begin(graphics, Widget.ARMOR, x, y, 104, 40, scale)) {
                int before = y;
                try { y = drawArmorHud(graphics, client, x, y); }
                finally { DashHudLayout.end(graphics); }
                if (y != before) y += gap - 2;
            }
            int potionRows = Math.max(1, Math.min(5, client.player.getActiveEffects().size()));
            if (potionHudEnabled && DashHudLayout.begin(graphics, Widget.POTIONS, x, y, 104, potionRows * 15 - 2, scale)) {
                try { drawPotionHud(graphics, client, x, y); }
                finally { DashHudLayout.end(graphics); }
            }
            if (information) {
                drawInformationHud(graphics, client, (int) (graphics.guiWidth() / scale) - 112,
                        8 + (int) Math.ceil(infoOffset / scale), scale, gap);
            }
        } finally {
            pose.popMatrix();
        }
        if (targetHudEnabled) {
            DashHud.drawTarget(graphics, client, target);
        }
        DashHudLayout.drawExtras(graphics, client);
    }

    private static int drawAuraStatus(GuiGraphics graphics, Minecraft client, int x, int y) {
        AimAssist.AuraStatus aura = AIM_ASSIST.auraStatus(client);
        if (aura == null) return y;
        String state = switch (aura.state()) {
            case LOCAL_ONLY -> text("Локально", "Локально", "Local only");
            case SEARCHING -> text("Пошук", "Поиск", "Searching");
            case SWEEPING -> text("Огляд 360°", "Обзор 360°", "360° sweep");
            case TURNING -> text("Поворот", "Поворот", "Turning");
            case RECHARGING -> text("Заряд", "Заряд", "Charging");
            case WAITING_FOR_CRIT -> text("Чекаю кріт", "Жду крит", "Crit wait");
            case READY -> text("Готово", "Готово", "Ready");
            case DISPATCHED -> text("Спроба", "Попытка", "Attempt");
            case UNAVAILABLE -> text("Пауза", "Пауза", "Paused");
        };
        DashHud.drawMetric(graphics, client.font, x, y, "AURA", state, HudSettings.accent());
        y += 15;
        if (aura.targetCount() > 0) {
            DashHud.drawMetric(graphics, client.font, x, y, text("ЦІЛЬ", "ЦЕЛЬ", "TARGET") + " " + aura.targetCount(),
                    aura.targetName(), HudSettings.secondary());
            y += 15;
        }
        return y;
    }

    private static void drawInformationHud(GuiGraphics graphics, Minecraft client, int x, int y, float scale, int gap) {
        if (pingHudEnabled && DashHudLayout.begin(graphics, Widget.PING, x, y, 104, 13, scale)) {
            try { y = DashTelemetry.drawPing(graphics, client, x, y) + gap - 2; }
            finally { DashHudLayout.end(graphics); }
        }
        if (tpsHudEnabled && DashHudLayout.begin(graphics, Widget.SERVER_TPS, x, y, 104, 13, scale)) {
            try { y = DashTelemetry.drawTps(graphics, client, x, y) + gap - 2; }
            finally { DashHudLayout.end(graphics); }
        }
        if (radarHudEnabled && DashHudLayout.begin(graphics, Widget.RADAR, x, y, 104, 31, scale)) {
            int before = y;
            try { y = DashTelemetry.drawRadar(graphics, client, x, y); }
            finally { DashHudLayout.end(graphics); }
            if (y != before) y += gap - 2;
        }
        if (keybindHudEnabled && DashHudLayout.begin(graphics, Widget.KEYBINDS, x, y, 104, 66, scale)) {
            int before = y;
            try { y = DashTelemetry.drawKeybinds(graphics, client, x, y); }
            finally { DashHudLayout.end(graphics); }
            if (y != before) y += gap - 2;
        }
        if (cooldownHudEnabled && DashHudLayout.begin(graphics, Widget.COOLDOWNS, x, y, 104, 52, scale)) {
            int before = y;
            try { y = DashTelemetry.drawCooldowns(graphics, client, x, y); }
            finally { DashHudLayout.end(graphics); }
            if (y != before) y += gap - 2;
        }
        if (StaffList.isEnabled() && DashHudLayout.begin(graphics, Widget.STAFF, x, y, 104, 70, scale)) {
            try { StaffList.draw(graphics, client, x, y); }
            finally { DashHudLayout.end(graphics); }
        }
    }

    // ==================== TARGET ESP ====================

    private static void renderTargetEsp(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (SaveMode.isPaused() || !targetEspEnabled || client.player == null || client.level == null
                || client.options.hideGui || context.consumers() == null) {
            return;
        }

        LivingEntity target = getAimedTarget(client);
        if (target == null || !target.isAlive()
                || target.level() != client.level
                || client.player.distanceTo(target) > 6.0F) {
            return;
        }

        var camera = client.gameRenderer.getMainCamera();
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(
                !client.level.tickRateManager().isEntityFrozen(target));
        var box = target.getBoundingBox();
        Vec3 center = box.getCenter().add(
                Mth.lerp(partialTick, target.xOld, target.getX()) - target.getX(),
                Mth.lerp(partialTick, target.yOld, target.getY()) - target.getY() + box.getYsize() * .11,
                Mth.lerp(partialTick, target.zOld, target.getZ()) - target.getZ()
        ).subtract(camera.position());

        double halfX = box.getXsize() * .5;
        double halfY = box.getYsize() * .17;
        double halfZ = box.getZsize() * .5;
        Vector3fc forward = camera.forwardVector();
        double depth = center.x * forward.x() + center.y * forward.y() + center.z * forward.z();
        if (depth <= .12) {
            return;
        }
        double front = Math.min(depth - .12, projectedTargetExtent(halfX, halfY, halfZ, forward) + .02);
        center = center.add(-forward.x() * front, -forward.y() * front, -forward.z() * front);

        float drift = (float) Math.sin((client.level.getGameTime() % 60 + partialTick) * (Math.TAU / 60));
        float padding = .075F + drift * .012F;
        float halfWidth = projectedTargetExtent(halfX, halfY, halfZ, camera.leftVector()) + padding;
        float halfHeight = projectedTargetExtent(halfX, halfY, halfZ, camera.upVector()) + padding;
        float arm = Math.min(.30F, Math.min(halfWidth, halfHeight) * .80F);
        Matrix4f billboard = new Matrix4f().translation((float) center.x, (float) center.y, (float) center.z)
                .rotate(camera.rotation()).rotateZ(drift * .07F);
        Matrix4f corner = new Matrix4f();
        VertexConsumer quads = context.consumers().getBuffer(RenderTypes.debugQuads());

        for (int side = -1; side <= 1; side += 2) {
            for (int end = -1; end <= 1; end += 2) {
                corner.set(billboard).translate(side * halfWidth, end * halfHeight, 0)
                        .scale(-side * arm, -end * arm, 1);
                drawTargetCorner(quads, corner);
            }
        }
    }

    private static float projectedTargetExtent(double halfX, double halfY, double halfZ, Vector3fc axis) {
        return (float) (halfX * Math.abs(axis.x()) + halfY * Math.abs(axis.y()) + halfZ * Math.abs(axis.z()));
    }

    private static void drawTargetCorner(VertexConsumer out, Matrix4f pose) {
        int innerGlow = 0xB279D9FF;
        int outerGlow = 0x0079D9FF;
        int core = 0xFFF5FDFF;
        int edge = 0xF2B6EBFF;
        for (int point = 0; point < TARGET_CORNER.length; point += 2) {
            int next = (point + 2) % TARGET_CORNER.length;
            float x = TARGET_CORNER[point];
            float y = TARGET_CORNER[point + 1];
            float nextX = TARGET_CORNER[next];
            float nextY = TARGET_CORNER[next + 1];
            out.addVertex(pose, x, y, 0).setColor(innerGlow);
            out.addVertex(pose, nextX, nextY, 0).setColor(innerGlow);
            out.addVertex(pose, TARGET_CORNER_GLOW[next], TARGET_CORNER_GLOW[next + 1], 0).setColor(outerGlow);
            out.addVertex(pose, TARGET_CORNER_GLOW[point], TARGET_CORNER_GLOW[point + 1], 0).setColor(outerGlow);

            // The rounded core is a fan of triangles in the existing quad buffer.
            out.addVertex(pose, .12F, .12F, 0).setColor(core);
            out.addVertex(pose, x, y, 0).setColor(edge);
            out.addVertex(pose, nextX, nextY, 0).setColor(edge);
            out.addVertex(pose, nextX, nextY, 0).setColor(edge);
        }
    }

    // ==================== AIM ASSIST ====================

    public static AimAssist.Input correctAimInput(Minecraft client, LocalPlayer player, double yaw, double pitch,
                                                  boolean physicalMovement) {
        return AIM_ASSIST.correct(client, player, yaw, pitch, physicalMovement);
    }

    public static void cancelAimInput() {
        AIM_ASSIST.reset();
    }

    public static boolean queueAimAttack(Minecraft client) {
        return assistedAttackSource == null && AIM_ASSIST.queueSmartAttack(client);
    }

    static String manualAttackSource() {
        return assistedAttackSource == null ? "manual" : assistedAttackSource;
    }

    public static void noteNativeAimAttack() {
        if (assistedAttackSource != null) assistedAttackDispatched = true;
    }

    private static void tickAim(Minecraft client) {
        AimCritTiming.markTick(client.player);
        AIM_ASSIST.tick(client);
        aimOwnsAttacksThisTick = AIM_ASSIST.blocksOtherAttacks(client);
        if (aimOwnsAttacksThisTick) AIM_ASSIST.attackWhenReady(client, () -> invokeAimAttack(client));
    }

    private static boolean invokeAimAttack(Minecraft client) {
        assistedAttackSource = AIM_ASSIST.settings().mode() == AimAssist.Mode.SMART ? "aim_smart" : "aim_aura";
        assistedAttackDispatched = false;
        try {
            ((MinecraftAttackInvoker) client).dash$startAttack();
            return assistedAttackDispatched;
        } finally {
            assistedAttackSource = null;
        }
    }

    // ==================== AUTO FARM ====================

    private static void tickAutoFarm(Minecraft client) {
        LocalPlayer player = client.player;

        if (player == null
                || client.level == null
                || client.gameMode == null
                || client.screen != null) {
            return;
        }

        if (autoFarmCooldown > 0) {
            autoFarmCooldown--;
            return;
        }

        HitResult hitResult = client.hitResult;
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockHitResult blockHit = (BlockHitResult) hitResult;
        BlockPos pos = blockHit.getBlockPos();
        var farmlandState = client.level.getBlockState(pos);
        var cropState = client.level.getBlockState(pos.above());

        int hoeSlot = findItemInInventory(
                player,
                Items.NETHERITE_HOE,
                Items.DIAMOND_HOE,
                Items.IRON_HOE,
                Items.GOLDEN_HOE,
                Items.STONE_HOE,
                Items.WOODEN_HOE
        );

        int carrotSlot = findItemInInventory(player, Items.CARROT);
        int boneMealSlot = findItemInInventory(player, Items.BONE_MEAL);

        if (farmlandState.is(Blocks.DIRT)
                || farmlandState.is(Blocks.GRASS_BLOCK)
                || farmlandState.is(Blocks.COARSE_DIRT)
                || farmlandState.is(Blocks.ROOTED_DIRT)) {

            if (hoeSlot != -1) {
                performActionWithInventoryItem(client, player, hoeSlot,
                        pos, blockHit.getDirection(), true);
                boneMealAppliedCount = 0;
                autoFarmCooldown = 10;
                return;
            }
        }

        if (farmlandState.is(Blocks.FARMLAND) && cropState.isAir()) {
            if (carrotSlot != -1) {
                performActionWithInventoryItem(client, player, carrotSlot,
                        pos, Direction.UP, true);
                boneMealAppliedCount = 0;
                autoFarmCooldown = 10;
                return;
            }
        }

        if (cropState.is(Blocks.CARROTS)) {
            int age = cropState.getValue(CarrotBlock.AGE);

            if (age < 7 && boneMealAppliedCount < 3) {
                if (boneMealSlot != -1) {
                    performActionWithInventoryItem(client, player, boneMealSlot,
                            pos.above(), Direction.UP, true);
                    boneMealAppliedCount++;
                    autoFarmCooldown = 10;
                    return;
                }
            }

            if (age >= 7 || boneMealAppliedCount >= 3) {
                if (hoeSlot != -1) {
                    ensureItemInHotbar(client, player, hoeSlot);
                }

                client.gameMode.destroyBlock(pos.above());
                player.swing(InteractionHand.MAIN_HAND);

                boneMealAppliedCount = 0;
                autoFarmCooldown = 15;
                return;
            }
        }
    }

    private static int findItemInInventory(Player player, Item... items) {
        var inventory = player.getInventory();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            for (Item item : items) {
                if (stack.is(item)) {
                    return i;
                }
            }
        }

        return -1;
    }

    private static void ensureItemInHotbar(Minecraft client, LocalPlayer player, int slot) {
        if (slot < 9) {
            player.getInventory().setSelectedSlot(slot);
            client.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        } else {
            int targetHotbarSlot = 0;
            for (int i = 0; i < 9; i++) {
                if (player.getInventory().getItem(i).isEmpty()) {
                    targetHotbarSlot = i;
                    break;
                }
            }
            client.gameMode.handleInventoryMouseClick(
                    player.inventoryMenu.containerId,
                    slot,
                    targetHotbarSlot,
                    ClickType.SWAP,
                    player
            );
            player.getInventory().setSelectedSlot(targetHotbarSlot);
            client.getConnection().send(
                    new ServerboundSetCarriedItemPacket(targetHotbarSlot)
            );
        }
    }

    private static void performActionWithInventoryItem(
            Minecraft client,
            LocalPlayer player,
            int itemSlot,
            BlockPos pos,
            Direction direction,
            boolean useOnBlock
    ) {
        if (client.gameMode == null || client.getConnection() == null) {
            return;
        }

        int originalSlot = player.getInventory().getSelectedSlot();
        ensureItemInHotbar(client, player, itemSlot);

        if (useOnBlock) {
            Vec3 hitPosition = Vec3.atCenterOf(pos)
                    .add(
                            direction.getStepX() * 0.5,
                            direction.getStepY() * 0.5,
                            direction.getStepZ() * 0.5
                    );

            BlockHitResult hit = new BlockHitResult(
                    hitPosition,
                    direction,
                    pos,
                    false
            );

            client.gameMode.useItemOn(
                    player,
                    InteractionHand.MAIN_HAND,
                    hit
            );
        }

        player.getInventory().setSelectedSlot(originalSlot);
        client.getConnection().send(
                new ServerboundSetCarriedItemPacket(originalSlot)
        );
    }

    // ==================== PACKET FLY ====================

    private static void tickPacketFly(Minecraft client) {
        Player player = client.player;

        if (player == null || client.getConnection() == null) {
            return;
        }

        player.setDeltaMovement(0, 0, 0);

        packetFlyCounter++;

        double verticalSpeed = 1.24 / 20.0;
        double yMotion = 0;

        if (client.options.keyJump.isDown()) {
            yMotion = verticalSpeed;
        } else if (client.options.keyShift.isDown()) {
            yMotion = -verticalSpeed;
        } else if (packetFlyCounter % 20 == 0) {
            yMotion = -0.04;
        }

        double horizontalSpeed = 0.5 / 20.0;
        double forward = 0;
        double strafe = 0;

        if (client.options.keyUp.isDown()) forward += 1;
        if (client.options.keyDown.isDown()) forward -= 1;
        if (client.options.keyLeft.isDown()) strafe += 1;
        if (client.options.keyRight.isDown()) strafe -= 1;

        float yaw = player.getYRot();

        double dx = (
                forward * -Math.sin(Math.toRadians(yaw))
                        + strafe * Math.cos(Math.toRadians(yaw))
        ) * horizontalSpeed;

        double dz = (
                forward * Math.cos(Math.toRadians(yaw))
                        - strafe * Math.sin(Math.toRadians(yaw))
        ) * horizontalSpeed;

        double newX = player.getX() + dx;
        double newY = player.getY() + yMotion;
        double newZ = player.getZ() + dz;

        client.getConnection().send(
                new ServerboundMovePlayerPacket.Pos(
                        newX, newY, newZ, false, false
                )
        );

        player.setPos(newX, newY, newZ);
    }

    // ==================== RENDER LINE ====================

    private static void drawLine(
            GuiGraphics graphics,
            int x1,
            int y1,
            int x2,
            int y2,
            int color
    ) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);

        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;

        int err = dx - dy;
        boolean horizontalRuns = dx >= dy;
        int startX = x1;
        int startY = y1;

        while (x1 != x2 || y1 != y2) {
            int previousX = x1;
            int previousY = y1;
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x1 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y1 += sy;
            }

            if (horizontalRuns ? y1 != previousY : x1 != previousX) {
                fillLineRun(graphics, startX, startY, previousX, previousY, color);
                startX = x1;
                startY = y1;
            }
        }
        fillLineRun(graphics, startX, startY, x1, y1, color);
    }

    private static void fillLineRun(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        graphics.fill(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2) + 1, Math.max(y1, y2) + 1, color);
    }

    // ==================== KILL AURA ====================

    private static void tickKillAura(Minecraft client) {
        Player player = client.player;

        if (player == null) return;

        LivingEntity target = getAimedTarget(client);

        if (target == null || Friends.isFriendPlayer(target)) return;
        if (player.distanceTo(target) > 3.0F) return;
        if (!canCrit(client, target)) return;
        if (player.getAttackStrengthScale(0.5F) < 0.9F) return;

        if (client.gameMode != null) {
            client.gameMode.attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private static void tickPvP18(Minecraft client) {
        Player player = client.player;

        if (player == null) return;

        LivingEntity target = getAimedTarget(client);

        if (target == null || Friends.isFriendPlayer(target)) return;
        if (player.distanceTo(target) > 3.0F) return;

        if (client.gameMode != null) {
            client.gameMode.attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    // ==================== AUTO TOTEM ====================

    static boolean reserveOffhandForTotem(Player player) {
        return autoTotemEnabled && (player.getHealth() <= 4.0F
                || player.getDeltaMovement().y < -0.8 && player.fallDistance > 5.0F);
    }

    private static void tickAutoTotem(Minecraft client) {
        Player player = client.player;

        if (player == null || client.screen != null || ConsumableBind.hasStartedUse(client)
                || InventoryBindSupport.reservationActive(client) || SwapBind.reservesOffhand(client)) return;

        ItemStack offhand = player.getOffhandItem();

        if (offhand.is(Items.TOTEM_OF_UNDYING)) return;

        boolean falling =
                player.getDeltaMovement().y < -0.8
                        && player.fallDistance > 5.0F;

        boolean lowHealth = player.getHealth() <= 4.0F;

        if (falling || lowHealth) {
            int totemSlot = findTotemSlot(player);

            if (totemSlot != -1) {
                swapToOffhand(client, player, totemSlot);
            }
        }
    }

    private static int findTotemSlot(Player player) {
        var inventory = player.getInventory();

        for (int i = 0; i < 9; i++) {
            if (inventory.getItem(i).is(Items.TOTEM_OF_UNDYING)) {
                return i;
            }
        }

        for (int i = 9; i < 36; i++) {
            if (inventory.getItem(i).is(Items.TOTEM_OF_UNDYING)) {
                return i;
            }
        }

        return -1;
    }

    private static void swapToOffhand(
            Minecraft client,
            Player player,
            int slot
    ) {
        if (client.getConnection() == null || client.gameMode == null
                || player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) return;
        int menuSlot = slot < 9 ? slot + 36 : slot;
        client.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, menuSlot, 40, ClickType.SWAP, player);
    }

    // ==================== TARGET ====================

    private static LivingEntity getAimedTarget(Minecraft client) {
        if (client.player == null || client.hitResult == null) {
            return null;
        }

        if (client.hitResult instanceof EntityHitResult hit
                && hit.getEntity() instanceof LivingEntity target
                && target != client.player
                && target.isAlive()) {
            return target;
        }

        return null;
    }

    private static boolean canCrit(
            Minecraft client,
            LivingEntity target
    ) {
        return client.player != null && target != null && target.isAlive() && AimCritTiming.isReady(client.player);
    }

    // ==================== HIT PARTICLES ====================

    private static void recordLocalAttack(Player player, Level level, Entity entity) {
        if (!level.isClientSide()) return;

        Minecraft client = Minecraft.getInstance();
        if (!hitParticlesEnabled || SaveMode.isPaused() || level != client.level
                || player != client.player || player.isSpectator() || entity == player
                || !(entity instanceof LivingEntity target) || !target.isAlive()) {
            return;
        }

        long now = System.nanoTime();
        expirePendingHits(now);
        pendingHits.removeIf(hit -> hit.player() == player && hit.target() == target);
        if (pendingHits.size() >= MAX_PENDING_HITS) pendingHits.removeFirst();
        pendingHits.addLast(new PendingHit(player, target, now));
    }

    private static void expirePendingHits(long now) {
        while (!pendingHits.isEmpty() && now - pendingHits.getFirst().time() > HIT_CONFIRMATION_NANOS) {
            pendingHits.removeFirst();
        }
    }

    public static void onDamageEvent(ClientboundDamageEventPacket packet) {
        Minecraft client = Minecraft.getInstance();
        if (SaveMode.isPaused() || !hitParticlesEnabled || client.player == null || client.level == null
                || !(client.level.getEntity(packet.entityId()) instanceof LivingEntity target)
                || target == client.player || client.player.distanceToSqr(target) > 144.0) {
            return;
        }

        int localId = client.player.getId();
        boolean explicitLocalHit = packet.sourceCauseId() == localId && packet.sourceDirectId() == localId;
        // Legacy protocol translation can omit both attacker IDs for an ordinary hit.
        boolean anonymousHit = packet.sourceCauseId() == -1 && packet.sourceDirectId() == -1
                && packet.sourceType().is(DamageTypes.GENERIC) && packet.sourcePosition().isEmpty();
        if (!explicitLocalHit && !anonymousHit) return;

        expirePendingHits(System.nanoTime());
        boolean recentLocalAttack = pendingHits.removeIf(hit -> hit.player() == client.player && hit.target() == target);
        if (!explicitLocalHit && !recentLocalAttack) return;

        if (target instanceof Player player && DashSparkParticle.spawn(client, player)) {
            return;
        }

        RandomSource random = client.level.random;
        double radius = Math.min(target.getBbWidth() * .55, .65);
        for (int i = 0; i < 5; i++) {
            double angle = random.nextDouble() * Math.TAU;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            double y = target.getY() + target.getBbHeight() * (.42 + random.nextDouble() * .25);
            if (client.particleEngine.createParticle(ParticleTypes.CRIT,
                    target.getX() + dx * radius, y, target.getZ() + dz * radius,
                    dx * .46, .27 + random.nextDouble() * .20, dz * .46) instanceof SingleQuadParticle spark) {
                spark.setColor(.75F + random.nextFloat() * .22F, .28F, 1.0F);
            }
        }
    }

    private record PendingHit(Player player, LivingEntity target, long time) {}

    // ==================== ARMOR HUD ====================

    private static int drawArmorHud(
            GuiGraphics graphics,
            Minecraft client,
            int x,
            int y
    ) {
        Player player = client.player;

        if (player == null) return y;
        if (HudSettings.hideEmpty() && !player.hasItemInSlot(EquipmentSlot.HEAD)
                && !player.hasItemInSlot(EquipmentSlot.CHEST) && !player.hasItemInSlot(EquipmentSlot.LEGS)
                && !player.hasItemInSlot(EquipmentSlot.FEET)) return y;

        DashHud.panel(graphics, x, y, x + 104, y + 40);

        Font font = DashTypography.font(client.font);
        graphics.drawString(font, text("БРОНЯ", "БРОНЯ", "ARMOR"), x + 6, y + 3,
                HudSettings.textColor(HudSettings.accent()), false);

        for (int i = 0; i < HUD_ARMOR_SLOTS.length; i++) {
            ItemStack stack = player.getItemBySlot(HUD_ARMOR_SLOTS[i]);
            int drawX = x + 6 + i * 24;

            if (!stack.isEmpty()) {
                graphics.renderItem(stack, drawX, y + 13);

                String durability = stack.isDamageableItem()
                        ? String.valueOf(
                                stack.getMaxDamage() - stack.getDamageValue()
                        )
                        : "OK";

                graphics.drawString(
                        font,
                        durability,
                        drawX,
                        y + 29,
                        HudSettings.textColor(DashHud.textColor()), false
                );
            } else {
                graphics.drawString(
                        font,
                        "--",
                        drawX + 2,
                        y + 17,
                        HudSettings.textColor(DashHud.mutedColor()), false
                );
            }
        }

        return y + 42;
    }

    // ==================== POTION HUD ====================

    private static void drawPotionHud(
            GuiGraphics graphics,
            Minecraft client,
            int x,
            int y
    ) {
        Player player = client.player;

        if (player == null) return;
        if (player.getActiveEffects().isEmpty()) {
            if (!HudSettings.hideEmpty()) {
                DashHud.drawMetric(graphics, client.font, x, y, text("ЗІЛЛЯ", "ЗЕЛЬЯ", "POTIONS"),
                        text("НЕМАЄ", "НЕТ", "NONE"), DashHud.MUTED);
            }
            return;
        }

        Font font = DashTypography.font(client.font);
        int count = 0;
        for (MobEffectInstance effect : player.getActiveEffects()) {
            if (count >= 5) break;

            String name = effect.getEffect().value()
                    .getDisplayName().getString();

            int seconds = effect.getDuration() / 20;

            SB.setLength(0);
            SB.append(seconds / 60).append(":");

            int s = seconds % 60;

            if (s < 10) {
                SB.append("0");
            }

            SB.append(s);

            String duration = effect.isInfiniteDuration() ? "∞" : SB.toString();
            int durationX = x + 98 - font.width(duration);
            DashHud.panel(graphics, x, y, x + 104, y + 13);
            graphics.drawString(font, DashHud.fit(font, name, durationX - x - 11), x + 6, y + 3,
                    HudSettings.textColor(DashHud.textColor()), false);
            graphics.drawString(font, duration, durationX, y + 3, HudSettings.textColor(HudSettings.accent()), false);

            y += 15;
            count++;
        }
    }

    // ==================== INVENTORY ====================

    private static int countItem(Player player, Item item) {
        int count = 0;
        var inventory = player.getInventory();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }

        ItemStack offhand = player.getOffhandItem();

        if (offhand.is(item)) {
            count += offhand.getCount();
        }

        return count;
    }

    // ==================== DASH CLICK GUI ====================

    public static class DashClickGui extends Screen {
        private static final int PANEL_WIDTH = 356;
        private static final int WIDE_PANEL_WIDTH = 540;
        private static final int PANEL_HEIGHT = 332;
        private static final int NAV_LEFT = 10;
        private static final int NAV_RIGHT = 101;
        private static final int NAV_TOP = 66;
        private static final int NAV_STEP = 29;
        private static final int NAV_HEIGHT = 24;
        private static final int CONTENT_LEFT = 119;
        private static final int CONTENT_BOTTOM = PANEL_HEIGHT - 23;
        private static final int SEARCH_Y = 17;
        private static final int SEARCH_HEIGHT = 18;
        private static final int ROW_TOP = 92;
        private static final int ROW_STEP = 19;
        private static final int ROW_HEIGHT = 17;
        private static final int ROW_CAPACITY = 1 + (CONTENT_BOTTOM - ROW_TOP - ROW_HEIGHT) / ROW_STEP;
        private static final int CARD_GAP = 8;
        private static final int WIDE_ROW_STEP = 35;
        private static final int WIDE_ROW_HEIGHT = 31;
        private static final String TRIGGER_BOT_LABEL = "Trigger Bot";
        private static final int TRIGGER_OPTIONS_HEIGHT = 52;
        private static final int TRIGGER_TRACK_LEFT = CONTENT_LEFT + 9;
        private static final TriggerBot.TargetType[] TRIGGER_TARGET_TYPES = TriggerBot.TargetType.values();
        private static final int SLIDER_TOP = 264;
        private static final int SLIDER_GAP = 12;
        private static final int THEME_TOP = 151;
        private static final int THEME_BOTTOM = 177;
        private static final int LANGUAGE_TOP = 101;
        private static final int LANGUAGE_HEIGHT = 24;
        private static final int LANGUAGE_GAP = 6;
        private static final DashLanguage[] LANGUAGES = DashLanguage.values();

        private static final int WHITE = 0xFFF2F5F9;
        private static final int MUTED = 0xFFAAB3C0;
        private static final Tab[] TABS = Tab.values();
        private static Tab selectedTab = Tab.COMBAT;

        private final float[] hover = new float[ROW_CAPACITY];
        private final float[] togglePosition = new float[ROW_CAPACITY];
        private Font uiFont;
        private EditBox searchBox;
        private String searchPrefix = "";
        private Module[] searchResults = {};
        private final long openedAt = System.nanoTime();
        private long frameAt = openedAt;
        private long clickedAt;
        private int clickedRow = -1;
        private int draggingSlider = -1;
        private boolean triggerExpanded;
        private boolean draggingTriggerRange;
        private float panelLeft;
        private float panelTop;
        private float panelScale = 1.0F;

        protected DashClickGui() {
            super(Component.literal("Dash Client"));
        }

        @Override
        protected void init() {
            updateLayout();
            uiFont = DashTypography.font(font);
            String previousSearch = searchBox == null ? "" : searchBox.getValue();
            searchBox = addWidget(new EditBox(uiFont, searchX() + 18, SEARCH_Y + 5, searchWidth() - 24, SEARCH_HEIGHT - 5,
                    Component.literal(text("Пошук модулів", "Поиск модулей", "Search modules"))));
            searchBox.setBordered(false);
            searchBox.setTextColor(WHITE);
            searchBox.setMaxLength(48);
            updateSearchHint();
            searchBox.setValue(previousSearch);
            searchBox.setResponder(this::updateSearch);
            updateSearch(previousSearch);
            setInitialFocus(searchBox);
        }

        @Override
        public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            if (HudSettings.blur()) {
                renderBlurredBackground(graphics);
            }
            minecraft.gui.renderDeferredSubtitles();
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            long now = System.nanoTime();
            float elapsed = Mth.clamp((now - openedAt) / 220_000_000.0F, 0.0F, 1.0F);
            float remaining = 1.0F - elapsed;
            float reveal = 1.0F - remaining * remaining * remaining;
            float step = Mth.clamp((now - frameAt) / 75_000_000.0F, 0.0F, 1.0F);
            frameAt = now;
            updateLayout();

            int shade = (int) (0xB4 * reveal);
            graphics.fillGradient(0, 0, width, height, shade << 24 | 0x040507,
                    shade << 24 | (HudSettings.shade(0.02F) & 0xFFFFFF));

            double localX = localX(mouseX);
            double localY = localY(mouseY);
            var pose = graphics.pose();
            pose.pushMatrix();
            try {
                pose.translate(panelLeft, panelTop).scale(panelScale);
                drawFrame(graphics, reveal);
                drawNavigation(graphics, localX, localY);
                if (selectedTab == Tab.SETTINGS && !isSearching()) {
                    drawSettings(graphics, localX, localY);
                } else {
                    drawModules(graphics, localX, localY, now, step);
                }
                searchBox.render(graphics, (int) Math.floor(localX), (int) Math.floor(localY), delta);
                if (isSearching()) {
                    return;
                }
                if (selectedTab == Tab.MOVEMENT) {
                    drawSlider(graphics, 0, text("Відкидання", "Отбрасывание", "Knockback"), Velocity.force(), Velocity.force(), localX, localY);
                    boolean serverKnockback = Velocity.isServerKnockback();
                    graphics.drawString(uiFont, serverKnockback ? text("Серверне · 100%", "Серверное · 100%", "Server · 100%")
                                    : text("0% · без поштовху", "0% · без толчка", "0% · no knockback"),
                            sliderX(1), SLIDER_TOP + 2, MUTED, false);
                    graphics.drawString(uiFont, serverKnockback ? text("Після корекції позиції", "После коррекции", "Position corrected")
                                    : text("100% · повна сила", "100% · полная сила", "100% · full force"),
                            sliderX(1), SLIDER_TOP + 14, MUTED, false);
                } else if (selectedTab == Tab.STYLE) {
                    drawThemePicker(graphics, localX, localY);
                    drawSlider(graphics, 0, text("Розмір HUD", "Размер HUD", "HUD size"), HudSettings.scaleSlider(), HudSettings.scale(), localX, localY);
                    drawSlider(graphics, 1, text("Прозорість", "Прозрачность", "Opacity"), HudSettings.opacitySlider(), HudSettings.opacity(), localX, localY);
                }
            } finally {
                pose.popMatrix();
            }
        }

        private boolean wideLayout() {
            return width >= WIDE_PANEL_WIDTH + 16;
        }

        private int panelWidth() {
            return wideLayout() ? WIDE_PANEL_WIDTH : PANEL_WIDTH;
        }

        private int contentRight() {
            return panelWidth() - 13;
        }

        private int searchX() {
            return wideLayout() ? 226 : 162;
        }

        private int searchWidth() {
            return panelWidth() - searchX() - 77;
        }

        private void updateLayout() {
            float fit = Math.min(1.0F, Math.min(
                    Math.max(1, width - 16) / (float) panelWidth(),
                    Math.max(1, height - 16) / (float) PANEL_HEIGHT
            ));
            float guiScale = (float) minecraft.getWindow().getGuiScale();
            int pixelsPerUnit = (int) (fit * guiScale + 0.0001F);
            panelScale = pixelsPerUnit > 0 ? pixelsPerUnit / guiScale : fit;
            panelLeft = Math.round((width - panelWidth() * panelScale) * guiScale * 0.5F) / guiScale;
            panelTop = Math.round((height - PANEL_HEIGHT * panelScale) * guiScale * 0.5F) / guiScale;
        }

        private void drawFrame(GuiGraphics graphics, float reveal) {
            int accent = HudSettings.accent();
            int secondary = HudSettings.secondary();
            int edge = HudSettings.shade(.27F);
            int innerEdge = HudSettings.shade(.14F);
            int panelWidth = panelWidth();
            DashShape.fill(graphics, -3, 3, panelWidth + 3, PANEL_HEIGHT + 6, 16, 0x68000000);
            DashShape.frame(graphics, 0, 0, panelWidth, PANEL_HEIGHT, 14, edge, HudSettings.shade(0.0F));
            DashShape.frame(graphics, 7, 7, panelWidth - 7, 50, 9, innerEdge, HudSettings.shade(.075F));
            if (reveal < 1.0F) {
                int sweep = 13 + (int) ((panelWidth - 58) * reveal);
                DashShape.fill(graphics, sweep, 3, sweep + 30, 5, 1, secondary);
            }

            DashShape.frame(graphics, 13, 14, 42, 42, 7, HudSettings.shade(.22F), HudSettings.shade(.12F));
            DashShape.fill(graphics, 23, 21, 36, 23, 1, secondary);
            DashShape.fill(graphics, 20, 27, 34, 29, 1, accent);
            DashShape.fill(graphics, 17, 33, 30, 35, 1, secondary);
            graphics.drawString(uiFont, "DASH CLIENT", 53, 24, WHITE, false);

            int searchX = searchX();
            DashShape.frame(graphics, searchX, SEARCH_Y, searchX + searchWidth(), SEARCH_Y + SEARCH_HEIGHT, 5,
                    HudSettings.shade(searchBox.isFocused() ? .34F : .19F), HudSettings.shade(.06F));
            DashShape.frame(graphics, searchX + 6, SEARCH_Y + 5, searchX + 13, SEARCH_Y + 12, 3,
                    MUTED, HudSettings.shade(.06F));
            graphics.fill(searchX + 12, SEARCH_Y + 11, searchX + 14, SEARCH_Y + 12, MUTED);
            graphics.fill(searchX + 13, SEARCH_Y + 12, searchX + 15, SEARCH_Y + 13, MUTED);
            DashShape.frame(graphics, panelWidth - 67, 18, panelWidth - 13, 33, 5, innerEdge,
                    HudSettings.shade(.09F));
            drawCenteredLabel(graphics, "1.21.11", panelWidth - 40, 22, accent);
            DashShape.frame(graphics, 6, 55, 106, CONTENT_BOTTOM + 2, 9, innerEdge, HudSettings.shade(.035F));
            DashShape.frame(graphics, 111, 55, panelWidth - 7, CONTENT_BOTTOM + 2, 9, innerEdge,
                    HudSettings.shade(.035F));
            DashShape.fill(graphics, CONTENT_LEFT, 61, CONTENT_LEFT + 20, 63, 1, accent);
            DashShape.frame(graphics, 10, CONTENT_BOTTOM + 4, 101, PANEL_HEIGHT - 4, 6, 0xFF5D343A, 0xFF2A1B1E);
            String pauseAction = text("ПАУЗА", "ПАУЗА", "PAUSE");
            drawCenteredLabel(graphics, pauseAction, 55, CONTENT_BOTTOM + 8, 0xFFFFB7BF);
            graphics.drawString(uiFont, "RSHIFT / ESC", contentRight() - uiFont.width("RSHIFT / ESC"),
                    CONTENT_BOTTOM + 8, MUTED, false);
        }

        private void drawCenteredLabel(GuiGraphics graphics, String label, int center, int y, int color) {
            graphics.drawString(uiFont, label, center - uiFont.width(label) / 2, y, color, false);
        }

        private void drawNavigation(GuiGraphics graphics, double mouseX, double mouseY) {
            for (int i = 0; i < TABS.length; i++) {
                Tab tab = TABS[i];
                int y = NAV_TOP + i * NAV_STEP;
                boolean selected = tab == selectedTab;
                boolean hovered = inside(mouseX, mouseY, NAV_LEFT, y, NAV_RIGHT, y + NAV_HEIGHT);
                int color = HudSettings.shade(selected ? .20F : hovered ? .13F : .075F);
                DashShape.fill(graphics, NAV_LEFT, y, NAV_RIGHT, y + NAV_HEIGHT, 7, color);
                if (selected) {
                    DashShape.fill(graphics, NAV_LEFT + 2, y + 6, NAV_LEFT + 4, y + NAV_HEIGHT - 6, 1,
                            HudSettings.accent());
                }
                graphics.drawString(uiFont, DashHud.fit(uiFont, tab.label(), NAV_RIGHT - NAV_LEFT - 18),
                        NAV_LEFT + 12, y + 8, selected ? WHITE : MUTED, false);
            }
        }

        private void drawSettings(GuiGraphics graphics, double mouseX, double mouseY) {
            graphics.drawString(uiFont, text("Налаштування", "Настройки", "Settings"), CONTENT_LEFT, 68, WHITE, false);
            graphics.drawString(uiFont, text("Мова інтерфейсу Dash", "Язык интерфейса Dash", "Dash interface language"),
                    CONTENT_LEFT, 85, MUTED, false);
            for (int i = 0; i < LANGUAGES.length; i++) {
                DashLanguage language = LANGUAGES[i];
                int x = languageX(i);
                boolean selected = language == HudSettings.language();
                boolean hovered = inside(mouseX, mouseY, x, LANGUAGE_TOP, x + languageWidth(), LANGUAGE_TOP + LANGUAGE_HEIGHT);
                DashShape.fill(graphics, x, LANGUAGE_TOP, x + languageWidth(), LANGUAGE_TOP + LANGUAGE_HEIGHT, 6,
                        HudSettings.shade(hovered ? .26F : selected ? .20F : .10F));
                if (selected) {
                    DashShape.fill(graphics, x + 2, LANGUAGE_TOP + 5, x + 4, LANGUAGE_TOP + LANGUAGE_HEIGHT - 5, 1,
                            HudSettings.accent());
                }
                drawCenteredLabel(graphics, language.displayName(), x + languageWidth() / 2, LANGUAGE_TOP + 8,
                        selected ? HudSettings.secondary() : WHITE);
            }
        }

        private boolean clickSettings(double x, double y) {
            for (int i = 0; i < LANGUAGES.length; i++) {
                int left = languageX(i);
                if (inside(x, y, left, LANGUAGE_TOP, left + languageWidth(), LANGUAGE_TOP + LANGUAGE_HEIGHT)) {
                    HudSettings.setLanguage(LANGUAGES[i]);
                    updateSearchHint();
                    return true;
                }
            }
            return false;
        }

        private int languageWidth() {
            return (contentRight() - CONTENT_LEFT - (LANGUAGES.length - 1) * LANGUAGE_GAP) / LANGUAGES.length;
        }

        private int languageX(int index) {
            return CONTENT_LEFT + index * (languageWidth() + LANGUAGE_GAP);
        }

        private void updateSearchHint() {
            searchBox.setHint(Component.literal(text("Пошук модулів", "Поиск модулей", "Search modules")));
        }

        private void drawModules(GuiGraphics graphics, double mouseX, double mouseY, long now, float step) {
            boolean searching = isSearching();
            Module[] modules = visibleModules();
            int expandedTrigger = expandedTriggerRow(modules);
            int active = 0;
            int height = moduleHeight();
            for (int i = 0; i < modules.length; i++) {
                Module module = modules[i];
                boolean enabled = module.isEnabled();
                if (enabled) {
                    active++;
                }
                int top = moduleRowTop(i, expandedTrigger);
                if (top + height > CONTENT_BOTTOM) continue;
                int left = moduleLeft(i);
                int right = moduleRight(i);
                boolean hovered = inside(mouseX, mouseY, left, top, right, top + height);
                hover[i] = Mth.lerp(step, hover[i], hovered ? 1.0F : 0.0F);
                togglePosition[i] = Mth.lerp(step, togglePosition[i], enabled ? 1.0F : 0.0F);
                int baseColor = HudSettings.shade(enabled ? .15F : .09F);
                DashShape.fill(graphics, left, top, right, top + height, wideLayout() ? 7 : 5,
                        ARGB.srgbLerp(hover[i], baseColor, HudSettings.shade(.22F)));

                if (enabled) {
                    DashShape.fill(graphics, left + 2, top + 4, left + 4, top + height - 4, 1, HudSettings.accent());
                }
                if (i == clickedRow) {
                    float flash = 1.0F - Mth.clamp((now - clickedAt) / 180_000_000.0F, 0.0F, 1.0F);
                    if (flash > 0.0F) {
                        graphics.fill(left + 7, top, right - 7, top + 1,
                                (int) (0xD0 * flash) << 24 | (HudSettings.accent() & 0xFFFFFF));
                    }
                }

                int middle = top + height / 2;
                boolean trigger = isTriggerBot(module);
                boolean configurable = trigger || module.configure() != null;
                int labelRight = right - (configurable ? 59 : 38);
                graphics.drawString(uiFont, DashHud.fit(uiFont, module.displayName(), labelRight - left - 10),
                        left + 10, middle - 4, enabled ? WHITE : MUTED, false);
                if (configurable) {
                    boolean actionHovered = overModuleAction(mouseX, mouseY, right, middle);
                    int actionLeft = right - 54;
                    DashShape.fill(graphics, actionLeft, middle - 7, actionLeft + 16, middle + 7, 4,
                            HudSettings.shade(actionHovered || i == expandedTrigger ? .29F : .15F));
                    String action = trigger ? (i == expandedTrigger ? "▴" : "▾") : "…";
                    drawCenteredLabel(graphics, action, actionLeft + 8, middle - 4, WHITE);
                }
                drawSwitch(graphics, right, middle, togglePosition[i]);
            }
            if (expandedTrigger >= 0) {
                drawTriggerOptions(graphics, moduleRowTop(expandedTrigger, expandedTrigger) + moduleRowStep(), mouseX, mouseY);
            }

            String status = searching ? text("Знайдено ", "Найдено ", "Found ") + modules.length
                    : active + "/" + modules.length + text(" УВІМК.", " ВКЛ.", " ON");
            int statusLeft = contentRight() - uiFont.width(status) - 6;
            String title = searching ? text("Результати пошуку", "Результаты поиска", "Search results") : selectedTab.label();
            graphics.drawString(uiFont, DashHud.fit(uiFont, title, statusLeft - CONTENT_LEFT - 12),
                    CONTENT_LEFT, 68, WHITE, false);
            if (!searching) {
                graphics.drawString(uiFont, DashHud.fit(uiFont, selectedTab.summary(), contentRight() - CONTENT_LEFT),
                        CONTENT_LEFT, 81, MUTED, false);
            }
            DashShape.fill(graphics, statusLeft - 6, 64, contentRight(), 80, 6, HudSettings.shade(.13F));
            graphics.drawString(uiFont, status, statusLeft, 68, HudSettings.secondary(), false);
            if (searching && modules.length == 0) {
                graphics.drawString(uiFont, text("Модулів не знайдено", "Модули не найдены", "No matching modules"),
                        CONTENT_LEFT, ROW_TOP + 6, MUTED, false);
            }
        }

        private static boolean isTriggerBot(Module module) {
            return TRIGGER_BOT_LABEL.equals(module.label());
        }

        private int expandedTriggerRow(Module[] modules) {
            if (triggerExpanded) {
                for (int i = 0; i < modules.length; i++) {
                    if (isTriggerBot(modules[i])) return i;
                }
            }
            return -1;
        }

        private int columns() {
            return wideLayout() ? 2 : 1;
        }

        private int moduleHeight() {
            return wideLayout() ? WIDE_ROW_HEIGHT : ROW_HEIGHT;
        }

        private int moduleRowStep() {
            return wideLayout() ? WIDE_ROW_STEP : ROW_STEP;
        }

        private int moduleLeft(int index) {
            return index % columns() == 0 ? CONTENT_LEFT : (CONTENT_LEFT + contentRight() + CARD_GAP) / 2;
        }

        private int moduleRight(int index) {
            return !wideLayout() || index % columns() != 0 ? contentRight() : moduleLeft(1) - CARD_GAP;
        }

        private int moduleRowTop(int index, int expandedTrigger) {
            int row = index / columns();
            int offset = expandedTrigger >= 0 && row > expandedTrigger / columns() ? TRIGGER_OPTIONS_HEIGHT : 0;
            return ROW_TOP + row * moduleRowStep() + offset;
        }

        private boolean overModule(int index, int expandedTrigger, double x, double y) {
            int top = moduleRowTop(index, expandedTrigger);
            int bottom = top + moduleHeight();
            return bottom <= CONTENT_BOTTOM && inside(x, y, moduleLeft(index), top, moduleRight(index), bottom);
        }

        private static boolean overModuleAction(double x, double y, int right, int middle) {
            return inside(x, y, right - 54, middle - 7, right - 38, middle + 7);
        }

        private int triggerTrackRight() {
            return contentRight() - 9;
        }

        private int targetChipWidth() {
            return (contentRight() - CONTENT_LEFT - 22) / 2;
        }

        private void drawTriggerOptions(GuiGraphics graphics, int top, double mouseX, double mouseY) {
            int right = triggerTrackRight();
            int chipWidth = targetChipWidth();
            DashShape.fill(graphics, CONTENT_LEFT, top, contentRight(), top + TRIGGER_OPTIONS_HEIGHT - 2, 6,
                    HudSettings.shade(.13F));
            graphics.drawString(uiFont, text("Макс. дистанція", "Макс. дистанция", "Max distance"), TRIGGER_TRACK_LEFT, top + 4, WHITE, false);
            int tenths = (int) Math.round(TriggerBot.range() * 10.0);
            String value = (tenths / 10) + text(",", ",", ".") + (tenths % 10) + text(" бл.", " бл.", " bl.");
            graphics.drawString(uiFont, value, right - uiFont.width(value), top + 4, HudSettings.accent(), false);

            double fraction = (TriggerBot.range() - TriggerBot.MIN_RANGE) / (TriggerBot.MAX_RANGE - TriggerBot.MIN_RANGE);
            int knob = TRIGGER_TRACK_LEFT + (int) Math.round(fraction * (right - TRIGGER_TRACK_LEFT));
            DashShape.fill(graphics, TRIGGER_TRACK_LEFT, top + 16, right, top + 19, 1,
                    HudSettings.shade(.24F));
            DashShape.fill(graphics, TRIGGER_TRACK_LEFT, top + 16, knob, top + 19, 1, HudSettings.accent());
            DashShape.fill(graphics, knob - 3, top + 14, knob + 4, top + 21, 3, WHITE);

            for (int i = 0; i < TRIGGER_TARGET_TYPES.length; i++) {
                var type = TRIGGER_TARGET_TYPES[i];
                int x = triggerTargetX(i);
                int y = triggerTargetY(top, i);
                boolean selected = TriggerBot.targets(type);
                boolean hovered = inside(mouseX, mouseY, x, y, x + chipWidth, y + 12);
                DashShape.fill(graphics, x, y, x + chipWidth, y + 12, 4,
                        HudSettings.shade(hovered ? .29F : selected ? .23F : .08F));
                DashShape.fill(graphics, x + 4, y + 3, x + 10, y + 9, 2,
                        selected ? HudSettings.accent() : HudSettings.shade(.28F));
                String label = text(type.label, switch (type) {
                    case PLAYERS -> "Игроки";
                    case HOSTILE -> "Враждебные";
                    case NEUTRAL -> "Нейтральные";
                    case PASSIVE -> "Мирные";
                }, switch (type) {
                    case PLAYERS -> "Players";
                    case HOSTILE -> "Hostile";
                    case NEUTRAL -> "Neutral";
                    case PASSIVE -> "Passive";
                });
                graphics.drawString(uiFont, DashHud.fit(uiFont, label, chipWidth - 19),
                        x + 14, y + 2, selected ? WHITE : MUTED, false);
            }
        }

        private int triggerTargetX(int index) {
            return TRIGGER_TRACK_LEFT + (index % 2) * (targetChipWidth() + 4);
        }

        private static int triggerTargetY(int panelTop, int index) {
            return panelTop + 23 + (index / 2) * 13;
        }

        private boolean clickTriggerOptions(int top, double x, double y) {
            if (!inside(x, y, CONTENT_LEFT, top, contentRight(), top + TRIGGER_OPTIONS_HEIGHT)) return false;
            if (inside(x, y, TRIGGER_TRACK_LEFT - 3, top + 12, triggerTrackRight() + 4, top + 23)) {
                draggingTriggerRange = true;
                updateTriggerRange(x);
                return true;
            }
            for (int i = 0; i < TRIGGER_TARGET_TYPES.length; i++) {
                int left = triggerTargetX(i);
                int row = triggerTargetY(top, i);
                if (inside(x, y, left, row, left + targetChipWidth(), row + 12)) {
                    TriggerBot.toggleTarget(TRIGGER_TARGET_TYPES[i]);
                    TriggerBot.saveSettings();
                    break;
                }
            }
            return true;
        }

        private void updateTriggerRange(double x) {
            double fraction = Mth.clamp((x - TRIGGER_TRACK_LEFT) / (triggerTrackRight() - TRIGGER_TRACK_LEFT), 0.0, 1.0);
            TriggerBot.setRange(Mth.lerp(fraction, TriggerBot.MIN_RANGE, TriggerBot.MAX_RANGE));
        }

        private void toggleTriggerOptions() {
            triggerExpanded = !triggerExpanded;
            draggingTriggerRange = false;
        }

        private void drawSwitch(GuiGraphics graphics, int right, int middle, float position) {
            int x = right - 33;
            int secondary = HudSettings.secondary();
            int track = ARGB.srgbLerp(position * .42F, HudSettings.shade(.19F), HudSettings.accent());
            DashShape.fill(graphics, x, middle - 5, x + 25, middle + 5, 5, track);
            int knob = x + 2 + Math.round(position * 13.0F);
            DashShape.fill(graphics, knob, middle - 4, knob + 8, middle + 4, 4,
                    ARGB.srgbLerp(position, 0xFF919AA6, secondary));
        }

        private void drawThemePicker(GuiGraphics graphics, double mouseX, double mouseY) {
            int rightEdge = contentRight();
            graphics.drawString(uiFont, text("ПАЛІТРА", "ПАЛИТРА", "COLOR PRESET"), CONTENT_LEFT, THEME_TOP - 11, MUTED, false);
            DashShape.fill(graphics, CONTENT_LEFT, THEME_TOP, rightEdge, THEME_BOTTOM, 6, HudSettings.shade(.14F));
            boolean left = inside(mouseX, mouseY, CONTENT_LEFT, THEME_TOP, CONTENT_LEFT + 28, THEME_BOTTOM);
            boolean right = inside(mouseX, mouseY, rightEdge - 28, THEME_TOP, rightEdge, THEME_BOTTOM);
            DashShape.fill(graphics, CONTENT_LEFT + 2, THEME_TOP + 2, CONTENT_LEFT + 26, THEME_BOTTOM - 2,
                    5, HudSettings.shade(left ? .38F : .23F));
            DashShape.fill(graphics, rightEdge - 26, THEME_TOP + 2, rightEdge - 2, THEME_BOTTOM - 2,
                    5, HudSettings.shade(right ? .38F : .23F));
            drawCenteredLabel(graphics, "<", CONTENT_LEFT + 14, THEME_TOP + 9, WHITE);
            drawCenteredLabel(graphics, ">", rightEdge - 14, THEME_TOP + 9, WHITE);
            drawCenteredLabel(graphics, HudSettings.themeName(), (CONTENT_LEFT + rightEdge) / 2,
                    THEME_TOP + 9, HudSettings.accent());
            graphics.drawString(uiFont, text("ПЕРЕГЛЯД HUD", "ПРЕДПРОСМОТР HUD", "HUD PREVIEW"), CONTENT_LEFT, 203, MUTED, false);
            DashHud.drawBrand(graphics, uiFont, CONTENT_LEFT, 215);
            DashHud.drawMetric(graphics, uiFont, CONTENT_LEFT + 112, 215, text("ПІНГ", "ПИНГ", "PING"),
                    text("42 мс", "42 мс", "42 ms"), HudSettings.secondary());
        }

        private void drawSlider(GuiGraphics graphics, int slider, String label, float value, float shown,
                                double mouseX, double mouseY) {
            int x = sliderX(slider);
            int width = sliderWidth();
            boolean highlighted = draggingSlider == slider || overSlider(slider, mouseX, mouseY);
            int y = SLIDER_TOP;
            DashShape.fill(graphics, x - 3, y, x + width + 4, y + 25, 5,
                    HudSettings.shade(highlighted ? .23F : .10F));
            String percent = Math.round(shown * 100.0F) + "%";
            int percentWidth = uiFont.width(percent);
            graphics.drawString(uiFont, DashHud.fit(uiFont, label, width - percentWidth - 4),
                    x, y + 3, WHITE, false);
            graphics.drawString(uiFont, percent, x + width - percentWidth, y + 3, HudSettings.accent(), false);
            int handle = x + Math.round(value * width);
            DashShape.fill(graphics, x, y + 17, x + width, y + 20, 1, HudSettings.shade(.18F));
            if (handle > x) {
                DashShape.fill(graphics, x, y + 17, handle, y + 20, 1, HudSettings.accent());
            }
            DashShape.fill(graphics, handle - 3, y + 15, handle + 4, y + 22, 3,
                    highlighted ? HudSettings.secondary() : WHITE);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            MouseButtonEvent local = localMouse(event);
            if (super.mouseClicked(local, doubleClick)) {
                return true;
            }
            Module[] modules = visibleModules();
            int expandedTrigger = expandedTriggerRow(modules);
            double x = localX(event.x());
            double y = localY(event.y());
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
                    && inside(x, y, searchX(), SEARCH_Y, searchX() + searchWidth(), SEARCH_Y + SEARCH_HEIGHT)) {
                setInitialFocus(searchBox);
                return true;
            }
            if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    for (int i = 0; i < modules.length; i++) {
                        if (!overModule(i, expandedTrigger, x, y)) continue;
                        if (isTriggerBot(modules[i])) {
                            toggleTriggerOptions();
                            return true;
                        }
                        if (modules[i].configure() != null) {
                            modules[i].configure().run();
                            return true;
                        }
                    }
                }
                return false;
            }
            draggingSlider = -1;
            draggingTriggerRange = false;

            if (inside(x, y, 10, CONTENT_BOTTOM + 4, 101, PANEL_HEIGHT - 4)) {
                PauseManager.pause(minecraft);
                return true;
            }

            for (int i = 0; i < TABS.length; i++) {
                int top = NAV_TOP + i * NAV_STEP;
                if (inside(x, y, NAV_LEFT, top, NAV_RIGHT, top + NAV_HEIGHT)) {
                    selectedTab = TABS[i];
                    triggerExpanded = false;
                    if (searchBox.getValue().isEmpty()) {
                        resetRowEffects();
                    } else {
                        searchBox.setValue("");
                    }
                    return true;
                }
            }
            if (selectedTab == Tab.SETTINGS && !isSearching()) {
                return clickSettings(x, y);
            }
            if (expandedTrigger >= 0
                    && clickTriggerOptions(moduleRowTop(expandedTrigger, expandedTrigger) + moduleRowStep(), x, y)) {
                return true;
            }

            if (!isSearching() && selectedTab == Tab.STYLE
                    && inside(x, y, CONTENT_LEFT, THEME_TOP, contentRight(), THEME_BOTTOM)) {
                if (x < CONTENT_LEFT + 28) HudSettings.cycleTheme(-1);
                if (x >= contentRight() - 28) HudSettings.cycleTheme(1);
                return true;
            }

            int sliders = isSearching() ? 0 : switch (selectedTab) {
                case STYLE -> 2;
                case MOVEMENT -> 1;
                default -> 0;
            };
            for (int slider = 0; slider < sliders; slider++) {
                if (overSlider(slider, x, y)) {
                    draggingSlider = slider;
                    updateSlider(slider, x);
                    return true;
                }
            }

            for (int i = 0; i < modules.length; i++) {
                if (overModule(i, expandedTrigger, x, y)) {
                    int middle = moduleRowTop(i, expandedTrigger) + moduleHeight() / 2;
                    boolean action = overModuleAction(x, y, moduleRight(i), middle);
                    if (action && isTriggerBot(modules[i])) {
                        toggleTriggerOptions();
                        return true;
                    }
                    if (action && modules[i].configure() != null) {
                        modules[i].configure().run();
                        return true;
                    }
                    modules[i].activate().run();
                    clickedRow = i;
                    clickedAt = System.nanoTime();
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
            if (draggingTriggerRange && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                updateTriggerRange(localX(event.x()));
                return true;
            }
            if (draggingSlider >= 0 && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                updateSlider(draggingSlider, localX(event.x()));
                return true;
            }
            return super.mouseDragged(localMouse(event), dragX / panelScale, dragY / panelScale);
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            if (draggingTriggerRange && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                draggingTriggerRange = false;
                TriggerBot.saveSettings();
                return true;
            }
            if (draggingSlider >= 0 && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                draggingSlider = -1;
                return true;
            }
            return super.mouseReleased(localMouse(event));
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (openMenuKey != null && openMenuKey.matches(event)) {
                onClose();
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        public void removed() {
            if (!SaveMode.isPaused()) {
                HudSettings.save();
                TriggerBot.saveSettings();
            }
            super.removed();
        }

        private void resetRowEffects() {
            Arrays.fill(hover, 0.0F);
            Module[] modules = visibleModules();
            for (int i = 0; i < modules.length; i++) {
                togglePosition[i] = modules[i].isEnabled() ? 1.0F : 0.0F;
            }
            clickedRow = -1;
        }

        private void updateSearch(String value) {
            searchPrefix = value.strip();
            triggerExpanded = false;
            draggingTriggerRange = false;
            searchResults = isSearching() ? Arrays.stream(TABS)
                    .flatMap(tab -> Arrays.stream(tab.modules))
                    .filter(module -> module.matchesPrefix(searchPrefix))
                    .limit(ROW_CAPACITY)
                    .toArray(Module[]::new) : new Module[0];
            draggingSlider = -1;
            resetRowEffects();
        }

        private boolean isSearching() {
            return !searchPrefix.isEmpty();
        }

        private Module[] visibleModules() {
            return isSearching() ? searchResults : selectedTab.modules;
        }

        private int sliderWidth() {
            return (contentRight() - CONTENT_LEFT - SLIDER_GAP - 8) / 2;
        }

        private int sliderX(int slider) {
            return CONTENT_LEFT + 3 + slider * (sliderWidth() + SLIDER_GAP);
        }

        private boolean overSlider(int slider, double x, double y) {
            int left = sliderX(slider);
            return inside(x, y, left - 3, SLIDER_TOP, left + sliderWidth() + 4, SLIDER_TOP + 25);
        }

        private void updateSlider(int slider, double x) {
            float min = selectedTab == Tab.MOVEMENT ? 0.0F : 0.01F;
            float value = Mth.clamp((float) (x - sliderX(slider)) / sliderWidth(), min, 1.0F);
            if (selectedTab == Tab.MOVEMENT) {
                Velocity.setForce(value);
                return;
            }
            if (selectedTab == Tab.STYLE) {
                if (slider == 0) {
                    HudSettings.setScaleSlider(value);
                } else {
                    HudSettings.setOpacitySlider(value);
                }
                return;
            }
        }

        private double localX(double screenX) {
            return (screenX - panelLeft) / panelScale;
        }

        private double localY(double screenY) {
            return (screenY - panelTop) / panelScale;
        }

        private MouseButtonEvent localMouse(MouseButtonEvent event) {
            return new MouseButtonEvent(localX(event.x()), localY(event.y()), event.buttonInfo());
        }

        private static boolean inside(double x, double y, int left, int top, int right, int bottom) {
            return x >= left && x < right && y >= top && y < bottom;
        }

        private record Module(String label, String ukrainian, String russian,
                              BooleanSupplier enabledState, Runnable activate, Runnable configure) {
            Module(String label, String ukrainian, String russian, BooleanSupplier enabled, Runnable toggle) {
                this(label, ukrainian, russian, enabled, toggle, null);
            }

            String displayName() {
                return text(ukrainian, russian, label);
            }

            boolean matchesPrefix(String prefix) {
                return label.regionMatches(true, 0, prefix, 0, prefix.length())
                        || ukrainian.regionMatches(true, 0, prefix, 0, prefix.length())
                        || russian.regionMatches(true, 0, prefix, 0, prefix.length());
            }

            boolean isEnabled() {
                return enabledState.getAsBoolean();
            }
        }

        private enum Tab {
            COMBAT("Бій", "Бой", "Combat",
                    "Цілі й налаштування удару", "Цели и настройки удара", "Target tools and timing",
                    new Module("AutoSwap", "Автозаміна", "Автозамена", AutoSwap::isEnabled, AutoSwap::toggle, () -> {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new AutoSwapScreen(client.screen));
                    }),
                    new Module(TRIGGER_BOT_LABEL, "Тригер-бот", "Триггер-бот", TriggerBot::isEnabled, TriggerBot::toggle),
                    new Module("Critical Assist", "Допомога з критами", "Помощь с критами",
                            () -> criticalAssistEnabled, () -> criticalAssistEnabled = !criticalAssistEnabled),
                    new Module("KillAura (Crits)", "KillAura (крити)", "KillAura (криты)",
                            () -> killAuraEnabled, () -> killAuraEnabled = !killAuraEnabled),
                    new Module("PvP 1.8", "PvP 1.8", "PvP 1.8", () -> pvp18Enabled, () -> pvp18Enabled = !pvp18Enabled),
                    new Module("Aim Assist", "Доводка прицілу", "Доводка прицела",
                            AIM_ASSIST::isEnabled, AIM_ASSIST::toggle, () -> {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new AimAssistScreen(client.screen, AIM_ASSIST));
                    })),
            VISUALS("Візуали", "Визуалы", "Visuals", "Ефекти й позначки", "Эффекты и метки", "Effects and markers",
                    new Module("Auto Chunk Reload", "Автооновлення чанків", "Обновление чанков",
                            HudSettings::autoChunkReload, HudSettings::toggleAutoChunkReload),
                    new Module("Cosmic Sky", "Космічне небо", "Космическое небо", CosmicSky::isEnabled, CosmicSky::toggle),
                    new Module("No Fire", "Приховати вогонь", "Скрыть огонь", () -> noFireEnabled, () -> noFireEnabled = !noFireEnabled),
                    new Module("Target ESP", "Контур цілі", "Контур цели", () -> targetEspEnabled, () -> targetEspEnabled = !targetEspEnabled),
                    new Module("China Hat", "Конусний капелюх", "Коническая шляпа", ChinaHat::isEnabled, ChinaHat::toggle),
                    new Module("Ore Highlight", "Підсвітка руд", "Подсветка руд", OreHighlight::isEnabled, OreHighlight::toggle),
                    new Module("XRay", "XRay", "XRay", XRay::isEnabled, XRay::toggle),
                    new Module("Target Render", "Позначка цілі", "Метка цели",
                            () -> targetRenderEnabled, () -> targetRenderEnabled = !targetRenderEnabled),
                    new Module("Hit Particles", "Іскри від удару", "Искры от удара",
                            () -> hitParticlesEnabled, () -> hitParticlesEnabled = !hitParticlesEnabled),
                    new Module("Night Vision", "Нічне бачення", "Ночное зрение",
                            () -> nightVisionEnabled, () -> nightVisionEnabled = !nightVisionEnabled),
                    new Module("Background Blur", "Розмиття фону", "Размытие фона", HudSettings::blur, HudSettings::toggleBlur)),
            HUD("HUD", "HUD", "HUD", "Інформація на екрані", "Информация на экране", "Information on screen",
                    new Module("Watermark", "Логотип Dash", "Логотип Dash", () -> hudEnabled, () -> hudEnabled = !hudEnabled),
                    new Module("Target HUD", "Панель цілі", "Панель цели", () -> targetHudEnabled, () -> targetHudEnabled = !targetHudEnabled),
                    new Module("FPS Counter", "Лічильник FPS", "Счётчик FPS", () -> fpsEnabled, () -> fpsEnabled = !fpsEnabled),
                    new Module("Keystrokes", "Натискання клавіш", "Нажатия клавиш", () -> keysEnabled, () -> keysEnabled = !keysEnabled),
                    new Module("Totem Counter", "Лічильник тотемів", "Счётчик тотемов",
                            () -> totemCounterEnabled, () -> totemCounterEnabled = !totemCounterEnabled),
                    new Module("Potion HUD", "Ефекти зілля", "Эффекты зелий", () -> potionHudEnabled, () -> potionHudEnabled = !potionHudEnabled),
                    new Module("Armor HUD", "Стан броні", "Состояние брони", () -> armorHudEnabled, () -> armorHudEnabled = !armorHudEnabled),
                    new Module("Hit Feedback", "Останній удар", "Последний удар", HudSettings::hitFeedback, HudSettings::toggleHitFeedback)),
            MOVEMENT("Рух", "Движение", "Movement",
                    "Рух і керування камерою", "Движение и управление камерой", "Movement and camera controls",
                    new Module("Sprint", "Спринт", "Спринт", () -> sprintEnabled, () -> sprintEnabled = !sprintEnabled),
                    new Module("Packet Fly", "Packet Fly", "Packet Fly", () -> packetFlyEnabled, () -> packetFlyEnabled = !packetFlyEnabled),
                    new Module("Velocity", "Відкидання", "Отбрасывание", Velocity::isEnabled, Velocity::toggle),
                    new Module("Freelook", "Вільний огляд", "Свободный обзор", Freelook::isEnabled, Freelook::toggle)),
            UTILITY("Утиліти", "Утилиты", "Utility",
                    "Інструменти для світу", "Инструменты для мира", "World tools",
                    new Module("AutoBuy", "Автокупівля", "Автопокупка", AutoBuy::isEnabled, AutoBuy::toggle, () -> {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new AutoBuyScreen(client.screen, AutoBuy::isEnabled,
                                AutoBuy::toggle, AutoBuy::status));
                    }),
                    new Module("AutoSell", "Автопродаж", "Автопродажа", AutoSell::isEnabled, AutoSell::toggle, () -> {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new AutoSellScreen(client.screen));
                    }),
                    new Module("Swap Bind", "Свап за клавішею", "Свап по клавише",
                            SwapBind::isEnabled, SwapBind::toggle, () -> {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new SwapBindScreen(client.screen));
                    }),
                    new Module("Consumable Bind", "Витратники", "Расходники",
                            ConsumableBind::isEnabled, ConsumableBind::toggle, () -> {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new ConsumableBindScreen(client.screen));
                    }),
                    new Module("UnInvisibility", "Видимість невидимих", "Видимость невидимок",
                            () -> unInvisibilityEnabled, () -> unInvisibilityEnabled = !unInvisibilityEnabled),
                    new Module("Auto Farm", "Автоферма", "Автоферма", () -> autoFarmEnabled, () -> autoFarmEnabled = !autoFarmEnabled),
                    new Module("Base Finder", "Пошук баз", "Поиск баз", BaseFinder::isEnabled,
                            () -> BaseFinder.setEnabled(!BaseFinder.isEnabled())),
                    new Module("Auto Totem", "Автототем", "Автототем", () -> autoTotemEnabled, () -> autoTotemEnabled = !autoTotemEnabled),
                    new Module("Name Protect", "Захист ніку", "Защита ника", NameProtect::isEnabled,
                            () -> NameProtect.setEnabled(!NameProtect.isEnabled())),
                    new Module("Waypoints", "Мітки маршруту", "Точки маршрута", DashWaypoints::isEnabled,
                            () -> DashWaypoints.setEnabled(!DashWaypoints.isEnabled())),
                    new Module("Marks", "Крижані мітки", "Ледяные метки", Marks::isEnabled, Marks::toggle, () -> {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new MarksScreen(client.screen));
                    })),
            INFO("Інфо", "Инфо", "Info", "Віджети мережі й світу", "Виджеты сети и мира", "Network and world widgets",
                    new Module("Ping", "Пінг", "Пинг", () -> pingHudEnabled, () -> pingHudEnabled = !pingHudEnabled),
                    new Module("Server TPS", "TPS сервера", "TPS сервера", () -> tpsHudEnabled, () -> tpsHudEnabled = !tpsHudEnabled),
                    new Module("Radar", "Радар", "Радар", () -> radarHudEnabled, () -> radarHudEnabled = !radarHudEnabled),
                    new Module("Keybinds", "Гарячі клавіші", "Горячие клавиши",
                            () -> keybindHudEnabled, () -> keybindHudEnabled = !keybindHudEnabled),
                    new Module("Cooldowns", "Перезарядки", "Перезарядки",
                            () -> cooldownHudEnabled, () -> cooldownHudEnabled = !cooldownHudEnabled),
                    new Module("StaffList", "Модератори", "Модераторы", StaffList::isEnabled, StaffList::toggle)),
            STYLE("Стиль", "Стиль", "Style", "Палітра й вигляд HUD", "Палитра и вид HUD", "Palette and HUD appearance",
                    new Module("Hide Empty", "Приховати порожні", "Скрыть пустые", HudSettings::hideEmpty, HudSettings::toggleHideEmpty)),
            SETTINGS("Параметри", "Настройки", "Settings", "", "", "");

            private final String ukrainian;
            private final String russian;
            private final String english;
            private final String ukrainianSummary;
            private final String russianSummary;
            private final String englishSummary;
            private final Module[] modules;

            Tab(String ukrainian, String russian, String english,
                String ukrainianSummary, String russianSummary, String englishSummary, Module... modules) {
                this.ukrainian = ukrainian;
                this.russian = russian;
                this.english = english;
                this.ukrainianSummary = ukrainianSummary;
                this.russianSummary = russianSummary;
                this.englishSummary = englishSummary;
                this.modules = modules;
            }

            String label() {
                return text(ukrainian, russian, english);
            }

            String summary() {
                return text(ukrainianSummary, russianSummary, englishSummary);
            }
        }
    }
}
