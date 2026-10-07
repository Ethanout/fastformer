package io.github.fastformer.fastplace.interaction;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.BlockHitResult;

final class TinkerRail {
   private TinkerRail() {}

   static BlockState apply(BlockState state, BlockHitResult hit) {
      EnumProperty<RailShape> property = state.hasProperty(BlockStateProperties.RAIL_SHAPE)
         ? BlockStateProperties.RAIL_SHAPE : BlockStateProperties.RAIL_SHAPE_STRAIGHT;
      RailShape shape = state.getValue(property);
      Direction.Axis axis = axis(shape);
      Direction side = TinkerSurfaceHit.sideOperation(hit);
      RailShape target;
      if (side.getAxis() == axis) {
         RailShape ascending = ascending(side);
         target = shape == ascending ? straight(axis) : ascending;
      } else if (side.getAxis().isHorizontal() && property == BlockStateProperties.RAIL_SHAPE) {
         boolean curved = shape == RailShape.NORTH_EAST || shape == RailShape.NORTH_WEST
            || shape == RailShape.SOUTH_EAST || shape == RailShape.SOUTH_WEST;
         var local = TinkerSurfaceHit.local(hit);
         Direction along = axis == Direction.Axis.Z ? (local.z < 0.5 ? Direction.NORTH : Direction.SOUTH)
            : (local.x < 0.5 ? Direction.WEST : Direction.EAST);
         target = curved ? straight(axis) : curve(along, side);
      } else {
         target = rotate(shape);
      }
      return property.getPossibleValues().contains(target) ? state.setValue(property, target) : state;
   }

   private static Direction.Axis axis(RailShape shape) {
      return switch (shape) {
         case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> Direction.Axis.X;
         default -> Direction.Axis.Z;
      };
   }

   private static RailShape straight(Direction.Axis axis) {
      return axis == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
   }

   private static RailShape ascending(Direction side) {
      return switch (side) {
         case EAST -> RailShape.ASCENDING_EAST;
         case WEST -> RailShape.ASCENDING_WEST;
         case NORTH -> RailShape.ASCENDING_NORTH;
         case SOUTH -> RailShape.ASCENDING_SOUTH;
         default -> throw new IllegalArgumentException("Rail slope requires a horizontal end");
      };
   }

   private static RailShape curve(Direction first, Direction second) {
      boolean north = first == Direction.NORTH || second == Direction.NORTH;
      boolean east = first == Direction.EAST || second == Direction.EAST;
      return north ? (east ? RailShape.NORTH_EAST : RailShape.NORTH_WEST)
         : (east ? RailShape.SOUTH_EAST : RailShape.SOUTH_WEST);
   }

   private static RailShape rotate(RailShape shape) {
      return switch (shape) {
         case NORTH_SOUTH -> RailShape.EAST_WEST;
         case EAST_WEST -> RailShape.NORTH_SOUTH;
         case ASCENDING_NORTH -> RailShape.ASCENDING_EAST;
         case ASCENDING_EAST -> RailShape.ASCENDING_SOUTH;
         case ASCENDING_SOUTH -> RailShape.ASCENDING_WEST;
         case ASCENDING_WEST -> RailShape.ASCENDING_NORTH;
         case NORTH_EAST -> RailShape.SOUTH_EAST;
         case SOUTH_EAST -> RailShape.SOUTH_WEST;
         case SOUTH_WEST -> RailShape.NORTH_WEST;
         case NORTH_WEST -> RailShape.NORTH_EAST;
      };
   }
}
