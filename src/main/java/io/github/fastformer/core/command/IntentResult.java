package io.github.fastformer.core.command;

import java.util.Objects;

/** Distinguishes an accepted action, an unhandled action, and an admission refusal. */
public record IntentResult(Status status, String reason) {
   public enum Status { HANDLED, PASS, DENIED }
   private static final IntentResult HANDLED = new IntentResult(Status.HANDLED, "");
   private static final IntentResult PASS = new IntentResult(Status.PASS, "");

   public IntentResult {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(reason, "reason");
      if ((status == Status.DENIED) == reason.isEmpty()) {
         throw new IllegalArgumentException("Only a denied intent carries a reason");
      }
   }

   public boolean handled() { return status == Status.HANDLED; }
   public static IntentResult success() { return HANDLED; }
   public static IntentResult pass() { return PASS; }
   public static IntentResult handled(boolean handled) { return handled ? HANDLED : PASS; }
   public static IntentResult denied(String reason) { return new IntentResult(Status.DENIED, reason); }
}
