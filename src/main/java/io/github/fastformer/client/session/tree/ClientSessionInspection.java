package io.github.fastformer.client.session.tree;

import java.util.List;

/** Immutable view of the active position in a player's session tree. */
public record ClientSessionInspection(List<String> path) {
   public ClientSessionInspection {
      path = List.copyOf(path);
   }

   public String activeKey() {
      return path.getLast();
   }
}
