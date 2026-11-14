package io.github.fastformer.server.settings;

import io.github.fastformer.core.command.Intent;
import io.github.fastformer.common.geometry.model.FillMode;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Local player settings not currently exposed by the settings wire protocol. */
public final class PlayerSettingsIntents {
   private PlayerSettingsIntents() {}

   public record Fill(Optional<FillMode> value) implements Intent {
      public Fill { Objects.requireNonNull(value, "value"); }
   }
   public record MaxPlacement(OptionalInt value) implements Intent {
      public MaxPlacement { Objects.requireNonNull(value, "value"); }
   }
   public record ForcePlacement(boolean enabled) implements Intent {}
   public record Toggle() implements Intent {}
   public record Status() implements Intent {}
   public record OpenScreen() implements Intent {}
}
