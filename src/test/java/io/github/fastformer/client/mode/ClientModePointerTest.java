package io.github.fastformer.client.mode;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.input.pointer.PointerDecision;
import io.github.fastformer.client.mode.input.pointer.PointerDecision.Left;
import io.github.fastformer.client.mode.input.pointer.PointerDecision.Right;
import io.github.fastformer.client.mode.input.pointer.PointerInput;
import io.github.fastformer.client.mode.input.pointer.PointerView;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class ClientModePointerTest {
   @Test void allLeftTargetCombinationsRetainTheirPreviousPriority() {
      for (int flags = 0; flags < 1 << 17; flags++) {
         var view = left(flags);
         for (State phase : new State[]{State.BUILDING, State.GEOMETRY, State.SELECTING, State.ADJUSTING}) {
            assertEquals(previousLeft(view, phase == State.SELECTING || phase == State.ADJUSTING),
               ClientModes.registry().onPointer(phase, new PointerInput(1, 0), view),
               () -> phase + " / " + view);
         }
      }
   }

   @Test void allRightTargetCombinationsRetainDistinctGeometryAndSelectionRules() {
      for (int flags = 0; flags < 1 << 13; flags++) {
         var view = right(flags);
         for (State phase : new State[]{State.GEOMETRY, State.SELECTING, State.ADJUSTING}) {
            assertEquals(previousRight(view, phase == State.GEOMETRY),
               ClientModes.registry().onPointer(phase, new PointerInput(1, 1), view),
               () -> phase + " / " + view);
         }
      }
   }

   @Test void releasesRepeatsOtherButtonsAndIdleRemainUnclaimedByPressDecisions() {
      for (State state : State.values()) {
         for (int action : new int[]{0, 1, 2}) {
            for (int button : new int[]{0, 1, 2, 3}) {
               if (action == 1 && (button == 0 || button == 1)
                  && (state == State.BUILDING || state == State.GEOMETRY || state == State.SELECTING || state == State.ADJUSTING)) continue;
               assertEquals(PointerDecision.Flow.PASS, ClientModes.registry().onPointer(state,
                  new PointerInput(action, button), button == 0 ? left((1 << 17) - 1) : right((1 << 13) - 1)));
            }
         }
      }
   }

   @Test void onlyPassFallsBackToIdleAndKeepsTheSameInputFacts() {
      var calls = new ArrayList<String>();
      var input = new PointerInput(1, 0);
      var view = left(0);
      var registry = custom((event, facts) -> {
         assertSame(input, event); assertSame(view, facts); calls.add("owner"); return PointerDecision.Flow.PASS;
      }, (event, facts) -> {
         assertSame(input, event); assertSame(view, facts); calls.add("idle"); return Left.UNDO;
      });
      assertEquals(Left.UNDO, registry.onPointer(State.BUILDING, input, view));
      assertEquals(java.util.List.of("owner", "idle"), calls);
      calls.clear();
      assertEquals(Left.UNDO, registry.onPointer(State.IDLE, input, view));
      assertEquals(java.util.List.of("idle"), calls);
      var blocked = custom((event, facts) -> PointerDecision.Flow.BLOCK, (event, facts) -> {
         fail("blocked input reached idle"); return Left.NONE;
      });
      assertEquals(PointerDecision.Flow.BLOCK, blocked.onPointer(State.SELECTING, input, view));
   }

   @Test void nullOrWrongButtonResultsFailAtTheModeBoundary() {
      var nullResult = custom((event, facts) -> null, (event, facts) -> PointerDecision.Flow.PASS);
      assertThrows(NullPointerException.class, () -> nullResult.onPointer(State.BUILDING, new PointerInput(1, 0), left(0)));
      var wrongButton = custom((event, facts) -> Right.GEOMETRY_CAPTURE, (event, facts) -> PointerDecision.Flow.PASS);
      assertThrows(IllegalStateException.class, () -> wrongButton.onPointer(State.BUILDING, new PointerInput(1, 0), left(0)));
      assertThrows(NullPointerException.class, () -> wrongButton.onPointer(State.BUILDING, null, left(0)));
      assertThrows(NullPointerException.class, () -> wrongButton.onPointer(State.BUILDING, new PointerInput(1, 0), null));
   }

   private static Left previousLeft(PointerView.Left state, boolean operationSession) {
      if (state.yieldToVanilla()) return Left.YIELD_TO_VANILLA;
      if (state.workspacePointerTarget()) return Left.WORKSPACE_POINTER;
      if (state.workspaceActive()) return Left.WORKSPACE_ADJUST_OR_CAPTURE;
      if (state.operationGizmoTarget()) return Left.OPERATION_GIZMO;
      if (state.operationPointTarget()) return Left.OPERATION_POINT;
      if (state.operationUndo()) return Left.UNDO;
      if (state.operationAlt()) return Left.OPERATION_ALT;
      if (state.operationAdjust()) return Left.OPERATION_ADJUST;
      if (state.cuboidWorldTarget()) return Left.CUBOID_POINT_UNDO;
      if (state.cuboidFaceTarget()) return Left.CUBOID_FACE;
      if (state.operationCaptured() || state.operationDragCaptured()) return Left.OPERATION_CAPTURE;
      if (state.geometryCaptured()) return Left.GEOMETRY_CAPTURE;
      if (state.geometryGizmoDragging()) return Left.GEOMETRY_GIZMO_CAPTURE;
      if (state.geometryGizmoTarget() || state.geometryInteractionTarget()) return Left.GEOMETRY_INTERACTION;
      if (operationSession) return Left.OPERATION_CAPTURE;
      return state.undoAvailable() ? Left.UNDO : Left.NONE;
   }

   private static Right previousRight(PointerView.Right state, boolean geometrySession) {
      if (geometrySession) {
         if (state.vanillaOwnsPointer()) return Right.YIELD_TO_VANILLA;
         return state.geometryCaptured() ? Right.GEOMETRY_CAPTURE : Right.GEOMETRY_INTERACTION;
      }
      if (state.workspacePointerTarget()) return Right.WORKSPACE_POINTER;
      if (state.workspaceActive()) return Right.WORKSPACE_ADJUST_OR_CAPTURE;
      if (state.selectionConfirmed()) {
         return state.vanillaOwnsPointer() ? Right.YIELD_TO_VANILLA : Right.CONFIRMED_GIZMO_CAPTURE;
      }
      if (state.vanillaOwnsPointer() && !state.operationAdjust() && !state.operationAlt() && !state.operationPointTarget()) {
         return Right.YIELD_TO_VANILLA;
      }
      if (state.operationAlt()) return Right.OPERATION_ALT;
      if (state.operationAdjust()) return Right.OPERATION_ADJUST;
      if (state.operationPointTarget()) return Right.OPERATION_POINT;
      if (state.operationEdgeTarget()) return Right.OPERATION_EDGE;
      if (state.operationNextPointTarget()) return Right.OPERATION_NEXT_POINT;
      if (state.cuboidWorldTarget()) return Right.CUBOID_NEXT_POINT;
      if (state.cuboidFaceTarget()) return Right.CUBOID_FACE;
      return state.closePathTarget() ? Right.CLOSE_PATH : Right.NONE;
   }

   private static PointerView.Left left(int value) {
      return new PointerView.Left(bit(value, 0), bit(value, 1), bit(value, 2), bit(value, 3), bit(value, 4),
         bit(value, 5), bit(value, 6), bit(value, 7), bit(value, 8), bit(value, 9), bit(value, 10), bit(value, 11),
         bit(value, 12), bit(value, 13), bit(value, 14), bit(value, 15), bit(value, 16));
   }
   private static PointerView.Right right(int value) {
      return new PointerView.Right(bit(value, 0), bit(value, 1), bit(value, 2), bit(value, 3), bit(value, 4),
         bit(value, 5), bit(value, 6), bit(value, 7), bit(value, 8), bit(value, 9), bit(value, 10), bit(value, 11), bit(value, 12));
   }
   private static boolean bit(int value, int offset) { return (value & (1 << offset)) != 0; }

   private static ClientModeRegistry custom(
      java.util.function.BiFunction<PointerInput, PointerView, PointerDecision> owner,
      java.util.function.BiFunction<PointerInput, PointerView, PointerDecision> idle
   ) {
      var registry = new ClientModeRegistry();
      var phases = new EnumMap<State, ModeInputPolicy>(State.class);
      for (State phase : State.values()) if (phase != State.IDLE) phases.put(phase, ClientModes.registry().policy(phase));
      registry.register(mode("owner", phases, owner));
      registry.register(mode("idle", Map.of(State.IDLE, ClientModes.registry().policy(State.IDLE)), idle));
      return registry.freeze();
   }
   private static ClientMode mode(String id, Map<State, ModeInputPolicy> phases,
      java.util.function.BiFunction<PointerInput, PointerView, PointerDecision> handler) {
      return new ClientMode() {
         public String id() { return id; }
         public int priority() { return 0; }
         public Map<State, ModeInputPolicy> phases() { return phases; }
         public Optional<State> observe(ModeObservation view) { return Optional.empty(); }
         public PointerDecision onPointer(PointerInput input, PointerView view) { return handler.apply(input, view); }
      };
   }
}
