package io.github.fastformer.client.operation.transform;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.core.math.Axis;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class OperationTransformsTest {
   @Test
   void movementAndCancellationStayWithinTheBoundWorkspace() {
      var first = workspace();
      var second = workspace();
      var changes = new AtomicInteger();
      var transforms = transforms(first, () -> false, changes);
      assertTrue(transforms.moveSelected(new BlockPos(3, 0, 0)));
      assertEquals(new Vec3(3, 0, 0), first.selectedParts().getFirst().transform().translation());
      assertEquals(Vec3.ZERO, second.selectedParts().getFirst().transform().translation());
      assertEquals(1, changes.get());

      assertTrue(first.beginEdit());
      var token = first.activeEditToken();
      var baseline = List.copyOf(first.selectedParts());
      transforms.updateTransformGesture(token, baseline, false, AxisGizmo.Operation.MOVE, Axis.X, 1, 4, Double.NaN);
      assertNotEquals(new Vec3(3, 0, 0), first.selectedParts().getFirst().transform().translation());
      transforms.cancel();
      assertFalse(first.editing());
      assertEquals(new Vec3(3, 0, 0), first.selectedParts().getFirst().transform().translation());
      assertEquals(Vec3.ZERO, second.selectedParts().getFirst().transform().translation());
   }

   @Test
   void aForeignEditTokenCannotFinishOrCancelAnotherGesture() {
      var first = workspace();
      var second = workspace();
      var transforms = transforms(first, () -> false, new AtomicInteger());
      assertTrue(first.beginEdit());
      assertTrue(second.beginEdit());
      var foreign = second.activeEditToken();
      assertFalse(transforms.finishTransformGesture(foreign));
      transforms.cancelTransformGesture(foreign);
      assertTrue(first.editing());
      transforms.cancel();
      assertFalse(first.editing());
   }

   @Test
   void pendingSubmissionOrCapturePreventsMovementAndPreservesHistory() {
      var workspace = workspace();
      var pending = new AtomicBoolean(true);
      var capturePending = new AtomicBoolean();
      var changes = new AtomicInteger();
      var transforms = new OperationTransforms(workspace, pending::get, capturePending::get,
         () -> true, key -> fail(key), changes::incrementAndGet);
      int undo = workspace.undoSize();
      assertFalse(transforms.moveSelected(new BlockPos(1, 0, 0)));
      assertEquals(undo, workspace.undoSize());
      assertEquals(Vec3.ZERO, workspace.selectedParts().getFirst().transform().translation());
      assertEquals(0, changes.get());
      pending.set(false);
      capturePending.set(true);
      assertFalse(transforms.moveSelected(new BlockPos(1, 0, 0)));
      assertEquals(undo, workspace.undoSize());
      capturePending.set(false);
      assertTrue(transforms.moveSelected(new BlockPos(1, 0, 0)));
   }

   private static OperationTransforms transforms(ClientOperationWorkspace workspace,
      java.util.function.BooleanSupplier pending, AtomicInteger changed) {
      return new OperationTransforms(workspace, pending, () -> false, () -> true,
         key -> fail(key), changed::incrementAndGet);
   }

   private static ClientOperationWorkspace workspace() {
      var workspace = new ClientOperationWorkspace();
      var selection = OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(1, 1, 1), BlockPos.ZERO, new BlockPos(1, 1, 1));
      assertTrue(workspace.addParts(List.of(new ClientSelectionPart(0, ClientSelectionPart.Source.WORLD,
         selection, Map.of(), WorkspaceTransform.IDENTITY, false))));
      return workspace;
   }
}
