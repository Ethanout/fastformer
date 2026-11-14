package io.github.fastformer.client.operation.selection;

import io.github.fastformer.common.selection.smart.SmartSelectionTopology;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Shared by editing and ownership marks. Membership changes invalidate one cached topology. */
public final class SmartSelectionTopologyCache {
   private final Map<Integer, Entry> entries = new HashMap<>();

   public SmartSelectionTopology get(ClientSelectionPart part) {
      Entry entry = entries.get(part.id());
      var positions = part.blocks().keySet();
      if (entry == null || entry.positions != positions && !entry.positions.equals(positions)) {
         entry = new Entry(positions, SmartSelectionTopology.of(positions));
         entries.put(part.id(), entry);
      }
      return entry.topology;
   }

   public void retain(List<ClientSelectionPart> parts) {
      var live = new java.util.HashSet<Integer>();
      for (var part : parts) if (part.smart()) live.add(part.id());
      entries.keySet().retainAll(live);
   }

   private record Entry(Set<BlockPos> positions, SmartSelectionTopology topology) { }
}
