package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LatestResultTest {
    private static <T> T await(LatestResult<T> r){
        long end=System.nanoTime()+5_000_000_000L;
        while(System.nanoTime()<end){T v=r.poll();if(v!=null)return v;Thread.onSpinWait();}
        return fail("no result within 5 s");
    }

    @Test void deliversTheAnswerOnceThenNothing(){
        var r=new LatestResult<String>("test");
        r.submit(()->"done");
        assertEquals("done",await(r));
        assertNull(r.poll());assertFalse(r.busy());
    }

    @Test void aReplacedRequestIsNeverDelivered() throws Exception{
        var r=new LatestResult<String>("test");
        var release=new CountDownLatch(1);
        r.submit(()->{try{release.await();}catch(InterruptedException e){}return "old";});
        r.submit(()->"new");
        release.countDown();
        assertEquals("new",await(r));
        Thread.sleep(50);
        assertNull(r.poll(),"the old answer must not turn up afterwards");
    }

    @Test void anOldRequestFinishingAfterTheNewOneIsDropped() throws Exception{
        var r=new LatestResult<String>("test");
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        r.submit(()->{started.countDown();try{release.await();}catch(InterruptedException e){}return "old";});
        started.await();          // the old one is running now
        r.submit(()->"new");      // queued behind it
        release.countDown();      // the old one finishes: too late
        assertEquals("new",await(r));
    }

    @Test void busyUntilTheLatestRequestFinishes() throws Exception{
        var r=new LatestResult<Integer>("test");
        var release=new CountDownLatch(1);
        r.submit(()->{try{release.await();}catch(InterruptedException e){}return 1;});
        assertTrue(r.busy());
        release.countDown();await(r);
        assertFalse(r.busy());
    }

    @Test void cancelDiscardsTheRequestAndIsNotBusy() throws Exception{
        var r=new LatestResult<String>("test");
        var release=new CountDownLatch(1);
        r.submit(()->{try{release.await();}catch(InterruptedException e){}return "late";});
        r.cancel();
        assertFalse(r.busy());
        release.countDown();Thread.sleep(50);
        assertNull(r.poll());
    }

    @Test void aFailingRequestDoesNotLeaveItBusy() throws Exception{
        var r=new LatestResult<String>("test");
        r.submit(()->{throw new IllegalStateException("boom");});
        long end=System.nanoTime()+5_000_000_000L;
        while(r.busy()&&System.nanoTime()<end)Thread.sleep(5);
        assertFalse(r.busy());assertNull(r.poll());
        r.submit(()->"after");
        assertEquals("after",await(r)); // and the worker survives it
    }
}
