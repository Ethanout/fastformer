package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.geometry.PlaneAxes;
import net.minecraft.world.phys.Vec3;

public final class ConePrismGeometry {
   private static final double EPSILON = 1.0E-7;

   private final Base base;
   private final boolean heightReady;
   private final int height;
   private final int direction;
   private final int layers;
   private final double topScale;
   private final double scaleX;
   private final double scaleZ;
   private final Vec3 topOffset;

   private ConePrismGeometry(
      Base base,
      boolean heightReady,
      int height,
      int direction,
      int layers,
      double topScale,
      double scaleX,
      double scaleZ,
      Vec3 topOffset
   ) {
      this.base = base;
      this.heightReady = heightReady;
      this.height = height;
      this.direction = direction;
      this.layers = layers;
      this.topScale = topScale;
      this.scaleX = scaleX;
      this.scaleZ = scaleZ;
      this.topOffset = topOffset;
   }

   public static ConePrismGeometry from(ConePrismParameters parameters) {
      Base resolvedBase = resolveBase(parameters);
      if (resolvedBase == null) {
         return null;
      }
      Base base = rotateBase(resolvedBase, parameters.rotationRadians());
      double scaleX = Math.clamp(parameters.scaleX(), 0.125, 8.0);
      double scaleZ = Math.clamp(parameters.scaleZ(), 0.125, 8.0);
      double topScale = resolveTopScale(parameters.shapeVariant(), parameters.topScaleOffset());
      Vec3 topOffset = parameters.topOffset();
      if (parameters.heightPoint().isEmpty()) {
         return new ConePrismGeometry(base, false, 0, 1, 1, topScale, scaleX, scaleZ, topOffset);
      }
      double signedHeight = parameters.heightPoint().orElseThrow().subtract(base.center()).dot(base.normal());
      int height = (int)Math.round(signedHeight);
      if (height == 0) {
         height = signedHeight >= 0.0 ? 1 : -1;
      }
      return new ConePrismGeometry(
         base,
         true,
         height,
         Integer.signum(height),
         Math.abs(height) + 1,
         topScale,
         scaleX,
         scaleZ,
         topOffset
      );
   }

   public Base base() {
      return this.base;
   }

   public boolean heightReady() {
      return this.heightReady;
   }

   public int height() {
      return this.height;
   }

   public int layers() {
      return this.layers;
   }

   public double topScale() {
      return this.topScale;
   }

   public double scaleX() {
      return this.scaleX;
   }

   public double scaleZ() {
      return this.scaleZ;
   }

   public Vec3 topOffset() {
      return this.topOffset;
   }

   public Layer layer(int index) {
      double ratio = this.layers <= 1 ? 0.0 : (double)index / (double)(this.layers - 1);
      double radius = this.base.radius() * (1.0 + (this.topScale - 1.0) * ratio);
      Vec3 center = this.base.center()
         .add(this.base.axisU().scale(this.topOffset.x * ratio))
         .add(this.base.axisV().scale(this.topOffset.z * ratio))
         .add(this.base.normal().scale(index * this.direction));
      return new Layer(index, ratio, center, radius, index == 0 || index == this.layers - 1);
   }

   public Vec3 topCenter() {
      return this.base.center()
         .add(this.base.axisU().scale(this.topOffset.x))
         .add(this.base.axisV().scale(this.topOffset.z))
         .add(this.base.normal().scale(this.height));
   }

   public boolean contains(Vec3 point) {
      return this.contains(point.x, point.y, point.z);
   }

