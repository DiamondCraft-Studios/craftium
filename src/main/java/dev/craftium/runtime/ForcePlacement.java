package dev.craftium.runtime;
/** Whether a force placement is in flight on this thread, from the start of the block item's placement to its end. */
public final class ForcePlacement {
    private static final ThreadLocal<Boolean> ACTIVE=ThreadLocal.withInitial(()->false);
    private ForcePlacement(){}
    public static void set(boolean active){if(active)ACTIVE.set(true);else ACTIVE.remove();}
    public static boolean active(){return ACTIVE.get();}
}
