package io.github.fastformer.protocol.settings;

import io.github.fastformer.core.command.Intent;
import java.util.Objects;
import java.util.Optional;

/** An absent value is a query, never a default value or a toggle. */
public record SettingRequest<T>(SettingKey<T> key, Optional<T> value) implements Intent {
   public SettingRequest {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
      value.ifPresent(key::cast);
   }
}
