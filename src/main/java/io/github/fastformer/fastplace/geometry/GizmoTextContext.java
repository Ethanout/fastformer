package io.github.fastformer.fastplace.geometry;

public record GizmoTextContext(
   String axis,
   String operation,
   String base,
   String delta,
   String current,
   String direction
) {
   public GizmoTextContext {
      axis = safe(axis);
      operation = safe(operation);
      base = safe(base);
      delta = safe(delta);
      current = safe(current);
      direction = safe(direction);
   }

   public String value(String name) {
      if (name == null) {
         return null;
      }
      return switch (name) {
         case "axis" -> this.axis;
         case "operation" -> this.operation;
         case "base" -> this.base;
         case "delta" -> this.delta;
         case "current" -> this.current;
         case "direction" -> this.direction;
         default -> null;
      };
   }

   public static GizmoTextContext empty() {
      return new GizmoTextContext("", "", "", "", "", "");
   }

   private static String safe(String value) {
      return value == null ? "" : value;
   }
}
