package io.github.fastformer.client.mode;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.input.ScrollDecision;
import io.github.fastformer.client.mode.input.ScrollInput;
import io.github.fastformer.client.mode.input.ScrollView;
import java.util.Map;
import java.util.Optional;

/** A registered workflow owns its observation priority and phase input policies. */
public interface ClientMode {
   String id();
   int priority();
   Map<State, ModeInputPolicy> phases();
   Optional<State> observe(ModeObservation observation);

   default ScrollDecision onScroll(ScrollInput input, ScrollView view) { return ScrollDecision.pass(); }

   /** Tasks are not editable interaction sessions, even when they own input. */
   default boolean interactive() { return true; }
}
