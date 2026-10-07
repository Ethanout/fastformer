package io.github.fastformer.workspace.transform;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.workspace.model.WorkspaceSymmetry;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.workspace.preview.WorkspacePreviewComposer;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class WorkspaceSymmetryTest {
   @Test
   void fourQuarterTurnsAndTwoReflectionsRestoreEveryPoint() {
      Vec3 pivot = new Vec3(8.5, 3, -2);
      WorkspaceSymmetry symmetry = WorkspaceSymmetry.IDENTITY;
      for (int turn = 0; turn < 4; turn++) symmetry = symmetry.then(WorkspaceSymmetry.quarterTurnY(), pivot);
      assertEquals(WorkspaceSymmetry.IDENTITY, symmetry);
      for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
         var reflection = WorkspaceSymmetry.reflection(axis);
         assertEquals(WorkspaceSymmetry.IDENTITY, WorkspaceSymmetry.IDENTITY.then(reflection, pivot).then(reflection, pivot));
      }
   }

   @Test
   void composedOperationsUseWorldAxesAndPreserveTheirOrder() {
      Vec3 pivot = new Vec3(2.5, 4.5, 8.5);
      Vec3 point = new Vec3(4.5, 5.5, 9.5);
      var rotate = WorkspaceSymmetry.IDENTITY.then(WorkspaceSymmetry.quarterTurnY(), pivot);
      var rotateThenFlip = rotate.then(WorkspaceSymmetry.reflection(AxisGizmo.Axis.X), pivot);
      assertEquals(new Vec3(3.5, 5.5, 10.5), rotateThenFlip.point(point));
      var flipThenRotate = WorkspaceSymmetry.IDENTITY.then(WorkspaceSymmetry.reflection(AxisGizmo.Axis.X), pivot)
         .then(WorkspaceSymmetry.quarterTurnY(), pivot);
      assertNotEquals(rotateThenFlip.point(point), flipThenRotate.point(point));
      assertEquals(rotateThenFlip, WorkspaceSymmetry.decode(rotateThenFlip.encode()));
   }

   @Test
   void voxelCompositionMirrorsAfterExistingRotationAndBeforeTranslation() {
      var source = Map.of(BlockPos.ZERO, "first", new BlockPos(2, 0, 0), "last");
      var transform = WorkspaceTransform.IDENTITY.withSymmetry(
         WorkspaceSymmetry.IDENTITY.then(WorkspaceSymmetry.reflection(AxisGizmo.Axis.X), new Vec3(1.5, 0.5, 0.5)));
      assertEquals(Map.of(BlockPos.ZERO, "last", new BlockPos(2, 0, 0), "first"),
         WorkspacePreviewComposer.resolveValues(source, transform));
      assertEquals(Map.of(new BlockPos(10, 0, 0), "last", new BlockPos(12, 0, 0), "first"),
         WorkspacePreviewComposer.resolveValues(source, transform.withTranslation(new Vec3(10, 0, 0))));
      assertEquals(transform.symmetry(), transform.withRotation(new Vec3(0, Math.PI / 2, 0)).symmetry());
      assertTrue(transform.hasBaseEffect());
   }

   @Test
   void viewAxisUsesTheDominantComponentIncludingVerticalViews() {
      assertEquals(AxisGizmo.Axis.X, SelectionShortcutCalculator.viewAxis(new Vec3(-0.8, 0.1, 0.3)));
      assertEquals(AxisGizmo.Axis.Y, SelectionShortcutCalculator.viewAxis(new Vec3(0.1, -0.9, 0.3)));
      assertEquals(AxisGizmo.Axis.Z, SelectionShortcutCalculator.viewAxis(new Vec3(0.1, 0.3, 0.9)));
      assertEquals(AxisGizmo.Axis.Z, SelectionShortcutCalculator.viewAxis(new Vec3(1, 0, 1)));
   }

   @Test
   void mirroredGizmoRotationsPreserveWorldHandedness() {
      var symmetry = WorkspaceSymmetry.reflection(AxisGizmo.Axis.X);
      assertEquals(0.0, new Vec3(0, -1, 0).distanceToSqr(symmetry.localRotationAxis(new Vec3(0, 1, 0))), 1.0E-12);
      assertEquals(0.0, new Vec3(1, 0, 0).distanceToSqr(symmetry.localRotationAxis(new Vec3(1, 0, 0))), 1.0E-12);
      assertEquals(0.0, new Vec3(0, 1, 0).distanceToSqr(WorkspaceSymmetry.quarterTurnY().localRotationAxis(new Vec3(0, 1, 0))), 1.0E-12);
   }
}
