package io.github.fastformer.core.command;

import io.github.fastformer.core.gate.Gate;
import io.github.fastformer.core.registry.Registry;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Frozen, typed command registration; admission runs on every dispatch before the handler. */
public final class IntentBus<C> {
   private final Registry<Class<? extends Intent>, Binding<C, ?>> bindings = Registry.create("intents");

   private IntentBus() {}

   public static <C> IntentBus<C> create(Consumer<IntentBus<C>> assembly) {
      var bus = new IntentBus<C>();
      Objects.requireNonNull(assembly, "assembly").accept(bus);
      bus.bindings.freeze();
      return bus;
   }

   public <I extends Intent> void register(Class<I> type, Gate<IntentContext<C>> gate,
      BiFunction<IntentContext<C>, I, IntentResult> handler) {
      bindings.register(type, new Binding<>(type, Objects.requireNonNull(gate, "gate"),
         Objects.requireNonNull(handler, "handler")));
   }

   public IntentResult dispatch(IntentContext<C> context, Intent intent) {
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(intent, "intent");
      return bindings.get(intent.getClass()).map(binding -> binding.dispatch(context, intent)).orElseGet(IntentResult::pass);
   }

   private record Binding<C, I extends Intent>(Class<I> type, Gate<IntentContext<C>> gate,
      BiFunction<IntentContext<C>, I, IntentResult> handler) {
      IntentResult dispatch(IntentContext<C> context, Intent intent) {
         var admission = gate.test(context);
         return admission.allowed()
            ? Objects.requireNonNull(handler.apply(context, type.cast(intent)), "intent result")
            : IntentResult.denied(admission.reason());
      }
   }
}
