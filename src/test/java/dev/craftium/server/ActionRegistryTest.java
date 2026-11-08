package dev.craftium.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActionRegistryTest {
    private static final ActionHandler NOTHING = use -> {};

    @AfterEach void clean() { ActionRegistry.clear(); }

    @Test void onlyPluginIdColonActionNamesAreNamespaced() {
        for (String ok : new String[]{"wall:build", "a:b", "my_plugin2:do_it"}) assertTrue(ActionRegistry.isNamespaced(ok), ok);
        // The names of the editor's own actions have no colon, so none of them is namespaced.
        for (String no : new String[]{"use", "select_box", "pending_confirm", "", "wall:", ":build", "Wall:build", "wall:Build", "1wall:build", "wall:build:again", "wall build:x", "wall:b-c"})
            assertFalse(ActionRegistry.isNamespaced(no), no);
        assertFalse(ActionRegistry.isNamespaced(null));
    }

    @Test void anActionIsFoundOnceItIsRegistered() {
        assertNull(ActionRegistry.find("wall:build"));
        ActionRegistry.register("wall:build", NOTHING);
        assertSame(NOTHING, ActionRegistry.find("wall:build"));
        assertNull(ActionRegistry.find("wall:other"));
        assertNull(ActionRegistry.find(null));
    }

    @Test void theEditorsOwnNamesCannotBeTaken() {
        assertThrows(IllegalArgumentException.class, () -> ActionRegistry.register("use", NOTHING));
        assertThrows(IllegalArgumentException.class, () -> ActionRegistry.register("status", NOTHING));
        assertThrows(IllegalArgumentException.class, () -> ActionRegistry.register("Wall:Build", NOTHING));
    }

    @Test void aNameCannotBeRegisteredTwiceOrWithoutAHandler() {
        ActionRegistry.register("wall:build", NOTHING);
        assertThrows(IllegalArgumentException.class, () -> ActionRegistry.register("wall:build", use -> {}));
        assertSame(NOTHING, ActionRegistry.find("wall:build"));
        assertThrows(IllegalArgumentException.class, () -> ActionRegistry.register("wall:null", null));
    }

    @Test void unregisteringANamespaceTakesBackEveryActionOfItAndNoOthers() {
        ActionRegistry.register("wall:build", NOTHING);
        ActionRegistry.register("wall:clear", NOTHING);
        ActionRegistry.register("wallpaper:build", NOTHING);
        assertEquals(2, ActionRegistry.unregister("wall"));
        assertNull(ActionRegistry.find("wall:build"));
        assertNull(ActionRegistry.find("wall:clear"));
        assertSame(NOTHING, ActionRegistry.find("wallpaper:build"));
        assertEquals(0, ActionRegistry.unregister("wall"));
        // The name is free again, as it is when a plugin is reloaded.
        assertDoesNotThrow(() -> ActionRegistry.register("wall:build", NOTHING));
    }

    @Test void theNamespaceIsTheWordBeforeTheColon() {
        assertEquals("wall", ActionRegistry.namespaceOf("wall:build"));
    }
}
