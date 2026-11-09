package dev.craftium.core;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** Every line Craftium puts in chat: a dark aqua "[Craftium]" tag, then the message in white. */
public final class Chat {
    private Chat() {}
    public static Component say(String message) {
        String text = message.startsWith("Craftium: ") ? message.substring(10) : message;
        return Component.literal("[Craftium] ").withStyle(ChatFormatting.DARK_AQUA).append(Component.literal(text).withStyle(ChatFormatting.WHITE));
    }
}
