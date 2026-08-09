package io.github.fastformer.fastplace.geometry;

import java.util.EnumSet;
import java.util.Set;

public record GeometryStage(String id, String labelKey, Set<GeometryAction> actions) {
   public GeometryStage {
      id = id == null || id.isBlank() ? "unknown" : id;
      labelKey = labelKey == null || labelKey.isBlank() ? "fastformer.geometry.stage." + id : labelKey;
      actions = actions == null || actions.isEmpty()
         ? Set.of()
         : Set.copyOf(actions);
   }

   public static GeometryStage collecting(String id) {
      return new GeometryStage(id, null, EnumSet.of(GeometryAction.POINT_INPUT, GeometryAction.CANDIDATE_INPUT));
   }

   public static GeometryStage collectingWithScroll(String id) {
      return collecting(id).allow(GeometryAction.SCALAR_ADJUST);
   }

   public static GeometryStage adjusting(String id) {
      return new GeometryStage(id, null, EnumSet.of(GeometryAction.SCALAR_ADJUST, GeometryAction.CONFIRM));
   }

   public static GeometryStage ready(String id) {
      return new GeometryStage(id, null, EnumSet.of(GeometryAction.CONFIRM));
   }

   public boolean allows(GeometryAction action) {
      return action != null && this.actions.contains(action);
   }

   public GeometryStage allow(GeometryAction action) {
      return this.withAction(action, true);
   }

   public GeometryStage deny(GeometryAction action) {
      return this.withAction(action, false);
   }

   public GeometryStage withAction(GeometryAction action, boolean value) {
      if (action == null || this.allows(action) == value) {
         return this;
      }
      EnumSet<GeometryAction> updated = this.actions.isEmpty()
         ? EnumSet.noneOf(GeometryAction.class)
         : EnumSet.copyOf(this.actions);
      if (value) {
         updated.add(action);
      } else {
         updated.remove(action);
      }
      return new GeometryStage(this.id, this.labelKey, updated);
   }

   public GeometryStage withLabel(String labelKey) {
      return new GeometryStage(this.id, labelKey, this.actions);
   }
}
