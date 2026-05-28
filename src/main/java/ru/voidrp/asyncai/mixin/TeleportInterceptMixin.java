package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.TeleportQueueManager;

import java.util.Set;

/**
 * Перехватывает телепортацию игрока в незагруженный чанк.
 *
 * Вместо немедленной телепортации (которая вешает сервер через BlockCollisions.getChunk
 * или падает в CraftWorld.getChunkAt) ставит запрос в TeleportQueueManager:
 *  - запускает загрузку чанков через ticket-систему;
 *  - показывает игроку отсчёт (10→1 сек) в actionbar;
 *  - выполняет телепортацию как только центральный чанк готов (или через 10 сек).
 *
 * Флаг TeleportQueueManager.EXECUTING_QUEUED предотвращает повторный перехват при
 * выполнении самого отложенного /tp из onTick().
 *
 * require=0: если сигнатура не совпадёт в конкретной сборке — mixin тихо пропускается.
 */
@Mixin(ServerPlayer.class)
public abstract class TeleportInterceptMixin {

    @Inject(
        method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp_interceptTeleportToUnloadedChunk(
        ServerLevel level, double x, double y, double z,
        Set<RelativeMovement> relativeMovements, float yRot, float xRot,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (TeleportQueueManager.EXECUTING_QUEUED.get()) return;

        ServerPlayer self = (ServerPlayer) (Object) this;
        if (TeleportQueueManager.tryQueue(self, level, x, y, z, relativeMovements, yRot, xRot)) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }
}
