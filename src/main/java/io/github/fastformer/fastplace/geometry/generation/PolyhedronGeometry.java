package io.github.fastformer.fastplace.geometry.generation;

import net.minecraft.world.phys.Vec3;

final class PolyhedronGeometry {
   private PolyhedronGeometry() {
   }

   static Bounds bounds(PolyhedronParameters parameters, boolean rotationPadding) {
      Vec3 center = parameters.center();
      double radius = parameters.radius(1.0);
      double padding = rotationPadding && parameters.rotation() != null && parameters.rotation().length == 9
         ? Math.sqrt(3.0)
         : 1.0;
      int extent = (int)Math.ceil(radius * parameters.maxScale() * padding);
      return new Bounds(
         (int)Math.floor(center.x - extent - 0.5),
         (int)Math.floor(center.y - extent - 0.5),
         (int)Math.floor(center.z - extent - 0.5),
         (int)Math.ceil(center.x + extent - 0.5),
         (int)Math.ceil(center.y + extent - 0.5),
         (int)Math.ceil(center.z + extent - 0.5)
      );
   }

   static Vec3 toLocal(Vec3 worldOffset, PolyhedronParameters parameters) {
      Vec3 worldScale = parameters.worldScale();
      double worldX = worldOffset.x / worldScale.x;
      double worldY = worldOffset.y / worldScale.y;
      double worldZ = worldOffset.z / worldScale.z;
      double[] rotation = parameters.rotation();
      double rotatedX = rotation == null || rotation.length != 9
         ? worldX
         : rotation[0] * worldX + rotation[3] * worldY + rotation[6] * worldZ;
      double rotatedY = rotation == null || rotation.length != 9
         ? worldY
         : rotation[1] * worldX + rotation[4] * worldY + rotation[7] * worldZ;
      double rotatedZ = rotation == null || rotation.length != 9
         ? worldZ
         : rotation[2] * worldX + rotation[5] * worldY + rotation[8] * worldZ;
      Vec3 localScale = parameters.localScale();
      return new Vec3(rotatedX / localScale.x, rotatedY / localScale.y, rotatedZ / localScale.z);
   }

   static Vec3 toWorld(Vec3 local, PolyhedronParameters parameters) {
      Vec3 localScale = parameters.localScale();
      double x = local.x * localScale.x;
      double y = local.y * localScale.y;
      double z = local.z * localScale.z;
      double[] rotation = parameters.rotation();
      double rotatedX = rotation == null || rotation.length != 9 ? x : rotation[0] * x + rotation[1] * y + rotation[2] * z;
      double rotatedY = rotation == null || rotation.length != 9 ? y : rotation[3] * x + rotation[4] * y + rotation[5] * z;
      double rotatedZ = rotation == null || rotation.length != 9 ? z : rotation[6] * x + rotation[7] * y + rotation[8] * z;
      Vec3 worldScale = parameters.worldScale();
      return new Vec3(rotatedX * worldScale.x, rotatedY * worldScale.y, rotatedZ * worldScale.z);
   }

   static Vec3 ringPoint(int plane, double radius, double angle) {
      double first = Math.cos(angle) * radius;
      double second = Math.sin(angle) * radius;
      return switch (Math.floorMod(plane, 3)) {
         case 0 -> new Vec3(0.0, first, second);
         case 1 -> new Vec3(first, 0.0, second);
         default -> new Vec3(first, second, 0.0);
      };
   }

   record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
      long volume() {
         long width = (long)this.maxX - this.minX + 1L;
         long height = (long)this.maxY - this.minY + 1L;
         long depth = (long)this.maxZ - this.minZ + 1L;
         return GenerationMath.saturatedMultiply(GenerationMath.saturatedMultiply(width, height), depth);
      }
   }
}
