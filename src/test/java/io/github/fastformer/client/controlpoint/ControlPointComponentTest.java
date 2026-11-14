package io.github.fastformer.client.controlpoint;

import io.github.fastformer.client.preview.style.ControlPointStyle;

import io.github.fastformer.common.geometry.controlpoint.ControlPoint;
import io.github.fastformer.common.geometry.controlpoint.ControlPointFeedback;
import io.github.fastformer.common.geometry.controlpoint.ControlPointState;

import io.github.fastformer.common.geometry.ControlPointRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ControlPointComponentTest {
   @Test
   void stateComponentSeparatesConfirmedPendingAndDerivedSemantics() {
      ControlPoint confirmed = ControlPoint.precise(new Vec3(0.5, 0.5, 0.5), ControlPointRole.SECONDARY);
      ControlPoint pending = ControlPoint.pending(new Vec3(1.5, 0.5, 0.5), ControlPointRole.SECONDARY);
      ControlPoint derived = ControlPoint.derived(new Vec3(1.0, 0.5, 0.5), ControlPointRole.DERIVED_CENTER);

      assertEquals(ControlPointState.CONFIRMED, confirmed.state());
      assertTrue(confirmed.confirmed());
      assertTrue(confirmed.state().participatesInBuild());
      assertTrue(confirmed.state().undoable());

      assertEquals(ControlPointState.PENDING, pending.state());
      assertFalse(pending.confirmed());
      assertTrue(pending.state().participatesInBuild());
      assertFalse(pending.state().undoable());

      assertEquals(ControlPointState.DERIVED, derived.state());
      assertTrue(derived.confirmed());
      assertFalse(derived.state().participatesInBuild());
      assertFalse(derived.state().undoable());
   }

   @Test
   void feedbackComponentOnlyChangesObjectsThatOptIntoHover() {
      ControlPoint inactiveStart = ControlPoint.of(new BlockPos(0, 0, 0), ControlPointRole.PRIMARY).withHovered(true);
      assertEquals(ControlPointStyle.START, ControlPointStyle.resolve(inactiveStart.role(), inactiveStart.feedback(), true));
      ControlPoint ordinary = ControlPoint.secondary(new BlockPos(0, 0, 0)).withHovered(true);
      ControlPoint closeable = ControlPoint.primary(new BlockPos(0, 0, 0), true);

      assertEquals(ControlPointStyle.CONTROL, ControlPointStyle.resolve(ordinary));
      assertEquals(ControlPointStyle.HOVER, ControlPointStyle.resolve(closeable));
      assertTrue(closeable.feedback().changesOnHover());
      assertFalse(ordinary.feedback().changesOnHover());
   }

   @Test
   void everyControlPointRoleProvidesHoverText() {
      for (ControlPointRole role : ControlPointRole.values()) {
         ControlPoint point = ControlPoint.precise(Vec3.ZERO, role);
         assertTrue(point.hoverText() != null);
      }
   }
}
