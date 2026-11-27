package net.minecraft.client.input;

/** Craftium's 1.21.1 copy; see package-info. A character typed. */
public record CharacterEvent(int codepoint, int modifiers) implements InputWithModifiers {
    public String codepointAsString() {
        return Character.toString(codepoint);
    }
}
