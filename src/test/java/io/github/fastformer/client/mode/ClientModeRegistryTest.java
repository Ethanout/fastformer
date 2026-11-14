package io.github.fastformer.client.mode;

import io.github.fastformer.common.mode.ModeId;
import io.github.fastformer.common.mode.BuiltinModes;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.input.state.ClientSemanticEvent;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientModeRegistryTest {
   @Test void everyPhaseKeepsItsStableTypedModeIdentity() {
      var expected = Map.of(
         State.IDLE, BuiltinModes.IDLE,
         State.BUILDING, BuiltinModes.QUICK_SHAPE,
         State.GEOMETRY, BuiltinModes.GEOMETRY,
         State.SELECTING, BuiltinModes.SELECTION,
         State.ADJUSTING, BuiltinModes.SELECTION,
         State.QUICK_REPLACE, BuiltinModes.QUICK_REPLACE,
         State.FILL_PREVIEW, BuiltinModes.SMART_FILL,
         State.RESTORING, BuiltinModes.BUSY,
         State.PLACING, BuiltinModes.BUSY);
      for (State phase : State.values()) {
         assertEquals(expected.getOrDefault(phase, BuiltinModes.BUSY), ClientModes.registry().id(phase), phase.name());
      }
      assertEquals("fastformer:idle", ClientModes.registry().id(State.IDLE).value().toString());
      assertEquals(BuiltinModes.QUICK_SHAPE, ModeId.of("quick_shape"));
   }

   @Test void allWorkflowCombinationsKeepTheExistingPriorityAndInteractiveOwnership() {
      for (int flags = 0; flags < 512; flags++) {
         var observation = new ModeObservation(bit(flags, 0), bit(flags, 1), bit(flags, 2), bit(flags, 3),
            bit(flags, 4), bit(flags, 5), bit(flags, 6), bit(flags, 7), bit(flags, 8));
         State expected = observation.restoringTask() ? State.RESTORING
            : observation.placementTask() ? State.PLACING
            : observation.fillSession() ? State.FILL_PREVIEW
            : observation.quickReplaceSession() ? State.QUICK_REPLACE
            : observation.operationSession() ? observation.workspaceHasParts() || observation.selectionConfirmed()
               ? State.ADJUSTING : State.SELECTING
            : observation.geometrySession() ? State.GEOMETRY
            : observation.buildingSession() ? State.BUILDING : State.IDLE;
         assertEquals(expected, ClientModes.registry().observe(observation), "flags=" + flags);
         assertEquals(observation.fillSession() || observation.quickReplaceSession() || observation.operationSession()
            || observation.geometrySession() || observation.buildingSession(),
            ClientModes.registry().hasInteractiveSession(observation), "flags=" + flags);
      }
   }

   @Test void everyPhaseHasExactlyTheEstablishedDraftLoadAndSubmissionRules() {
      for (State phase : State.values()) {
         var policy = ClientModes.registry().policy(phase);
         assertEquals(phase != State.IDLE && phase != State.SELECTING && phase != State.ADJUSTING, policy.blocksDraftLoad());
         assertEquals(phase == State.BUILDING || phase == State.GEOMETRY || phase == State.ADJUSTING,
            policy.accepts(new ClientSemanticEvent.Submit.Placement(1)));
         assertEquals(phase == State.BUILDING || phase == State.GEOMETRY || phase == State.ADJUSTING || phase == State.SELECTING,
            policy.accepts(new ClientSemanticEvent.Submit.Workspace(UUID.randomUUID())));
      }
   }

   @Test void dataDrivenFillInputsDoNotChangeKeyboardCancellationOrSubmissionAdmission() {
      var fill = ClientModes.registry().policy(State.FILL_PREVIEW);
      for (var input : ClientInputStateMachine.InputKind.values()) {
         var expected = switch (input) {
            case POINTER, INTERACTION, SCROLL -> ClientInputStateMachine.Dispatch.VANILLA;
            case CANCEL -> ClientInputStateMachine.Dispatch.CANCEL;
            default -> ClientInputStateMachine.Dispatch.BLOCKED;
         };
         assertEquals(expected, fill.dispatch(input), input.name());
      }
      assertThrows(IllegalArgumentException.class, () -> new ModeInputPolicy(VANILLA, BLOCKED, BLOCKED, BLOCKED, CANCEL,
         false, false, true, java.util.Set.of(ClientInputStateMachine.InputKind.CANCEL)));
      assertThrows(IllegalArgumentException.class, () -> new ModeInputPolicy(VANILLA, BLOCKED, BLOCKED, BLOCKED, CANCEL,
         false, false, true, java.util.Set.of(ClientInputStateMachine.InputKind.SUBMIT)));
   }

   @Test void missingPhasesAndDuplicateRegistrationFailWithoutPartiallyAddingDefinitions() {
      var registry = new ClientModeRegistry();
      var idlePolicy = policy();
      registry.register(mode("first", 1, Map.of(State.IDLE, idlePolicy), State.IDLE));
      assertThrows(IllegalStateException.class, registry::freeze);
      assertThrows(IllegalStateException.class, () -> registry.observe(ModeObservation.idle()));
      assertThrows(IllegalArgumentException.class, () -> registry.register(
         mode("first", 10, Map.of(State.BUILDING, idlePolicy), State.BUILDING)));
      assertThrows(IllegalArgumentException.class, () -> registry.register(
         mode("second", 10, Map.of(State.IDLE, idlePolicy, State.BUILDING, idlePolicy), State.BUILDING)));
      var remaining = allPhases();
      remaining.remove(State.IDLE);
      registry.register(mode("remaining", 10, remaining, State.BUILDING));
      registry.freeze();
      assertEquals(State.BUILDING, registry.observe(ModeObservation.idle()));
      assertThrows(IllegalStateException.class, () -> registry.register(mode("late", 1, Map.of(), State.IDLE)));
   }

   @Test void registrationFreezesPolicyMapsAndPriority() {
      var registry = new ClientModeRegistry();
      var mutable = allPhases();
      registry.register(mode("owner", 5, mutable, State.IDLE));
      mutable.clear();
      registry.freeze();
      assertEquals(State.IDLE, registry.observe(ModeObservation.idle()));
      assertEquals(VANILLA, registry.policy(State.IDLE).regular());
   }

   @Test void aModeCannotObserveAnotherModesPhase() {
      var registry = new ClientModeRegistry();
      registry.register(mode("invalid", 2, Map.of(State.IDLE, policy()), State.BUILDING));
      var remaining = allPhases();
      remaining.remove(State.IDLE);
      registry.register(mode("other", 1, remaining, State.BUILDING));
      registry.freeze();
      assertThrows(IllegalStateException.class, () -> registry.observe(ModeObservation.idle()));
   }

   @Test void observationCannotReleaseASubmissionOrCancellationOwner() {
      var machine = new ClientInputStateMachine();
      machine.observe(State.BUILDING);
      assertTrue(machine.submit(12));
      machine.observe(ClientModes.registry().observe(ModeObservation.idle()));
      assertTrue(machine.awaitsPlacementRequest(12));
      assertTrue(machine.cancel());
      machine.observe(ClientModes.registry().observe(new ModeObservation(false, false, false, false, false, false, true, false, false)));
      assertEquals(State.CANCELLING, machine.state());
      machine.observe(ClientModes.registry().observe(ModeObservation.idle()));
      assertEquals(State.IDLE, machine.state());
   }

   private static boolean bit(int value, int offset) { return (value & (1 << offset)) != 0; }
   private static ModeInputPolicy policy() {
      return new ModeInputPolicy(VANILLA, BLOCKED, BLOCKED, VANILLA, BLOCKED, false, false, false);
   }
   private static EnumMap<State, ModeInputPolicy> allPhases() {
      var phases = new EnumMap<State, ModeInputPolicy>(State.class);
      for (State phase : State.values()) phases.put(phase, policy());
      return phases;
   }
   private static ClientMode mode(String id, int priority, Map<State, ModeInputPolicy> phases, State observed) {
      return new ClientMode() {
         public ModeId id() { return ModeId.of(id); }
         public int priority() { return priority; }
         public Map<State, ModeInputPolicy> phases() { return phases; }
         public Optional<State> observe(ModeObservation ignored) { return Optional.of(observed); }
      };
   }
}
