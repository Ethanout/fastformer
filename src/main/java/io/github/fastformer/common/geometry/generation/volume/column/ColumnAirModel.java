package io.github.fastformer.common.geometry.generation.volume.column;

import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finite air intervals and their face connectivity, without expanding solid columns. */
final class ColumnAirModel {
   private static final int[][] ADJACENT_COLUMNS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
   private final Map<ColumnKey, List<ColumnSpan>> occupied;
   private final Map<ColumnKey, List<ColumnSpan>> gaps;

   private ColumnAirModel(Map<ColumnKey, List<ColumnSpan>> occupied, Map<ColumnKey, List<ColumnSpan>> gaps) {
      this.occupied = occupied;
      this.gaps = gaps;
   }

   static ColumnAirModel create(Map<ColumnKey, ArrayList<ColumnSpan>> raw, BlockGenerationObserver observer) {
      LinkedHashMap<ColumnKey, List<ColumnSpan>> occupied = new LinkedHashMap<>();
      LinkedHashMap<ColumnKey, List<ColumnSpan>> gaps = new LinkedHashMap<>();
      raw.forEach((key, intervals) -> {
         observer.checkCancelled();
         intervals.sort(Comparator.comparingInt(ColumnSpan::minimum).thenComparingInt(ColumnSpan::maximum));
         ArrayList<ColumnSpan> merged = new ArrayList<>();
         for (ColumnSpan interval : intervals) {
            if (merged.isEmpty() || (long)interval.minimum() > (long)merged.getLast().maximum() + 1L) {
               merged.add(interval);
            } else {
               ColumnSpan previous = merged.removeLast();
               merged.add(new ColumnSpan(previous.minimum(), Math.max(previous.maximum(), interval.maximum())));
            }
         }
         occupied.put(key, List.copyOf(merged));
         ArrayList<ColumnSpan> finiteGaps = new ArrayList<>();
         for (int index = 0; index + 1 < merged.size(); index++) {
            finiteGaps.add(new ColumnSpan(merged.get(index).maximum() + 1, merged.get(index + 1).minimum() - 1));
         }
         if (!finiteGaps.isEmpty()) gaps.put(key, List.copyOf(finiteGaps));
      });
      return new ColumnAirModel(Collections.unmodifiableMap(occupied), Collections.unmodifiableMap(gaps));
   }

   boolean hasEnclosedAir(BlockGenerationObserver observer) {
      HashSet<AirGap> visited = new HashSet<>();
      for (Map.Entry<ColumnKey, List<ColumnSpan>> entry : this.gaps.entrySet()) {
         for (int index = 0; index < entry.getValue().size(); index++) {
            AirGap start = new AirGap(entry.getKey(), index);
            if (visited.add(start) && !this.reachesExterior(start, visited, observer)) return true;
         }
      }
      return false;
   }

   private boolean reachesExterior(AirGap start, HashSet<AirGap> visited, BlockGenerationObserver observer) {
      boolean exterior = false;
      ArrayDeque<AirGap> open = new ArrayDeque<>();
      open.add(start);
      while (!open.isEmpty()) {
         observer.checkCancelled();
         AirGap current = open.removeFirst();
         ColumnSpan interval = this.gaps.get(current.key()).get(current.index());
         for (int[] step : ADJACENT_COLUMNS) {
            ColumnKey neighborKey = current.key().neighbor(step[0], step[1]);
            List<ColumnSpan> neighborOccupied = neighborKey == null ? null : this.occupied.get(neighborKey);
            if (neighborOccupied == null) {
               exterior = true;
               continue;
            }
            if (interval.minimum() < neighborOccupied.getFirst().minimum()
               || interval.maximum() > neighborOccupied.getLast().maximum()) exterior = true;
            List<ColumnSpan> neighborGaps = this.gaps.getOrDefault(neighborKey, List.of());
            int first = firstGapEndingAtOrAfter(neighborGaps, interval.minimum());
            for (int index = first; index < neighborGaps.size(); index++) {
               ColumnSpan neighbor = neighborGaps.get(index);
               if (neighbor.minimum() > interval.maximum()) break;
               if (neighbor.maximum() >= interval.minimum()) {
                  AirGap next = new AirGap(neighborKey, index);
                  if (visited.add(next)) open.addLast(next);
               }
            }
         }
      }
      return exterior;
   }

   private static int firstGapEndingAtOrAfter(List<ColumnSpan> gaps, int coordinate) {
      int low = 0;
      int high = gaps.size();
      while (low < high) {
         int middle = (low + high) >>> 1;
         if (gaps.get(middle).maximum() < coordinate) low = middle + 1;
         else high = middle;
      }
      return low;
   }

   private record AirGap(ColumnKey key, int index) {}
}
