package io.github.fastformer.client.mode;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.input.pointer.PointerDecision;
import io.github.fastformer.client.mode.input.pointer.PointerInput;
import io.github.fastformer.client.mode.input.pointer.PointerView;
import io.github.fastformer.core.registry.Registry;
import io.github.fastformer.common.mode.ModeId;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Frozen mode definitions; session state remains in the environment's input machine. */
public final class ClientModeRegistry {
   private final Registry<ModeId, Definition> modes = Registry.create("client modes");
   private final EnumMap<State, ModeInputPolicy> policies = new EnumMap<>(State.class);
   private final EnumMap<State, Definition> owners = new EnumMap<>(State.class);
   private List<Definition> ordered;

   public void register(ClientMode mode) {
      if (ordered != null) throw new IllegalStateException("Client modes are frozen");
      Objects.requireNonNull(mode, "mode");
      ModeId id = Objects.requireNonNull(mode.id(), "mode id");
      Map<State, ModeInputPolicy> phases = Map.copyOf(mode.phases());
      if (phases.isEmpty()) throw new IllegalArgumentException("Client mode has no phases: " + id);
      for (State state : phases.keySet()) {
         if (policies.containsKey(state)) throw new IllegalArgumentException("Client phase already owned: " + state);
      }
      var definition = new Definition(id, mode, mode.priority(), phases, mode.interactive());
      modes.register(id, definition);
      policies.putAll(phases);
      phases.keySet().forEach(state -> owners.put(state, definition));
   }

   public ClientModeRegistry freeze() {
      if (ordered != null) return this;
      for (State state : State.values()) {
         if (!policies.containsKey(state)) throw new IllegalStateException("Missing client phase owner: " + state);
      }
      modes.freeze();
      ordered = modes.ordered(Comparator.comparingInt(Definition::priority).reversed()).toList();
      return this;
   }

   public State observe(ModeObservation observation) {
      Objects.requireNonNull(observation, "observation");
      for (Definition mode : ordered()) {
         var state = mode.observe(observation);
         if (state.isPresent()) {
            if (!mode.phases().containsKey(state.get()))
               throw new IllegalStateException("Mode observed an unowned phase: " + mode.id() + " / " + state.get());
            return state.get();
         }
      }
      throw new IllegalStateException("No client mode accepted the observation");
   }

   public boolean hasInteractiveSession(ModeObservation observation) {
      Objects.requireNonNull(observation, "observation");
      return ordered().stream().anyMatch(mode -> mode.interactive() && mode.observe(observation).isPresent());
   }

   public ModeInputPolicy policy(State state) {
      ordered();
      return Objects.requireNonNull(policies.get(Objects.requireNonNull(state, "state")), "phase policy");
   }

   public ModeId id(State state) {
      ordered();
      return owners.get(Objects.requireNonNull(state, "state")).id();
   }

   /** Selects visual ownership independently of input routing, using the same registered modes. */
   public java.util.Optional<ModeId> preview(PreviewObservation observation) {
      Objects.requireNonNull(observation, "observation");
      return ordered().stream().filter(definition -> definition.mode().previewActive(observation))
         .max(Comparator.comparingInt(definition -> definition.mode().previewPriority(observation)))
         .map(Definition::id);
   }

   private List<Definition> ordered() {
      if (ordered == null) throw new IllegalStateException("Client modes are not frozen");
      return ordered;
   }

   public PointerDecision onPointer(State state, PointerInput input, PointerView view) {
      ordered();
      Objects.requireNonNull(input, "input");
      Objects.requireNonNull(view, "view");
      Definition owner = owners.get(Objects.requireNonNull(state, "state"));
      PointerDecision result = Objects.requireNonNull(owner.mode().onPointer(input, view), "pointer decision");
      if (result == PointerDecision.Flow.PASS && owner != owners.get(State.IDLE)) {
         result = Objects.requireNonNull(owners.get(State.IDLE).mode().onPointer(input, view), "idle pointer decision");
      }
      if (result instanceof PointerDecision.Left && !(view instanceof PointerView.Left)
         || result instanceof PointerDecision.Right && !(view instanceof PointerView.Right)) {
         throw new IllegalStateException("Mode returned a pointer decision for another button: " + owner.id());
      }
      return result;
   }

   private record Definition(ModeId id, ClientMode mode, int priority, Map<State, ModeInputPolicy> phases, boolean interactive) {
      java.util.Optional<State> observe(ModeObservation observation) {
         return Objects.requireNonNull(mode.observe(observation), "mode observation");
      }
   }
}
