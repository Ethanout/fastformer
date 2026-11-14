package io.github.fastformer.client.operation.selection;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import io.github.fastformer.client.operation.selection.tool.SelectionTool;
import io.github.fastformer.client.operation.selection.tool.SelectionToolState;
import io.github.fastformer.client.operation.selection.tool.SelectionTools;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.session.draft.ClientOperationDraft;
import io.github.fastformer.client.session.draft.ClientOperationDraftCodec;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SelectionToolExtensionTest {
   private static final RadiusTool TOOL = new RadiusTool();
   @BeforeAll static void register() { SelectionTools.register(TOOL); }

   @Test void customToolOwnsProductionPressReadyPreviewAndRestoredStateWithoutAnEnumEntry() {
      var session = new ClientSelectionSession();
      session.setSelectionTool(TOOL.id());
      assertEquals(SelectionDraftResult.UPDATED, session.onDraftEvent(new SelectionDraftEvent(SelectionDraftEvent.Button.RIGHT, BlockPos.ZERO, false)));
      assertFalse(session.selectionReady());
      assertEquals(SelectionDraftResult.READY, session.onDraftEvent(new SelectionDraftEvent(SelectionDraftEvent.Button.RIGHT, new BlockPos(0, 3, 0), false)));
      assertTrue(session.selectionReady());
      assertEquals(TOOL.id(), session.selectionToolId());
      assertEquals(new AABB(-2, 0, -2, 3, 4, 3), SelectionDraftPreview.volume(session.draftState(), null).bounds());
      var saved = session.draftState();
      session.clearDraft();
      assertEquals(TOOL.id(), session.selectionToolId());
      session.restoreDraftEdit(saved);
      assertEquals(saved, session.draftState());
      assertEquals(new AABB(-2, 0, -2, 3, 7, 3), SelectionDraftPreview.volume(session.draftState(), new BlockPos(0, 6, 0)).bounds());
   }

   @Test void customCodecAndToolIdSurviveWholeDraftPersistenceAndUnknownToolIsAnError() throws Exception {
      var state = new SelectionToolState(TOOL.id(), TOOL.geometry(), List.of(BlockPos.ZERO), 0,
         BlockPos.ZERO, BlockPos.ZERO, false, Map.of("radius", "3"));
      var selection = new ClientSelectionSession.DraftState(state);
      var draft = new ClientOperationDraft(null, new ClientOperationWorkspace.DraftState(List.of(), Set.of(), 0), selection);
      var encoded = ClientOperationDraftCodec.encode(draft);
      assertEquals(selection, ClientOperationDraftCodec.decode(encoded, null).selection());
      encoded.getCompound("Selection").putString("tool", "example:missing");
      assertThrows(java.io.IOException.class, () -> ClientOperationDraftCodec.decode(encoded, null));
      encoded.getCompound("Selection").putString("tool", TOOL.id().toString());
      encoded.getCompound("Selection").getCompound("parameters").putString("radius", "-1");
      assertThrows(java.io.IOException.class, () -> ClientOperationDraftCodec.decode(encoded, null));
   }

   @Test void selectedCustomToolOutlivesSessionsAndKeepsItsIdentifier() {
      var previous = SelectionToolPreference.id();
      try {
         SelectionToolPreference.select(TOOL.id());
         var session = new ClientSelectionSession();
         session.startNewSession();
         assertEquals(TOOL.id(), session.selectionToolId());
         session.clearLiveInteraction();
         assertEquals(TOOL.id(), session.selectionToolId());
         var status = io.github.fastformer.client.preview.hud.SessionHudText.idle(true, false,
            io.github.fastformer.common.quickshape.mode.LineMode.values()[0], List.of(),
            new io.github.fastformer.client.preview.hud.SelectionToolDisplay(TOOL.id(), TOOL.translationKey()));
         var highlighted = status.getSiblings().stream().filter(component -> component.getStyle().getColor() != null
            && component.getStyle().getColor().getValue() == io.github.fastformer.client.preview.style.GeometryPalette.accent().rgb()).toList();
         assertEquals(1, highlighted.size());
         var name = (net.minecraft.network.chat.contents.TranslatableContents) highlighted.getFirst().getContents();
         assertEquals(TOOL.translationKey(), name.getKey());
         assertEquals(TOOL.id().toString(), name.getFallback());
      } finally { SelectionToolPreference.select(previous); }
   }

   private static final class RadiusTool implements SelectionTool {
      @Override public ResourceLocation id() { return ResourceLocation.parse("example:radius_tool_test"); }
      @Override public OperationSelectionMode geometry() { return OperationSelectionMode.CUBOID; }
      @Override public boolean cycleVisible() { return false; }
      @Override public Transition onPress(SelectionToolState state, Press press) {
         var next = state.append(press.point());
         return new Transition(next, ready(next) ? Result.READY : Result.UPDATED);
      }
      @Override public boolean ready(SelectionToolState state) { return state.points().size() >= 2; }
      @Override public OperationSelectionVolume volume(SelectionToolState state, BlockPos candidate) {
         if (state.points().isEmpty()) return null;
         int radius = Integer.parseInt(state.parameters().getOrDefault("radius", "2"));
         BlockPos last = candidate == null ? state.points().getLast() : candidate;
         return OperationSelectionVolume.cuboid(state.points().getFirst().offset(-radius, 0, -radius),
            last.offset(radius, 0, radius), state.points().getFirst(), last);
      }
      @Override public MapCodec<SelectionToolState> codec() {
         return SelectionTool.super.codec().flatXmap(RadiusTool::validate, RadiusTool::validate);
      }
      private static DataResult<SelectionToolState> validate(SelectionToolState state) {
         try {
            int radius = Integer.parseInt(state.parameters().getOrDefault("radius", "2"));
            if (radius >= 0 && radius <= 8) return DataResult.success(state);
         } catch (NumberFormatException ignored) { }
         return DataResult.error(() -> "Radius must be between 0 and 8");
      }
   }
}