   public boolean contains(double x, double y, double z) {
      if (!this.heightReady || this.height == 0) {
         return false;
      }
      double fromBaseX = x - this.base.center().x;
      double fromBaseY = y - this.base.center().y;
      double fromBaseZ = z - this.base.center().z;
      double ratio = (
         fromBaseX * this.base.normal().x
            + fromBaseY * this.base.normal().y
            + fromBaseZ * this.base.normal().z
      ) / this.height;
      if (ratio < -EPSILON || ratio > 1.0 + EPSILON) {
         return false;
      }
      double centerX = this.base.center().x
         + this.base.normal().x * this.height * ratio
         + this.base.axisU().x * this.topOffset.x * ratio
         + this.base.axisV().x * this.topOffset.z * ratio;
      double centerY = this.base.center().y
         + this.base.normal().y * this.height * ratio
         + this.base.axisU().y * this.topOffset.x * ratio
         + this.base.axisV().y * this.topOffset.z * ratio;
      double centerZ = this.base.center().z
         + this.base.normal().z * this.height * ratio
         + this.base.axisU().z * this.topOffset.x * ratio
         + this.base.axisV().z * this.topOffset.z * ratio;
      double localX = x - centerX;
      double localY = y - centerY;
      double localZ = z - centerZ;
      double localU = (
         localX * this.base.axisU().x + localY * this.base.axisU().y + localZ * this.base.axisU().z
      ) / this.scaleX;
      double localV = (
         localX * this.base.axisV().x + localY * this.base.axisV().y + localZ * this.base.axisV().z
      ) / this.scaleZ;
      double radius = this.sectionRadius(ratio) + 0.25;
      return localU * localU + localV * localV <= radius * radius + EPSILON;
   }

   public boolean isBoundary(double x, double y, double z) {
      return !this.contains(x + 1.0, y, z)
         || !this.contains(x - 1.0, y, z)
         || !this.contains(x, y + 1.0, z)
         || !this.contains(x, y - 1.0, z)
         || !this.contains(x, y, z + 1.0)
         || !this.contains(x, y, z - 1.0);
   }

   public VoxelBounds voxelBounds() {
      Vec3 bottom = this.base.center();
      Vec3 top = this.heightReady ? this.topCenter() : bottom;
      double bottomRadius = this.base.radius() + 0.25;
      double topRadius = (this.heightReady ? this.base.radius() * this.topScale : this.base.radius()) + 0.25;
      int[] minimum = new int[3];
      int[] maximum = new int[3];
      for (int axis = 0; axis < 3; axis++) {
         double bottomExtent = this.worldExtent(bottomRadius, axis);
         double topExtent = this.worldExtent(topRadius, axis);
         double minimumCoordinate = Math.min(component(bottom, axis) - bottomExtent, component(top, axis) - topExtent);
         double maximumCoordinate = Math.max(component(bottom, axis) + bottomExtent, component(top, axis) + topExtent);
         minimum[axis] = (int)Math.ceil(minimumCoordinate - 0.5 - EPSILON);
         maximum[axis] = (int)Math.floor(maximumCoordinate - 0.5 + EPSILON);
      }
      return new VoxelBounds(minimum[0], minimum[1], minimum[2], maximum[0], maximum[1], maximum[2]);
   }

   private double sectionRadius(double ratio) {
      return Math.max(0.0, this.base.radius() * (1.0 + (this.topScale - 1.0) * ratio));
   }

   private double worldExtent(double radius, int axis) {
      return Math.abs(component(this.base.axisU(), axis)) * radius * this.scaleX
         + Math.abs(component(this.base.axisV(), axis)) * radius * this.scaleZ;
   }

   private static double component(Vec3 vector, int axis) {
      return switch (axis) {
         case 0 -> vector.x;
         case 1 -> vector.y;
         default -> vector.z;
      };
   }

   private static Base resolveBase(ConePrismParameters parameters) {
      if (parameters.facePoints().size() < parameters.planeMode().facePointCount()) {
         return null;
      }
      return switch (parameters.planeMode()) {
         case RADIUS -> createBase(
            parameters.facePoints().getFirst(),
            Math.max(0.5, parameters.radius()),
            new Vec3(0.0, 1.0, 0.0)
         );
         case DIAMETER -> {
            Vec3 first = parameters.facePoints().get(0);
            Vec3 second = parameters.facePoints().get(1);
            double radius = Math.max(0.5, Math.round(horizontalRadius(second.subtract(first))) * 0.5);
            yield createBase(first.add(second).scale(0.5), radius, new Vec3(0.0, 1.0, 0.0));
         }
         case THREE_POINT -> threePointCircle(
            parameters.facePoints().get(0),
            parameters.facePoints().get(1),
            parameters.facePoints().get(2)
         );
      };
   }

