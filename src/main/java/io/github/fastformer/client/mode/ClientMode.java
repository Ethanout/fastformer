package io.github.fastformer.client.mode;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.input.ScrollDecision;
import io.github.fastformer.client.mode.input.ScrollInput;
import io.github.fastformer.client.mode.input.ScrollView;
import io.github.fastformer.client.mode.input.pointer.PointerDecision;
import io.github.fastformer.client.mode.input.pointer.PointerInput;
import io.github.fastformer.client.mode.input.pointer.PointerView;
import java.util.Map;
import java.util.Optional;

/** A registered workflow owns its observation priority and phase input policies. */
public interface ClientMode {
   String id();
   int priority();
   Map<State, ModeInputPolicy> phases();
   Optional<State> observe(ModeObservation observation);

   default ScrollDecision onScroll(ScrollInput input, ScrollView view) { return ScrollDecision.pass(); }
   default PointerDecision onPointer(PointerInput input, PointerView view) { return PointerDecision.Flow.PASS; }

   /** Tasks are not editable interaction sessions, even when they own input. */
   default boolean interactive() { return true; }
}
