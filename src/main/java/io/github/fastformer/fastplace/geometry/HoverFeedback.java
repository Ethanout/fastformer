package io.github.fastformer.fastplace.geometry;

public record HoverFeedback(int normalColor, int hoverColor, int activeColor) {
   public static final int GIZMO_HOVER_COLOR = 0xFFFFFF;
   private static final float AXIS_HOVER_BRIGHTNESS = 0.32F;

   public HoverFeedback {
      normalColor &= 0xFFFFFF;
      hoverColor &= 0xFFFFFF;
      activeColor = hoverColor;
   }

   public int color(State state) {
      return switch (state == null ? State.NORMAL : state) {
         case NORMAL -> this.normalColor;
         case HOVER -> this.hoverColor;
         case ACTIVE -> this.activeColor;
      };
   }

   public int color(boolean hovered, boolean active) {
      return this.color(active ? State.ACTIVE : hovered ? State.HOVER : State.NORMAL);
   }

   public static HoverFeedback axis(int normalColor) {
      return new HoverFeedback(normalColor, brighten(normalColor, AXIS_HOVER_BRIGHTNESS), normalColor);
   }

   public static HoverFeedback pointGizmo(int normalColor) {
      return new HoverFeedback(normalColor, GIZMO_HOVER_COLOR, GIZMO_HOVER_COLOR);
   }

   public static int brighten(int color, float amount) {
      float mix = Math.clamp(Float.isFinite(amount) ? amount : 0.0F, 0.0F, 1.0F);
      int red = (color >>> 16) & 0xFF;
      int green = (color >>> 8) & 0xFF;
      int blue = color & 0xFF;
      red = Math.round(red + (255 - red) * mix);
      green = Math.round(green + (255 - green) * mix);
      blue = Math.round(blue + (255 - blue) * mix);
      return (red << 16) | (green << 8) | blue;
   }

   public enum State {
      NORMAL,
      HOVER,
      ACTIVE
   }
}
