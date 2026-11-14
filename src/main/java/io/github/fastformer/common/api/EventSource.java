package io.github.fastformer.common.api;

import java.util.function.Consumer;

/** Server-thread subscriptions owned by one running server, released when it stops. */
public interface EventSource {
   <E extends FastFormerEvent> Subscription subscribe(Class<E> type, Consumer<? super E> listener);

   /** Removing a subscription is idempotent. */
   interface Subscription extends AutoCloseable {
      @Override void close();
   }
}
