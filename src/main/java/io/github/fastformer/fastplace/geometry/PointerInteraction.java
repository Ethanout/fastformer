package io.github.fastformer.fastplace.geometry;

import java.util.EnumMap;
import java.util.Map;

public final class PointerInteraction {
   private final Map<PointerGesture, GeometryInteractionAction> actions;

   private PointerInteraction(Map<PointerGesture, GeometryInteractionAction> actions) {
      this.actions = Map.copyOf(actions);
   }

   public static PointerInteraction empty() {
      return new PointerInteraction(Map.of());
   }

   public static PointerInteraction selectControlPoint() {
      return empty().bindLeftClick(GeometryInteractionAction.SELECT_CONTROL_POINT).bindRightClick(GeometryInteractionAction.SELECT_CONTROL_POINT);
   }

   public static PointerInteraction closePath() {
      return empty()
         .bindRightClick(GeometryInteractionAction.CLOSE_PATH)
         .bindRightDoubleClick(GeometryInteractionAction.CLOSE_PATH);
   }

   public PointerInteraction bindLeftClick(GeometryInteractionAction action) {
      return this.bind(PointerGesture.LEFT_CLICK, action);
   }

   public PointerInteraction bindRightClick(GeometryInteractionAction action) {
      return this.bind(PointerGesture.RIGHT_CLICK, action);
   }

   public PointerInteraction bindLeftDoubleClick(GeometryInteractionAction action) {
      return this.bind(PointerGesture.LEFT_DOUBLE_CLICK, action);
   }

   public PointerInteraction bindRightDoubleClick(GeometryInteractionAction action) {
      return this.bind(PointerGesture.RIGHT_DOUBLE_CLICK, action);
   }

   public PointerInteraction bindLeftLongPress(GeometryInteractionAction action) {
      return this.bind(PointerGesture.LEFT_LONG_PRESS, action);
   }

   public PointerInteraction bindRightLongPress(GeometryInteractionAction action) {
      return this.bind(PointerGesture.RIGHT_LONG_PRESS, action);
   }

   public GeometryInteractionAction action(PointerGesture gesture) {
      return gesture == null ? null : this.actions.get(gesture);
   }

   private PointerInteraction bind(PointerGesture gesture, GeometryInteractionAction action) {
      EnumMap<PointerGesture, GeometryInteractionAction> next = new EnumMap<>(PointerGesture.class);
      next.putAll(this.actions);
      if (action == null) {
         next.remove(gesture);
      } else {
         next.put(gesture, action);
      }
      return new PointerInteraction(next);
   }
}
