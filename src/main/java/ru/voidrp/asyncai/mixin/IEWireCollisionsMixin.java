package ru.voidrp.asyncai.mixin;

import blusunrize.immersiveengineering.common.config.IEServerConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Caches IEServerConfig.WIRES.enableWireDamage per-call overhead in
 * WireCollisions.handleEntityCollision.
 *
 * Root cause: every entity movement through a block fires entityInside(),
 * IE patches that via Mixin to call handleEntityCollision(), which calls
 * ModConfigSpec$BooleanValue.get() — a ConcurrentHashMap lookup + holder
 * resolution — on every invocation (profiler: 0.13% self-time).
 *
 * Fix: @Redirect replaces the get() call with a cached boolean refreshed
 * every 200 invocations (~10 seconds at 20 TPS with many entities).
 * The cache is global (static) because enableWireDamage is a server config,
 * not per-entity.
 */
@Mixin(value = blusunrize.immersiveengineering.common.wires.WireCollisions.class, remap = false)
public abstract class IEWireCollisionsMixin {

    private static final int CACHE_REFRESH_EVERY = 200;
    private static volatile boolean cachedEnableWireDamage = true;
    private static int cacheCounter = CACHE_REFRESH_EVERY - 1; // first call triggers refresh

    @Redirect(
        method = "handleEntityCollision",
        at = @At(
            value = "INVOKE",
            target = "Lnet/neoforged/neoforge/common/ModConfigSpec$BooleanValue;get()Ljava/lang/Object;",
            remap = false
        ),
        require = 0,
        remap = false
    )
    private static Object voidrp_cachedEnableWireDamage(ModConfigSpec.BooleanValue instance) {
        if (++cacheCounter >= CACHE_REFRESH_EVERY) {
            cacheCounter = 0;
            cachedEnableWireDamage = instance.get();
        }
        return cachedEnableWireDamage;
    }
}
