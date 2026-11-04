package dev.craftium.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** How numbers are shown to the player. */
public final class Numbers {
    private Numbers(){}
    /**
     * The number with at most two decimals and no needless zeros: 3 is "3", 1.8 is "1.8" (not "1.80"), 1.236 is "1.24".
     * A number that rounds to a whole one shows as whole, and a negative one that rounds to zero as "0".
     */
    public static String trim(double value){
        if(Double.isNaN(value)||Double.isInfinite(value))return "0";
        var rounded=BigDecimal.valueOf(value).setScale(2,RoundingMode.HALF_UP).stripTrailingZeros();
        if(rounded.signum()==0)return "0";
        return rounded.toPlainString();
    }
}