   private static Base threePointCircle(Vec3 first, Vec3 second, Vec3 third) {
      Vec3 firstSecond = second.subtract(first);
      Vec3 firstThird = third.subtract(first);
      Vec3 fullNormal = firstSecond.cross(firstThird);
      Vec3 normal = fullNormal.lengthSqr() < EPSILON ? fallbackNormal(firstSecond) : fullNormal.normalize();
      double normalLengthSqr = fullNormal.lengthSqr();
      if (normalLengthSqr < EPSILON) {
         return createBase(first, Math.max(1.0, firstSecond.length()), normal);
      }
      Vec3 centerOffset = fullNormal.cross(firstSecond).scale(firstThird.lengthSqr())
         .add(firstThird.cross(fullNormal).scale(firstSecond.lengthSqr()))
         .scale(1.0 / (2.0 * normalLengthSqr));
      Vec3 center = first.add(centerOffset);
      return createBase(center, Math.max(1.0, first.subtract(center).length()), normal);
   }

   private static Base createBase(Vec3 center, double radius, Vec3 normal) {
      Vec3 safeNormal = normal.lengthSqr() < EPSILON ? new Vec3(0.0, 1.0, 0.0) : normal.normalize();
      PlaneAxes axes = PlaneAxes.fromNormal(safeNormal);
      return new Base(center, radius, safeNormal, axes.horizontal(), axes.vertical());
   }

   private static Base rotateBase(Base base, double angle) {
      if (Math.abs(angle) < EPSILON) {
         return base;
      }
      double cosine = Math.cos(angle);
      double sine = Math.sin(angle);
      Vec3 axisU = base.axisU().scale(cosine).add(base.axisV().scale(sine)).normalize();
      Vec3 axisV = base.axisV().scale(cosine).add(base.axisU().scale(-sine)).normalize();
      return new Base(base.center(), base.radius(), base.normal(), axisU, axisV);
   }

   private static Vec3 fallbackNormal(Vec3 vector) {
      Vec3 safe = vector.lengthSqr() < EPSILON ? new Vec3(1.0, 0.0, 0.0) : vector.normalize();
      Vec3 normal = safe.cross(new Vec3(0.0, 1.0, 0.0));
      if (normal.lengthSqr() < EPSILON) {
         normal = safe.cross(new Vec3(0.0, 0.0, 1.0));
      }
      return normal.lengthSqr() < EPSILON ? new Vec3(0.0, 1.0, 0.0) : normal.normalize();
   }

   private static double horizontalRadius(Vec3 vector) {
      return Math.hypot(vector.x, vector.z);
   }

   private static double resolveTopScale(int shapeVariant, double offset) {
      double baseScale = switch (Math.floorMod(shapeVariant, 3)) {
         case 0 -> 1.0;
         case 1 -> 0.0;
         default -> 0.5;
      };
      return Math.clamp(baseScale + offset, 0.0, 8.0);
   }

   public record Base(Vec3 center, double radius, Vec3 normal, Vec3 axisU, Vec3 axisV) {
   }

   public record Layer(int index, double ratio, Vec3 center, double radius, boolean endLayer) {
   }

   public record VoxelBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
      public long volume() {
         long width = Math.max(0L, (long)this.maxX - this.minX + 1L);
         long height = Math.max(0L, (long)this.maxY - this.minY + 1L);
         long depth = Math.max(0L, (long)this.maxZ - this.minZ + 1L);
         return GenerationMath.saturatedMultiply(GenerationMath.saturatedMultiply(width, height), depth);
      }
   }
}
