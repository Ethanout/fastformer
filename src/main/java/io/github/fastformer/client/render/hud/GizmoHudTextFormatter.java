package io.github.fastformer.client.render.hud;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.geometry.GizmoTextContext;
import net.minecraft.network.chat.Component;

/** Builds human-readable Gizmo values without depending on rendering state. */
public final class GizmoHudTextFormatter {
   private static final double EPSILON = 1.0E-7;

   private GizmoHudTextFormatter() {
   }

   public static GizmoTextContext operationTransformTextContext(
      AxisGizmo.Axis axis,
      AxisGizmo.Operation operation,
      double baseValue,
      double currentValue,
      int fallbackSteps
   ) {
      GizmoTextContext generic = textContext(axis, operation, baseValue, currentValue, fallbackSteps);
      String operationName = Component.translatable(
         operation == AxisGizmo.Operation.SCALE
            ? "fastformer.hud.transform.stack"
            : transformLabelKey(operation)
      ).getString();
      return new GizmoTextContext(
         generic.axis(), operationName, generic.base(), generic.delta(), generic.current(), generic.direction()
      );
   }

   public static String formatValue(
      AxisGizmo.Operation operation, double baseValue, double currentValue, int fallbackSteps
   ) {
      GizmoTextContext context = textContext(AxisGizmo.Axis.X, operation, baseValue, currentValue, fallbackSteps);
      return context.base() + context.delta();
   }

   public static GizmoTextContext textContext(
      AxisGizmo.Axis axis,
      AxisGizmo.Operation operation,
      double baseValue,
      double currentValue,
      int fallbackSteps
   ) {
      double delta = GeometryNumbers.cleanZero(currentValue - baseValue);
      if (Math.abs(delta) < EPSILON && fallbackSteps != 0) {
         delta = switch (operation) {
            case MOVE -> fallbackSteps * 0.5;
            case ROTATE -> fallbackSteps * 360.0 / 1024.0;
            case SCALE -> 0.0;
         };
      }
      String base = operation == AxisGizmo.Operation.SCALE
         ? HudValueFormatter.scale(baseValue)
         : HudValueFormatter.coordinate(baseValue);
      String change = operation == AxisGizmo.Operation.SCALE
         ? HudValueFormatter.scale(Math.abs(delta))
         : HudValueFormatter.coordinate(Math.abs(delta));
      String suffix = operation == AxisGizmo.Operation.ROTATE ? "\u00b0" : "";
      String deltaText = (delta < 0.0 ? "-" : "+") + change + suffix;
      String current = operation == AxisGizmo.Operation.SCALE
         ? HudValueFormatter.scale(currentValue)
         : HudValueFormatter.coordinate(currentValue);
      String direction = delta < -EPSILON ? "NEGATIVE" : delta > EPSILON ? "POSITIVE" : "NONE";
      return new GizmoTextContext(
         axis.name(),
         Component.translatable(transformLabelKey(operation)).getString(),
         base,
         deltaText,
         current,
         direction
      );
   }

   public static String transformLabelKey(AxisGizmo.Operation operation) {
      return switch (operation) {
         case MOVE -> "fastformer.hud.transform.position";
         case SCALE -> "fastformer.hud.transform.scale";
         case ROTATE -> "fastformer.hud.transform.rotation";
      };
   }
}
