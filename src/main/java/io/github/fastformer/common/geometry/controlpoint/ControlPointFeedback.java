package io.github.fastformer.common.geometry.controlpoint;

public record ControlPointFeedback(boolean changesOnHover) {
   public static ControlPointFeedback none() {
      return new ControlPointFeedback(false);
   }

   public static ControlPointFeedback closeable() {
      return new ControlPointFeedback(true);
   }

   public static ControlPointFeedback hoverable() {
      return new ControlPointFeedback(true);
   }

}
