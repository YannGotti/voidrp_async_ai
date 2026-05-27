package ru.voidrp.asyncai;

import net.minecraft.server.level.ServerPlayer;

/**
 * Tracks which player issued the current server-thread command.
 * Set at the start of handleChatCommand, cleared at its return.
 * Used by CraftWorldGetChunkMixin to message the player when a chunk
 * isn't loaded yet during a teleport command.
 */
public final class CurrentCommandPlayer {

    private static volatile ServerPlayer current = null;

    private CurrentCommandPlayer() {}

    public static void set(ServerPlayer player) { current = player; }
    public static ServerPlayer get()            { return current; }
    public static void clear()                  { current = null; }
}
