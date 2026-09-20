package io.github.fastformer.client.gizmo;

/** Screen-oriented world scale derived from camera distance. */
public record GizmoViewScale(double axisLength, double handleRadius) {
   private static final double AXIS_DISTANCE_SCALE = 0.117;
   private static final double MIN_AXIS_LENGTH = 1.625;
   private static final double MAX_AXIS_LENGTH = 20.8;
   private static final double HANDLE_AXIS_SCALE = 0.0845;
   private static final double MIN_HANDLE_RADIUS = 0.13;
   private static final double MAX_HANDLE_RADIUS = 1.3;

   public static GizmoViewScale fromDistance(double distance) {
      double safeDistance = Double.isFinite(distance) ? Math.max(0.0, distance) : 0.0;
      double axisLength = Math.clamp(
         safeDistance * AXIS_DISTANCE_SCALE, MIN_AXIS_LENGTH, MAX_AXIS_LENGTH
      );
      double handleRadius = Math.clamp(
         axisLength * HANDLE_AXIS_SCALE, MIN_HANDLE_RADIUS, MAX_HANDLE_RADIUS
      );
      return new GizmoViewScale(axisLength, handleRadius);
   }
}
