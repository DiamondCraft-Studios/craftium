package dev.craftium.core;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Asks the project's GitHub releases whether a newer build exists for this Minecraft version. Releases are tagged
 * {@code v<version>-mc<minecraft>}, as the release workflow requires. It only looks; nothing is downloaded or installed.
 */
public final class UpdateChecker {
    private UpdateChecker() {}
    public static final String REPO="DiamondCraft-Studios/craftium";
    private static final Pattern TAG=Pattern.compile("^v([0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.]+)?)-mc([0-9]+(?:\\.[0-9]+)+)$");
    private static final Gson GSON=new Gson();
    public record Release(String version,String url) {}
    private record Entry(@SerializedName("tag_name") String tag,@SerializedName("html_url") String url,boolean draft,boolean prerelease) {}

    /** The newest release for {@code minecraft} that is newer than {@code current}, or empty. A stable build is only offered stable releases. */
    public static Optional<Release> newer(String json,String minecraft,String current){
        Entry[] entries;
        try{entries=GSON.fromJson(json,Entry[].class);}catch(RuntimeException e){return Optional.empty();}
        if(entries==null)return Optional.empty();
        String mine=clean(current);boolean onPrerelease=mine.contains("-");
        Release best=null;
        for(Entry e:entries){
            if(e==null||e.tag()==null||e.draft()||e.prerelease()&&!onPrerelease)continue;
            var m=TAG.matcher(e.tag());
            if(!m.matches()||!m.group(2).equals(minecraft))continue;
            if(best==null||compare(m.group(1),best.version())>0)best=new Release(m.group(1),e.url()==null?"https://github.com/"+REPO+"/releases":e.url());
        }
        return best!=null&&compare(best.version(),mine)>0?Optional.of(best):Optional.empty();
    }
    /** Drops the +mc build suffix a Fabric version carries. */
    public static String clean(String version){int plus=version.indexOf('+');return plus<0?version:version.substring(0,plus);}
    /** Semantic-version order: the numbers first, then a version with no pre-release tag beats one with it, then the tag's parts one by one. */
    public static int compare(String a,String b){
        a=clean(a);b=clean(b);
        String[] x=a.split("-",2),y=b.split("-",2);
        String[] p=x[0].split("\\."),q=y[0].split("\\.");
        for(int i=0;i<Math.max(p.length,q.length);i++){
            int c=Long.compare(i<p.length?number(p[i]):0,i<q.length?number(q[i]):0);
            if(c!=0)return c;
        }
        if(x.length==1||y.length==1)return x.length==y.length?0:x.length==1?1:-1;
        String[] s=x[1].split("\\."),t=y[1].split("\\.");
        for(int i=0;i<Math.min(s.length,t.length);i++){
            boolean sn=s[i].chars().allMatch(Character::isDigit),tn=t[i].chars().allMatch(Character::isDigit);
            int c=sn&&tn?Long.compare(number(s[i]),number(t[i])):sn?-1:tn?1:s[i].compareTo(t[i]);
            if(c!=0)return c;
        }
        return Integer.compare(s.length,t.length);
    }
    private static long number(String text){try{return Long.parseLong(text);}catch(NumberFormatException e){return 0;}}

    /** Looks once, off the calling thread. Any failure (offline, rate limited, a repository with no releases) is an empty answer. */
    public static CompletableFuture<Optional<Release>> check(String current,String minecraft){
        var request=HttpRequest.newBuilder(URI.create("https://api.github.com/repos/"+REPO+"/releases?per_page=30")).timeout(Duration.ofSeconds(8))
            .header("Accept","application/vnd.github+json").header("User-Agent","craftium/"+clean(current)).GET().build();
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().sendAsync(request,HttpResponse.BodyHandlers.ofString())
            .thenApply(response->response.statusCode()==200?newer(response.body(),minecraft,current):Optional.<Release>empty())
            .exceptionally(e->Optional.empty());
    }
}
