package io.github.fastformer.server.recovery;

public final class RecoveryJournalTestAccess {
   private RecoveryJournalTestAccess() {}

   public static void resetWriteGate(net.minecraft.server.MinecraftServer server) {
      var gate = RecoverySystem.of(server).writeGate();
      gate.finishRecovery(gate.beginRecovery(), true);
   }
}
