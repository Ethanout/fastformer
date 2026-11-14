package io.github.fastformer.server.task.block;

import io.github.fastformer.common.placement.PlacementUpdateMode;
import io.github.fastformer.common.selection.OperationMode;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import io.github.fastformer.common.selection.OperationStackRegion;
import io.github.fastformer.server.recovery.WorldJournalPreparation;
import io.github.fastformer.server.worldwrite.snapshot.BlockSnapshotReader;
import io.github.fastformer.common.workspace.model.OperationConflictMode;
import io.github.fastformer.common.workspace.submission.OperationWorkspacePlan;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Builds real writers with a controlled journal worker, without mutating running tasks. */
public final class WorldTaskFixtures {
   private WorldTaskFixtures() {}

   public static PlacementTask placement(Set<BlockPos> targets, PlacementTaskPlan plan, WorldJournalPreparation journal) {
      return new PlacementTask(null, null, targets, plan, null, journal);
   }

   public static PlacementTask placement(Set<BlockPos> targets, PlacementTaskPlan plan, WorldJournalPreparation journal,
      BlockSnapshotReader snapshots, BlockTaskMemoryPolicy memoryPolicy) {
      return new PlacementTask(null, null, targets, plan, null, journal, snapshots, memoryPolicy);
   }

   public static SubmittedWorkspaceTask workspace(UUID transferId, OperationWorkspacePlan plan,
      PlacementUpdateMode updateMode, int maxPlacement, ResourceKey<Level> dimension, WorldJournalPreparation journal) {
      return new SubmittedWorkspaceTask(transferId, plan, updateMode, maxPlacement, dimension, journal);
   }

   public static SelectionOperationTask selection(OperationSelectionVolume selection, OperationMode mode,
      OperationConflictMode conflictMode, boolean copy, BlockPos translation, OperationStackRegion stack,
      PlacementUpdateMode updateMode, int maxPlacement, ResourceKey<Level> dimension, WorldJournalPreparation journal) {
      return new SelectionOperationTask(selection, mode, conflictMode, copy, translation, stack, updateMode,
         maxPlacement, dimension, journal);
   }
}
