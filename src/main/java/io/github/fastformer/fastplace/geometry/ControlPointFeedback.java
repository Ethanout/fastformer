package io.github.fastformer.fastplace.geometry;

public record ControlPointFeedback(ControlPointStyle hoverStyle) {
   public static ControlPointFeedback none() {
      return new ControlPointFeedback(null);
   }

   public static ControlPointFeedback closeable() {
      return new ControlPointFeedback(ControlPointStyle.HOVER);
   }

   public static ControlPointFeedback hoverable() {
      return new ControlPointFeedback(ControlPointStyle.HOVER);
   }

   public ControlPointStyle style(ControlPointRole role, boolean hovered) {
      ControlPointStyle normal = role == null ? ControlPointStyle.AUXILIARY : role.style();
      return hovered && this.hoverStyle != null ? this.hoverStyle : normal;
   }

   public boolean changesOnHover() {
      return this.hoverStyle != null;
   }
}
