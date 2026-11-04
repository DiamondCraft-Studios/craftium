package dev.craftium.client;

import dev.craftium.core.Restore;

/** Takes up what undo and redo hand back besides blocks, put back where each piece of client-side state lives. */
final class ClientRestore {
    private ClientRestore(){}
    static void apply(String update){
        for(var entry:Restore.parse(update).entrySet()){
            String value=entry.getValue();
            switch(entry.getKey()){
                case "box" -> BuilderMode.restoreBox(value);
                case "corners" -> {
                    ClientState.boxCorners.clear();
                    if(!value.equals("none"))ClientState.boxCorners.addAll(Restore.parsePoints(value));
                }
                case "shape" -> ShapeObject.restore(value);
                case "entity" -> ClientState.options.entityUuid=value.equals("none")?"":value;
                default -> {}
            }
        }
    }
}
