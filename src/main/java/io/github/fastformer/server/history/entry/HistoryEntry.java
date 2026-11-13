package io.github.fastformer.server.history.entry;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Immutable replayable world change; concrete entry codecs are registered by type ID. */
public interface HistoryEntry {
   ResourceLocation type();
   ResourceKey<Level> dimension();
   UUID operationId();
   HistoryEntry withOperationId(UUID operationId);
   int size();
   int estimatedBytes();
   HistoryBounds bounds();
   BlockPos position(int index);

   /** Returns 1 for the expected source, 2 for the target already present, and 0 for a conflict. */
   int match(ServerLevel level, int index, boolean undo);

   /** Applies the target of one undo or redo step. The caller owns admission and journaling. */
   boolean apply(ServerLevel level, int index, boolean undo, int flags);
}
