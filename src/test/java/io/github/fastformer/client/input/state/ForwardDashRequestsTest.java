package io.github.fastformer.client.input.state;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ForwardDashRequestsTest {
   @Test
   void tapsDuringAnAuthorizationAreRememberedAndSentAfterItReturns() {
      var requests = new ForwardDashRequests();
      long first = requests.request(0);
      assertEquals(0, requests.request(10));
      assertEquals(0, requests.request(20));
      assertFalse(requests.accept(first + 1));
      assertTrue(requests.pending());
      assertTrue(requests.accept(first));
      long next = requests.nextQueued(30);
      assertTrue(next > first);
      assertFalse(requests.accept(first));
      assertTrue(requests.accept(next));
      assertEquals(0, requests.nextQueued(40));
   }

   @Test
   void completedRequestsDoNotImposeADashDurationCooldown() {
      var requests = new ForwardDashRequests();
      for (int tap = 0; tap < 10; tap++) {
         long id = requests.request(tap * 100_000_000L);
         assertTrue(id > 0);
         assertTrue(requests.accept(id));
      }
   }

   @Test
   void cancellationAndTimeoutRejectOldRepliesWithoutReusingTheirIdentity() {
      var requests = new ForwardDashRequests();
      long cancelled = requests.request(0);
      requests.request(10);
      requests.reset();
      assertFalse(requests.accept(cancelled));
      assertEquals(0, requests.nextQueued(20));
      long expired = requests.request(30);
      requests.request(40);
      requests.expire(1_000_000_031L);
      assertFalse(requests.accept(expired));
      assertFalse(requests.pending());
      assertEquals(0, requests.nextQueued(1_000_000_040L));
      long fresh = requests.request(1_000_000_050L);
      assertTrue(fresh > expired && expired > cancelled);
      assertFalse(requests.accept(cancelled));
      assertTrue(requests.accept(fresh));
   }
}
