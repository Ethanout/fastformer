package io.github.fastformer.client.render.hud;

/** Snaps HUD positions to framebuffer pixels without a time delay. */
public final class ScreenPixelHysteresis {
   private static final double RELEASE_DISTANCE = 0.75;
   public record Position(double x, double y) { }
   private double scale = Double.NaN;
   private double pixelX;
   private double pixelY;

   public Position update(double x, double y, double guiScale) {
      double nextScale = Math.max(1, guiScale);
      double nextX = x * nextScale;
      double nextY = y * nextScale;
      if (scale != nextScale) {
         pixelX = Math.rint(nextX);
         pixelY = Math.rint(nextY);
         scale = nextScale;
      } else {
         pixelX = snap(nextX, pixelX);
         pixelY = snap(nextY, pixelY);
      }
      return new Position(pixelX / scale, pixelY / scale);
   }

   private static double snap(double target, double previous) {
      return Math.abs(target - previous) <= RELEASE_DISTANCE ? previous : Math.rint(target);
   }
}
