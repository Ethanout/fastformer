package io.github.fastformer.fastplace.quickshape;


import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Lists valid input modes. Session transitions belong to the client session owner. */
public final class QuickShapeModeRules {
   private static final Map<QuickShapeMode, List<? extends QuickShapeMode>> NEXT_MODES = createTransitions();

   private QuickShapeModeRules() {
   }

   public static List<? extends QuickShapeMode> allowedModes(QuickShapeStage stage, LineMode lineMode) {
      return switch (stage) {
         case POINT -> List.of(PointMode.RAYCAST);
         case LINE -> allowedNextModes(PointMode.RAYCAST);
         case FACE -> allowedNextModes(lineMode);
         case VOLUME -> List.of(VolumeMode.PERPENDICULAR_TO_FACE, VolumeMode.FREE);
      };
   }

   public static List<? extends QuickShapeMode> allowedNextModes(QuickShapeMode mode) {
      return NEXT_MODES.getOrDefault(mode, List.of());
   }

   public static QuickShapeMode nextMode(QuickShapeStage stage, LineMode lineMode, QuickShapeMode current) {
      List<? extends QuickShapeMode> allowed = allowedModes(stage, lineMode);
      int index = allowed.indexOf(current);
      return allowed.get((index + 1) % allowed.size());
   }

   public static QuickShapeMode validMode(QuickShapeStage stage, LineMode lineMode, QuickShapeMode current) {
      List<? extends QuickShapeMode> allowed = allowedModes(stage, lineMode);
      return allowed.contains(current) ? current : allowed.getFirst();
   }

   private static Map<QuickShapeMode, List<? extends QuickShapeMode>> createTransitions() {
      Map<QuickShapeMode, List<? extends QuickShapeMode>> transitions = new HashMap<>();
      transitions.put(PointMode.RAYCAST, List.of(LineMode.AXIS, LineMode.FREE_SCROLL, LineMode.RAYCAST));
      transitions.put(LineMode.AXIS, List.of(FaceMode.COORDINATE_PLANE, FaceMode.PARALLELOGRAM_BASE_PLANE, FaceMode.POLYGON));
      transitions.put(LineMode.FREE_SCROLL, List.of(FaceMode.PARALLELOGRAM_BASE_PLANE, FaceMode.POLYGON));
      transitions.put(LineMode.RAYCAST, List.of(FaceMode.PARALLELOGRAM_BASE_PLANE, FaceMode.POLYGON));
      for (FaceMode mode : FaceMode.values()) {
         transitions.put(
            mode,
            List.of(VolumeMode.PERPENDICULAR_TO_FACE, VolumeMode.FREE)
         );
      }
      return Map.copyOf(transitions);
   }
}
