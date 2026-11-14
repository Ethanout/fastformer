package io.github.fastformer.server.intent.quickshape;

import io.github.fastformer.core.command.Intent;
import io.github.fastformer.common.quickshape.mode.QuickShapeMode;
import java.util.Objects;

/** Explicit quick-shape edits; the workflow owns their stage-specific interpretation. */
public final class QuickShapeIntents {
   private QuickShapeIntents() {}

   public record ContextValue(double value) implements Intent {}
   public record FaceAngle(double degrees) implements Intent {}
   public record LineDistance(int blocks) implements Intent {}
   public record VolumeDistance(int blocks) implements Intent {}
   public record SelectStageMode(QuickShapeMode mode) implements Intent {
      public SelectStageMode { Objects.requireNonNull(mode, "mode"); }
   }
}
