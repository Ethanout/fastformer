package io.github.fastformer.fastplace.geometry.raycast;

import io.github.fastformer.fastplace.world.*;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A block ray with no gameplay range cap. It stops at dimension/world bounds or
 * before the first unloaded chunk, so querying a distant empty sky never loads
 * chunks or walks millions of block cells.
 */
public final class LongRangeBlockRaycast {
   public static final double MAX_REACH = 29_999_984.0;
   private static final double EPSILON = 1.0E-7;
   private static final double END_INSET = 1.0E-4;
   private static final double MAX_COORDINATE = MAX_REACH;
   private static final int MAX_LOADED_CHUNK_STEPS = 8192;

   private LongRangeBlockRaycast() {
   }

   public static Result clip(Level level, Entity source, Vec3 start, Vec3 direction) {
      return clip(level, source, start, direction, ClipContext.Block.OUTLINE);
   }

   public static Result clipForPlacement(Level level, Entity source, Vec3 start, Vec3 direction) {
      return clip(level, source, start, direction, ClipContext.Block.OUTLINE, true, false, ClipContext.Fluid.NONE);
   }

   public static Result clipForSelection(Level level, Entity source, Vec3 start, Vec3 direction, boolean throughFluids) {
      return clip(level, source, start, direction, ClipContext.Block.OUTLINE, false, false,
         throughFluids ? ClipContext.Fluid.NONE : ClipContext.Fluid.ANY);
   }

   /** Full cells and near-ray tolerance only decide which input mode owns the pointer. */
   public static Result clipForReachTransition(Level level, Entity source, Vec3 start, Vec3 direction) {
      return clip(level, source, start, direction, ClipContext.Block.OUTLINE, false, true, ClipContext.Fluid.NONE);
   }

   public static Result clip(
      Level level, Entity source, Vec3 start, Vec3 direction, ClipContext.Block blockMode
   ) {
      return clip(level, source, start, direction, blockMode, false, false, ClipContext.Fluid.NONE);
   }

   private static Result clip(
      Level level, Entity source, Vec3 start, Vec3 direction, ClipContext.Block blockMode,
      boolean skipReplaceable, boolean reachTransition, ClipContext.Fluid fluidMode
   ) {
      long startedAt = System.nanoTime();
      Vec3 ray = direction.lengthSqr() < EPSILON ? Vec3.ZERO : direction.normalize();
      if (ray.lengthSqr() < EPSILON) {
         BlockHitResult hit = level.clip(context(start, start, blockMode, source, skipReplaceable, reachTransition, fluidMode));
         return new Result(hit, 0.0, 0.0, Limit.ZERO_DIRECTION, 0, System.nanoTime() - startedAt);
      }

      LimitDistance world = worldLimit(level, start, ray);
      LimitDistance dimension = dimensionLimit(level, start, ray);
      LimitDistance bounded = world.distance() <= dimension.distance() ? world : dimension;
      LoadedLimit loaded = loadedChunkLimit(level, start, ray, bounded.distance());
      double distance = Math.max(0.0, Math.min(bounded.distance(), loaded.distance()));
      Limit limit = loaded.distance() + EPSILON < bounded.distance() ? Limit.UNLOADED_CHUNK : bounded.limit();
      Vec3 end = start.add(ray.scale(distance));
      BlockHitResult hit = level.clip(context(start, end, blockMode, source, skipReplaceable, reachTransition, fluidMode));
      if (reachTransition && !hit.isInside()) {
         hit = NearBlockRaycast.assist(level, source, start, ray, hit, distance, skipReplaceable);
      }
      // Keep the actual shape hit. A partial block's cell entry can be on a different face.
      return new Result(hit, distance, world.distance(), limit, loaded.checkedChunks(), System.nanoTime() - startedAt);
   }

   private static ClipContext context(
      Vec3 start, Vec3 end, ClipContext.Block mode, Entity source, boolean skipReplaceable, boolean reachTransition,
      ClipContext.Fluid fluidMode
   ) {
      return new ClipContext(start, end, mode, fluidMode, source) {
         @Override
         public net.minecraft.world.phys.shapes.VoxelShape getBlockShape(
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.BlockGetter level, BlockPos pos
         ) {
            return skipReplaceable && state.canBeReplaced()
               ? net.minecraft.world.phys.shapes.Shapes.empty()
               : reachTransition && !state.isAir()
                  && !super.getBlockShape(state, level, pos).isEmpty()
                  ? net.minecraft.world.phys.shapes.Shapes.block() : super.getBlockShape(state, level, pos);
         }
      };
   }

