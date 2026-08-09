package io.github.fastformer.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GizmoViewScaleTest {
   @Test
   void growsWithCameraDistanceAndRemainsBounded() {
      GizmoViewScale near = GizmoViewScale.fromDistance(2.0);
      GizmoViewScale medium = GizmoViewScale.fromDistance(50.0);
      GizmoViewScale far = GizmoViewScale.fromDistance(10_000.0);

      assertEquals(1.625, near.axisLength(), 0.0001);
      assertEquals(5.85, medium.axisLength(), 0.0001);
      assertEquals(20.8, far.axisLength(), 0.0001);
      assertTrue(near.handleRadius() <= medium.handleRadius());
      assertTrue(medium.handleRadius() <= far.handleRadius());
   }
}
