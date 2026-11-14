package io.github.fastformer.common.workspace.submission;

import io.github.fastformer.core.math.Axis;

import io.github.fastformer.common.selection.OperationStackRegion;


import io.github.fastformer.common.selection.session.*;
import io.github.fastformer.common.geometry.workflow.*;
import io.github.fastformer.common.geometry.workflow.plan.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.common.world.BlockSnapshot;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.common.workspace.model.WorkspaceTransform;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class OperationWorkspaceValidatorTest {
   @Test
   void rejectsMoreThanTenPartsBeforeReadingAnyWorldState() {
      List<OperationWorkspacePlan.Part> parts = new ArrayList<>();
      for (int id = 1; id <= 11; id++) {
         parts.add(emptyPart(id));
      }

      var result = OperationWorkspaceValidator.validate(
         new OperationWorkspacePlan(parts), 100
      );

      assertFalse(result.success());
      assertTrue(result.writes().isEmpty());
   }

   @Test
   void rejectsDuplicateIdsBeforeReadingAnyWorldState() {
      var result = OperationWorkspaceValidator.validate(
         new OperationWorkspacePlan(List.of(emptyPart(1), emptyPart(1))),
         100
      );

      assertFalse(result.success());
   }

   @Test
   void rejectsUnboundedRepeatMetadataBeforeVoxelExpansion() {
      WorkspaceTransform hostile = new WorkspaceTransform(
         Vec3.ZERO,
         Vec3.ZERO,
         new OperationStackRegion(BlockPos.ZERO, new BlockPos(1_000_000, 0, 0)),
         BlockPos.ZERO
      );

      assertFalse(OperationWorkspaceValidator.validTransform(hostile));
   }

   @Test
   void rejectsTheSmallestIntegerEndpointInsteadOfLettingTheAbsoluteValueOverflow() {
      // Math.abs(Integer.MIN_VALUE) is still Integer.MIN_VALUE, so a plain int comparison
      // reads it as below the limit. This region holds ONE cell, so no cell count or budget
      // check can reject it: only the endpoint bound can.
      BlockPos extreme = new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
      OperationStackRegion singleCell = new OperationStackRegion(extreme, extreme);
      assertEquals(1L, singleCell.cellCount());
      WorkspaceTransform hostile = new WorkspaceTransform(
         Vec3.ZERO,
         Vec3.ZERO,
         singleCell,
         BlockPos.ZERO
      );

      assertFalse(OperationWorkspaceValidator.validTransform(hostile));
   }

   @Test
   void acceptsAnEndpointExactlyAtTheLimit() {
      // The widened comparison must not become exclusive and reject the legal boundary.
      BlockPos limit = new BlockPos(
         OperationStackRegion.ENDPOINT_LIMIT, OperationStackRegion.ENDPOINT_LIMIT,
         OperationStackRegion.ENDPOINT_LIMIT
      );
      WorkspaceTransform legal = new WorkspaceTransform(
         Vec3.ZERO,
         Vec3.ZERO,
         new OperationStackRegion(BlockPos.ZERO, limit),
         BlockPos.ZERO
      );

      assertTrue(OperationWorkspaceValidator.validTransform(legal));
   }

   @Test
   void doesNotReadLiveWorldWhenSubmittingSavedWorldSnapshot() throws ReflectiveOperationException {
      BlockPos source = new BlockPos(4, 5, 6);
      BlockSnapshot clientSnapshot = inertSnapshot();
      OperationWorkspacePlan.Part part = new OperationWorkspacePlan.Part(
         1,
         ClientSelectionPart.Source.WORLD,
         Map.of(source, clientSnapshot),
         WorkspaceTransform.IDENTITY,
         true
      );

      var result = OperationWorkspaceValidator.validate(
         new OperationWorkspacePlan(List.of(part)),
         100
      );

      assertTrue(result.success());
      assertTrue(result.clears().contains(source));
   }

   private static BlockSnapshot inertSnapshot() throws ReflectiveOperationException {
      Field field = Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      return (BlockSnapshot)((Unsafe)field.get(null)).allocateInstance(BlockSnapshot.class);
   }

   private static OperationWorkspacePlan.Part emptyPart(int id) {
      return new OperationWorkspacePlan.Part(
         id, ClientSelectionPart.Source.CLIPBOARD, Map.of(), WorkspaceTransform.IDENTITY, false
      );
   }

   /**
    * The snapshots below are inert, so {@code state()} returns {@code null} and a part that
    * reaches the air filter would throw. Each test therefore uses a real production input whose
    * verdict is decided before that filter: the budget gate rejects the plan, or the transform
    * resolves to an empty voxel map. The cap rule against non-empty output is covered in
    * {@code WorkspaceGeometryBudgetTest}, which tests the module this validator calls.
    */
   @Test
   void rejectsRealUpscaleWhoseScannedVolumeExceedsTheLimit() throws ReflectiveOperationException {
      BlockSnapshot snapshot = inertSnapshot();
      Map<BlockPos, BlockSnapshot> dense = Map.of(
         new BlockPos(0, 0, 0), snapshot,
         new BlockPos(1, 0, 0), snapshot
      );
      WorkspaceTransform upscaled = WorkspaceTransform.IDENTITY.withScale(Axis.X, 500.0);

      var result = OperationWorkspaceValidator.validate(
         new OperationWorkspacePlan(List.of(part(1, dense, upscaled, false))),
         100
      );

      assertFalse(result.success(), "a real 1000-cell scan must still obey the scan budget");
      assertTrue(result.writes().isEmpty());
   }

   @Test
   void rejectsSparsePartWhoseRepeatCellsPushItPastTheCap() throws ReflectiveOperationException {
      BlockSnapshot snapshot = inertSnapshot();
      Map<BlockPos, BlockSnapshot> sparse = Map.of(
         new BlockPos(0, 64, 0), snapshot,
         new BlockPos(100, 64, 0), snapshot
      );
      // 2 voxels over 51 repetition cells spend 102, which exceeds the cap of 100.
      WorkspaceTransform overLimit = new WorkspaceTransform(
         Vec3.ZERO, Vec3.ZERO, new OperationStackRegion(BlockPos.ZERO, new BlockPos(50, 0, 0))
      );

      var result = OperationWorkspaceValidator.validate(
         new OperationWorkspacePlan(List.of(part(1, sparse, overLimit, false))),
         100
      );

      assertFalse(result.success(), "a sparse part still spends its repetition cells");
   }

   @Test
   void rejectsUnboundedRepeatRegionOnASparsePart() throws ReflectiveOperationException {
      BlockSnapshot snapshot = inertSnapshot();
      Map<BlockPos, BlockSnapshot> sparse = Map.of(
         new BlockPos(0, 0, 0), snapshot,
         new BlockPos(1_000_000, 0, 0), snapshot
      );
      WorkspaceTransform huge = new WorkspaceTransform(
         Vec3.ZERO, Vec3.ZERO, new OperationStackRegion(BlockPos.ZERO, new BlockPos(128, 128, 128))
      );

      var result = OperationWorkspaceValidator.validate(
         new OperationWorkspacePlan(List.of(part(1, sparse, huge, false))),
         100
      );

      assertFalse(result.success(), "a sparse part must still respect the repetition budget");
   }

   @Test
   void acceptsPartThatScalesToAnEmptySampleThroughTheFullPath() throws ReflectiveOperationException {
      BlockSnapshot snapshot = inertSnapshot();
      // Only x=0 and x=2 are occupied. A scale of one third samples x=1, which is empty.
      Map<BlockPos, BlockSnapshot> threeWide = Map.of(
         new BlockPos(0, 64, 0), snapshot,
         new BlockPos(2, 64, 0), snapshot
      );
      WorkspaceTransform shrink = WorkspaceTransform.IDENTITY.withScale(Axis.X, 1.0 / 3.0);

      var result = OperationWorkspaceValidator.validate(
         new OperationWorkspacePlan(List.of(part(1, threeWide, shrink, false))),
         100
      );

      assertTrue(result.success(), "the budget gate accepts, and an empty sample is a legal result");
      assertTrue(result.writes().isEmpty());
   }

   private static OperationWorkspacePlan.Part part(
      int id,
      Map<BlockPos, BlockSnapshot> blocks,
      WorkspaceTransform transform,
      boolean pendingDelete
   ) {
      return new OperationWorkspacePlan.Part(
         id, ClientSelectionPart.Source.CLIPBOARD, blocks, transform, pendingDelete
      );
   }
}
