package io.github.fastformer.fastplace.geometry;

import java.util.List;

/**
 * Pure budget gate shared by the render budget and the submission validator.
 *
 * <p>An unscaled sparse part spends its occupied voxel count. A part that really scales spends
 * its sampler scan volume, because nearest-neighbour scaling duplicates voxels. Repetition
 * multiplies the spend. The rule is all-or-nothing: one part above the cap rejects the plan.
 *
 * <p>This class holds the whole cap rule so that both callers and their tests use one
 * implementation. It is side-effect free and never touches a world.
 */
public final class WorkspaceGeometryBudget {
   private WorkspaceGeometryBudget() {
   }

   /**
    * Result of an assessment of a whole plan.
    *
    * @param fits whether every part stays inside the cap
    * @param plannedUpperBound accumulated spend, or 0 when {@code fits} is false
    */
   public record Assessment(boolean fits, long plannedUpperBound) {
   }

   /** Returns whether one part stays inside the cap. */
   public static boolean fits(long maxBlocks, WorkspaceGeometryCost.Cost cost) {
      return cost != null && cost.canProduceVoxels() && cost.projectedUpperBound() <= maxBlocks;
   }

   /** Assesses every cost of a plan in order. */
   public static Assessment assess(long maxBlocks, List<WorkspaceGeometryCost.Cost> costs) {
      if (maxBlocks < 1L || costs == null) {
         return new Assessment(false, 0L);
      }
      long planned = 0L;
      for (WorkspaceGeometryCost.Cost cost : costs) {
         if (cost == null || !cost.canProduceVoxels()) {
            return new Assessment(false, 0L);
         }
         planned = saturatingAdd(planned, cost.projectedUpperBound());
         if (planned > maxBlocks) {
            return new Assessment(false, 0L);
         }
      }
      return new Assessment(true, planned);
   }

   private static long saturatingAdd(long left, long right) {
      return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
   }
}
