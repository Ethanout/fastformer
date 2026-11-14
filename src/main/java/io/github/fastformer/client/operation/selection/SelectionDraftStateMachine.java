package io.github.fastformer.client.operation.selection;

import io.github.fastformer.client.operation.selection.ClientSelectionSession.DraftState;
import io.github.fastformer.client.operation.selection.tool.SelectionTool;
import io.github.fastformer.client.operation.selection.tool.SelectionToolState;
import io.github.fastformer.client.operation.selection.tool.SelectionTools;
import io.github.fastformer.common.selection.OperationSelectionMode;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Owns atomic draft publication; tool strategies own the transitions. */
final class SelectionDraftStateMachine {
   private final ClientSelectionStack stack;
   SelectionDraftStateMachine() { this(new ClientSelectionStack()); }
   SelectionDraftStateMachine(ClientSelectionStack stack) { this.stack = stack; }
   DraftState snapshot() { return stack.draft(); }

   Phase phase() {
      var draft = snapshot();
      if (draft.selectionMode() == OperationSelectionMode.PRISM) return draft.prismBaseCount() == 0 ? Phase.PRISM_BASE : Phase.PRISM_HEIGHT;
      return switch (draft.points().size()) {
         case 0 -> Phase.CUBOID_EMPTY;
         case 1 -> draft.secondPointOnly() ? Phase.CUBOID_SECOND : Phase.CUBOID_FIRST;
         default -> Phase.CUBOID_BOUNDS;
      };
   }

   SelectionDraftResult onEvent(SelectionDraftEvent event) {
      var transition = inspect(event);
      stack.setDraft(transition.next());
      return transition.result();
   }

   Transition inspect(SelectionDraftEvent event) {
      DraftState previous = snapshot();
      if (event == null) return new Transition(previous, SelectionDraftResult.REJECTED);
      var tool = SelectionTools.require(previous.toolId());
      var press = new SelectionTool.Press(SelectionTool.Button.valueOf(event.button().name()), event.point(), event.alt());
      var transition = tool.onPress(previous.toolState(), press);
      validate(tool, transition.state());
      if (transition.result() == SelectionTool.Result.READY && !tool.ready(transition.state())) {
         throw new IllegalArgumentException("Selection tool completed an unready state: " + tool.id());
      }
      return new Transition(transition.state() == previous.toolState() ? previous : new DraftState(transition.state()),
         SelectionDraftResult.valueOf(transition.result().name()));
   }

   void setMode(OperationSelectionMode mode) { if (mode != null) setTool(SelectionTools.builtinId(mode)); }
   void setTool(ResourceLocation id) {
      var tool = SelectionTools.require(id);
      var previous = snapshot();
      if (id.equals(previous.toolId())) return;
      var points = tool.undoPreviousPoint() && previous.points().size() >= 2
         ? List.of(previous.minPoint(), previous.maxPoint()) : previous.points();
      var state = new SelectionToolState(id, tool.geometry(), List.of(), 0, null, null, false, Map.of());
      stack.setDraft(new DraftState(state.withPoints(points, 0, false)));
   }

   void clear() { stack.setDraft(new DraftState(snapshot().toolState().withPoints(List.of(), 0, false))); }
   void restore(DraftState draft) {
      if (draft == null) { clear(); return; }
      validate(SelectionTools.require(draft.toolId()), draft.toolState());
      var normalized = draft.toolState().withPoints(draft.points(), draft.prismBaseCount(), draft.secondPointOnly());
      stack.setDraft(new DraftState(normalized.withBounds(draft.minPoint(), draft.maxPoint())));
   }
   void addPoint(BlockPos point) { if (point != null) stack.setDraft(new DraftState(snapshot().toolState().append(point))); }
   BlockPos removeLastPoint() {
      if (snapshot().points().isEmpty()) return null;
      BlockPos removed = snapshot().points().getLast();
      stack.setDraft(new DraftState(snapshot().toolState().removeLast(false)));
      return removed;
   }
   boolean expandTo(BlockPos point) {
      var previous = snapshot().toolState();
      var expanded = previous.expand(point);
      if (expanded == previous) return false;
      stack.setDraft(new DraftState(expanded));
      return true;
   }
   void restoreBounds(BlockPos min, BlockPos max) { stack.setDraft(new DraftState(snapshot().toolState().withBounds(min, max))); }

   private static void validate(SelectionTool tool, SelectionToolState state) {
      if (!tool.id().equals(state.toolId()) || tool.geometry() != state.geometry()) {
         throw new IllegalArgumentException("Selection tool returned a state owned by another tool: " + tool.id());
      }
   }
   record Transition(DraftState next, SelectionDraftResult result) {}
   enum Phase { CUBOID_EMPTY, CUBOID_FIRST, CUBOID_SECOND, CUBOID_BOUNDS, PRISM_BASE, PRISM_HEIGHT }
}
