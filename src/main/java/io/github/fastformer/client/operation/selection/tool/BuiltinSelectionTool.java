package io.github.fastformer.client.operation.selection.tool;

import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** The existing four geometric tools, registered as strategies without a central input switch. */
final class BuiltinSelectionTool implements SelectionTool {
   private final OperationSelectionMode geometry;
   BuiltinSelectionTool(OperationSelectionMode geometry) { this.geometry = geometry; }
   @Override public ResourceLocation id() { return SelectionTools.builtinId(geometry); }
   @Override public OperationSelectionMode geometry() { return geometry; }
   @Override public String translationKey() { return geometry.translationKey(); }
   @Override public boolean worldSampling() { return geometry == OperationSelectionMode.SMART; }
   @Override public boolean undoPreviousPoint() { return geometry == OperationSelectionMode.CUBOID; }
   @Override public boolean cycleVisible() { return geometry == OperationSelectionMode.CUBOID || geometry == OperationSelectionMode.SMART; }

   @Override public Transition onPress(SelectionToolState draft, Press event) {
      if (worldSampling() || geometry == OperationSelectionMode.CONVEX_HULL && !event.alt()) return Transition.rejected(draft);
      return geometry == OperationSelectionMode.PRISM ? prism(draft, event) : cuboid(draft, event);
   }

   private Transition cuboid(SelectionToolState draft, Press event) {
      if (undoPreviousPoint() && event.button() == Button.LEFT) {
         return draft.points().isEmpty() ? Transition.rejected(draft) : new Transition(draft.removeLast(false), Result.UPDATED);
      }
      if (draft.points().size() >= 2 && event.button() == Button.MIDDLE) {
         var next = draft.expand(event.point());
         return next == draft ? Transition.rejected(draft) : new Transition(next, Result.READY);
      }
      BlockPos first = draft.firstPoint(), second = draft.secondPoint();
      switch (event.button()) {
         case RIGHT -> { if (first == null && geometry == OperationSelectionMode.CUBOID) first = event.point(); else second = event.point(); }
         case MIDDLE -> { if (first == null) first = event.point(); else second = event.point(); }
         case LEFT -> first = event.point();
      }
      boolean complete = first != null && second != null;
      var points = complete ? List.of(first, second) : List.of(first == null ? second : first);
      var next = draft.withPoints(points, 0, first == null && geometry == OperationSelectionMode.CUBOID);
      return complete && next.equals(draft) ? Transition.rejected(draft)
         : new Transition(next, complete ? Result.READY : Result.UPDATED);
   }

   private Transition prism(SelectionToolState draft, Press event) {
      if (event.button() == Button.LEFT) return new Transition(draft.points().isEmpty()
         ? draft.append(event.point()) : draft.removeLast(true), Result.UPDATED);
      if (draft.prismBaseCount() == 0 && event.button() == Button.RIGHT && draft.points().size() >= 3
         && event.point().equals(draft.points().getFirst())) {
         return new Transition(new SelectionToolState(draft.toolId(), geometry, draft.points(), draft.points().size(),
            draft.minPoint(), draft.maxPoint(), false, draft.parameters()), Result.UPDATED);
      }
      var next = draft.append(event.point());
      return new Transition(next, ready(next) ? Result.READY : Result.UPDATED);
   }

   @Override public boolean ready(SelectionToolState draft) {
      return geometry == OperationSelectionMode.PRISM ? draft.prismBaseCount() >= 3 && draft.points().size() > draft.prismBaseCount()
         : !worldSampling() && draft.firstPoint() != null && draft.secondPoint() != null;
   }

   @Override public OperationSelectionVolume volume(SelectionToolState draft, BlockPos candidate) {
      if (draft.points().isEmpty() || worldSampling()) return null;
      var points = new ArrayList<>(draft.points());
      if (candidate != null && !points.contains(candidate)) {
         if (draft.secondPointOnly()) points.addFirst(candidate); else points.add(candidate);
      }
      var volume = OperationSelectionVolume.create(geometry, points, draft.prismBaseCount(), BlockPos.ZERO, BlockPos.ZERO, 0);
      if (volume != null && geometry == OperationSelectionMode.CUBOID) {
         volume = volume.expandCuboidTo(draft.minPoint()).expandCuboidTo(draft.maxPoint());
      }
      return volume;
   }
}
