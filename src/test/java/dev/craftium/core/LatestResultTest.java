package dev.craftium.core;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LatestResultTest {
    private static <T> T await(LatestResult<T> r){
        long end=System.nanoTime()+5_000_000_000L;
        while(System.nanoTime()<end){T v=r.poll();if(v!=null)return v;Thread.onSpinWait();}
        return fail("no result within 5 s");
    }

    /** The answer can be picked up a moment before the request counts as finished, which is the safe way round for a caller; so "not busy" is waited for. */
    private static void settled(LatestResult<?> r){
        long end=System.nanoTime()+5_000_000_000L;
        while(r.busy()&&System.nanoTime()<end)Thread.onSpinWait();
        assertFalse(r.busy());
    }

    @Test void deliversTheAnswerOnceThenNothing(){
        var r=new LatestResult<String>("test");
        r.submit(()->"done");
        assertEquals("done",await(r));
        assertNull(r.poll());settled(r);
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
        settled(r);
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

    @Test void inTheOtherModeAnAnswerToAReplacedRequestIsStillDelivered() throws Exception{
        var r=new LatestResult<String>("test",false);
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var releaseNew=new CountDownLatch(1);
        r.submit(()->{started.countDown();try{release.await();}catch(InterruptedException e){}return "old";});
        started.await();
        r.submit(()->{try{releaseNew.await();}catch(InterruptedException e){}return "new";}); // asked for while the first is still running, and held back
        release.countDown();
        assertEquals("old",await(r),"the answer that was already being worked on, a little out of date");
        assertTrue(r.busy(),"the newer request has not finished");
        releaseNew.countDown();
        assertEquals("new",await(r),"and then the newer one");
        assertFalse(r.busy());
    }

    @Test void inTheOtherModeAReplacedRequestThatNeverStartedIsSkipped() throws Exception{
        var r=new LatestResult<String>("test",false);
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        r.submit(()->{started.countDown();try{release.await();}catch(InterruptedException e){}return "first";});
        started.await();
        var skipped=new AtomicInteger();var releaseThird=new CountDownLatch(1);
        r.submit(()->{skipped.incrementAndGet();return "second";});
        r.submit(()->{try{releaseThird.await();}catch(InterruptedException e){}return "third";}); // held back, so "first" can be read before it lands
        release.countDown();
        assertEquals("first",await(r));
        releaseThird.countDown();
        assertEquals("third",await(r));
        assertEquals(0,skipped.get(),"a request replaced before it began is never run");
    }

    @Test void inTheOtherModeCancelStillDiscardsARunningRequest() throws Exception{
        var r=new LatestResult<String>("test",false);
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        r.submit(()->{started.countDown();try{release.await();}catch(InterruptedException e){}return "late";});
        started.await();
        r.cancel();
        release.countDown();Thread.sleep(50);
        assertNull(r.poll());assertFalse(r.busy());
    }

    @Test void resultsSharingAThreadKeepToTheirOwnRequests() throws Exception{
        var shared=Executors.newSingleThreadExecutor();
        try{
            var a=new LatestResult<String>(shared,true);var b=new LatestResult<String>(shared,true);
            a.submit(()->"a");b.submit(()->"b");
            assertEquals("a",await(a));assertEquals("b",await(b));
        }finally{shared.shutdownNow();}
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
