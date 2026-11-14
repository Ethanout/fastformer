package io.github.fastformer.common.raycast;


import io.github.fastformer.common.selection.session.*;
import io.github.fastformer.common.geometry.workflow.*;
import io.github.fastformer.common.geometry.workflow.plan.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LongRangeBlockRaycastTest {
   @Test
   void verticalRaysStopAtTheDimensionPlanes() {
      LongRangeBlockRaycast.LimitDistance upward = LongRangeBlockRaycast.dimensionLimit(-64, 320, 64.0, 1.0);
      LongRangeBlockRaycast.LimitDistance downward = LongRangeBlockRaycast.dimensionLimit(-64, 320, 64.0, -1.0);

      assertEquals(LongRangeBlockRaycast.Limit.DIMENSION_TOP, upward.limit());
      assertEquals(LongRangeBlockRaycast.Limit.DIMENSION_BOTTOM, downward.limit());
      assertTrue(upward.distance() < 256.0 && upward.distance() > 255.0);
      assertTrue(downward.distance() < 128.0 && downward.distance() > 127.0);
   }

   @Test
   void horizontalRaysAreNotArtificiallyHeightLimited() {
      LongRangeBlockRaycast.LimitDistance horizontal = LongRangeBlockRaycast.dimensionLimit(-64, 320, 64.0, 0.0);

      assertEquals(Double.POSITIVE_INFINITY, horizontal.distance());
   }

}
