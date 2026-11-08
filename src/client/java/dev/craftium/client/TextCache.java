package dev.craftium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Measured, shortened and wrapped text, remembered between frames. The editor lays out the same labels, tooltips and paragraphs on every
 * frame, and measuring or splitting a string walks every glyph of it, so the answer is worked out once per string and size. Results depend
 * on the font and the language, so a change of either starts afresh; the maps are also emptied if they ever grow large.
 */
final class TextCache {
    private record Key(String text,int pixels){}
    private static final int LIMIT=4096;
    private static final Map<String,Integer> WIDTHS=new HashMap<>();
    private static final Map<Key,String> FITTED=new HashMap<>();
    private static final Map<Key,List<FormattedCharSequence>> SPLIT=new HashMap<>();
    private static Font font;
    private static String language="";
    private TextCache(){}

    /** Drops everything when the font or language has changed since the last call, and bounds the memory otherwise. */
    private static void check(Font current){
        String selected=Minecraft.getInstance().getLanguageManager().getSelected();
        if(current!=font||!selected.equals(language)||WIDTHS.size()>LIMIT||FITTED.size()>LIMIT||SPLIT.size()>LIMIT){WIDTHS.clear();FITTED.clear();SPLIT.clear();font=current;language=selected;}
    }
    /** The width of {@code text} in pixels, as {@link Font#width(String)} gives it. */
    static int width(Font font,String text){check(font);return WIDTHS.computeIfAbsent(text,font::width);}
    /** {@code text} cut to fit in {@code pixels}, as {@link Font#plainSubstrByWidth(String,int)} does. */
    static String fit(Font font,String text,int pixels){check(font);return FITTED.computeIfAbsent(new Key(text,pixels),k->font.plainSubstrByWidth(text,pixels));}
    /** {@code text} wrapped to {@code pixels}, as {@link Font#split} does for a literal. */
    static List<FormattedCharSequence> split(Font font,String text,int pixels){check(font);return SPLIT.computeIfAbsent(new Key(text,pixels),k->font.split(Component.literal(text),pixels));}
}
