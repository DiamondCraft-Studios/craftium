package dev.craftium.core;

/**
 * What a pointer step is worth to a gizmo handle: the blocks it moves along one axis, or along the two axes of a plane. The handle is held at a place
 * in the world and the camera sees it with some perspective; a step of the pointer is turned into blocks with the perspective at the handle <em>as it
 * is then</em>, and the blocks are added up by the caller. Nothing here remembers where the pointer was or how the camera stood, so flying about moves
 * nothing, and there is no place on the screen where the handle is out of reach.
 *
 * <p>Where the handle's axes are almost a point on the screen (looking along an axis, or at a plane edge-on) a pixel would be worth without end, so the
 * pixels' worth is capped at one distance-to-the-handle per focal length of pointer travel in the directions the screen cannot tell apart.
 * Everywhere else the answer is exact: the handle goes the way the pointer does.
 */
public final class DragMath {
    private DragMath(){}

    /** A handle behind or at the camera cannot be steered by the pointer. */
    public static final double NEAREST=.05;

    /**
     * The blocks the pointer stepping {@code dx}, {@code dy} pixels (y down) moves the handle along each of {@code axes} (one or two world vectors).
     *
     * @param from    where the handle is, relative to the camera
     * @param right   the camera's right, up and forward as unit vectors
     * @param focal   the focal length in pixels: the screen's height over twice the tangent of half the field of view
     * @return one amount per axis, all zero when the handle is behind the camera
     */
    public static double[] step(double[] from,double[][] axes,double[] right,double[] up,double[] forward,double focal,double dx,double dy){
        int n=axes.length;double[] out=new double[n];
        double depth=dot(from,forward);if(depth<=NEAREST||n<1||n>2)return out;
        double fr=dot(from,right),fu=dot(from,up);
        double[][] g=new double[n][2]; // the pixels one block along each axis moves the handle by
        for(int i=0;i<n;i++){
            double toward=dot(axes[i],forward);
            g[i][0]=(dot(axes[i],right)-fr/depth*toward)/depth*focal;
            g[i][1]=(-dot(axes[i],up)+fu/depth*toward)/depth*focal;
        }
        double floor=Math.pow(focal/depth,2);
        if(n==1){
            double a=Math.max(g[0][0]*g[0][0]+g[0][1]*g[0][1],floor);
            out[0]=(g[0][0]*dx+g[0][1]*dy)/a;return out;
        }
        // The normal matrix J'J of the two columns, with every direction of it that is weaker than the floor lifted to it.
        double a=g[0][0]*g[0][0]+g[0][1]*g[0][1],b=g[0][0]*g[1][0]+g[0][1]*g[1][1],c=g[1][0]*g[1][0]+g[1][1]*g[1][1];
        double theta=.5*Math.atan2(2*b,a-c),cs=Math.cos(theta),sn=Math.sin(theta),mean=(a+c)/2,radius=Math.hypot((a-c)/2,b);
        double[] eigen={mean+radius,mean-radius};double[][] vector={{cs,sn},{-sn,cs}};
        double ma=a,mb=b,mc=c;
        for(int k=0;k<2;k++)if(eigen[k]<floor){double lift=floor-eigen[k];ma+=lift*vector[k][0]*vector[k][0];mb+=lift*vector[k][0]*vector[k][1];mc+=lift*vector[k][1]*vector[k][1];}
        double r0=g[0][0]*dx+g[0][1]*dy,r1=g[1][0]*dx+g[1][1]*dy,det=ma*mc-mb*mb;
        if(det<=0)return out;
        out[0]=(mc*r0-mb*r1)/det;out[1]=(ma*r1-mb*r0)/det;
        return out;
    }
    private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
}
