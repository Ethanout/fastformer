package io.github.fastformer.fastplace.quickshape;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class QuickShapeDraftStageTest {
   @Test
   void draftOwnsStageResolutionForPolygonLifecycle() {
      var draft = new QuickShapeDraft();
      assertEquals(QuickShapeStage.POINT, draft.stage(FaceMode.POLYGON));
      draft.addPoint(new BlockPos(1, 2, 3), Vec3.ZERO, new Vec3(1, 0, 0));
      draft.addPoint(new BlockPos(2, 2, 3), Vec3.ZERO, new Vec3(1, 0, 0));
      assertEquals(QuickShapeStage.FACE, draft.stage(FaceMode.POLYGON));
      draft.addPoint(new BlockPos(2, 2, 4), Vec3.ZERO, new Vec3(1, 0, 0));
      draft.closePolygon();
      assertEquals(QuickShapeStage.VOLUME, draft.stage(FaceMode.POLYGON));
   }
}
