package com.dash.client.client;

import static com.dash.client.client.DashLanguage.text;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

public final class AimAssist {
    static final float MIN_SETTING = 0.0F;
    static final float MIN_RADIUS = 1.0F;
    static final float MAX_RADIUS = 3.5F;
    static final float DEFAULT_RADIUS = MAX_RADIUS;
    static final int MIN_DURATION_MS = 30;
    static final int MAX_DURATION_MS = 200;
    static final int DEFAULT_DURATION_MS = 120;
    static final int MIN_AURA_TURN_SPEED = 60;
    static final int MAX_AURA_TURN_SPEED = 440;
    static final int DEFAULT_AURA_TURN_SPEED = 230;
    static final Priority DEFAULT_AURA_PRIORITY = Priority.NEAREST;
    static final boolean DEFAULT_AURA_CRIT_TIMING = true;
    private static final Settings DEFAULT_SETTINGS =
            new Settings(0.50F, 0.50F, DEFAULT_RADIUS, DEFAULT_DURATION_MS, Mode.SMART,
                    DEFAULT_AURA_TURN_SPEED, DEFAULT_AURA_PRIORITY, DEFAULT_AURA_CRIT_TIMING);
    private static final double CONE_ALIGNMENT = Math.cos(Math.toRadians(45.0));
    private static final double VANILLA_TURN_SCALE = 0.15;
    private static final int SMART_TURN_SPEED = 700;
    private static final double MIN_MANUAL_STEP = 0.0075;
    private static final double MAX_MANUAL_STEP = 6.0;
    private static final long SCAN_NANOS = 50_000_000;
    private static final long MANUAL_PAUSE_NANOS = 350_000_000;
    private static final long TERMINAL_FRAME_NANOS = 25_000_000;
    private static final long ALIGNED_GRACE_NANOS = 70_000_000;
    private static final long AURA_DISPATCH_STATUS_NANOS = 250_000_000;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final AimMotion motion = new AimMotion();
    private KeyMapping key;
    private Settings settings = DEFAULT_SETTINGS;
    private boolean enabled;
    private boolean replaying;
    private LocalPlayer owner;
    private LivingEntity target;
    private ItemStack queuedItem = ItemStack.EMPTY;
    private long smartUntil;
    private long alignedUntil;
    private long nextScan;
    private long manualPauseUntil;
    private long smartIntentUntil;
    private boolean turning;
    private int turnMillis;
    private boolean clickQueued;
    private boolean waitForCritical;
    private boolean preparedThisJump;
    private List<AuraCandidate> auraCandidates = List.of();
    private final Set<Integer> auraVisited = new HashSet<>();
    private AuraState auraState = AuraState.SEARCHING;
    private String dispatchedTargetName = "";
    private long showDispatchUntil;
    private long sweepFrameAt;
    private double sweptDegrees;
    private boolean sweepSpent;

    void register() {
        key = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.dash-client.aim-assist",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KeyMapping.Category.MISC));
        DashBindInput.register(key, client -> toggle());
        load();
    }

