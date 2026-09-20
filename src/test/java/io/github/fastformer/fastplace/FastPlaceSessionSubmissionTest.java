package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.quickshape.LineMode;

import io.github.fastformer.fastplace.session.FastPlaceSession;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FastPlaceSessionSubmissionTest {
   @Test
   void scrollSubmissionIncludesCandidateWithoutChangingDraft() {
      FastPlaceSession session = sessionWithFirstPoint();
      session.setFreeScrollOffset(new BlockPos(5, -2, 3));
      List<BlockPos> submitted = session.submissionPoints(LineMode.FREE_SCROLL);
      assertEquals(List.of(BlockPos.ZERO, new BlockPos(5, -2, 3)), submitted);
      assertEquals(List.of(BlockPos.ZERO), session.points());
      session.adjustFreeScrollOffset(new Vec3(1, 0, 0), 1);
      assertEquals(new BlockPos(5, -2, 3), submitted.getLast());
      assertThrows(UnsupportedOperationException.class, () -> submitted.add(BlockPos.ZERO));
   }

   @Test
   void ordinaryModesSubmitOnlyConfirmedPoints() {
      FastPlaceSession session = sessionWithFirstPoint();
      session.setFreeScrollOffset(new BlockPos(5, -2, 3));
      for (LineMode mode : LineMode.values()) {
         if (mode != LineMode.FREE_SCROLL) {
            assertEquals(List.of(BlockPos.ZERO), session.submissionPoints(mode));
         }
      }
   }

   @Test
   void faceStageDoesNotAppendOldScrollCandidate() {
      FastPlaceSession session = sessionWithFirstPoint();
      session.addPoint(new BlockPos(3, 0, 0), Vec3.ZERO, new Vec3(1, 0, 0));
      session.setFreeScrollOffset(new BlockPos(0, 4, 0));
      assertEquals(session.points(), session.submissionPoints(LineMode.FREE_SCROLL));
   }

   @Test
   void zeroOffsetSubmitsOnePointWithoutDuplicate() {
      FastPlaceSession session = sessionWithFirstPoint();
      session.adjustFreeScrollOffset(new Vec3(1, 0, 0), 1);
      session.adjustFreeScrollOffset(new Vec3(1, 0, 0), -1);
      assertEquals(List.of(BlockPos.ZERO), session.submissionPoints(LineMode.FREE_SCROLL));
   }

   @Test
   void modeEntryCandidateIsIncludedWithoutAnExtraScroll() {
      FastPlaceSession session = sessionWithFirstPoint();
      session.onModeChanged(new BlockPos(5, -2, 3));
      assertEquals(List.of(BlockPos.ZERO, new BlockPos(5, -2, 3)), session.submissionPoints(LineMode.FREE_SCROLL));
   }

   @Test
   void emptySessionHasNoSubmissionPoints() {
      assertEquals(List.of(), new FastPlaceSession().submissionPoints(LineMode.FREE_SCROLL));
   }

   private static FastPlaceSession sessionWithFirstPoint() {
      FastPlaceSession session = new FastPlaceSession();
      session.addPoint(BlockPos.ZERO, Vec3.ZERO, new Vec3(1, 0, 0));
      return session;
   }
}
