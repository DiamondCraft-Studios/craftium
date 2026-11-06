package dev.craftium.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/**
 * Work done on a background thread whose answer is picked up later by another thread, where only the most recent request
 * matters. A request that has been replaced before it starts is never run, and one that finishes after being replaced is dropped,
 * so a caller that asks again and again (a selection changing while it is dragged) never sees an old answer.
 */
public final class LatestResult<T> {
    private record Done<T>(long id,T value){}

    private final ExecutorService worker;
    private final AtomicLong latest=new AtomicLong();
    /** The newest request that has finished, failed or been cancelled; equal to {@link #latest} when nothing is outstanding. */
    private final AtomicLong settled=new AtomicLong();
    private final AtomicReference<Done<T>> done=new AtomicReference<>();

    public LatestResult(String threadName){
        worker=Executors.newSingleThreadExecutor(task->{
            var thread=new Thread(task,threadName);
            thread.setDaemon(true); // never keeps the game open
            thread.setPriority(Thread.NORM_PRIORITY-1); // the game's own threads come first
            return thread;
        });
    }

    /** Starts {@code work}, replacing whatever was asked before. */
    public void submit(Supplier<T> work){
        long id=latest.incrementAndGet();
        done.set(null);
        worker.execute(()->{
            if(latest.get()!=id)return; // replaced before it began
            try{
                T value=work.get();
                if(latest.get()==id)done.set(new Done<>(id,value));
            }finally{
                if(latest.get()==id)settled.set(id); // also when the work threw, so busy() does not stay true for ever
            }
        });
    }

    /** The answer to the latest request once it is ready, and only once; null while it is still running or after it was taken. */
    public T poll(){
        var finished=done.get();
        if(finished==null||finished.id()!=latest.get()||!done.compareAndSet(finished,null))return null;
        return finished.value();
    }

    /** Whether the latest request has not finished yet. */
    public boolean busy(){return settled.get()!=latest.get();}

    /** Forgets the latest request, so nothing it produces is ever returned. */
    public void cancel(){
        long id=latest.incrementAndGet();
        settled.set(id);done.set(null);
    }
}
