package dev.craftium.client;

/** The one hover look every interactable control shares: its own colour lifted toward white. */
final class Hover {
    /** The lift, out of 255, applied to each colour channel under the pointer. */
    static final int LIFT=34;
    /** The overlay drawn over a control in the editor workspace that has the pointer on it. */
    static final int OVERLAY=0x2cffffff;
    private Hover(){}
    static int fill(int argb){
        int a=argb>>>24,r=Math.min(255,(argb>>16&255)+LIFT),g=Math.min(255,(argb>>8&255)+LIFT),b=Math.min(255,(argb&255)+LIFT);
        return a<<24|r<<16|g<<8|b;
    }
}
