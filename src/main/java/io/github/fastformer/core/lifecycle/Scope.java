package io.github.fastformer.core.lifecycle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Lazy typed state whose lifecycle is traversed once by its enclosing scope. Not thread-safe. */
public final class Scope implements AutoCloseable {
   private final Map<String, Entry<?>> entries = new LinkedHashMap<>();
   private final Set<String> constructing = new HashSet<>();
   private boolean closed;
   private boolean notifying;

   public <T extends Scoped> T get(ScopedKey<T> key) {
      requireOpen();
      Objects.requireNonNull(key, "key");
      if (notifying) throw new IllegalStateException("scope state cannot be created during lifecycle notification");
      Entry<?> entry = entries.get(key.id());
      if (entry == null) {
         if (!constructing.add(key.id())) throw new IllegalStateException("recursive scope factory: " + key.id());
         try {
            T state = key.create();
            entries.put(key.id(), new Entry<>(key, state));
            return state;
         } finally { constructing.remove(key.id()); }
      }
      return cast(key, entry);
   }

   public <T extends Scoped> Optional<T> find(ScopedKey<T> key) {
      Objects.requireNonNull(key, "key");
      Entry<?> entry = entries.get(key.id());
      return entry == null ? Optional.empty() : Optional.of(cast(key, entry));
   }

   public void detach() { notifyStates(Scoped::onDetach); }
   public void dimensionChanged() { notifyStates(Scoped::onDimensionChanged); }

   /** Marks the scope closed before callbacks, so a cleanup callback cannot resurrect it. */
   @Override public void close() {
      if (closed) return;
      requireNoFactories();
      if (notifying) throw new IllegalStateException("cannot close a scope from its lifecycle callback");
      closed = true;
      try { notifyStates(Scoped::onDiscard); }
      finally { entries.clear(); }
   }

   private void notifyStates(Consumer<Scoped> callback) {
      requireNoFactories();
      if (notifying) throw new IllegalStateException("reentrant scope lifecycle notification");
      notifying = true;
      RuntimeException failure = null;
      try {
         for (Entry<?> entry : new ArrayList<>(entries.values())) {
            try { callback.accept(entry.state()); }
            catch (RuntimeException next) {
               if (failure == null) failure = next;
               else if (failure != next) failure.addSuppressed(next);
            }
         }
      } finally { notifying = false; }
      if (failure != null) throw failure;
   }

   @SuppressWarnings("unchecked")
   private static <T extends Scoped> T cast(ScopedKey<T> key, Entry<?> entry) {
      if (entry.key() != key) throw new IllegalArgumentException("different scope key already registered as " + key.id());
      return (T) entry.state();
   }

   private void requireOpen() { if (closed) throw new IllegalStateException("scope is closed"); }
   private void requireNoFactories() {
      if (!constructing.isEmpty()) throw new IllegalStateException("cannot change scope lifecycle from a state factory");
   }
   private record Entry<T extends Scoped>(ScopedKey<T> key, T state) {}
}
