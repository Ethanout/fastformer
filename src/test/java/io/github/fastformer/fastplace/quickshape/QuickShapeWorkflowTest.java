package io.github.fastformer.fastplace.quickshape;

import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.fastformer.fastplace.quickshape.QuickShapeWorkflow.Action.*;

class QuickShapeWorkflowTest {
   private static final Vec3 VIEW = new Vec3(1, 0, 0);
   private static final List<BlockPos> BASE = List.of(BlockPos.ZERO, new BlockPos(3, 0, 0), new BlockPos(3, 0, 3));

   @Test
   void deniedSubmissionKeepsTheFaceAndOffsetsBeforeAnyEffect() {
      QuickShapeDraft draft = base(FaceMode.POLYGON);
      close(draft, FaceMode.POLYGON);
      draft.setFreeScrollOffset(new BlockPos(2, 3, 4));
      boolean accepted = QuickShapeTransitionExecutor.execute(draft, FaceMode.POLYGON,
         new QuickShapeWorkflow.Event.ConfirmPoint(new BlockPos(3, 4, 3), Vec3.ZERO, VIEW, false),
         new QuickShapeTransitionExecutor.Effects() {
            public boolean accepts(QuickShapeWorkflow.Decision decision) {
               assertEquals(SUBMIT, decision.action());
               assertEquals(BASE, draft.points());
               assertFalse(draft.polygonHeightConfirmed());
               return false;
            }
            public void preview() { fail("Rejected transition emitted preview"); }
            public void submit() { fail("Rejected transition submitted"); }
            public void finishClosedPath() { fail("Rejected transition finished path"); }
            public void cancelEmptyDraft() { fail("Rejected transition cancelled draft"); }
         });
      assertFalse(accepted);
      assertEquals(BASE, draft.points());
      assertTrue(draft.polygonClosed());
      assertFalse(draft.polygonHeightConfirmed());
      assertEquals(new BlockPos(2, 3, 4), draft.freeScrollOffset());
   }

   @Test
   void decisionsDoNotChangePointsOffsetsOrPolygonState() {
      QuickShapeDraft draft = base(FaceMode.POLYGON);
      draft.setFreeScrollOffset(new BlockPos(2, 3, 4));
      var close = QuickShapeWorkflow.onEvent(draft, FaceMode.POLYGON, QuickShapeWorkflow.Event.ClosePath.INSTANCE);
      var undo = QuickShapeWorkflow.onEvent(draft, FaceMode.POLYGON, QuickShapeWorkflow.Event.Undo.INSTANCE);
      var point = QuickShapeWorkflow.onEvent(draft, FaceMode.POLYGON,
         new QuickShapeWorkflow.Event.ConfirmPoint(new BlockPos(0, 0, 3), Vec3.ZERO, VIEW, false));
      assertTrue(close.closePolygon());
      assertEquals(PREVIEW, undo.action());
      assertTrue(point.addPoint());
      assertEquals(BASE, draft.points());
      assertFalse(draft.polygonClosed());
      assertEquals(new BlockPos(2, 3, 4), draft.freeScrollOffset());
   }

   @Test
   void ordinaryPointsAdvanceStagesAndRequestSubmissionOnlyAtTheFourthPoint() {
      QuickShapeDraft draft = new QuickShapeDraft();
      assertEquals(PREVIEW, confirm(draft, FaceMode.COORDINATE_PLANE, BASE.get(0)));
      assertEquals(QuickShapeStage.LINE, stage(draft, FaceMode.COORDINATE_PLANE));
      draft.setFreeScrollOffset(new BlockPos(2, 0, 0));
      assertEquals(PREVIEW, confirm(draft, FaceMode.COORDINATE_PLANE, BASE.get(1)));
      assertEquals(QuickShapeStage.FACE, stage(draft, FaceMode.COORDINATE_PLANE));
      assertEquals(BlockPos.ZERO, draft.freeScrollOffset());
      assertEquals(PREVIEW, confirm(draft, FaceMode.COORDINATE_PLANE, BASE.get(2)));
      assertEquals(QuickShapeStage.VOLUME, stage(draft, FaceMode.COORDINATE_PLANE));
      assertEquals(SUBMIT, confirm(draft, FaceMode.COORDINATE_PLANE, new BlockPos(3, 4, 3)));
      assertEquals(4, draft.points().size());
   }

   @Test
   void polygonRemainsAFaceUntilItClosesWithoutAddingTheFirstPointAgain() {
      QuickShapeDraft draft = base(FaceMode.POLYGON);
      BlockPos extra = new BlockPos(0, 0, 3);
      assertEquals(PREVIEW, confirm(draft, FaceMode.POLYGON, extra));
      assertEquals(QuickShapeStage.FACE, stage(draft, FaceMode.POLYGON));
      draft.setFreeScrollOffset(new BlockPos(1, 2, 3));
      assertEquals(PREVIEW, confirm(draft, FaceMode.POLYGON, BASE.getFirst()));
      assertTrue(draft.polygonClosed());
      assertEquals(4, draft.points().size());
      assertEquals(QuickShapeStage.VOLUME, stage(draft, FaceMode.POLYGON));
      assertEquals(BlockPos.ZERO, draft.freeScrollOffset());
   }

