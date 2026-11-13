package io.github.fastformer.core.gate;

import java.util.Objects;

/** An allowed result has no reason; a refused result carries the first failed rule. */
public record GateResult(boolean allowed, String reason) {
   private static final GateResult ALLOWED = new GateResult(true, "");

   public GateResult {
      Objects.requireNonNull(reason, "reason");
      if (allowed != reason.isEmpty()) throw new IllegalArgumentException("Only denied results carry a reason");
   }

   public static GateResult allow() { return ALLOWED; }
   public static GateResult deny(String reason) { return new GateResult(false, reason); }
}
