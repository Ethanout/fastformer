package io.github.fastformer.fastplace.world;


import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class WorldWriteSideEffectGuardTest {
   @Test
   void suppressionIsLimitedToTheExactWorldAndBlock() {
      Object level = new Object();
      Object otherLevel = new Object();
      BlockPos pos = new BlockPos(3, 4, 5);

      try (WorldWriteSideEffectGuard.Suppression ignored = WorldWriteSideEffectGuard.suppress(level, pos)) {
         assertTrue(WorldWriteSideEffectGuard.suppresses(level, pos));
         assertFalse(WorldWriteSideEffectGuard.suppresses(level, pos.offset(1, 0, 0)));
         assertFalse(WorldWriteSideEffectGuard.suppresses(otherLevel, pos));
      }

      assertFalse(WorldWriteSideEffectGuard.suppresses(level, pos));
   }

   @Test
   void nestedWritesKeepTheOuterSuppressionUntilItFinishes() {
      Object level = new Object();
      BlockPos outer = new BlockPos(1, 2, 3);
      BlockPos inner = new BlockPos(7, 8, 9);

      try (WorldWriteSideEffectGuard.Suppression ignored = WorldWriteSideEffectGuard.suppress(level, outer)) {
         try (WorldWriteSideEffectGuard.Suppression nested = WorldWriteSideEffectGuard.suppress(level, inner)) {
            assertTrue(WorldWriteSideEffectGuard.suppresses(level, outer));
            assertTrue(WorldWriteSideEffectGuard.suppresses(level, inner));
         }
         assertTrue(WorldWriteSideEffectGuard.suppresses(level, outer));
         assertFalse(WorldWriteSideEffectGuard.suppresses(level, inner));
      }
   }
}
