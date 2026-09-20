package io.github.fastformer.client.operation.workspace;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-only chain of reversible input events under a count budget and a
 * weight budget.
 *
 * <p>Undo order is fixed. {@link #undo()} runs the newest node first, and a
 * {@link #pushChain(List) chain} node runs its children in reverse order. When a
 * budget is exceeded, the stack discards the oldest nodes. The newest node is
 * always kept, because it is the action that the player most likely expects to
 * undo next.</p>
 *
 * <h2>What one weight unit means</h2>
 *
 * <p>A weight unit is one retained reference. It is not a byte. A
 * {@link Runnable} closure cannot be measured, so every node declares a
 * {@link Retention}:</p>
 *
 * <ul>
 *   <li>{@link Retention#nodeUnits()} counts the references that belong to this
 *       node alone, such as its own snapshot entries and its closure.</li>
 *   <li>{@link Retention#sharedPayloads()} lists the payload holders that this
 *       node keeps alive. Holders are compared by identity, so a holder that
 *       several nodes share is charged once while at least one node holds it. An
 *       unchanged workspace part therefore costs its payload once, no matter how
 *       many snapshots point at it.</li>
 * </ul>
 *
 * <p>The budgets bound retained references. They do not measure JVM object
 * overhead, block state size, or the size of data that a caller does not
 * declare. A producer that cannot declare its retention uses
 * {@link Retention#UNMEASURED}. That value is a deliberate lower bound of one
 * unit, never a measurement.</p>
 */
public final class ClientOperationEventStack {
   /**
    * Default node count budget. A settings integration may pass another value to
    * {@link #ClientOperationEventStack(int, int)}.
    */
   public static final int DEFAULT_RECORD_LIMIT = 512;
   /** Default weight budget, in retained-reference units. */
   public static final int DEFAULT_WEIGHT_LIMIT = 65_536;

   private final Deque<Node> undo = new ArrayDeque<>();
   /** Live node count per payload holder, compared by identity. */
   private final Map<Object, Integer> payloadReferences = new IdentityHashMap<>();
   private final Map<Object, Integer> payloadUnits = new IdentityHashMap<>();
   private final int recordLimit;
   private final int weightLimit;
   private long retainedWeight;
   private long discarded;

   public ClientOperationEventStack() {
      this(DEFAULT_RECORD_LIMIT, DEFAULT_WEIGHT_LIMIT);
   }

   public ClientOperationEventStack(int recordLimit, int weightLimit) {
      if (recordLimit < 1) {
         throw new IllegalArgumentException("A history needs at least one record slot");
      }
      if (weightLimit < 1) {
         throw new IllegalArgumentException("A history needs a positive weight budget");
      }
      this.recordLimit = recordLimit;
      this.weightLimit = weightLimit;
   }

   /** Adds one event whose retention the caller does not declare. */
   public void push(Runnable inverse) {
      this.push(inverse, Retention.UNMEASURED);
   }

   /** Adds one event with a declared retention. */
   public void push(Runnable inverse, Retention retention) {
      if (inverse == null) {
         return;
      }
      Retention supplied = retention == null ? Retention.UNMEASURED : retention;
      Retention declared = Retention.of(supplied.nodeUnits(), supplied.sharedPayloads());
      this.validatePayloadUnits(declared);
      this.retainedWeight += declared.nodeUnits();
      this.retainedWeight += this.retainPayloads(declared);
      this.undo.addLast(new Node(inverse, declared));
      this.trim();
   }

   /** Adds several inverses as one node; they run in reverse child order. */
   public void pushChain(List<Runnable> inverses) {
      if (inverses == null || inverses.isEmpty()) {
         return;
      }
      List<Runnable> chain = List.copyOf(inverses);
      this.push(() -> {
         for (int index = chain.size() - 1; index >= 0; index--) {
            Runnable inverse = chain.get(index);
            if (inverse != null) inverse.run();
         }
      }, Retention.of(1 + chain.size()));
   }

   public boolean undo() {
      Node node = this.undo.pollLast();
      if (node == null) {
         return false;
      }
      // Release before running, so the accounting stays correct even when an
      // inverse reaches back into the workspace.
      this.release(node);
      node.inverse().run();
      return true;
   }

   /** Removes every node and releases every retained reference. */
   public void clear() {
      this.undo.clear();
      this.payloadReferences.clear();
      this.payloadUnits.clear();
      this.retainedWeight = 0L;
   }

   public int size() {
      return this.undo.size();
   }

   /** Current weight in retained-reference units. */
   public long retainedWeight() {
      return this.retainedWeight;
   }

   /** Distinct payload holders that live nodes keep alive. */
   public int retainedPayloadCount() {
      return this.payloadReferences.size();
   }

   public int recordLimit() {
      return this.recordLimit;
   }

   public int weightLimit() {
      return this.weightLimit;
   }

   /**
    * Nodes that the budgets dropped, oldest first. {@link #clear()} does not
    * reset this counter, so a caller can still see that the history was
    * truncated before the clear.
    */
   public long discardedRecords() {
      return this.discarded;
   }

   private void trim() {
      while (this.undo.size() > 1
         && (this.undo.size() > this.recordLimit || this.retainedWeight > this.weightLimit)) {
         Node oldest = this.undo.pollFirst();
         this.release(oldest);
         this.discarded++;
      }
   }

   /** Charges the payload holders that this node is the first live holder of. */
   private long retainPayloads(Retention retention) {
      List<SharedPayload> payloads = retention.sharedPayloads();
      if (payloads.isEmpty()) {
         return 0L;
      }
      long added = 0L;
      for (SharedPayload payload : payloads) {
         Integer live = this.payloadReferences.get(payload.holder());
         if (live == null) {
            this.payloadReferences.put(payload.holder(), 1);
            this.payloadUnits.put(payload.holder(), payload.units());
            added += payload.units();
         } else {
            this.payloadReferences.put(payload.holder(), live + 1);
         }
      }
      return added;
   }

   /** Drops this node and returns the weight of the payloads it released. */
   private void release(Node node) {
      this.retainedWeight -= node.retention().nodeUnits();
      List<SharedPayload> payloads = node.retention().sharedPayloads();
      for (SharedPayload payload : payloads) {
         Integer live = this.payloadReferences.get(payload.holder());
         if (live == null) {
            continue;
         }
         if (live <= 1) {
            this.payloadReferences.remove(payload.holder());
            this.payloadUnits.remove(payload.holder());
            this.retainedWeight -= payload.units();
         } else {
            this.payloadReferences.put(payload.holder(), live - 1);
         }
      }
      if (this.retainedWeight < 0L) {
         // A negative total would mean the accounting drifted; clamp instead of
         // reporting a budget that cannot be trusted.
         this.retainedWeight = 0L;
      }
   }

   private void validatePayloadUnits(Retention retention) {
      Map<Object, Integer> declaredUnits = new IdentityHashMap<>();
      for (SharedPayload payload : retention.sharedPayloads()) {
         Integer previous = declaredUnits.put(payload.holder(), payload.units());
         Integer live = this.payloadUnits.get(payload.holder());
         if ((previous != null && previous != payload.units())
            || (live != null && live != payload.units())) {
            throw new IllegalArgumentException("A shared payload must keep the same declared units while retained");
         }
      }
   }

   /**
    * Declares what one history node keeps alive.
    *
    * <p>A node that cannot declare its retention uses {@link #UNMEASURED}. That
    * constant charges one unit and is a lower bound, not a measurement.</p>
    */
   public interface Retention {
      /** Declares only the node itself, with no measurable payload. */
      Retention UNMEASURED = new Retention() {
         @Override
         public int nodeUnits() {
            return 1;
         }

         @Override
         public List<SharedPayload> sharedPayloads() {
            return List.of();
         }
      };

      /** References that belong to this node alone. */
      int nodeUnits();

      /** Payload holders that this node keeps alive, counted once while shared. */
      List<SharedPayload> sharedPayloads();

      static Retention of(int nodeUnits) {
         return of(nodeUnits, List.of());
      }

      static Retention of(int nodeUnits, List<SharedPayload> sharedPayloads) {
         if (nodeUnits < 0) {
            throw new IllegalArgumentException("Node units must not be negative");
         }
         List<SharedPayload> payloads = sharedPayloads == null ? List.of() : List.copyOf(sharedPayloads);
         return new Retention() {
            @Override
            public int nodeUnits() {
               return nodeUnits;
            }

            @Override
            public List<SharedPayload> sharedPayloads() {
               return payloads;
            }
         };
      }
   }

   /**
    * One payload holder that history nodes can share, with its size in weight
    * units.
    *
    * <p>The holder is compared by identity. Two equal maps are two payloads,
    * because they are two separate allocations.</p>
    */
   public record SharedPayload(Object holder, int units) {
      public SharedPayload {
         if (holder == null) {
            throw new IllegalArgumentException("A shared payload requires a holder");
         }
         if (units < 0) {
            throw new IllegalArgumentException("Shared payload units must not be negative");
         }
      }

      public static SharedPayload of(Object holder, int units) {
         return new SharedPayload(holder, units);
      }
   }

   private record Node(Runnable inverse, Retention retention) {
   }
}
