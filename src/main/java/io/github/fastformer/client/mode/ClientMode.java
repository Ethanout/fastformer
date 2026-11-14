package io.github.fastformer.client.mode;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.common.mode.ModeId;
import io.github.fastformer.client.mode.input.pointer.PointerDecision;
import io.github.fastformer.client.mode.input.pointer.PointerInput;
import io.github.fastformer.client.mode.input.pointer.PointerView;
import java.util.Map;
import java.util.Optional;

/** A registered workflow owns its observation priority and phase input policies. */
public interface ClientMode {
   ModeId id();
   int priority();
   Map<State, ModeInputPolicy> phases();
   Optional<State> observe(ModeObservation observation);

   default PointerDecision onPointer(PointerInput input, PointerView view) { return PointerDecision.Flow.PASS; }

   default boolean previewActive(PreviewObservation observation) { return false; }
   default int previewPriority(PreviewObservation observation) { return priority(); }

   /** Tasks are not editable interaction sessions, even when they own input. */
   default boolean interactive() { return true; }
}
