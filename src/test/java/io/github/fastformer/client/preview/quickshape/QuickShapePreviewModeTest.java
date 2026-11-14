package io.github.fastformer.client.preview.quickshape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.geometry.GuideLine;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class QuickShapePreviewModeTest {
   @Test
   void largeLayerBoundaryFallbackDoesNotExposeCenterLinesOrDuplicateSolidShells() {
      var first = new GuideLine(Vec3.ZERO, new Vec3(1, 0, 0));
      var second = new GuideLine(new Vec3(1, 0, 0), new Vec3(1, 1, 0));
      assertTrue(QuickShapePreviewMode.boundaryFallback(List.of(), true).isEmpty());
      assertTrue(QuickShapePreviewMode.boundaryFallback(List.of(first), true).isEmpty());
      assertTrue(QuickShapePreviewMode.boundaryFallback(List.of(first, second), false).isEmpty());
      assertEquals(List.of(first, second), QuickShapePreviewMode.boundaryFallback(List.of(first, second), true));
   }
}
