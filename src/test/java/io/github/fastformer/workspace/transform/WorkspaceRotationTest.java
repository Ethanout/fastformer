package io.github.fastformer.workspace.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.workspace.model.WorkspaceSymmetry;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class WorkspaceRotationTest {
   @Test
   void sequentialRotationsMatchTheCapturedAxisAcrossPolesAndReflections() {
      for (Vec3 angles : new Vec3[]{Vec3.ZERO, new Vec3(0.3, 0.7, -0.2),
         new Vec3(0.2, Math.PI / 2, 0.6), new Vec3(-0.4, -Math.PI / 2, 0.3),
         new Vec3(Math.PI / 2, Math.PI / 2, -Math.PI / 2)}) {
         for (int mirror = 0; mirror < 8; mirror++) {
            WorkspaceSymmetry symmetry = WorkspaceSymmetry.IDENTITY;
            for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
               if ((mirror & (1 << axis.ordinal())) != 0) symmetry = symmetry.then(WorkspaceSymmetry.reflection(axis), Vec3.ZERO);
            }
            symmetry = symmetry.then(WorkspaceSymmetry.quarterTurnY(), Vec3.ZERO);
            var before = WorkspaceTransform.IDENTITY.withRotation(angles).withSymmetry(symmetry);
            for (AxisGizmo.Axis handle : AxisGizmo.Axis.values()) {
               Vec3 normal = WorkspaceRotation.localAxis(before, handle);
               for (double delta : new double[]{0.31, -0.64, Math.PI / 2}) {
                  var after = before.withRotation(WorkspaceRotation.rotateWorld(before, normal, delta));
                  for (AxisGizmo.Axis basis : AxisGizmo.Axis.values()) {
                     Vec3 expected = WorkspaceRotation.rotateAroundAxis(WorkspaceRotation.localAxis(before, basis), normal, delta);
                     assertEquals(0.0, expected.distanceToSqr(WorkspaceRotation.localAxis(after, basis)), 1.0E-14,
                        "orientation mismatch: " + angles + ", " + mirror + ", " + handle);
                  }
               }
            }
         }
      }
   }

   @Test
   void capturedLocalRotationAxisStaysFixedDuringItsOwnRotation() {
      var before = WorkspaceTransform.IDENTITY.withRotation(new Vec3(0.4, -0.7, 0.3));
      for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
         Vec3 normal = WorkspaceRotation.localAxis(before, axis);
         var after = before.withRotation(WorkspaceRotation.rotateWorld(before, normal, 1.2));
         assertEquals(0.0, normal.distanceToSqr(WorkspaceRotation.localAxis(after, axis)), 1.0E-14);
      }
   }
}
