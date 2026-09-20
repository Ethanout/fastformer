package io.github.fastformer.fastplace.geometry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class AxisGizmoTextDefaultTest {
   @Test
   void defaultGizmoHasHoverAndDragText() {
      AxisGizmo gizmo = new AxisGizmo(Vec3.ZERO, 1.0, 0.1);
      assertFalse(gizmo.textComponent().empty());
      assertTrue(gizmo.textComponent().hoverVisible());
      assertTrue(gizmo.textComponent().dragVisible());
   }

   @Test
   void explicitNoneStillDisablesText() {
      AxisGizmo gizmo = new AxisGizmo(Vec3.ZERO, 1.0, 0.1)
         .withTextComponent(GizmoTextComponent.none());
      assertTrue(gizmo.textComponent().empty());
   }

   @Test
   void everyHandleProvidesAStableHoverText() {
      AxisGizmo gizmo = new AxisGizmo(Vec3.ZERO, 1.0, 0.1);
      gizmo.handles().forEach(handle -> assertTrue(handle.hoverText() != null));
   }
}