   @Test
   void invalidCloseLeavesTheDraftAndOffsetsIntact() {
      QuickShapeDraft draft = new QuickShapeDraft();
      confirm(draft, FaceMode.POLYGON, BASE.getFirst());
      draft.setFreeScrollOffset(new BlockPos(2, 3, 4));
      assertEquals(REJECT, close(draft, FaceMode.POLYGON));
      assertEquals(List.of(BASE.getFirst()), draft.points());
      assertEquals(new BlockPos(2, 3, 4), draft.freeScrollOffset());
      assertFalse(draft.polygonClosed());
      assertEquals(REJECT, close(base(FaceMode.COORDINATE_PLANE), FaceMode.COORDINATE_PLANE));
   }

   @Test
   void repeatedCloseDoesNotResetNewVolumeOffsets() {
      QuickShapeDraft draft = base(FaceMode.POLYGON);
      assertEquals(PREVIEW, close(draft, FaceMode.POLYGON));
      draft.setFreeScrollOffset(new BlockPos(0, 4, 0));
      assertEquals(REJECT, close(draft, FaceMode.POLYGON));
      assertEquals(new BlockPos(0, 4, 0), draft.freeScrollOffset());
   }

   @Test
   void repeatedPointDoesNotConfirmHeightOrSubmit() {
      QuickShapeDraft draft = base(FaceMode.POLYGON);
      close(draft, FaceMode.POLYGON);
      assertEquals(PREVIEW, confirm(draft, FaceMode.POLYGON, BASE.getLast()));
      assertFalse(draft.polygonHeightConfirmed());
      assertEquals(3, draft.points().size());
      assertEquals(SUBMIT, confirm(draft, FaceMode.POLYGON, new BlockPos(3, 4, 3)));
      assertTrue(draft.polygonHeightConfirmed());
   }

   @Test
   void undoAfterHeightKeepsTheBaseThenReopensTheFace() {
      QuickShapeDraft draft = base(FaceMode.POLYGON);
      close(draft, FaceMode.POLYGON);
      confirm(draft, FaceMode.POLYGON, new BlockPos(3, 4, 3));
      assertEquals(PREVIEW, undo(draft));
      assertEquals(BASE, draft.points());
      assertTrue(draft.polygonClosed());
      assertFalse(draft.polygonHeightConfirmed());
      assertEquals(PREVIEW, undo(draft));
      assertFalse(draft.polygonClosed());
      assertEquals(BASE.subList(0, 2), draft.points());
      assertEquals(PREVIEW, undo(draft));
      assertEquals(CANCEL_EMPTY_DRAFT, undo(draft));
      assertTrue(draft.points().isEmpty());
   }

   @Test
   void ordinaryClosedPathKeepsTheExistingFinishDecision() {
      QuickShapeDraft draft = base(FaceMode.COORDINATE_PLANE);
      draft.addPoint(new BlockPos(3, 4, 3), Vec3.ZERO, VIEW);
      assertEquals(FINISH_CLOSED_PATH, confirm(draft, FaceMode.COORDINATE_PLANE, BASE.getFirst()));
      assertEquals(4, draft.points().size());
   }

   @Test
   void confirmationCapturesMutablePointAndFaceBias() {
      QuickShapeDraft draft = new QuickShapeDraft();
      confirm(draft, FaceMode.COORDINATE_PLANE, BASE.get(0));
      confirm(draft, FaceMode.COORDINATE_PLANE, BASE.get(1));
      BlockPos.MutableBlockPos point = new BlockPos.MutableBlockPos(3, 0, 3);
      var event = new QuickShapeWorkflow.Event.ConfirmPoint(point, Vec3.ZERO, VIEW, true);
      point.set(99, 99, 99);
      assertEquals(PREVIEW, execute(draft, FaceMode.COORDINATE_PLANE, event));
      assertEquals(BASE, draft.points());
      assertEquals(LineTieBias.OPPOSITE, draft.faceTieBias());
   }

   private static QuickShapeDraft base(FaceMode mode) {
      QuickShapeDraft draft = new QuickShapeDraft();
      BASE.forEach(point -> confirm(draft, mode, point));
      return draft;
   }

   private static QuickShapeWorkflow.Action confirm(QuickShapeDraft draft, FaceMode mode, BlockPos point) {
      return execute(draft, mode, new QuickShapeWorkflow.Event.ConfirmPoint(point, Vec3.ZERO, VIEW, false));
   }

   private static QuickShapeWorkflow.Action close(QuickShapeDraft draft, FaceMode mode) {
      return execute(draft, mode, QuickShapeWorkflow.Event.ClosePath.INSTANCE);
   }

   private static QuickShapeWorkflow.Action undo(QuickShapeDraft draft) {
      return execute(draft, FaceMode.POLYGON, QuickShapeWorkflow.Event.Undo.INSTANCE);
   }

   private static QuickShapeStage stage(QuickShapeDraft draft, FaceMode mode) {
      return QuickShapeStage.resolve(draft.points().size(), mode, draft.polygonClosed());
   }

   private static QuickShapeWorkflow.Action execute(QuickShapeDraft draft, FaceMode mode, QuickShapeWorkflow.Event event) {
      var emitted = new java.util.ArrayList<QuickShapeWorkflow.Action>();
      boolean accepted = QuickShapeTransitionExecutor.execute(draft, mode, event, new QuickShapeTransitionExecutor.Effects() {
         public boolean accepts(QuickShapeWorkflow.Decision decision) { return true; }
         public void preview() { emitted.add(PREVIEW); }
         public void submit() { emitted.add(SUBMIT); }
         public void finishClosedPath() { emitted.add(FINISH_CLOSED_PATH); }
         public void cancelEmptyDraft() { emitted.add(CANCEL_EMPTY_DRAFT); }
      });
      assertEquals(accepted ? 1 : 0, emitted.size());
      return accepted ? emitted.getFirst() : REJECT;
   }
}
