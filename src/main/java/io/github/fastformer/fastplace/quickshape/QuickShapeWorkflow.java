package io.github.fastformer.fastplace.quickshape;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Computes editing decisions without changing the draft. */
public final class QuickShapeWorkflow {
   private QuickShapeWorkflow() { }

   public sealed interface Event {
      record ConfirmPoint(BlockPos point, Vec3 eye, Vec3 view, boolean modifierHeld) implements Event {
         public ConfirmPoint {
            point = Objects.requireNonNull(point, "point").immutable();
            Objects.requireNonNull(eye, "eye");
            Objects.requireNonNull(view, "view");
         }
      }
      enum ClosePath implements Event { INSTANCE }
      enum Undo implements Event { INSTANCE }
   }

   public enum Action { PREVIEW, SUBMIT, FINISH_CLOSED_PATH, CANCEL_EMPTY_DRAFT, REJECT }

   public record Decision(Action action, boolean addPoint, boolean closePolygon,
      boolean confirmHeight, boolean confirmFaceBias, boolean resetStage) { }

   private static Decision unchanged(Action action) {
      return new Decision(action, false, false, false, false, false);
   }

   public static Decision onEvent(QuickShapeDraft draft, FaceMode faceMode, Event event) {
      Objects.requireNonNull(draft, "draft");
      Objects.requireNonNull(faceMode, "faceMode");
      Objects.requireNonNull(event, "event");
      return switch (event) {
         case Event.ConfirmPoint point -> confirmPoint(draft, faceMode, point);
         case Event.ClosePath ignored -> closePath(draft, faceMode);
         case Event.Undo ignored -> unchanged(draft.points().size() > 1 ? Action.PREVIEW : Action.CANCEL_EMPTY_DRAFT);
      };
   }

   private static Decision confirmPoint(QuickShapeDraft draft, FaceMode faceMode, Event.ConfirmPoint event) {
      int previousCount = draft.points().size();
      boolean addPoint = previousCount == 0 || !draft.points().getLast().equals(event.point());
      if (draft.polygonClosed()) {
         return new Decision(Action.SUBMIT, addPoint, false, addPoint, false, false);
      }

      QuickShapeStage previousStage = QuickShapeStage.resolve(previousCount, faceMode, false);
      if (previousStage == QuickShapeStage.VOLUME && previousCount == 3) {
         return new Decision(Action.SUBMIT, addPoint, false, false, false, false);
      }
      boolean polygonFace = faceMode == FaceMode.POLYGON && previousCount >= 2;
      int closingPoints = polygonFace || previousStage != QuickShapeStage.VOLUME ? 3 : 4;
      boolean closed = previousCount >= closingPoints && draft.points().getFirst().equals(event.point());
      if (closed) return polygonFace ? closePath(draft, faceMode) : unchanged(Action.FINISH_CLOSED_PATH);
      int nextCount = previousCount + (addPoint ? 1 : 0);
      QuickShapeStage currentStage = QuickShapeStage.resolve(nextCount, faceMode, false);
      return new Decision(!polygonFace && nextCount == 4 ? Action.SUBMIT : Action.PREVIEW,
         addPoint, false, false, !polygonFace && previousCount == 2 && nextCount == 3,
         !polygonFace && currentStage != previousStage);
   }

   private static Decision closePath(QuickShapeDraft draft, FaceMode faceMode) {
      if (faceMode != FaceMode.POLYGON || draft.polygonClosed() || draft.points().size() < 3) {
         return unchanged(Action.REJECT);
      }
      return new Decision(Action.PREVIEW, false, true, false, false, true);
   }
}
