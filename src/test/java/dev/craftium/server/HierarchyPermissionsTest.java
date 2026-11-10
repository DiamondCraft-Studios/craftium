package dev.craftium.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HierarchyPermissionsTest {
    private static boolean may(String json,boolean operator,String uuid,String name,String verb){return HierarchyPermissions.allowed(HierarchyPermissions.parse(json),operator,uuid,name,verb);}
    private static final String U="0a1b2c3d-0000-0000-0000-000000000001";

    @Test void everyoneMayDoEverythingUnlessTheConfigSaysOtherwise(){
        for(var verb:HierarchyPermissions.VERBS){
            assertTrue(may("{}",false,U,"Alex",verb),verb+" with an empty config");
            assertTrue(HierarchyPermissions.allowed(HierarchyPermissions.Config.OPEN,false,U,"Alex",verb),verb+" with no config at all");
        }
    }
    @Test void theTemplateThatIsWrittenFirstAllowsEverything(){
        for(var verb:HierarchyPermissions.VERBS)assertTrue(may("{\"everyone\":{\"create\":true,\"rename\":true,\"move\":true,\"delete\":true,\"recolour\":true,\"edit\":true},\"players\":{},\"operatorsBypass\":true}",false,U,"Alex",verb));
    }
    @Test void anActionCanBeDeniedToEveryone(){
        var json="{\"everyone\":{\"delete\":false}}";
        assertFalse(may(json,false,U,"Alex","delete"));assertTrue(may(json,false,U,"Alex","create"),"and nothing else is");
    }
    @Test void aPlayersOwnEntryWinsByNameOrUuidWhateverTheCase(){
        var json="{\"everyone\":{\"delete\":false,\"move\":true},\"players\":{\"ALEX\":{\"delete\":true},\""+U+"\":{\"move\":false}}}";
        assertTrue(may(json,false,"ffffffff-0000-0000-0000-000000000009","alex","delete"),"by name, any case");
        assertFalse(may(json,false,U,"Sam","move"),"by UUID");
        assertFalse(may(json,false,"ffffffff-0000-0000-0000-000000000009","Sam","delete"),"someone else still has the default");
    }
    @Test void operatorsMayDoEverythingUnlessTheBypassIsOff(){
        var denied="{\"everyone\":{\"delete\":false}}";
        assertTrue(may(denied,true,U,"Op","delete"),"the bypass is on unless it is turned off");
        assertFalse(may("{\"everyone\":{\"delete\":false},\"operatorsBypass\":false}",true,U,"Op","delete"));
    }
    @Test void unknownActionsAndBadValuesAreIgnoredNotTrusted(){
        var json="{\"everyone\":{\"explode\":false,\"create\":\"no\"},\"players\":{\"Alex\":\"nope\"}}";
        assertTrue(may(json,false,U,"Alex","create"));
    }
}
