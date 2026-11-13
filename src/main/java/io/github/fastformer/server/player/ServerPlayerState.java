package io.github.fastformer.server.player;

import io.github.fastformer.core.lifecycle.Scope;
import io.github.fastformer.core.lifecycle.Scoped;
import io.github.fastformer.core.lifecycle.ScopedKey;
import java.util.Optional;

/** Transient state of one player UUID on one running server. */
public final class ServerPlayerState implements AutoCloseable {
   private final Scope scope = new Scope();

   public <T extends Scoped> T get(ScopedKey<T> key) { return scope.get(key); }
   public <T extends Scoped> Optional<T> find(ScopedKey<T> key) { return scope.find(key); }
   void dimensionChanged() { scope.dimensionChanged(); }
   void detach() {
      try { scope.detach(); }
      finally { scope.close(); }
   }
   @Override public void close() { scope.close(); }
}
