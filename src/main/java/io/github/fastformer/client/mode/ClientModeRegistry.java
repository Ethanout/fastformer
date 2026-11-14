package io.github.fastformer.client.mode;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.input.ScrollDecision;
import io.github.fastformer.client.mode.input.ScrollInput;
import io.github.fastformer.client.mode.input.ScrollView;
import io.github.fastformer.client.mode.input.KeyDecision;
import io.github.fastformer.client.mode.input.KeyInput;
import io.github.fastformer.client.mode.input.KeyView;
import io.github.fastformer.core.registry.Registry;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Frozen mode definitions; session state remains in the environment's input machine. */
public final class ClientModeRegistry {
   private final Registry<String, Definition> modes = Registry.create("client modes");
   private final EnumMap<State, ModeInputPolicy> policies = new EnumMap<>(State.class);
   private final EnumMap<State, Definition> owners = new EnumMap<>(State.class);
   private List<Definition> ordered;

   public void register(ClientMode mode) {
      if (ordered != null) throw new IllegalStateException("Client modes are frozen");
      Objects.requireNonNull(mode, "mode");
      String id = Objects.requireNonNull(mode.id(), "mode id");
      if (id.isBlank()) throw new IllegalArgumentException("Client mode id is blank");
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

   public ScrollDecision onScroll(State state, ScrollInput input, ScrollView view) {
      ordered();
      Definition owner = owners.get(Objects.requireNonNull(state, "state"));
      ScrollDecision result = Objects.requireNonNull(owner.mode().onScroll(
         Objects.requireNonNull(input, "input"), Objects.requireNonNull(view, "view")), "scroll decision");
      if (result.command() == ScrollDecision.Command.NONE && owner != owners.get(State.IDLE)) {
         return Objects.requireNonNull(owners.get(State.IDLE).mode().onScroll(input, view), "idle scroll decision");
      }
      return result;
   }

   public KeyDecision onKey(State state, KeyInput input, KeyView view) {
      ordered();
      Definition owner = owners.get(Objects.requireNonNull(state, "state"));
      KeyDecision result = Objects.requireNonNull(owner.mode().onKey(
         Objects.requireNonNull(input, "input"), Objects.requireNonNull(view, "view")), "key decision");
      if (result == KeyDecision.PASS && owner != owners.get(State.IDLE)) {
         return Objects.requireNonNull(owners.get(State.IDLE).mode().onKey(input, view), "idle key decision");
      }
      return result;
   }

   private List<Definition> ordered() {
      if (ordered == null) throw new IllegalStateException("Client modes are not frozen");
      return ordered;
   }

   private record Definition(String id, ClientMode mode, int priority, Map<State, ModeInputPolicy> phases, boolean interactive) {
      java.util.Optional<State> observe(ModeObservation observation) {
         return Objects.requireNonNull(mode.observe(observation), "mode observation");
      }
   }
}
