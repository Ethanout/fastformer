package io.github.fastformer.client.render.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import org.junit.jupiter.api.Test;

class GizmoHudTextFormatterTest {
   @Test
   void mapsEachGizmoOperationToItsTranslationKey() {
      assertEquals("fastformer.hud.transform.position", GizmoHudTextFormatter.transformLabelKey(AxisGizmo.Operation.MOVE));
      assertEquals("fastformer.hud.transform.scale", GizmoHudTextFormatter.transformLabelKey(AxisGizmo.Operation.SCALE));
      assertEquals("fastformer.hud.transform.rotation", GizmoHudTextFormatter.transformLabelKey(AxisGizmo.Operation.ROTATE));
   }
}
