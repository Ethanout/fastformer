package io.github.fastformer.client.operation.selection;

import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.client.operation.selection.tool.SelectionTools;
import net.minecraft.resources.ResourceLocation;

/** User tool choice outlives selection sessions and is not part of undo snapshots. */
public final class SelectionToolPreference {
   private static ResourceLocation id = SelectionTools.builtinId(OperationSelectionMode.CUBOID);
   private SelectionToolPreference() { }
   public static OperationSelectionMode get() { return SelectionTools.require(id).geometry(); }
   public static ResourceLocation id() { return id; }
   public static void select(ResourceLocation value) {
      SelectionTools.require(value);
      id = value;
   }
   public static void set(OperationSelectionMode value) {
      select(SelectionTools.builtinId(value == OperationSelectionMode.SMART ? value : OperationSelectionMode.CUBOID));
   }
}
