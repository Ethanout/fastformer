package io.github.fastformer.server.recovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoverySystemTest {
   @TempDir Path directory;

   @Test
   void rejectedIoBoundaryBlocksOnlyItsOwningRuntime() {
      try (var first = new RecoverySystem(null, work -> { throw new RejectedExecutionException("expected rejection"); }, directory);
           var second = new RecoverySystem(null, Runnable::run, directory)) {
         assertFalse(first.awaitIoIdle());
         assertFalse(first.writeGate().writesAllowed());
         assertTrue(second.awaitIoIdle());
         assertTrue(second.writeGate().writesAllowed());
      }
   }

   @Test
   void retiredRuntimeCannotCreateJournalsOrReopenAdmission() {
      var runtime = new RecoverySystem(null, Runnable::run, directory.resolve("uncreated"));
      var snapshot = new ReversibleBlockSnapshot(BlockPos.ZERO, null, null, null);
      var gate = runtime.writeGate();
      UUID owner = UUID.randomUUID();
      var lease = runtime.writeLeases().acquire(Level.OVERWORLD, owner);
      long pendingRecovery = gate.beginRecovery();
      runtime.close();
      runtime.close();
      gate.finishRecovery(pendingRecovery, true);
      assertFalse(runtime.begin(UUID.randomUUID(), Level.OVERWORLD, List.of(snapshot), List.of(snapshot)).isPresent());
      assertFalse(gate.writesAllowed());
      assertFalse(lease.isCurrent());
      assertNull(runtime.writeLeases().acquire(Level.OVERWORLD, owner));
      assertNull(runtime.writeLeases().takeOver(Level.OVERWORLD, owner));
      assertFalse(Files.exists(directory.resolve("uncreated")));
   }
}