    void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (!canAssist(client, player)) {
            reset();
            return;
        }
        if (player.onGround()) preparedThisJump = false;
        if (target != null && !canKeepTarget(player, System.nanoTime())) clearTarget();
    }

    boolean queueSmartAttack(Minecraft client) {
        if (replaying || settings.mode() != Mode.SMART) return false;
        LocalPlayer player = client.player;
        HitResult clicked = client.hitResult;
        if (!canAssist(client, player) || clicked == null) return false;

        clearTarget();
        long now = System.nanoTime();
        if (clicked instanceof EntityHitResult) {
            markSmartIntent(now);
            return false;
        }
        Vec3 eye = player.getEyePosition();
        AimCritTiming.Window critical = AimCritTiming.assess(player, settings.durationMs());
        boolean readySoon = !critical.criticalNow() && critical.untilCriticalMs() > 0;
        double blockDistance = Double.POSITIVE_INFINITY;
        if (clicked.getType() == HitResult.Type.BLOCK) {
            if (!player.getMainHandItem().is(ItemTags.SWORDS)) return false;
            blockDistance = clicked.getLocation().distanceToSqr(eye);
        } else {
            markSmartIntent(now);
        }
        LivingEntity candidate = selectSmartTarget(player, eye, player.getLookAngle(), blockDistance);
        if (candidate == null) return false;
        markSmartIntent(now);

        clickQueued = true;
        waitForCritical = readySoon || critical.criticalNow();
        beginSmart(player, candidate, now, waitForCritical ? correctionTime(critical) : settings.durationMs());
        if (waitForCritical) preparedThisJump = true;
        return true;
    }

    boolean blocksOtherAttacks(Minecraft client) {
        LocalPlayer player = client.player;
        return canAssist(client, player) && (settings.mode() == Mode.AURA
                || clickQueued && target != null && canKeepTarget(player, System.nanoTime()));
    }

    void attackWhenReady(Minecraft client, BooleanSupplier vanillaAttack) {
        LocalPlayer player = client.player;
        if (replaying || target == null || !canAssist(client, player)) return;
        long now = System.nanoTime();
        if (!canKeepTarget(player, now)) {
            clearTarget();
            return;
        }

        boolean smart = settings.mode() == Mode.SMART;
        if (smart && (!clickQueued || waitForCritical && !AimCritTiming.isReady(player))) return;
        if (!smart && !auraIsReady(player)) return;
        Vec3 eye = player.getEyePosition();
        AABB box = target.getBoundingBox();
        if (!hits(box, eye, player.getLookAngle(), player.entityInteractionRange())
                || !isVisible(player, eye, aimPoint(box))) {
            if (!smart) auraState = AuraState.TURNING;
            return;
        }

        // Refresh the ordinary client pick from the rotation already applied to the player.
        client.gameRenderer.pick(1.0F);
        if (!(client.hitResult instanceof EntityHitResult hit) || hit.getEntity() != target
                || hit.getLocation().distanceToSqr(eye) > player.entityInteractionRange() * player.entityInteractionRange()) {
            if (!smart) {
                auraVisited.add(target.getId());
                clearTarget();
                auraState = AuraState.SEARCHING;
                nextScan = 0;
            }
            return;
        }

        LivingEntity dispatched = target;
        if (smart) clearTarget();
        else auraState = AuraState.READY;
        replaying = true;
        try {
            if (vanillaAttack.getAsBoolean() && !smart) {
                auraVisited.add(dispatched.getId());
                dispatchedTargetName = dispatched.getName().getString();
                showDispatchUntil = System.nanoTime() + AURA_DISPATCH_STATUS_NANOS;
                clearTarget();
                nextScan = 0;
            }
        } finally {
            replaying = false;
        }
    }

    private boolean auraIsReady(LocalPlayer player) {
        if (player.getAttackStrengthScale(0.0F) < 1.0F) {
            auraState = AuraState.RECHARGING;
            return false;
        }
        if (settings.auraCritTiming()) {
            AimCritTiming.Window critical = AimCritTiming.assess(player, 600);
            if (!critical.criticalNow() && critical.untilCriticalMs() > 0) {
                auraState = AuraState.WAITING_FOR_CRIT;
                return false;
            }
        }
        return true;
    }

    Input correct(Minecraft client, LocalPlayer player, double yawInput, double pitchInput, boolean physicalMovement) {
        if (!canAssist(client, player)) {
            if (target != null || nextScan != 0 || manualPauseUntil != 0) reset();
            return null;
        }

        boolean smart = settings.mode() == Mode.SMART;
        long now = System.nanoTime();
        if (smart && target == null && (!canPrepareSmart(client, player, now)
                || scheduled(now, nextScan))) return null;
        if (!physicalMovement && target == null && scheduled(now, manualPauseUntil)) return null;

        double yaw = player.getYRot();
        double pitch = player.getXRot();
        double nextYaw = yaw + yawInput * VANILLA_TURN_SCALE;
        double nextPitch = Mth.clamp(pitch + pitchInput * VANILLA_TURN_SCALE, -90.0, 90.0);
        double manualStep = Math.hypot(nextYaw - yaw, nextPitch - pitch);
        boolean manual = physicalMovement && manualStep >= MIN_MANUAL_STEP;
        if (!Double.isFinite(manualStep) || manual && manualStep > MAX_MANUAL_STEP
                || scheduled(now, manualPauseUntil)) {
            if (manual || !Double.isFinite(manualStep)) yieldToPlayer(now);
            return null;
        }
        if (target != null && !canKeepTarget(player, now)) {
            clearTarget();
            nextScan = now + SCAN_NANOS;
            return null;
        }

        DeltaTracker tracker = client.getDeltaTracker();
        Vec3 eye = player.getEyePosition(tracker.getGameTimeDeltaPartialTick(true));
        Vec3 look = lookDirection(yaw, pitch);
        Vec3 projected = lookDirection(nextYaw, nextPitch);
        double reach = player.entityInteractionRange();
        if (smart && !clickQueued && client.hitResult instanceof EntityHitResult hit
                && hit.getEntity() instanceof Player aimed && isEligible(player, aimed, true)
                && hits(interpolatedBox(aimed, tracker), eye, projected, reach)) {
            clearTarget();
            return null;
        }
        if (!smart && !scheduled(now, nextScan)) scanAura(player, eye, projected, now);
        if (target == null) {
            LivingEntity candidate;
            if (smart) {
                if (scheduled(now, nextScan)) return null;
                nextScan = now + SCAN_NANOS;
                AimCritTiming.Window critical = AimCritTiming.assess(player, settings.durationMs());
                if (!critical.criticalNow() && critical.untilCriticalMs() <= 0) return null;
                if (client.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity aimed
                        && hits(interpolatedBox(aimed, tracker), eye, projected, player.entityInteractionRange())) {
                    preparedThisJump = true;
                    return null;
                }
                candidate = selectSmartTarget(player, eye, projected, Double.POSITIVE_INFINITY);
                if (candidate != null) {
                    beginSmart(player, candidate, now, correctionTime(critical));
                    preparedThisJump = true;
                }
            } else {
                candidate = nextAuraTarget(player);
            }
            if (candidate == null) return smart ? null : sweepAura(now, yawInput, pitchInput, manual);
            AABB candidateBox = interpolatedBox(candidate, tracker);
            Vec3 point = aimPoint(candidateBox);
            if (manual && alignment(eye, point, projected) < alignment(eye, point, look)) {
                yieldToPlayer(now);
                return null;
            }
            target = candidate;
            owner = player;
            resetSweep();
        }

        AABB box = interpolatedBox(target, tracker);
        Vec3 point = aimPoint(box);
        double alignment = alignment(eye, point, projected);
        if (!isVisible(player, eye, point) || smart && alignment < CONE_ALIGNMENT) {
            clearTarget();
            nextScan = now + SCAN_NANOS;
            return null;
        }

        if (hits(box, eye, projected, reach)) {
            markAligned(now);
            return null;
        }
        if (manual && alignment < alignment(eye, point, look)) {
            yieldToPlayer(now);
            return null;
        }
        if (smart && !turning) return null;

        Vec3 offset = point.subtract(eye);
        double targetYaw = Math.toDegrees(Math.atan2(offset.z, offset.x)) - 90.0;
        double targetPitch = -Math.toDegrees(Math.atan2(offset.y, Math.hypot(offset.x, offset.z)));
        double yawError = Mth.wrapDegrees(targetYaw - nextYaw);
        double pitchError = targetPitch - nextPitch;
        if (smart && Math.abs(yawError) > 90.0) {
            clearTarget();
            return null;
        }
        if (!turning || !motion.isTimely(now)) {
            if (smart) {
                clearTarget();
                return null;
            }
            startTurn(now, AimMotion.durationForAura(settings.durationMs(), yawError, pitchError,
                    settings.auraTurnSpeed()));
        }
        AimMotion.Turn turn = motion.step(now, yawError, pitchError, turnMillis,
                settings.strength(), settings.smoothness(), smart ? SMART_TURN_SPEED : settings.auraTurnSpeed());
        if (hits(box, eye, lookDirection(nextYaw + turn.yaw(), nextPitch + turn.pitch()), reach)) {
            markAligned(now);
        } else if (turn.finished()) {
            if (smart) clearTarget();
            else turning = false;
        }
        if (turn.yaw() == 0.0 && turn.pitch() == 0.0) return null;
        return new Input(yawInput + turn.yaw() / VANILLA_TURN_SCALE, pitchInput + turn.pitch() / VANILLA_TURN_SCALE);
    }

    private Input sweepAura(long now, double yaw, double pitch, boolean manual) {
        if (manual) {
            yieldToPlayer(now);
            return null;
        }
        if (sweepSpent) return null;
        double step = sweepFrameAt == 0 ? 0.0 : AimMotion.sweepStep(now - sweepFrameAt, settings.auraTurnSpeed());
        sweepFrameAt = now;
        step = Math.min(step, 360.0 - sweptDegrees);
        if (step <= 0.0) return null;
        sweptDegrees += step;
        sweepSpent = sweptDegrees >= 360.0;
        auraState = sweepSpent ? AuraState.SEARCHING : AuraState.SWEEPING;
        return new Input(yaw + step / VANILLA_TURN_SCALE, pitch);
    }

    private void resetSweep() {
        sweepFrameAt = 0;
        sweptDegrees = 0.0;
        sweepSpent = false;
    }

    private boolean canPrepareSmart(Minecraft client, LocalPlayer player, long now) {
        return !player.onGround() && !preparedThisJump && !scheduled(now, manualPauseUntil)
                && (client.options.keyAttack.isDown() || scheduled(now, smartIntentUntil));
    }

    private void markSmartIntent(long now) {
        smartIntentUntil = now + settings.durationMs() * 1_000_000L;
    }

    private void beginSmart(LocalPlayer player, LivingEntity candidate, long now, int correctionMs) {
        owner = player;
        target = candidate;
        queuedItem = player.getMainHandItem().copy();
        smartUntil = now + settings.durationMs() * 1_000_000L;
        startTurn(now, correctionMs);
    }

    private int correctionTime(AimCritTiming.Window window) {
        int desired = settings.durationMs();
        if (window.untilCriticalMs() > 0) desired = Math.min(desired, window.untilCriticalMs());
        if (window.untilLandingMs() > 0) desired = Math.min(desired, window.untilLandingMs());
        return Math.max(MIN_DURATION_MS, desired);
    }

    private void startTurn(long now, int duration) {
        turnMillis = duration;
        turning = true;
        motion.begin(now);
    }

    private void markAligned(long now) {
        turning = false;
        if (settings.mode() != Mode.SMART) return;
        if (!clickQueued) {
            clearTarget();
        } else if (alignedUntil == 0 && withinSmartFrame(now)) {
            alignedUntil = (waitForCritical && scheduled(now, smartUntil) ? smartUntil : now) + ALIGNED_GRACE_NANOS;
        }
    }

    private boolean withinSmartFrame(long now) {
        return smartUntil != 0 && scheduled(now, smartUntil + TERMINAL_FRAME_NANOS);
    }

    private void yieldToPlayer(long now) {
        clearTarget();
        manualPauseUntil = now + MANUAL_PAUSE_NANOS;
        if (settings.mode() == Mode.AURA) {
            auraState = AuraState.SEARCHING;
            sweepSpent = true;
        }
    }

    private boolean canKeepTarget(LocalPlayer player, long now) {
        boolean smart = settings.mode() == Mode.SMART;
        return player == owner && isEligible(player, target, smart)
                && (!smart || ItemStack.isSameItemSameComponents(queuedItem, player.getMainHandItem())
                && (clickQueued || !player.onGround())
                && (turning && withinSmartFrame(now) || scheduled(now, alignedUntil)));
    }

    private static boolean scheduled(long now, long until) {
        return until != 0 && now - until < 0;
    }

    private boolean canAssist(Minecraft client, LocalPlayer player) {
        return enabled && settings.strength() > 0.0F && player != null && client.player == player
                && client.level == player.level() && client.gameMode != null && client.getConnection() != null
                && client.screen == null && client.getOverlay() == null && !client.isPaused() && !SaveMode.isPaused()
                && client.isWindowActive() && client.mouseHandler.isMouseGrabbed()
                && client.options.getCameraType().isFirstPerson() && client.getCameraEntity() == player
                && player.isAlive() && !player.isSpectator() && !player.isPassenger() && !player.isSleeping()
                && !player.isUsingItem() && !player.isHandsBusy() && !client.gameMode.isDestroying()
                && !ConsumableBind.busy(client) && !player.getMainHandItem().has(DataComponents.PIERCING_WEAPON)
                && (settings.mode() != Mode.AURA || localAuraWorld(client)
                && player.getMainHandItem().is(ItemTags.SWORDS));
    }

    private static boolean localAuraWorld(Minecraft client) {
        var server = client.getSingleplayerServer();
        var connection = client.getConnection();
        return server != null && !server.isPublished() && connection != null
                && connection.getConnection().isMemoryConnection();
    }

    private LivingEntity selectSmartTarget(LocalPlayer player, Vec3 eye, Vec3 look, double blockDistanceSquared) {
        LivingEntity best = null;
        double bestAlignment = CONE_ALIGNMENT;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (LivingEntity candidate : player.level().getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(settings.radius()), entity -> isEligible(player, entity, true))) {
            AABB box = candidate.getBoundingBox();
            if (box.distanceToSqr(eye) >= blockDistanceSquared) continue;
            Vec3 point = aimPoint(box);
            double alignment = alignment(eye, point, look);
            double distance = player.distanceToSqr(candidate);
            if (alignment < bestAlignment || alignment == bestAlignment && distance >= bestDistance) continue;
            if (!isVisible(player, eye, point)) continue;
            best = candidate;
            bestAlignment = alignment;
            bestDistance = distance;
        }
        return best;
    }

    private void scanAura(LocalPlayer player, Vec3 eye, Vec3 look, long now) {
        nextScan = now + SCAN_NANOS;
        Vec3 rayEnd = eye.add(look.scale(player.entityInteractionRange()));
        List<AuraCandidate> candidates = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        for (LivingEntity candidate : player.level().getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(settings.radius()), entity -> isEligible(player, entity, false))) {
            AABB box = candidate.getBoundingBox();
            Vec3 point = aimPoint(box);
            if (!isVisible(player, eye, point)) continue;
            candidates.add(new AuraCandidate(candidate, player.distanceToSqr(candidate),
                    -alignment(eye, point, look), rayDistanceSquared(box, eye, rayEnd), candidate.getHealth()));
            ids.add(candidate.getId());
        }
        Comparator<AuraCandidate> priority = switch (settings.auraPriority()) {
            case NEAREST -> Comparator.comparingDouble(AuraCandidate::distance);
            case CROSSHAIR -> Comparator.comparingDouble(AuraCandidate::rayDistance)
                    .thenComparingDouble(AuraCandidate::angleScore);
            case LOWEST_HEALTH -> Comparator.comparingDouble(AuraCandidate::health);
        };
        candidates.sort(priority.thenComparingDouble(AuraCandidate::distance)
                .thenComparingInt(candidate -> candidate.entity().getId()));
        auraCandidates = candidates;
        auraVisited.retainAll(ids);
    }

    private LivingEntity nextAuraTarget(LocalPlayer player) {
        LivingEntity first = null;
        for (AuraCandidate candidate : auraCandidates) {
            LivingEntity entity = candidate.entity();
            if (!isEligible(player, entity, false)) continue;
            if (!auraVisited.contains(entity.getId())) return entity;
            if (first == null) first = entity;
        }
        if (first != null) auraVisited.clear();
        return first;
    }

    private boolean isEligible(LocalPlayer player, LivingEntity entity, boolean smart) {
        float radius = settings.radius();
        return entity != player && entity.level() == player.level() && entity.isAlive() && entity.isAttackable()
                && !entity.isSpectator() && entity.isPickable() && !entity.isInvisibleTo(player)
                && !entity.isInvulnerable() && !Friends.isFriendPlayer(entity)
                && (smart ? entity instanceof Player : !(entity instanceof ArmorStand)
                && !(entity instanceof TamableAnimal pet && pet.isOwnedBy(player)))
                && player.distanceToSqr(entity) <= radius * radius
                && player.isWithinEntityInteractionRange(entity, 0.0);
    }

    private static AABB interpolatedBox(LivingEntity entity, DeltaTracker tracker) {
        float partial = tracker.getGameTimeDeltaPartialTick(!entity.level().tickRateManager().isEntityFrozen(entity));
        return entity.getBoundingBox().move(
                Mth.lerp(partial, entity.xOld, entity.getX()) - entity.getX(),
                Mth.lerp(partial, entity.yOld, entity.getY()) - entity.getY(),
                Mth.lerp(partial, entity.zOld, entity.getZ()) - entity.getZ());
    }

    private static Vec3 aimPoint(AABB box) {
        return box.getCenter().add(0.0, box.getYsize() * 0.17, 0.0);
    }

    private static Vec3 lookDirection(double yaw, double pitch) {
        return Vec3.directionFromRotation((float) pitch, (float) yaw);
    }

    private static boolean hits(AABB box, Vec3 eye, Vec3 look, double length) {
        return box.contains(eye) || box.clip(eye, eye.add(look.scale(length))).isPresent();
    }

    private static double rayDistanceSquared(AABB box, Vec3 eye, Vec3 rayEnd) {
        if (box.contains(eye)) return 0.0;
        return box.clip(eye, rayEnd).map(eye::distanceToSqr).orElse(Double.POSITIVE_INFINITY);
    }

    private static double alignment(Vec3 eye, Vec3 point, Vec3 look) {
        Vec3 offset = point.subtract(eye);
        double length = offset.length();
        return length < 1.0e-6 ? -1.0 : look.dot(offset) / length;
    }

    private static boolean isVisible(LocalPlayer player, Vec3 eye, Vec3 point) {
        return player.level().clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
    }

    boolean isEnabled() { return enabled; }
    KeyMapping key() { return key; }
    Settings settings() { return settings; }

    AuraStatus auraStatus(Minecraft client) {
        if (!enabled || settings.mode() != Mode.AURA) return null;
        if (!localAuraWorld(client)) return new AuraStatus("", 0, AuraState.LOCAL_ONLY);
        if (!canAssist(client, client.player)) return new AuraStatus("", 0, AuraState.UNAVAILABLE);
        int count = auraCandidates.size();
        if (scheduled(System.nanoTime(), showDispatchUntil)) {
            return new AuraStatus(dispatchedTargetName, count, AuraState.DISPATCHED);
        }
        if (target == null) {
            AuraState idle = auraState == AuraState.SWEEPING ? AuraState.SWEEPING : AuraState.SEARCHING;
            return new AuraStatus("", count, idle);
        }
        return new AuraStatus(target.getName().getString(), count, turning ? AuraState.TURNING : auraState);
    }

    boolean toggle() {
        if (save(!enabled, settings)) {
            enabled = !enabled;
            reset();
            return true;
        }
        Minecraft.getInstance().gui.setOverlayMessage(Component.literal(text(
                "Не вдалося зберегти Aim Assist.", "Не удалось сохранить Aim Assist.",
                "Could not save Aim Assist.")), false);
        return false;
    }

    boolean update(Settings newSettings) {
        if (!save(enabled, newSettings)) return false;
        settings = newSettings;
        reset();
        return true;
    }

    void reset() {
        clearTarget();
        nextScan = 0;
        manualPauseUntil = 0;
        smartIntentUntil = 0;
        preparedThisJump = false;
        auraCandidates = List.of();
        auraVisited.clear();
        auraState = AuraState.SEARCHING;
        dispatchedTargetName = "";
        showDispatchUntil = 0;
        resetSweep();
    }

    private void clearTarget() {
        owner = null;
        target = null;
        queuedItem = ItemStack.EMPTY;
        smartUntil = 0;
        alignedUntil = 0;
        turning = false;
        clickQueued = false;
        waitForCritical = false;
    }

    private static Path file() {
        return InventoryBindSupport.configFile("dash-aim-assist.properties");
    }

    private void load() {
        try {
            Properties properties = InventoryBindSupport.load(file());
            enabled = Boolean.parseBoolean(properties.getProperty("enabled", "false"));
            settings = new Settings(
                    readSetting(properties, "strength", DEFAULT_SETTINGS.strength(), MIN_SETTING, 1.0F),
                    readSetting(properties, "smoothness", DEFAULT_SETTINGS.smoothness(), MIN_SETTING, 1.0F),
                    readSetting(properties, "radius", DEFAULT_RADIUS, MIN_RADIUS, MAX_RADIUS),
                    readDuration(properties), readMode(properties),
                    readInt(properties, "auraTurnSpeed", DEFAULT_AURA_TURN_SPEED,
                            MIN_AURA_TURN_SPEED, MAX_AURA_TURN_SPEED), readPriority(properties),
                    Boolean.parseBoolean(properties.getProperty("auraCritTiming",
                            Boolean.toString(DEFAULT_AURA_CRIT_TIMING))));
        } catch (IOException | IllegalArgumentException error) {
            LOGGER.warn("Unable to read Aim Assist settings", error);
        }
    }

    private static Mode readMode(Properties properties) {
        try {
            return Mode.valueOf(properties.getProperty("mode", Mode.SMART.name()));
        } catch (IllegalArgumentException ignored) {
            return Mode.SMART;
        }
    }

    private static int readDuration(Properties properties) {
        return readInt(properties, "durationMs", DEFAULT_DURATION_MS, MIN_DURATION_MS, MAX_DURATION_MS);
    }

    private static Priority readPriority(Properties properties) {
        try {
            return Priority.valueOf(properties.getProperty("auraPriority", DEFAULT_AURA_PRIORITY.name()));
        } catch (IllegalArgumentException ignored) {
            return DEFAULT_AURA_PRIORITY;
        }
    }

    private static int readInt(Properties properties, String property, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(properties.getProperty(property, Integer.toString(fallback)));
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException error) {
            LOGGER.debug("Ignoring invalid Aim Assist setting {}", property);
        }
        return fallback;
    }

    private static float readSetting(Properties properties, String key, float fallback, float min, float max) {
        String raw = properties.getProperty(key);
        if (raw == null) return fallback;
        try {
            float value = Float.parseFloat(raw);
            if (Float.isFinite(value) && value >= min && value <= max) return value;
        } catch (NumberFormatException error) {
            LOGGER.debug("Ignoring invalid Aim Assist setting {}", key);
        }
        return fallback;
    }

    private static boolean save(boolean enabled, Settings settings) {
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("strength", Float.toString(settings.strength()));
        properties.setProperty("smoothness", Float.toString(settings.smoothness()));
        properties.setProperty("radius", Float.toString(settings.radius()));
        properties.setProperty("durationMs", Integer.toString(settings.durationMs()));
        properties.setProperty("mode", settings.mode().name());
        properties.setProperty("auraTurnSpeed", Integer.toString(settings.auraTurnSpeed()));
        properties.setProperty("auraPriority", settings.auraPriority().name());
        properties.setProperty("auraCritTiming", Boolean.toString(settings.auraCritTiming()));
        try {
            InventoryBindSupport.save(file(), properties, "Dash Aim Assist");
            return true;
        } catch (IOException error) {
            LOGGER.warn("Unable to write Aim Assist settings", error);
            return false;
        }
    }

    enum Mode { SMART, AURA }
    enum Priority { NEAREST, CROSSHAIR, LOWEST_HEALTH }
    enum AuraState { LOCAL_ONLY, SEARCHING, SWEEPING, TURNING, RECHARGING, WAITING_FOR_CRIT, READY, DISPATCHED, UNAVAILABLE }

    record AuraStatus(String targetName, int targetCount, AuraState state) {}
    private record AuraCandidate(LivingEntity entity, double distance, double angleScore, double rayDistance, float health) {}

    record Settings(float strength, float smoothness, float radius, int durationMs, Mode mode,
                    int auraTurnSpeed, Priority auraPriority, boolean auraCritTiming) {
        Settings {
            if (!Float.isFinite(strength) || !Float.isFinite(smoothness) || !Float.isFinite(radius)
                    || mode == null || auraPriority == null) {
                throw new IllegalArgumentException("Aim Assist settings must be finite and have a mode and priority");
            }
            strength = Mth.clamp(strength, MIN_SETTING, 1.0F);
            smoothness = Mth.clamp(smoothness, MIN_SETTING, 1.0F);
            radius = Mth.clamp(radius, MIN_RADIUS, MAX_RADIUS);
            durationMs = Mth.clamp(durationMs, MIN_DURATION_MS, MAX_DURATION_MS);
            auraTurnSpeed = Mth.clamp(auraTurnSpeed, MIN_AURA_TURN_SPEED, MAX_AURA_TURN_SPEED);
        }
    }

    public record Input(double yaw, double pitch) {}
}
