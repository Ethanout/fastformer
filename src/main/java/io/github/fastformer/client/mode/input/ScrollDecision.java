package io.github.fastformer.client.mode.input;

import java.util.Objects;

/** A mode command; physical capture and queued execution remain at the input boundary. */
public record ScrollDecision(Command command, int direction) {
   public ScrollDecision {
      Objects.requireNonNull(command, "command");
      boolean directional = command == Command.WORKSPACE_TRANSFORM || command == Command.CANDIDATE_SCROLL;
      if (directional ? direction != -1 && direction != 1 : direction != 0) {
         throw new IllegalArgumentException("Invalid wheel direction for " + command + ": " + direction);
      }
   }

   public static ScrollDecision pass() { return new ScrollDecision(Command.NONE, 0); }
   public static ScrollDecision block() { return new ScrollDecision(Command.BLOCK, 0); }
   public static ScrollDecision workspace(int direction) { return new ScrollDecision(Command.WORKSPACE_TRANSFORM, direction); }
   public static ScrollDecision candidate(int direction) { return new ScrollDecision(Command.CANDIDATE_SCROLL, direction); }
   public static ScrollDecision yieldToVanilla() { return new ScrollDecision(Command.YIELD_TO_VANILLA, 0); }

   public enum Command { NONE, BLOCK, WORKSPACE_TRANSFORM, CANDIDATE_SCROLL, YIELD_TO_VANILLA }
}
