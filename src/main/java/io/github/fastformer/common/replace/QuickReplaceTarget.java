package io.github.fastformer.common.replace;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** A replacement result with shared block-state properties preserved. */
public record QuickReplaceTarget(BlockPos position, BlockState state) {
   public static BlockState copySharedProperties(BlockState source, BlockState target) {
      BlockState result = target;
      for (Property<?> property : source.getProperties()) {
         if (result.hasProperty(property)) result = copyProperty(source, result, property);
      }
      return result;
   }

   private static <T extends Comparable<T>> BlockState copyProperty(BlockState source, BlockState target, Property<T> property) {
      T value = source.getValue(property);
      return property.getPossibleValues().contains(value) ? target.setValue(property, value) : target;
   }
}
