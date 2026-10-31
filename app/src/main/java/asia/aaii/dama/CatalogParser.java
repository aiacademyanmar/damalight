package asia.aaii.dama;

import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** HTML index parser: no MP3 download, only publisher-listed links and link labels. */
public class CatalogParser {
    private static final Pattern ANCHOR=Pattern.compile("(?is)<a\\b([^>]*?)>(.*?)</a\\s*>");
    private static final Pattern HREF=Pattern.compile("(?is)\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern TAG=Pattern.compile("(?s)<[^>]*>");
    private static final Pattern NUMERIC=Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);");
    private static String decode(String t){
        t=t.replace("&amp;","&").replace("&quot;","\"").replace("&apos;","'").replace("&nbsp;"," ").replace("&lt;","<").replace("&gt;",">");
        Matcher m=NUMERIC.matcher(t);StringBuffer sb=new StringBuffer();
        while(m.find()){
            try{String s=m.group(1);int v=s.startsWith("x")||s.startsWith("X")?Integer.parseInt(s.substring(1),16):Integer.parseInt(s);m.appendReplacement(sb,Matcher.quoteReplacement(new String(Character.toChars(v))));}
            catch(Exception e){m.appendReplacement(sb,Matcher.quoteReplacement(m.group()));}
        }
        m.appendTail(sb);return sb.toString().replaceAll("\\s+"," ").trim();
    }
    public static List<JSONObject> parseJson(String html,String base,int monkId,String monkName){
        LinkedHashMap<String,JSONObject> result=new LinkedHashMap<>();
        Matcher matches=ANCHOR.matcher(html);
        while(matches.find()){
            Matcher href=HREF.matcher(matches.group(1));if(!href.find())continue;
            String ref=href.group(1)!=null?href.group(1):href.group(2)!=null?href.group(2):href.group(3);
            if(ref==null)continue;
            ref=decode(ref).replace(" ","%20");
            String lower=ref.toLowerCase(Locale.ROOT);
            if(!lower.matches("(?s).*\\.mp3(?:[?#].*)?$"))continue;
            String url;
            try{
                URL target=new URL(new URL(base),ref);
                String host=target.getHost().toLowerCase(Locale.ROOT);
                // Do not follow offsite / embedded ads masquerading as audio.
                if(!(host.equals("dhammadownload.com")||host.equals("www.dhammadownload.com")||host.endsWith(".dhammadownload.com")))continue;
                url=target.toExternalForm();
            }catch(Exception ex){continue;}
            String title=decode(TAG.matcher(matches.group(2).replaceAll("(?i)<br\\s*/?>"," ")).replaceAll(" "));
            if(title.length()<2)title="တရားတော်: "+url.substring(url.lastIndexOf('/')+1);
            if(title.length()>220)title=title.substring(0,220);
            JSONObject j=new JSONObject();
            try{
                j.put("id","AUTO-"+Integer.toHexString(url.hashCode()));j.put("monk_id",monkId);
                j.put("monk",monkName);j.put("title",title);j.put("category","အခြား");
                j.put("url",url);j.put("listing_url",base);j.put("status","publisher_index_link_not_stream_tested");
            }catch(Exception ignored){}
            result.putIfAbsent(url,j);
        }
        return new ArrayList<>(result.values());
    }
    public static List<MainActivity.Track> parse(String html,String base,int monkId,String monkName){
        ArrayList<MainActivity.Track> tracks=new ArrayList<>();
        for(JSONObject j:parseJson(html,base,monkId,monkName))tracks.add(new MainActivity.Track(j));
        return tracks;
    }
}