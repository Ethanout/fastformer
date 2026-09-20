package io.github.fastformer.client.input;

/** Maps a physical scroll direction to the session command that owns it. */
public final class ScrollInputSemantics {
   private ScrollInputSemantics() {
   }

   public static Decision decide(
      double scrollDelta,
      ClientInputStateMachine.Dispatch inputRoute,
      boolean workspaceActive,
      boolean usesScrollContext,
      boolean modifierYieldsToVanilla,
      boolean nearVanillaBlock
   ) {
      if (inputRoute == ClientInputStateMachine.Dispatch.BLOCKED) {
         return Decision.blocked();
      }
      if (scrollDelta == 0.0) {
         return Decision.ignored();
      }
      if (inputRoute == ClientInputStateMachine.Dispatch.OPERATION && workspaceActive) {
         return Decision.workspaceMove(direction(scrollDelta));
      }
      if (inputRoute == ClientInputStateMachine.Dispatch.VANILLA || !usesScrollContext) {
         return Decision.ignored();
      }
      if (modifierYieldsToVanilla) {
         return Decision.yieldToVanilla();
      }
      if (nearVanillaBlock) {
         return Decision.ignored();
      }
      return Decision.candidateScroll(direction(scrollDelta));
   }

   private static int direction(double scrollDelta) {
      return scrollDelta > 0.0 ? 1 : -1;
   }

   public enum Command {
      NONE,
      BLOCK,
      WORKSPACE_MOVE,
      CANDIDATE_SCROLL,
      YIELD_TO_VANILLA
   }

   public record Decision(Command command, int direction) {
      public static Decision ignored() {
         return new Decision(Command.NONE, 0);
      }

      public static Decision blocked() {
         return new Decision(Command.BLOCK, 0);
      }

      public static Decision workspaceMove(int direction) {
         return new Decision(Command.WORKSPACE_MOVE, direction);
      }

      public static Decision candidateScroll(int direction) {
         return new Decision(Command.CANDIDATE_SCROLL, direction);
      }

      public static Decision yieldToVanilla() {
         return new Decision(Command.YIELD_TO_VANILLA, 0);
      }
   }
}
