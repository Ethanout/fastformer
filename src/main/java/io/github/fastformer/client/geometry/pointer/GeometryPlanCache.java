package io.github.fastformer.client.geometry.pointer;

import io.github.fastformer.common.geometry.preview.GeometryPreviewPlan;
import io.github.fastformer.common.geometry.workflow.GeometryWorkflows;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Owns the plan shared by one geometry interaction's input, HUD and drawing. */
final class GeometryPlanCache {
   private final BiConsumer<String, Throwable> failureLogger;
   private final Function<GeometryPlanInput, GeometryPreviewPlan> planner;
   private GeometryPlanInput key;
   private GeometryPreviewPlan plan;
   private long lastFailureLogAt;

   GeometryPlanCache(BiConsumer<String, Throwable> failureLogger) {
      this(failureLogger, input -> GeometryWorkflows.previewPlan(
         GeometryWorkflowSnapshot.view(input.snapshot(), input.modifierHeld(), input.view()),
         input.snapshot().points(), input.hoveredPoint(), input.candidate(), input.eye()));
   }

   GeometryPlanCache(BiConsumer<String, Throwable> failureLogger, Function<GeometryPlanInput, GeometryPreviewPlan> planner) {
      this.failureLogger = Objects.requireNonNull(failureLogger, "failureLogger");
      this.planner = Objects.requireNonNull(planner, "planner");
   }

   GeometryPreviewPlan build(GeometryPlanInput input) {
      if (!sameInput(input)) {
         try {
            plan = Objects.requireNonNull(planner.apply(input), "geometry plan");
         } catch (RuntimeException exception) {
            reportFailure(exception);
            plan = GeometryPreviewPlan.controlPoints(input.snapshot().points(), input.hoveredPoint());
         }
         key = input;
      }
      return plan;
   }

   private boolean sameInput(GeometryPlanInput input) {
      return key != null && key.version() == input.version() && key.snapshot() == input.snapshot()
         && key.modifierHeld() == input.modifierHeld() && key.eye().equals(input.eye())
         && key.view().equals(input.view()) && Objects.equals(key.candidate(), input.candidate())
         && Objects.equals(key.hoveredPoint(), input.hoveredPoint());
   }

   void clear() { key = null; plan = null; }

   private void reportFailure(RuntimeException failure) {
      long now = System.nanoTime();
      if (lastFailureLogAt == 0 || now < lastFailureLogAt || now - lastFailureLogAt >= 5_000_000_000L) {
         lastFailureLogAt = now;
         failureLogger.accept("Unable to generate FastFormer geometry preview plan; using control points", failure);
      }
   }
}
