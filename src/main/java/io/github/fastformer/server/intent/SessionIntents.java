package io.github.fastformer.server.intent;

import io.github.fastformer.core.command.Intent;
import net.minecraft.core.BlockPos;

/** Shared session actions requested by keys, packets, commands, or internal workflows. */
public final class SessionIntents {
   private SessionIntents() {}
   public record CycleMode(BlockPos lineCandidate) implements Intent {
      public CycleMode { if (lineCandidate != null) lineCandidate = lineCandidate.immutable(); }
   }
   public record Adjust(int steps) implements Intent {}
   public record Confirm() implements Intent {}
   public record Submode(boolean held) implements Intent {}
}
