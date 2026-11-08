package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HandshakeAndUpdateTest {
    @Test void matchingProtocolsHaveNoProblem(){
        assertNull(Handshake.problem(Handshake.read(Handshake.write("0.9.1-beta.1")),true));
    }
    @Test void aNewerServerAsksTheClientToUpdate(){
        var problem=Handshake.problem(new Handshake.Hello(Handshake.PROTOCOL+1,"1.0.0",null),true);
        assertNotNull(problem);assertTrue(problem.contains("Update the client"),problem);
    }
    @Test void aNewerClientAsksTheServerToUpdate(){
        var problem=Handshake.problem(new Handshake.Hello(Handshake.PROTOCOL+1,"1.0.0",null),false);
        assertTrue(problem.contains("Update the server"),problem);
    }
    @Test void anOlderBuildsPlainSentenceIsNotAHello(){
        assertNull(Handshake.read("Craftium 0.3 / protocol 1"));assertNull(Handshake.problem(null,true));
    }
    @Test void refusalIsRememberedUntilAccepted(){
        var id=UUID.randomUUID();Handshake.refuse(id,"no");assertEquals("no",Handshake.refused(id));
        Handshake.accept(id,null);assertNull(Handshake.refused(id));
    }

    @Test void aHelloCarriesTheBuildsCapabilities(){
        var hello=Handshake.read(Handshake.write("0.9.2-beta.1"));
        assertTrue(hello.has(Handshake.MODIFY_EXPANSION));assertFalse(hello.has("something-newer"));
        assertTrue(hello.missingFrom(Handshake.CAPABILITIES).isEmpty());
    }
    @Test void onlyTheFabricServerListsFastMovement(){
        assertFalse(Handshake.read(Handshake.write("0.10.0-beta.1")).has(Handshake.FAST_MOVEMENT),"the default list, as Paper sends it, leaves it out");
        var fabric=Handshake.read(Handshake.write("0.10.0-beta.1",Handshake.FABRIC_SERVER_CAPABILITIES));
        assertTrue(fabric.has(Handshake.FAST_MOVEMENT));assertTrue(fabric.has(Handshake.MODIFY_EXPANSION));assertTrue(fabric.has(Handshake.AI_SPEC_V2),"the AI client reads a Fabric server without it as one that predates spec v2");
        assertTrue(fabric.missingFrom(Handshake.CAPABILITIES).isEmpty(),"a client that knows less loses nothing");
    }
    @Test void aHelloFromBeforeTheListHasNoCapabilities(){
        var old=Handshake.read("{\"protocol\":1,\"version\":\"0.9.1-beta.1\"}");
        assertNotNull(old);assertFalse(old.has(Handshake.MODIFY_EXPANSION));
        assertEquals(Handshake.CAPABILITIES,old.missingFrom(Handshake.CAPABILITIES));
        assertNull(Handshake.problem(old,true),"still compatible, only without the optional features");
    }
    @Test void unknownCapabilitiesFromANewerPeerAreIgnored(){
        var newer=Handshake.read("{\"protocol\":1,\"version\":\"2.0.0\",\"capabilities\":[\"modify-expansion\",\"ai-spec-v2\",\"ai-sessions\",\"from-the-future\"]}");
        assertTrue(newer.has(Handshake.MODIFY_EXPANSION));assertTrue(newer.missingFrom(Handshake.CAPABILITIES).isEmpty());assertNull(Handshake.problem(newer,true));
    }
    @Test void theServerRemembersWhatEachClientSupports(){
        var id=UUID.randomUUID();
        assertFalse(Handshake.clientHas(id,Handshake.MODIFY_EXPANSION),"no hello, no capabilities");
        Handshake.accept(id,Handshake.read(Handshake.write("0.9.2-beta.1")));assertTrue(Handshake.clientHas(id,Handshake.MODIFY_EXPANSION));
        Handshake.forget(id);assertFalse(Handshake.clientHas(id,Handshake.MODIFY_EXPANSION));
    }

    private static final String RELEASES="""
        [{"tag_name":"v0.9.1-beta.1-mc26.2","html_url":"https://x/a","draft":false,"prerelease":true},
         {"tag_name":"v0.9.1-beta.1-mc1.21.10","html_url":"https://x/b","draft":false,"prerelease":true},
         {"tag_name":"v0.9.2-beta.1-mc26.2","html_url":"https://x/c","draft":true,"prerelease":true},
         {"tag_name":"v0.9.0-beta.1-mc26.2","html_url":"https://x/d","draft":false,"prerelease":true},
         {"tag_name":"v1.0.0-mc26.2","html_url":"https://x/e","draft":false,"prerelease":false}]""";
    @Test void versionsOrderLikeSemver(){
        assertTrue(UpdateChecker.compare("0.9.1-beta.1","0.9.0-beta.1")>0);
        assertTrue(UpdateChecker.compare("0.9.0","0.9.0-beta.9")>0);
        assertTrue(UpdateChecker.compare("0.9.0-beta.10","0.9.0-beta.2")>0);
        assertEquals(0,UpdateChecker.compare("0.9.1-beta.1+mc26.2","0.9.1-beta.1"));
    }
    @Test void aBetaIsOfferedTheNewestBetaForItsOwnMinecraftVersion(){
        var found=UpdateChecker.newer(RELEASES,"26.2","0.9.0-beta.1+mc26.2");
        assertTrue(found.isPresent());assertEquals("1.0.0",found.get().version(),"a stable release outranks the betas");
        found=UpdateChecker.newer(RELEASES.replace("v1.0.0-mc26.2","v0.0.1-mc26.2"),"26.2","0.9.0-beta.1");
        assertEquals("0.9.1-beta.1",found.orElseThrow().version());assertEquals("https://x/a",found.get().url());
    }
    @Test void drafts_otherMinecraftVersionsAndCurrentBuildsAreNotOffered(){
        assertTrue(UpdateChecker.newer(RELEASES,"1.21.10","0.9.1-beta.1").isEmpty());
        assertTrue(UpdateChecker.newer(RELEASES.replace("v1.0.0-mc26.2","v0.0.1-mc26.2"),"26.2","0.9.1-beta.1").isEmpty());
    }
    @Test void aStableBuildIsNotOfferedPrereleases(){
        assertTrue(UpdateChecker.newer(RELEASES.replace("v1.0.0-mc26.2","v0.0.1-mc26.2"),"26.2","0.9.0").isEmpty());
    }
    @Test void garbageIsNoUpdate(){assertTrue(UpdateChecker.newer("not json","26.2","0.9.0").isEmpty());assertTrue(UpdateChecker.newer("{}","26.2","0.9.0").isEmpty());}

    @Test void theCacheIsKeptForSixHoursAndSurvivesRestarts(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir){
        var file=dir.resolve("deep/update-check.json");
        assertNull(UpdateChecker.load(file));
        UpdateChecker.store(file,new UpdateChecker.Cache(1_000,"\"abc\"",RELEASES));
        var back=UpdateChecker.load(file);assertEquals("\"abc\"",back.etag());assertEquals(RELEASES,back.body());
        assertTrue(UpdateChecker.fresh(back,1_000+UpdateChecker.TTL_MILLIS-1));
        assertFalse(UpdateChecker.fresh(back,1_000+UpdateChecker.TTL_MILLIS));
        assertFalse(UpdateChecker.fresh(back,500),"a clock that went back is not fresh");
        assertFalse(UpdateChecker.fresh(null,1_000));
    }
    @Test void aDamagedCacheIsIgnored(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)throws Exception{
        var file=dir.resolve("update-check.json");java.nio.file.Files.writeString(file,"{not json");assertNull(UpdateChecker.load(file));
        java.nio.file.Files.writeString(file,"{}");assertNull(UpdateChecker.load(file));
    }
    @Test void aFreshCacheAnswersWithoutTheNetwork(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)throws Exception{
        var file=dir.resolve("update-check.json");
        UpdateChecker.store(file,new UpdateChecker.Cache(System.currentTimeMillis(),null,RELEASES));
        var found=UpdateChecker.check("0.9.0-beta.1","26.2",file).get(1,java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("1.0.0",found.orElseThrow().version());
    }
}
