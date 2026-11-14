package io.github.fastformer.client.operation.selection.tool;

import com.mojang.serialization.MapCodec;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** A selection tool owns its draft transitions, completion rule, volume and persisted state. */
public interface SelectionTool {
   ResourceLocation id();
   OperationSelectionMode geometry();
   default String translationKey() { return id().toLanguageKey("selection_tool"); }
   Transition onPress(SelectionToolState state, Press press);
   boolean ready(SelectionToolState state);
   OperationSelectionVolume volume(SelectionToolState state, BlockPos candidate);
   default MapCodec<SelectionToolState> codec() { return SelectionToolState.codec(id(), geometry()); }
   default boolean worldSampling() { return false; }
   default boolean undoPreviousPoint() { return false; }
   default boolean cycleVisible() { return true; }

   enum Button { LEFT, RIGHT, MIDDLE }
   enum Result { REJECTED, UPDATED, READY }
   record Press(Button button, BlockPos point, boolean alt) {
      public Press {
         Objects.requireNonNull(button, "button");
         point = Objects.requireNonNull(point, "point").immutable();
      }
   }
   record Transition(SelectionToolState state, Result result) {
      public Transition {
         Objects.requireNonNull(state, "state");
         Objects.requireNonNull(result, "result");
      }
      public static Transition rejected(SelectionToolState state) { return new Transition(state, Result.REJECTED); }
   }
}
