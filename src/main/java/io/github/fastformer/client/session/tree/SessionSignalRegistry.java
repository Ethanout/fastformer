package io.github.fastformer.client.session.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Registers and dispatches semantic signals for one state node. */
public final class SessionSignalRegistry {
   private final Map<String, List<Consumer<SessionSignal>>> handlers = new HashMap<>();

   public Subscription register(String signalName, Consumer<SessionSignal> handler) {
      Objects.requireNonNull(handler, "handler");
      if (signalName == null || signalName.isBlank()) {
         throw new IllegalArgumentException("Signal name must not be blank");
      }
      List<Consumer<SessionSignal>> signalHandlers = handlers.computeIfAbsent(signalName, ignored -> new ArrayList<>());
      signalHandlers.add(handler);
      return () -> unregister(signalName, handler);
   }

   public int emit(SessionSignal signal) {
      Objects.requireNonNull(signal, "signal");
      List<Consumer<SessionSignal>> signalHandlers = handlers.get(signal.name());
      if (signalHandlers == null || signalHandlers.isEmpty()) {
         return 0;
      }
      for (Consumer<SessionSignal> handler : List.copyOf(signalHandlers)) {
         handler.accept(signal);
      }
      return signalHandlers.size();
   }

   public void clear() {
      handlers.clear();
   }

   private void unregister(String signalName, Consumer<SessionSignal> handler) {
      List<Consumer<SessionSignal>> signalHandlers = handlers.get(signalName);
      if (signalHandlers == null) {
         return;
      }
      signalHandlers.remove(handler);
      if (signalHandlers.isEmpty()) {
         handlers.remove(signalName);
      }
   }

   @FunctionalInterface
   public interface Subscription {
      void close();
   }
}
