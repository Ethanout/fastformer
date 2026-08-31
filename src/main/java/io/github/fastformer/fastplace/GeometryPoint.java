package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.geometry.ControlPointRole;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public record GeometryPoint(Vec3 location, ControlPointRole role) {
   public GeometryPoint {
      location = GeometryNumbers.finiteOrZero(location);
      role = role == null ? ControlPointRole.SECONDARY : role;
   }

   public GeometryPoint(Vec3 location) {
      this(location, ControlPointRole.SECONDARY);
   }

   public static GeometryPoint block(BlockPos block) {
      return new GeometryPoint(Vec3.atCenterOf(block), ControlPointRole.SECONDARY);
   }

   public BlockPos block() {
      return BlockPos.containing(this.location);
   }

   public GeometryPoint offset(Vec3 offset) {
      return new GeometryPoint(this.location.add(GeometryNumbers.finiteOrZero(offset)), this.role);
   }

   public GeometryPoint withLocation(Vec3 location) {
      return new GeometryPoint(location, this.role);
   }
}
