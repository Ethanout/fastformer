package io.github.fastformer.client.operation.pointer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.selection.OperationMode;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationStageMode;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class RemoteSelectionPointerTest {
   private final InteractionContext context = new InteractionContext(null, null, new Vec3(0.5,0.5,-5),
      new Vec3(0,0,1), new Vec3(0.5,0.5,-5), false, false, false);

   @Test
   void pointQueriesBelongToTheBoundPreviewAndKeepThroughBlockSelection() {
      var first = new AtomicReference<>(snapshot(List.of(BlockPos.ZERO), false));
      var second = new AtomicReference<>(snapshot(List.of(new BlockPos(9,0,0)), false));
      var wall = new BlockHitResult(new Vec3(0.5,0.5,-2), Direction.NORTH, new BlockPos(0,0,-2), false);
      var a = pointer(first, wall);
      var b = pointer(second, wall);
      assertEquals(0, a.operationPointUnderCrosshairIndex());
      assertEquals(-1, b.operationPointUnderCrosshairIndex());
      assertEquals(new Vec3(0.5,0.5,0.5), a.operationPointCenter(0));
      assertNull(a.operationPointCenter(-1));
      assertTrue(a.operationNeedsSecond());
      first.set(OperationPreviewPayload.inactive());
      assertEquals(-1, a.operationPointUnderCrosshairIndex());
      assertEquals(0, a.operationPointCount());
      assertEquals(1, b.operationPointCount());
   }

   @Test
   void worldOccluderAndSelectionFaceKeepNearestTargetPriority() {
      var snapshot = new AtomicReference<>(snapshot(List.of(BlockPos.ZERO, new BlockPos(1,1,1)), true));
      var near = pointer(snapshot, new BlockHitResult(new Vec3(0.5,0.5,-2), Direction.NORTH, new BlockPos(0,0,-2), false));
      assertEquals(OperationPointerKind.WORLD, near.operationPointerTarget().kind());
      var far = pointer(snapshot, new BlockHitResult(new Vec3(0.5,0.5,5), Direction.NORTH, new BlockPos(0,0,5), false));
      assertEquals(OperationPointerKind.FACE, far.operationPointerTarget().kind());
      assertNotNull(far.operationFaceHit());
      assertNotNull(far.operationSelection());
   }

   @Test
   void nullPointerContextNeverProducesATargetOrGizmo() {
      var state = snapshot(List.of(BlockPos.ZERO), false);
      var pointer = new RemoteSelectionPointer(() -> state, () -> false, c -> { throw new AssertionError(); }, () -> null, () -> null);
      assertNull(pointer.operationCandidatePoint());
      assertNull(pointer.operationGizmo());
      assertNull(pointer.operationPrismEdgeInsertion());
      assertNull(pointer.operationFaceHit());
      assertEquals(OperationPointerKind.NONE, pointer.operationPointerTarget().kind());
   }

   private RemoteSelectionPointer pointer(AtomicReference<OperationPreviewPayload> state, BlockHitResult hit) {
      var result = new LongRangeBlockRaycast.Result(hit, 20, 20, null, 1, 0);
      return new RemoteSelectionPointer(state::get, () -> false, c -> result, () -> context, () -> null);
   }

   private static OperationPreviewPayload snapshot(List<BlockPos> points, boolean second) {
      return OperationPreviewPayload.active(1, true, second, points, BlockPos.ZERO, BlockPos.ZERO, OperationSelectionMode.CUBOID,
         0, -1, 0, OperationMode.MOVE, OperationStageMode.TRANSFORM, BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, Vec3.ZERO, false, false, false);
   }
}
