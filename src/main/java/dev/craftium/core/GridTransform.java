package dev.craftium.core;

/**
 * Where a lifted BuildObject sits: quarter turns about Y, then a whole-block shift. Keeping to the grid is what makes every
 * object cell line up exactly with a world cell, so overlap is a yes or no per cell.
 */
public record GridTransform(int turns,int dx,int dy,int dz){
    public static final GridTransform IDENTITY=new GridTransform(0,0,0,0);

    public GridTransform{turns=Math.floorMod(turns,4);}

    public static GridTransform shift(int dx,int dy,int dz){return new GridTransform(0,dx,dy,dz);}

    /** A local cell of the object as a world cell. */
    public Point apply(Point local){
        Point p=local;
        for(int i=0;i<turns;i++)p=p.rotateY();
        return p.add(dx,dy,dz);
    }

    /** A world cell as the local cell of the object that sits here. */
    public Point invert(Point world){
        Point p=world.add(-dx,-dy,-dz);
        for(int i=0;i<(4-turns)%4;i++)p=p.rotateY();
        return p;
    }

    /** The transform of a child that has {@code child} inside this one: applying the result equals applying child, then this. */
    public GridTransform then(GridTransform child){
        // child(p)=R_c p + t_c ; this(q)=R_t q + t_t ; this(child(p))=R_t R_c p + R_t t_c + t_t
        Point t=new GridTransform(turns,0,0,0).apply(new Point(child.dx,child.dy,child.dz));
        return new GridTransform(turns+child.turns,t.x()+dx,t.y()+dy,t.z()+dz);
    }

    public GridTransform shifted(int ax,int ay,int az){return new GridTransform(turns,dx+ax,dy+ay,dz+az);}
    public GridTransform turned(){return new GridTransform(turns+1,dx,dy,dz);}
}
