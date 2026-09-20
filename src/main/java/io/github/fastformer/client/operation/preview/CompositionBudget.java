package io.github.fastformer.client.operation.preview;

/**
 * Two independent limits for one composition.
 *
 * <p>{@link #maxOutputCells} bounds unique cells in every intermediate map and in the result.
 * {@link #maxWorkSteps} bounds visits and insert attempts across scale, repeat, rotate and
 * translate. A scan that is too large is a work refusal, not an output refusal.
 *
 * <p>Work uses factor {@link #WORK_FACTOR} of the output cap so a legal plan at that cap
 * still fits: a no-rotation plan spends 2N or 3N, a three-axis rotate spends at most 35N.
 */
public record CompositionBudget(long maxOutputCells, long maxWorkSteps) {
   /** Visits per legal output cell that a three-axis rotate at the cap can spend. */
   public static final int WORK_FACTOR = 36;

   public static final CompositionBudget RENDER = ofOutput(WorkspacePreviewComposer.CLIENT_RENDER_BLOCK_LIMIT);
   public static final CompositionBudget INTERACTION = ofOutput(WorkspacePreviewComposer.CLIENT_INTERACTION_BLOCK_LIMIT);

   public CompositionBudget {
      if (maxOutputCells < 1L || maxWorkSteps < 1L) {
         throw new IllegalArgumentException("composition budget must be positive");
      }
   }

   public static CompositionBudget ofOutput(long maxOutputCells) {
      return new CompositionBudget(maxOutputCells, saturatingMultiply(maxOutputCells, WORK_FACTOR));
   }

   public static CompositionBudget server(int maxPlacement) {
      return ofOutput(Math.max(1L, maxPlacement));
   }

   private static long saturatingMultiply(long left, long right) {
      if (left <= 0L || right <= 0L) {
         return 0L;
      }
      return right > Long.MAX_VALUE / left ? Long.MAX_VALUE : left * right;
   }
}
