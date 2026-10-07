package io.github.fastformer.fastplace.interaction;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

final class TinkerAttachment {
   private TinkerAttachment() {}

   static BlockState apply(BlockState state, BlockHitResult hit) {
      List<Direction> existing = new ArrayList<>();
      for (Direction direction : Direction.values()) {
         BooleanProperty property = PipeBlock.PROPERTY_BY_DIRECTION.get(direction);
         if (state.hasProperty(property) && state.getValue(property)) existing.add(direction);
      }
      if (existing.isEmpty()) return state;
      // The outward normal of a thin plate need not point toward its support.
      Direction attached = existing.getFirst();
      double best = TinkerSurfaceHit.distance(TinkerSurfaceHit.local(hit), attached);
      for (Direction direction : existing) {
         double candidate = TinkerSurfaceHit.distance(TinkerSurfaceHit.local(hit), direction);
         if (candidate < best) {
            attached = direction;
            best = candidate;
         }
      }
      Direction add = TinkerSurfaceHit.edge(hit, attached,
         direction -> state.hasProperty(PipeBlock.PROPERTY_BY_DIRECTION.get(direction)));
      if (add != null) return state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(add), true);
      return existing.size() == 1 ? state : state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(attached), false);
   }
}