   static LimitDistance dimensionLimit(Level level, Vec3 start, Vec3 direction) {
      return dimensionLimit(level.getMinBuildHeight(), level.getMaxBuildHeight(), start.y, direction.y);
   }

   static LimitDistance dimensionLimit(int minBuildHeight, int maxBuildHeight, double startY, double directionY) {
      if (directionY > EPSILON) {
         return new LimitDistance(nonNegative((maxBuildHeight - END_INSET - startY) / directionY), Limit.DIMENSION_TOP);
      }
      if (directionY < -EPSILON) {
         return new LimitDistance(nonNegative((minBuildHeight + END_INSET - startY) / directionY), Limit.DIMENSION_BOTTOM);
      }
      return new LimitDistance(Double.POSITIVE_INFINITY, Limit.WORLD_BOUNDARY);
   }

   private static LimitDistance worldLimit(Level level, Vec3 start, Vec3 direction) {
      double minX = Math.max(-MAX_COORDINATE, level.getWorldBorder().getMinX()) + END_INSET;
      double maxX = Math.min(MAX_COORDINATE, level.getWorldBorder().getMaxX()) - END_INSET;
      double minZ = Math.max(-MAX_COORDINATE, level.getWorldBorder().getMinZ()) + END_INSET;
      double maxZ = Math.min(MAX_COORDINATE, level.getWorldBorder().getMaxZ()) - END_INSET;
      double distance = Double.POSITIVE_INFINITY;
      if (direction.x > EPSILON) {
         distance = Math.min(distance, nonNegative((maxX - start.x) / direction.x));
      } else if (direction.x < -EPSILON) {
         distance = Math.min(distance, nonNegative((minX - start.x) / direction.x));
      }
      if (direction.z > EPSILON) {
         distance = Math.min(distance, nonNegative((maxZ - start.z) / direction.z));
      } else if (direction.z < -EPSILON) {
         distance = Math.min(distance, nonNegative((minZ - start.z) / direction.z));
      }
      return new LimitDistance(distance, Limit.WORLD_BOUNDARY);
   }

   private static LoadedLimit loadedChunkLimit(Level level, Vec3 start, Vec3 direction, double hardLimit) {
      int chunkX = BlockPos.containing(start).getX() >> 4;
      int chunkZ = BlockPos.containing(start).getZ() >> 4;
      if (!level.hasChunk(chunkX, chunkZ)) {
         return new LoadedLimit(0.0, 0);
      }
      int stepX = direction.x > EPSILON ? 1 : direction.x < -EPSILON ? -1 : 0;
      int stepZ = direction.z > EPSILON ? 1 : direction.z < -EPSILON ? -1 : 0;
      double nextX = stepX == 0
         ? Double.POSITIVE_INFINITY
         : (((stepX > 0 ? chunkX + 1 : chunkX) * 16.0) - start.x) / direction.x;
      double nextZ = stepZ == 0
         ? Double.POSITIVE_INFINITY
         : (((stepZ > 0 ? chunkZ + 1 : chunkZ) * 16.0) - start.z) / direction.z;
      double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(direction.x);
      double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(direction.z);
      int checked = 1;
      while (checked < MAX_LOADED_CHUNK_STEPS) {
         double boundary = Math.min(nextX, nextZ);
         if (!Double.isFinite(boundary) || boundary >= hardLimit) {
            return new LoadedLimit(hardLimit, checked);
         }
         if (nextX < nextZ) {
            chunkX += stepX;
            nextX += deltaX;
         } else if (nextZ < nextX) {
            chunkZ += stepZ;
            nextZ += deltaZ;
         } else {
            chunkX += stepX;
            chunkZ += stepZ;
            nextX += deltaX;
            nextZ += deltaZ;
         }
         checked++;
         if (!level.hasChunk(chunkX, chunkZ)) {
            return new LoadedLimit(Math.max(0.0, boundary - END_INSET), checked);
         }
      }
      return new LoadedLimit(Math.min(hardLimit, Math.min(nextX, nextZ)), checked);
   }

   private static double nonNegative(double value) {
      return Double.isFinite(value) ? Math.max(0.0, value) : Double.POSITIVE_INFINITY;
   }

   public enum Limit {
      DIMENSION_TOP,
      DIMENSION_BOTTOM,
      WORLD_BOUNDARY,
      UNLOADED_CHUNK,
      ZERO_DIRECTION
   }

   public record Result(BlockHitResult hit, double distance, double uncappedDistance, Limit limit, int checkedChunks, long elapsedNanos) {
   }

   record LimitDistance(double distance, Limit limit) {
   }

   private record LoadedLimit(double distance, int checkedChunks) {
   }
}
