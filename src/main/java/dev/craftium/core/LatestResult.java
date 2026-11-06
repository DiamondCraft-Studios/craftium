package dev.craftium.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/**
 * Work done on a background thread whose answer is picked up later by another thread, where only the most recent request
 * matters. A request that has been replaced before it starts is never run.
 * <p>
 * What happens to a request that is replaced while it is running depends on the mode. By default its answer is dropped, so a
 * caller that asks again and again (a selection changing while it is dragged) never sees an old answer. In the other mode the answer
 * is still delivered, a little out of date, until a newer one is ready. That is for work that takes longer than the time between
 * requests, such as something worked out for a camera that keeps moving: dropping every answer would mean it never shows anything.
 */
public final class LatestResult<T> {
    private record Done<T>(long id,T value){}

    private final ExecutorService worker;
    private final boolean latestOnly;
    private final AtomicLong latest=new AtomicLong();
    /** The newest request that has finished, failed or been cancelled; equal to {@link #latest} when nothing is outstanding. */
    private final AtomicLong settled=new AtomicLong();
    /** Requests up to this one were cancelled, and are never delivered. */
    private final AtomicLong cancelled=new AtomicLong();
    private final AtomicReference<Done<T>> done=new AtomicReference<>();

    /** Work on a thread of its own, delivering only the answer to the latest request. */
    public LatestResult(String threadName){this(threadName,true);}

    /** Work on a thread of its own. With {@code latestOnly} false, the answer to a request that has been replaced is still delivered. */
    public LatestResult(String threadName,boolean latestOnly){
        this(Executors.newSingleThreadExecutor(task->{
            var thread=new Thread(task,threadName);
            thread.setDaemon(true); // never keeps the game open
            thread.setPriority(Thread.NORM_PRIORITY-1); // the game's own threads come first
            return thread;
        }),latestOnly);
    }

    /**
     * Work on a thread shared with other results, which run one after another. A request of this result that is replaced while it waits
     * for its turn is skipped, so sharing costs only the time spent on the requests ahead of it.
     */
    public LatestResult(ExecutorService shared,boolean latestOnly){this.worker=shared;this.latestOnly=latestOnly;}

    /** Starts {@code work}, replacing whatever was asked before. */
    public void submit(Supplier<T> work){
        long id=latest.incrementAndGet();
        if(latestOnly)done.set(null);
        worker.execute(()->{
            if(latest.get()!=id)return; // replaced before it began
            try{
                T value=work.get();
                if(latestOnly?latest.get()==id:id>cancelled.get())done.set(new Done<>(id,value));
            }finally{
                if(latest.get()==id)settled.set(id); // also when the work threw, so busy() does not stay true for ever
            }
        });
    }

    /** The newest finished answer, once; null while there is none or after it was taken. */
    public T poll(){
        var finished=done.get();
        if(finished==null||latestOnly&&finished.id()!=latest.get()||!done.compareAndSet(finished,null))return null;
        return finished.value();
    }

    /** Whether the latest request has not finished yet. */
    public boolean busy(){return settled.get()!=latest.get();}

    /** Forgets the latest request, so nothing it produces is ever returned. */
    public void cancel(){
        long id=latest.incrementAndGet();
        cancelled.set(id);settled.set(id);done.set(null);
    }
}
