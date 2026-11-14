package io.github.fastformer.client.mode;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch;
import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.input.ScrollDecision;
import io.github.fastformer.client.mode.input.ScrollInput;
import io.github.fastformer.client.mode.input.ScrollView;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ClientModeScrollTest {
   @Test void allPhasesAndContextCombinationsPreserveWheelOwnership() {
      var registry = ClientModes.registry();
      for (State state : State.values()) for (int flags = 0; flags < 16; flags++) {
         var view = new ScrollView(bit(flags, 0), bit(flags, 1), bit(flags, 2), bit(flags, 3));
         for (double delta : new double[] {-2.5, -0.25, 0.0, 0.25, 2.5}) {
            var actual = registry.onScroll(state, new ScrollInput(delta), view);
            var route = registry.policy(state).dispatch(io.github.fastformer.client.input.state.ClientInputStateMachine.InputKind.SCROLL);
            assertEquals(previousDecision(route, delta, view), actual, state + " flags=" + flags + " delta=" + delta);
         }
      }
   }

   @Test void onlyPassFallsBackToIdleAndPreservesTheCapturedFacts() {
      var idleCalls = new AtomicInteger();
      var input = new ScrollInput(1);
      var view = new ScrollView(true, true, false, false);
      var registry = registry((in, facts) -> {
         assertSame(input, in);
         assertSame(view, facts);
         idleCalls.incrementAndGet();
         return ScrollDecision.yieldToVanilla();
      }, (in, facts) -> in.delta() > 0 ? ScrollDecision.pass() : ScrollDecision.block());
      assertEquals(ScrollDecision.yieldToVanilla(), registry.onScroll(State.BUILDING, input, view));
      assertEquals(1, idleCalls.get());
      assertEquals(ScrollDecision.block(), registry.onScroll(State.BUILDING, new ScrollInput(-1), view));
      assertEquals(1, idleCalls.get());
      registry.onScroll(State.IDLE, input, view);
      assertEquals(2, idleCalls.get(), "idle must not fall back to itself");
   }

   @Test void invalidCommandsAndHandlerFailuresAreNotSilentlyTreatedAsPass() {
      assertThrows(IllegalArgumentException.class, () -> ScrollDecision.workspace(0));
      assertThrows(IllegalArgumentException.class, () -> ScrollDecision.candidate(2));
      assertThrows(IllegalArgumentException.class, () -> new ScrollDecision(ScrollDecision.Command.BLOCK, 1));
      var facts = new ScrollView(false, false, false, false);
      var registry = registry((in, view) -> ScrollDecision.pass(), (in, view) -> null);
      assertThrows(NullPointerException.class, () -> registry.onScroll(State.BUILDING, new ScrollInput(1), facts));
      var broken = registry((in, view) -> ScrollDecision.pass(), (in, view) -> { throw new IllegalStateException("failed"); });
      assertThrows(IllegalStateException.class, () -> broken.onScroll(State.BUILDING, new ScrollInput(1), facts));
   }

   private static ScrollDecision previousDecision(Dispatch route, double delta, ScrollView view) {
      if (route == Dispatch.BLOCKED) return ScrollDecision.block();
      if (delta == 0) return ScrollDecision.pass();
      int direction = delta > 0 ? 1 : -1;
      if (route == Dispatch.OPERATION && view.workspaceActive()) return ScrollDecision.workspace(direction);
      if (route == Dispatch.VANILLA || !view.usesScrollContext()) return ScrollDecision.pass();
      if (view.modifierYieldsToVanilla()) return ScrollDecision.yieldToVanilla();
      if (view.vanillaOwnsPointer()) return ScrollDecision.pass();
      return ScrollDecision.candidate(direction);
   }

   private static boolean bit(int flags, int offset) { return (flags & (1 << offset)) != 0; }
   private interface Handler { ScrollDecision apply(ScrollInput input, ScrollView view); }
   private static ClientModeRegistry registry(Handler idle, Handler active) {
      var policy = ClientModes.registry().policy(State.IDLE);
      var remaining = new EnumMap<State, ModeInputPolicy>(State.class);
      for (State state : State.values()) if (state != State.IDLE) remaining.put(state, policy);
      var registry = new ClientModeRegistry();
      registry.register(mode("idle", Map.of(State.IDLE, policy), idle));
      registry.register(mode("active", remaining, active));
      return registry.freeze();
   }
   private static ClientMode mode(String id, Map<State, ModeInputPolicy> phases, Handler handler) {
      return new ClientMode() {
         public String id() { return id; }
         public int priority() { return 0; }
         public Map<State, ModeInputPolicy> phases() { return phases; }
         public Optional<State> observe(ModeObservation observation) { return Optional.empty(); }
         public ScrollDecision onScroll(ScrollInput input, ScrollView view) { return handler.apply(input, view); }
      };
   }
}
