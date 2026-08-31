package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FastPlaceStateMachine {
   private static final Map<FastPlaceMode, List<? extends FastPlaceMode>> NEXT_MODES = createTransitions();

   private FastPlaceStateMachine() {
   }

   public static List<? extends FastPlaceMode> allowedModes(FastPlaceStage stage, LineMode lineMode) {
      return allowedModes(stage, lineMode, FaceMode.POLYGON);
   }

   public static List<? extends FastPlaceMode> allowedModes(FastPlaceStage stage, LineMode lineMode, FaceMode faceMode) {
      return switch (stage) {
         case POINT -> List.of(PointMode.RAYCAST);
         case LINE -> allowedNextModes(PointMode.RAYCAST);
         case FACE -> allowedNextModes(lineMode);
         case VOLUME -> List.of(VolumeMode.PERPENDICULAR_TO_FACE, VolumeMode.FREE);
      };
   }

   public static List<? extends FastPlaceMode> allowedNextModes(FastPlaceMode mode) {
      return NEXT_MODES.getOrDefault(mode, List.of());
   }

   public static FastPlaceMode nextMode(FastPlaceStage stage, FastPlaceSettings settings) {
      List<? extends FastPlaceMode> allowed = allowedModes(stage, settings.storedLineMode(), settings.storedFaceMode());
      FastPlaceMode current = settings.storedModeFor(stage);
      int index = allowed.indexOf(current);
      return allowed.get((index + 1) % allowed.size());
   }

   public static FastPlaceMode validMode(FastPlaceStage stage, FastPlaceSettings settings) {
      List<? extends FastPlaceMode> allowed = allowedModes(stage, settings.storedLineMode(), settings.storedFaceMode());
      FastPlaceMode current = settings.storedModeFor(stage);
      return allowed.contains(current) ? current : allowed.getFirst();
   }

   private static Map<FastPlaceMode, List<? extends FastPlaceMode>> createTransitions() {
      Map<FastPlaceMode, List<? extends FastPlaceMode>> transitions = new HashMap<>();
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
