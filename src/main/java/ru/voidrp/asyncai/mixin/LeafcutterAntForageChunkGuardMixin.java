package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards LeafcutterAntAIForageLeaves.isValidTarget() against main-thread deadlock.
 *
 * Root cause (Watchdog dump 2026-05-28 12:47):
 *   EntityLeafcutterAnt.tick() → Mob.serverAiStep() → GoalSelector.tick()
 *   → LeafcutterAntAIForageLeaves.canUse() → MoveToBlockGoal.canUse()
 *   → LeafcutterAntAIForageLeaves.findNearestBlock() → isValidTarget()
 *   → Level.getBlockState() → ServerChunkCache.getChunk(FULL, true)
 *   → MainThreadExecutor.managedBlock() → LockSupport.parkNanos()
 *   The ant scans a wide radius for leaf blocks; for distant unloaded chunks
 *   getChunk() blocks the main thread until the chunk finishes loading.
 *
 * Fix: redirect Level.getBlockState() inside isValidTarget() to a non-blocking
 * version using getChunkNow(). If the chunk is not loaded, return AIR — the ant
 * sees no leaf block there and moves on, same as if the block were stone.
 */
@Mixin(targets = "com.github.alexthe666.alexsmobs.entity.ai.LeafcutterAntAIForageLeaves", remap = false)
public abstract class LeafcutterAntForageChunkGuardMixin {

    @Redirect(
        method = "isValidTarget",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        ),
        require = 0,
        remap = true
    )
    private BlockState voidrp_safeGetBlockStateInForage(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            ServerChunkCache cache = serverLevel.getChunkSource();
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            LevelChunk chunk = cache.getChunkNow(cx, cz);
            if (chunk == null) {
                long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
                if (suppressed >= 0) {
                    VoidRpAsyncAI.LOGGER.warn(
                        "[VoidRP] LeafcutterAnt forage guard — chunk [{},{}] not loaded " +
                        "at block pos {},{},{} — returning AIR to prevent main-thread deadlock{}",
                        cx, cz, pos.getX(), pos.getY(), pos.getZ(),
                        suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
                }
                return Blocks.AIR.defaultBlockState();
            }
            return chunk.getBlockState(pos);
        }
        return level.getBlockState(pos);
    }
}
