package io.github.fastformer.fastplace.task;

import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import io.github.fastformer.server.recovery.WorldRecoverySnapshot;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Resources retained by a world writer until commit or recovery accepts ownership. */
public interface WorldWriteTask {
   ResourceKey<Level> dimension();
   PersistentRecoveryJournal journal();
   WorldRecoverySnapshot stopAndTransferRecovery();
   void releaseAfterCancelledJournal(WorldTaskContext context);
   default void releaseMemoryReservation() {}
}
