package io.github.fastformer.core.lifecycle;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScopeTest {
   @Test void stateIsLazyAndOwnedByOneScope() {
      var key = ScopedKey.create("feature", State::new);
      var first = new Scope();
      var second = new Scope();
      assertTrue(first.find(key).isEmpty());
      assertSame(first.get(key), first.get(key));
      assertNotSame(first.get(key), second.get(key));
   }

   @Test void lifecycleInvokesAllFeaturesAndCloseIsFinal() {
      var calls = new ArrayList<String>();
      var scope = new Scope();
      var key = ScopedKey.create("feature", () -> new State(calls));
      scope.get(key);
      scope.detach();
      scope.dimensionChanged();
      scope.close();
      scope.close();
      assertEquals(List.of("detach", "dimension", "discard"), calls);
      assertTrue(scope.find(key).isEmpty());
      assertThrows(IllegalStateException.class, () -> scope.get(key));
   }

   @Test void failureDoesNotSkipOtherFeaturesOrLeaveEntriesBehind() {
      var scope = new Scope();
      var failure = new IllegalStateException("first");
      var secondFailure = new IllegalArgumentException("second");
      var calls = new ArrayList<String>();
      scope.get(ScopedKey.create("first", () -> (Scoped) () -> { throw failure; }));
      scope.get(ScopedKey.create("second", () -> (Scoped) () -> { throw secondFailure; }));
      var last = ScopedKey.create("last", () -> new State(calls));
      scope.get(last);
      assertSame(failure, assertThrows(IllegalStateException.class, scope::close));
      assertArrayEquals(new Throwable[] {secondFailure}, failure.getSuppressed());
      assertEquals(List.of("discard"), calls);
      assertTrue(scope.find(last).isEmpty());
      assertDoesNotThrow(scope::close);
   }

   @Test void duplicateIdentityAndReentrantCreationAreRejected() {
      var scope = new Scope();
      var key = ScopedKey.create("same", State::new);
      scope.get(key);
      assertThrows(IllegalArgumentException.class, () -> scope.get(ScopedKey.create("same", State::new)));
      scope.get(ScopedKey.create("reentrant", () -> (Scoped) () -> {
         assertThrows(IllegalStateException.class, () -> scope.get(key));
      }));
      scope.close();
   }

   @Test void recursiveFactoryFailsWithoutPublishingPartialStateAndCanRetry() {
      var scope = new Scope();
      var attempts = new java.util.concurrent.atomic.AtomicInteger();
      var reference = new java.util.concurrent.atomic.AtomicReference<ScopedKey<State>>();
      var key = ScopedKey.create("recursive", () -> {
         if (attempts.incrementAndGet() == 1) return scope.get(reference.get());
         return new State();
      });
      reference.set(key);
      assertThrows(IllegalStateException.class, () -> scope.get(key));
      assertTrue(scope.find(key).isEmpty());
      assertNotNull(scope.get(key));
   }

   @Test void factoryCannotCloseOrNotifyScopeWhileConstructingState() {
      var scope = new Scope();
      var key = ScopedKey.create("feature", () -> {
         assertThrows(IllegalStateException.class, scope::close);
         assertThrows(IllegalStateException.class, scope::detach);
         assertThrows(IllegalStateException.class, scope::dimensionChanged);
         return new State();
      });
      assertSame(scope.get(key), scope.get(key));
      scope.close();
      assertTrue(scope.find(key).isEmpty());
   }

   private static final class State implements Scoped {
      private final List<String> calls;
      private State() { this(new ArrayList<>()); }
      private State(List<String> calls) { this.calls = calls; }
      @Override public void onDetach() { calls.add("detach"); }
      @Override public void onDimensionChanged() { calls.add("dimension"); }
      @Override public void onDiscard() { calls.add("discard"); }
   }
}
