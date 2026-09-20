package io.github.fastformer.client.operation.preview;

/**
 * Running output and work counters for one composition. Sort comparisons are not work.
 *
 * <p>The composer and {@code VoxelRotation} share the counter. A negative work step
 * is a refusal, never a no-op.
 */
public final class CompositionCounter {
   private final CompositionBudget budget;
   private long work;
   private long retainedOutput;

   CompositionCounter(CompositionBudget budget) {
      this.budget = budget;
   }

   CompositionBudget budget() {
      return this.budget;
   }

   long work() {
      return this.work;
   }

   public <T> Composition.OverBudget<T> addWork(long steps) {
      if (steps < 0L) {
         return new Composition.OverBudget<>(Composition.Limit.WORK, this.budget.maxWorkSteps(), Long.MAX_VALUE);
      }
      if (steps == 0L) {
         return null;
      }
      if (steps > this.budget.maxWorkSteps() - this.work) {
         long reached = this.work > Long.MAX_VALUE - steps ? Long.MAX_VALUE : this.work + steps;
         return new Composition.OverBudget<>(Composition.Limit.WORK, this.budget.maxWorkSteps(), reached);
      }
      this.work += steps;
      return null;
   }

   public <T> Composition.OverBudget<T> checkOutput(long uniqueCells) {
      if (uniqueCells < 0L || uniqueCells > this.budget.maxOutputCells() - this.retainedOutput) {
         long reached = uniqueCells < 0L || uniqueCells > Long.MAX_VALUE - this.retainedOutput
            ? Long.MAX_VALUE : this.retainedOutput + uniqueCells;
         return new Composition.OverBudget<>(
            Composition.Limit.OUTPUT, this.budget.maxOutputCells(), reached
         );
      }
      return null;
   }

   void retainOutput(int cells) {
      this.retainedOutput += cells;
   }
}
