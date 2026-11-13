package io.github.fastformer.client.input;

/** Physical-press arbitration shared by the idle adapter and coexistence tests. */
final class IdleEntryRules {
   enum Owner { EXTERNAL, ACTIVE_SESSION, ENTRY_GUARD, ALT_ENTRY, DISTANT_ENTRY, DOUBLE_ENTRY, TINKER, VANILLA, HOLD_CANDIDATE }
   enum Aim { AIR, ENTITY, PASSTHROUGH_BLOCK, ORDINARY_BLOCK }
   record Context(boolean externallyCancelled, boolean activeSession, boolean protectedPress, boolean alt,
      boolean distant, boolean doubleSecondPress, boolean tinkerConsumes, Aim aim) {}

   private IdleEntryRules() {}

   static Owner owner(Context context) {
      if (context.externallyCancelled()) return Owner.EXTERNAL;
      if (context.activeSession()) return Owner.ACTIVE_SESSION;
      if (context.protectedPress()) return Owner.ENTRY_GUARD;
      if (context.alt()) return Owner.ALT_ENTRY;
      if (context.distant()) return Owner.DISTANT_ENTRY;
      if (context.doubleSecondPress()) return Owner.DOUBLE_ENTRY;
      if (context.tinkerConsumes()) return Owner.TINKER;
      if (context.aim() != Aim.ORDINARY_BLOCK) return Owner.VANILLA;
      return Owner.HOLD_CANDIDATE;
   }
}
