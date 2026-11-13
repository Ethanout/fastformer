package io.github.fastformer.server.settings;

import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.core.registry.Registry;
import io.github.fastformer.protocol.settings.SettingKey;
import io.github.fastformer.protocol.settings.SettingRequest;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Keeps each setting's value type paired with its runtime handler. */
final class SettingBindings<C> {
   private final Registry<SettingKey<?>, Binding<C, ?>> bindings = Registry.create("setting handlers");

   private SettingBindings() {}

   static <C> SettingBindings<C> create(Consumer<SettingBindings<C>> assembly) {
      var result = new SettingBindings<C>();
      assembly.accept(result);
      result.bindings.freeze();
      return result;
   }

   <T> void register(SettingKey<T> key, BiFunction<C, Optional<T>, IntentResult> handler) {
      bindings.register(key, new Binding<>(key, Objects.requireNonNull(handler, "handler")));
   }

   IntentResult apply(C context, SettingRequest<?> request) {
      return bindings.get(request.key()).map(binding -> binding.apply(context, request.value())).orElseGet(IntentResult::pass);
   }

   private record Binding<C, T>(SettingKey<T> key, BiFunction<C, Optional<T>, IntentResult> handler) {
      IntentResult apply(C context, Optional<?> value) {
         return Objects.requireNonNull(handler.apply(context, value.map(key::cast)), "setting result");
      }
   }
}
