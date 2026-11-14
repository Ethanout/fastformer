package io.github.fastformer.common.mode;

import io.github.fastformer.common.geometry.GeometryMode;

public final class BuiltinModes {
   public static final ModeId IDLE = ModeId.of("idle");
   public static final ModeId GEOMETRY = ModeId.of("geometry");
   public static final ModeId BUSY = ModeId.of("busy");
   public static final ModeId QUICK_SHAPE = ModeId.of("quick_shape");
   public static final ModeId SELECTION = ModeId.of("selection");
   public static final ModeId SPECIAL_ITEM = ModeId.of("special_item");
   public static final ModeId VANILLA = ModeId.of("vanilla");
   public static final ModeId QUICK_REPLACE = ModeId.of("quick_replace");
   public static final ModeId SMART_FILL = ModeId.of("smart_fill");
   private static final ModeId WALL = ModeId.of("geometry/wall");
   private static final ModeId POLYHEDRON = ModeId.of("geometry/polyhedron");
   private static final ModeId CONE_PRISM = ModeId.of("geometry/cone_prism");
   private static final ModeId COMPOUND = ModeId.of("geometry/compound");
   private static final ModeId CONVEX_POLYHEDRON = ModeId.of("geometry/convex_polyhedron");
   private BuiltinModes() {}

   public static ModeId geometry(GeometryMode mode) {
      return switch (mode) {
         case WALL -> WALL;
         case POLYHEDRON -> POLYHEDRON;
         case CONE_PRISM -> CONE_PRISM;
         case COMPOUND -> COMPOUND;
         case CONVEX_POLYHEDRON -> CONVEX_POLYHEDRON;
      };
   }
}
