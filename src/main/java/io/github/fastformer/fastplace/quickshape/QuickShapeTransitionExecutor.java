package io.github.fastformer.fastplace.quickshape;

import java.util.Objects;
import io.github.fastformer.fastplace.geometry.generation.LineTieBias;

/** Executes the side effects requested by the quick-shape workflow. */
public final class QuickShapeTransitionExecutor {
   private QuickShapeTransitionExecutor() { }

   public interface Effects {
      boolean accepts(QuickShapeWorkflow.Decision decision);
      void preview();
      void submit();
      void finishClosedPath();
      void cancelEmptyDraft();
   }

   private static QuickShapeWorkflow.Action apply(QuickShapeDraft draft, QuickShapeWorkflow.Event event,
      QuickShapeWorkflow.Decision decision) {
      if (decision.action() == QuickShapeWorkflow.Action.REJECT) return decision.action();
      if (event instanceof QuickShapeWorkflow.Event.Undo) draft.undoStep();
      if (event instanceof QuickShapeWorkflow.Event.ConfirmPoint point) {
         if (decision.addPoint()) draft.addPoint(point.point(), point.eye(), point.view());
         if (decision.confirmFaceBias()) {
            draft.confirmFaceTieBias(point.modifierHeld() ? LineTieBias.OPPOSITE : LineTieBias.DEFAULT);
         }
      }
      if (decision.closePolygon()) draft.closePolygon();
      if (decision.confirmHeight()) draft.confirmPolygonHeight();
      if (decision.resetStage()) draft.onStageChanged();
      return decision.action();
   }

   public static boolean execute(QuickShapeDraft draft, FaceMode mode, QuickShapeWorkflow.Event event, Effects effects) {
      Objects.requireNonNull(effects, "effects");
      var decision = QuickShapeWorkflow.onEvent(draft, mode, event);
      if (decision.action() == QuickShapeWorkflow.Action.REJECT || !effects.accepts(decision)) return false;
      var action = apply(draft, event, decision);
      switch (action) {
         case PREVIEW -> effects.preview();
         case SUBMIT -> effects.submit();
         case FINISH_CLOSED_PATH -> effects.finishClosedPath();
         case CANCEL_EMPTY_DRAFT -> effects.cancelEmptyDraft();
         case REJECT -> {
            return false;
         }
      }
      return true;
   }
}
