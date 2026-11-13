package io.github.fastformer.server.task;

import io.github.fastformer.server.recovery.RecoverySystem;

public final class WorldTaskSchedulerTestAccess {
   private WorldTaskSchedulerTestAccess() {}

   public static boolean tickSafely(net.minecraft.server.MinecraftServer server, String name, Runnable tick) {
      return WorldTaskScheduler.tickSafely(RecoverySystem.of(server).writeGate(), name, tick);
   }
}
