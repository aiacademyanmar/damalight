package asia.aaii.dama;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** DAMA RADIO V1: native Android streaming client. All audio remains on its source website. */
public class MainActivity extends Activity {
    private final int BG=Color.rgb(6,29,30), CARD=Color.rgb(14,52,48), GOLD=Color.rgb(229,189,111);
    private final int TEXT=Color.rgb(255,248,229), MUTED=Color.rgb(191,213,199), RED=Color.rgb(126,39,34);
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ArrayList<Monk> monks=new ArrayList<>();
    private final LinkedHashMap<String,Track> records=new LinkedHashMap<>();
    private ArrayList<Track> visible=new ArrayList<>();
    private LinearLayout root,center,playerBar,radioPanel,nowStatus;
    private TextView stats,progress,nowText;
    private ListView trackList;
    private EditText search;
    private Spinner monkSpinner,categorySpinner;
    private boolean syncing=false,playerReady=false;
    private final Handler searchHandler=new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private MediaPlayer player;
    private Track selected;
    private String tab="Radio";
    private String query="";
    private int monkFilter=0,categoryFilter=0;
    private final String[] categoryNames={"အားလုံး", "ပဋ္ဌာန်း", "ဝိပဿနာ", "ပါဠိ / ပရိတ်", "အခြား"};
    private final String CATALOG_FILE="dama_radio_catalog_v1.json";

    private static final class Monk {
        int id;String name,school,source;
        Monk(JSONObject o){id=o.optInt("id");name=o.optString("name");school=o.optString("school");source=o.optString("source_url");}
    }
    static final class Track {
        String id,monk,title,category,url,source,status;
        int monkId;
        Track(JSONObject o){
            id=o.optString("id");monk=o.optString("monk");title=o.optString("title");category=o.optString("category");
            url=o.optString("url");source=o.optString("listing_url");status=o.optString("status");monkId=o.optInt("monk_id");
        }
        JSONObject json(){JSONObject j=new JSONObject();try{j.put("id",id);j.put("monk",monk);j.put("title",title);j.put("category",category);j.put("url",url);j.put("listing_url",source);j.put("status",status);j.put("monk_id",monkId);}catch(Exception ignored){}return j;}
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        loadMonks();loadSeeds();loadBundledCatalog();loadCache();showTab("Home");
    }
    private JSONArray assetJson(String file){
        try(InputStream f=getAssets().open(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=f.read(b))!=-1)out.write(b,0,n);
            return new JSONArray(out.toString("UTF-8"));
        }catch(Exception ex){return new JSONArray();}
    }
    private void loadMonks(){JSONArray a=assetJson("monks.json");for(int i=0;i<a.length();i++){JSONObject v=a.optJSONObject(i);if(v!=null)monks.add(new Monk(v));}}
    private void loadSeeds(){JSONArray a=assetJson("seed_tracks.json");for(int i=0;i<a.length();i++){JSONObject v=a.optJSONObject(i);if(v!=null)addTrack(new Track(v));}}
    private void loadBundledCatalog(){
        JSONArray a=assetJson("full_catalog.json");
        for(int i=0;i<a.length();i++){JSONObject v=a.optJSONObject(i);if(v!=null)addTrack(new Track(v));}
    }
    private void loadCache(){
        File f=new File(getFilesDir(),CATALOG_FILE);if(!f.exists())return;
        try(FileInputStream stream=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=stream.read(b))!=-1)out.write(b,0,n);
            JSONArray rows=new JSONArray(out.toString("UTF-8"));
            for(int i=0;i<rows.length();i++){JSONObject v=rows.optJSONObject(i);if(v!=null)addTrack(new Track(v));}
        }catch(Exception e){android.util.Log.w("DAMA","Cache failed, using seed",e);}
    }
    private void addTrack(Track t){
        if(t.url==null||!t.url.startsWith("http")||t.title==null||t.title.isEmpty())return;
        String key=t.url.replaceFirst("^http://","https://").replaceFirst("^https://www\\.","https://");
        synchronized(records){if(!records.containsKey(key))records.put(key,t);}
    }
    private void saveCache(){
        File target=new File(getFilesDir(),CATALOG_FILE),tmp=new File(getFilesDir(),CATALOG_FILE+".tmp");
        try(FileOutputStream f=new FileOutputStream(tmp)){
            JSONArray a=new JSONArray();synchronized(records){for(Track t:records.values())a.put(t.json());}
            f.write(a.toString().getBytes(StandardCharsets.UTF_8));f.getFD().sync();
            if(!tmp.renameTo(target))android.util.Log.w("DAMA","Save cache rename failed");
        }catch(Exception e){android.util.Log.e("DAMA","Save failed",e);}
    }
    private int dp(float x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable bg(int c,int radius,int stroke){
        GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;
    }
    private TextView text(String s,int sp,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private LinearLayout vertical(){LinearLayout a=new LinearLayout(this);a.setOrientation(LinearLayout.VERTICAL);return a;}
    private LinearLayout horizontal(){LinearLayout a=new LinearLayout(this);a.setOrientation(LinearLayout.HORIZONTAL);a.setGravity(Gravity.CENTER_VERTICAL);return a;}
    private View gap(int h){View v=new View(this);v.setLayoutParams(new LinearLayout.LayoutParams(1,dp(h)));return v;}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private TextView pill(String s,Runnable onClick){
        TextView t=text(s,13,GOLD,true);t.setGravity(Gravity.CENTER);t.setPadding(dp(13),dp(10),dp(13),dp(10));t.setBackground(bg(CARD,12,GOLD));
        t.setOnClickListener(v->onClick.run());return t;
    }
    private void showTab(String which){
        if(which.equals(tab) && root!=null)return;
        if(pendingSearch!=null){searchHandler.removeCallbacks(pendingSearch);pendingSearch=null;}
        tab=which;
        root=vertical();root.setBackgroundColor(BG);
        setContentView(root);
        ScrollView page=new ScrollView(this);page.setFillViewport(false);
        root.addView(page,new LinearLayout.LayoutParams(-1,0,1));
        center=vertical();center.setPadding(dp(15),dp(8),dp(15),dp(16));page.addView(center);
        TextView menu=text("☰   DAMA    •    "+which,14,GOLD,true);menu.setPadding(dp(10),dp(12),dp(10),dp(12));
        center.addView(menu);menu.setOnClickListener(v->showTab("Menu"));
        TextView premium=text("✦ Premium / Account",14,GOLD,true);center.addView(premium);premium.setOnClickListener(v->startActivity(new Intent(this,PremiumActivity.class)));
        if(!"Radio".equals(which)){radioPanel=null;stats=null;progress=null;}
        if("Radio".equals(which))renderRadio();
        else if("Home".equals(which))renderHome();
        else if("Library".equals(which))renderLibrary();
        else if("TV".equals(which))renderTV();
        else if("Worship".equals(which))renderWorship();
        else if("Search".equals(which))renderSearch();
        else if("Settings".equals(which))renderSettings();
        else if("Menu".equals(which))renderMenu();
        else renderHome();
        drawPlayer();drawBottomBar();
    }
    private void hero(String title,String subtitle){
        LinearLayout hero=vertical();hero.setPadding(dp(16),dp(14),dp(16),dp(14));
        GradientDrawable gd=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(5,29,33),Color.rgb(23,80,68),Color.rgb(8,43,43)});
        gd.setCornerRadius(dp(20));gd.setStroke(dp(1),GOLD);hero.setBackground(gd);
        TextView lotus=text("✿",30,GOLD,true);lotus.setGravity(Gravity.CENTER);hero.addView(lotus);
        TextView h=text(title,24,GOLD,true);h.setGravity(Gravity.CENTER);hero.addView(h);
        TextView sub=text(subtitle,13,TEXT,false);sub.setGravity(Gravity.CENTER);sub.setPadding(dp(2),dp(8),dp(2),0);hero.addView(sub);
        center.addView(hero,lp(-1,-2));center.addView(gap(10));
    }
    private void renderHome(){
        hero("DAMA APP", "ဓမ္မနှင့်အတူ နေ့စဉ် စိတ်အေးချမ်းမှု · Listen / Read / Watch / Worship");
        section("အဓိက ကဏ္ဍ ၄ ခု");
        addBigCard("♫  DAMA RADIO", "ဆရာတော်ကြီးများ၏ တရားတော် MP3 နားဆင်ရန်", "Radio");
        addBigCard("▣  DAMA LIBRARY", "ဓမ္မစာအုပ်များ · ပဋ္ဌာန်း · အဘိဓမ္မာ", "Library");
        addBigCard("▶  DAMA TV", "ဗုဒ္ဓဘာသာ တရားတော် ဗီဒီယိုများ", "TV");
        addBigCard("✿  DAILY WORSHIP", "နေ့စဉ် ရှိခိုး၊ ပရိတ်၊ မေတ္တာပို့ · မှတ်တမ်း", "Worship");
        section("ယနေ့အတွက် ဓမ္မအလင်း");
        detail("နေ့စဉ် အကျင့်ကောင်းတစ်ခုကို ရွေး၍ တည်ငြိမ်စွာ လေ့ကျင့်ပါ။");
        addBigCard("⌕  အကြောင်းအရာအားလုံး ရှာရန်", "Radio · Library · TV", "Search");
    }
    private void addBigCard(String label,String detail,String to){
        LinearLayout card=vertical();card.setPadding(dp(14),dp(10),dp(14),dp(10));card.setBackground(bg(CARD,14,GOLD));
        card.addView(text(label,18,GOLD,true));card.addView(text(detail,13,MUTED,false));
        center.addView(card,lp(-1,-2));center.addView(gap(7));card.setOnClickListener(v->showTab(to));
    }
    private void detail(String message){
        TextView v=text(message,14,TEXT,false);v.setPadding(dp(15),dp(16),dp(15),dp(16));v.setBackground(bg(CARD,12,0));
        center.addView(v,lp(-1,-2));center.addView(gap(10));
    }
    private void linkCard(String title,String body,String link){
        LinearLayout card=vertical();card.setPadding(dp(14),dp(13),dp(14),dp(13));card.setBackground(bg(CARD,12,Color.rgb(51,96,83)));
        card.addView(text(title,15,GOLD,true));card.addView(gap(3));card.addView(text(body,12,MUTED,false));
        center.addView(card,lp(-1,-2));center.addView(gap(9));
        card.setOnClickListener(v->openUrl(link));
    }
    private void renderLibrary(){
        hero("DAMA LIBRARY", "ဓမ္မစာအုပ် · ပဋ္ဌာန်း · အဘိဓမ္မာ");
        section("အွန်လိုင်း ဓမ္မစာကြည့်တိုက်");
        detail("စာအုပ်လင့်ခ်ကိုနှိပ်ပြီး မူရင်းစာကြည့်တိုက်တွင် ဖတ်ရှု၊ Download လုပ်နိုင်ပါသည်။ သက်ဆိုင်ရာမူပိုင်ခွင့်စည်းမျဉ်းများ လိုက်နာပါ။");
        linkCard("ပဋ္ဌာန်း ၂၄ ပစ္စည်း — ရုပ်ပြစာအုပ်","အဓိပ္ပာယ်၊ ပုံနှင့် ရှင်းလင်းချက် (PDF)","https://www.dhammadownload.com/images/ebook/Pahtan-in-Picture/PaHtan-In-Picture-Ver01.pdf");
        linkCard("DhammaDownload eBooks", "မြန်မာဘာသာ ဓမ္မစာအုပ်များ", "https://www.dhammadownload.com/Others-eBookInMyanmar.htm");
        linkCard("သစ္စာပါရမီ — ဓမ္မစာအုပ်များ", "ဆရာတော်ကြီးများ၏ eBook စုစည်းမှု", "https://thitsarparami.org/ebooks");
        linkCard("ကမ္ဘာအေး ဗုဒ္ဓသုတေသနစာကြည့်တိုက်", "သာသနာရေး စာအုပ်များ", "https://www.kbrl.gov.mm/");
        linkCard("ပိဋကတ်သုံးပုံ — Tipitaka", "ပါဠိကျမ်းများကို Online ဖတ်ရှုရန်", "https://www.tipitaka.org/");
        linkCard("ဒေါက်တာနန္ဒမာလာဘိဝံသ — Books", "Abhidhamma · Satipatthana", "https://nandamala.org/books/");
    }
    private void renderTV(){
        hero("DAMA TV", "တရားတော် ဗီဒီယို · YouTube");
        section("ပဋ္ဌာန်းတရားတော် ဗီဒီယိုများ");
        detail("YouTube Link များကို YouTube App သို့မဟုတ် Browser ဖြင့် ဖွင့်ပါမည်။ Live Streaming မပါသေးပါ။");
        linkCard("ပဋ္ဌာန်း (၂၄) ပစ္စည်းဆိုတာ ဘာလဲ", "Dhamma Lulin · မြန်မာဘာသာ", "https://www.youtube.com/watch?v=UgeOGn7xUV8");
        linkCard("ပဋ္ဌာန်းတရားတော် အနှစ်ချုပ် — နိဒါန်း", "Dhamma Dharna · သင်ကြားရေးအတွဲ", "https://www.youtube.com/watch?v=V76bW_AueBE");
        linkCard("ပဋ္ဌာန်းတရားတော် — အာဟာရပစ္စည်း", "ဆရာတော်ကြီးများ၏ အဘိဓမ္မာရှင်းလင်းချက်", "https://www.youtube.com/watch?v=33ShTjVwEGg");
        linkCard("ပဋ္ဌာန်း ပါဠိတော်", "Mahar Bodhi · ပရိတ်နှင့် ပါဠိ", "https://www.youtube.com/watch?v=y5a0czNa79I");
        section("Playlists");
        linkCard("Paṭṭhāna Lectures", "Ashin Janakābhivaṃsa", "https://www.youtube.com/playlist?list=PL8wdiyqqpfrrrDe2p0hzldKIM6fEOg1Df");
        linkCard("Kusalattika Paṭṭhāna", "Bhikkhu Atulañāṇa", "https://www.youtube.com/playlist?list=PLtYBvkguVF4ZL5Zf2pf2evtZbeHYVpa5T");
    }
    private String[] worshipTasks={"ဘုရားရှိခိုးခြင်း", "ပရိတ်တော်ရွတ်ဖတ်ခြင်း", "မေတ္တာပို့ခြင်း", "တရားထိုင်ခြင်း", "ကောင်းမှုကုသိုလ် ပြုလုပ်ခြင်း", "နေ့စဉ် ဆင်ခြင်သုံးသပ်ခြင်း"};
    private String todayKey(){return new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new java.util.Date());}
    private void renderWorship(){
        hero("DAILY WORSHIP", "နေ့စဉ် ပူဇော်မှု · အလေ့အကျင့် မှတ်တမ်း");
        section("ယနေ့ ကျင့်သုံးရန်");
        detail("မနက်နှင့် ညနေ့စဉ် ရှိခိုးမှုကို မှတ်တမ်းတင်နိုင်သည်။ ဖုန်းအတွင်းသာ သိမ်းဆည်းထားပါသည်။");
        android.content.SharedPreferences pref=getSharedPreferences("worship",MODE_PRIVATE);
        for(int i=0;i<worshipTasks.length;i++){
            final int index=i;String key=todayKey()+"_"+i;
            CheckBox check=new CheckBox(this);check.setText(worshipTasks[i]);check.setTextColor(TEXT);check.setTextSize(15);
            check.setPadding(dp(10),dp(10),dp(10),dp(10));check.setButtonTintList(android.content.res.ColorStateList.valueOf(GOLD));
            check.setBackground(bg(CARD,10,0));check.setChecked(pref.getBoolean(key,false));
            check.setOnCheckedChangeListener((button,isChecked)->pref.edit().putBoolean(key,isChecked).apply());
            center.addView(check,lp(-1,58));center.addView(gap(10));
        }
        section("ဝတ်ပြု ပူဇော်စာမျက်နှာ");
        linkCard("ပရိတ်ကြီး ၁၁ သုတ်", "စာသား/MP3 မူရင်းစာမျက်နှာ", "https://www.dhammadownload.com/");
        linkCard("ပဋ္ဌာန်းကျမ်း လေ့လာရန်", "ပဋ္ဌာန်း ၂၄ ပစ္စည်း", "https://my.wikipedia.org/wiki/%E1%80%95%E1%80%8B%E1%80%B9%E1%80%8C%E1%80%AC%E1%80%94%E1%80%BA%E1%80%B8");
    }
    private void renderSearch(){
        hero("DAMA SEARCH", "တရားတော် / စာအုပ် / ဗီဒီယို ရှာရန်");
        section("တရားတော် MP3 ကို ရှာဖွေရန်");
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("တရားတော်အမည် သို့မဟုတ် ဆရာတော်...");
        input.setTextSize(15);input.setTextColor(TEXT);input.setHintTextColor(MUTED);input.setPadding(dp(12),dp(10),dp(12),dp(10));input.setBackground(bg(CARD,10,GOLD));center.addView(input,lp(-1,52));
        center.addView(gap(10));center.addView(pill("⌕  Search MP3",()->{query=input.getText().toString();showTab("Radio");}));
        center.addView(gap(12));
        linkCard("ဓမ္မစာအုပ် အွန်လိုင်း Search", "DhammaDownload eBook Library", "https://www.dhammadownload.com/Others-eBookInMyanmar.htm");
        linkCard("YouTube မှ ဓမ္မ Video များရှာရန်", "YouTube Search", "https://www.youtube.com/results?search_query=Pahtan+Myanmar+Dhamma");
    }
    private void renderMenu(){
        hero("DAMA MENU", "Listen · Read · Watch · Worship");
        addBigCard("⌂  Home", "အဓိက စာမျက်နှာ", "Home");
        addBigCard("♫  DAMA RADIO", "ဆရာတော်များ၏ MP3", "Radio");
        addBigCard("▣  DAMA LIBRARY", "စာအုပ်စာကြည့်တိုက်", "Library");
        addBigCard("▶  DAMA TV", "ဓမ္မ Video", "TV");
        addBigCard("✿  DAILY WORSHIP", "နေ့စဉ် ဝတ်ပြုမှု", "Worship");
        addBigCard("⚙  Settings", "အက်ပ်အကြောင်း", "Settings");
    }
    private void renderSettings(){
        hero("DAMA SETTINGS", "Version 1.1 · Native Android");
        section("အက်ပ်အကြောင်း");
        detail("DAMA — Dhamma for a Better Life\nRadio · Library · TV · Daily Worship\nOnline Media အသုံးပြုရန် Internet လိုအပ်ပါသည်။");
        detail("MP3 Catalog သည် DhammaDownload Website မှ မူရင်း Link များကိုသာ ရယူသည်။ အသံဖိုင်များကို APK ထဲမှာ မကူးယူထားပါ။");
        addBigCard("⌕  Search", "ဓမ္မအကြောင်းအရာ ရှာရန်", "Search");
        linkCard("Source: DhammaDownload", "Original publisher and media links", "https://www.dhammadownload.com/");
    }
    private void section(String label){TextView t=text(label,16,GOLD,true);t.setPadding(0,dp(4),0,dp(9));center.addView(t);}
    private void renderRadio(){
        hero("DAMA RADIO", "ဆရာတော်ကြီးများ · တရားတော်နားဆင်ရန်");
        stats=text("လင့်ခ်စာရင်း "+records.size()+" ခု · ဆရာတော်စာရင်း "+monks.size()+" ပါး",14,TEXT,true);
        center.addView(stats);
        TextView caution=text("လင့်ခ်စာရင်းကို မူရင်း DhammaDownload မှ ရယူသည်။ MP3 တစ်ခုချင်း အလုပ်လုပ်မှုကို မစစ်ရသေးပါ။",11,MUTED,false);
        center.addView(caution);center.addView(gap(9));
        LinearLayout actions=horizontal();
        TextView sync=pill(syncing?"⏳ စုစည်းနေသည်":"↻ MP3 စာရင်း Sync",()->syncCatalog());actions.addView(sync);
        actions.addView(new View(this),new LinearLayout.LayoutParams(dp(10),1));
        TextView source=pill("မူရင်း Website ↗",()->openUrl("https://www.dhammadownload.com/AudioInMyanmar.htm"));actions.addView(source);
        center.addView(actions);center.addView(gap(8));
        progress=text(syncing?"စာရင်းရယူနေဆဲ…":"Sync ကိုနှိပ်ပြီး ဆရာတော်စာမျက်နှာ ၂၂ ခုမှ MP3 များ ရယူပါ။",11,MUTED,false);
        center.addView(progress);center.addView(gap(12));
        search=new EditText(this);search.setSingleLine(true);search.setTextColor(TEXT);search.setHintTextColor(MUTED);
        search.setTextSize(14);search.setHint("တရားခေါင်းစဉ် / ဆရာတော်အမည် ရှာရန်");
        search.setPadding(dp(12),dp(8),dp(12),dp(8));search.setBackground(bg(CARD,12,GOLD));search.setText(query);
        center.addView(search,lp(-1,48));center.addView(gap(9));
        monkSpinner=spinner();ArrayList<String> monkOpts=new ArrayList<>();monkOpts.add("ဆရာတော်အားလုံး");for(Monk m:monks)monkOpts.add(m.name);
        initSpinner(monkSpinner,monkOpts,monkFilter,which->{monkFilter=which;filterTracks();});
        center.addView(monkSpinner,lp(-1,48));center.addView(gap(8));
        categorySpinner=spinner();initSpinner(categorySpinner,Arrays.asList(categoryNames),categoryFilter,which->{categoryFilter=which;filterTracks();});
        center.addView(categorySpinner,lp(-1,48));center.addView(gap(10));
        radioPanel=vertical();center.addView(radioPanel);
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int count,int after){};
            public void onTextChanged(CharSequence s,int st,int before,int count){
                query=s.toString();
                if(pendingSearch!=null)searchHandler.removeCallbacks(pendingSearch);
                pendingSearch=()->{pendingSearch=null;filterTracks();};
                searchHandler.postDelayed(pendingSearch,180);
            }
            public void afterTextChanged(Editable e){}
        });
        filterTracks();
    }
    private interface OnSelected{void next(int selected);}
    private Spinner spinner(){Spinner s=new Spinner(this);s.setBackground(bg(CARD,12,GOLD));return s;}
    private void initSpinner(Spinner s,List<String> options,int value,OnSelected f){
        ArrayAdapter<String> a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,options){
            public View getView(int position,View convertView,ViewGroup parent){TextView v=(TextView)super.getView(position,convertView,parent);v.setTextColor(GOLD);v.setTextSize(13);v.setPadding(dp(11),dp(9),dp(7),dp(9));return v;}
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);
        s.setSelection(Math.max(0,Math.min(value,options.size()-1)));
        s.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int i,long id){f.next(i);}
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
    }
    private String group(String title){
        if(title.contains("ပဋ္ဌာန်း")||title.toLowerCase(Locale.ROOT).contains("pahtan"))return "ပဋ္ဌာန်း";
        if(title.contains("ဝိပဿနာ")||title.contains("ပဋိစ္စ")||title.toLowerCase(Locale.ROOT).contains("vipassana"))return "ဝိပဿနာ";
        if(title.contains("ပါဠိ")||title.contains("ပရိတ်")||title.contains("သုတ်"))return "ပါဠိ / ပရိတ်";
        return "အခြား";
    }
    private void filterTracks(){
        if(radioPanel==null||!"Radio".equals(tab))return;
        visible.clear();String q=query.trim().toLowerCase(Locale.ROOT);
        ArrayList<Track> snapshot; synchronized(records){snapshot=new ArrayList<>(records.values());}
        for(Track t:snapshot){
            if(monkFilter>0 && (monkFilter>monks.size()||t.monkId!=monks.get(monkFilter-1).id))continue;
            if(categoryFilter>0&&!categoryNames[categoryFilter].equals(t.category))continue;
            if(!q.isEmpty()&&!(t.title+" "+t.monk).toLowerCase(Locale.ROOT).contains(q))continue;
            visible.add(t);
        }
        if(stats!=null)stats.setText("တရားတော် "+records.size()+" ခု • ရွေးထား "+visible.size()+" ခု • ဆရာတော် "+monks.size()+" ပါး");
        radioPanel.removeAllViews();
        if(visible.isEmpty()){
            TextView m=text("ရှာဖွေမှုမတွေ့ပါ။ MP3 Sync လုပ်၍ စာရင်းထပ်ရယူပါ။",13,MUTED,false);radioPanel.addView(m);return;
        }
        // Use native ListView with bounded height so this scrolling screen stays responsive for thousands of items.
        trackList=new ListView(this);trackList.setBackgroundColor(BG);trackList.setDividerHeight(dp(5));trackList.setCacheColorHint(BG);
        trackList.setAdapter(new BaseAdapter(){
            public int getCount(){return visible.size();}
            public Object getItem(int position){return visible.get(position);}
            public long getItemId(int position){return position;}
            public View getView(int position,View convert,ViewGroup parent){
                LinearLayout row;
                TextView title,sub;
                if(convert instanceof LinearLayout && ((LinearLayout)convert).getChildCount()==2){
                    row=(LinearLayout)convert;
                    title=(TextView)row.getChildAt(0);
                    sub=(TextView)row.getChildAt(1);
                }else{
                    row=vertical();row.setPadding(dp(12),dp(8),dp(12),dp(8));row.setBackground(bg(CARD,10,0));
                    title=text("",14,TEXT,true);title.setMaxLines(2);row.addView(title);
                    sub=text("",11,MUTED,false);sub.setMaxLines(1);row.addView(sub);
                }
                Track t=visible.get(position);
                title.setText("▶   "+t.title);
                sub.setText(t.monk+" · "+t.category);
                return row;
            }
        });
        trackList.setOnItemClickListener((p,v,pos,id)->trackDialog(visible.get(pos)));
        // 8 visible rows at a time; list recycling keeps 1,000+ items fast.
        radioPanel.addView(trackList,lp(-1,405));
        radioPanel.addView(gap(8));
        TextView all=text("ခေါင်းစဉ်တစ်ခုကို နှိပ်ပြီး ▶ နားထောင်နိုင်သည်။",11,MUTED,false);radioPanel.addView(all);
    }
    private void trackDialog(Track t){
        new AlertDialog.Builder(this).setTitle(t.title).setMessage(t.monk+"\n"+t.category+"\n\nDhammaDownload မှ Audio Stream ဖွင့်မည်။")
            .setPositiveButton("▶ နားထောင်ရန်",(d,w)->play(t))
            .setNeutralButton("မူရင်း Link",(d,w)->openUrl(t.url))
            .setNegativeButton("ပယ်ဖျက်",null).show();
    }
    private void openUrl(String url){
        try{Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse(url));startActivity(i);}
        catch(Exception e){Toast.makeText(this,"Browser မဖွင့်နိုင်ပါ",Toast.LENGTH_LONG).show();}
    }
    private void drawPlayer(){
        playerBar=horizontal();playerBar.setPadding(dp(14),dp(11),dp(14),dp(11));playerBar.setBackground(bg(CARD,12,GOLD));
        nowText=text(selected==null?"♫  DAMA RADIO · Online MP3":"♫  "+selected.title,12,GOLD,true);nowText.setMaxLines(1);
        playerBar.addView(nowText,new LinearLayout.LayoutParams(0,dp(42),1));
        TextView button=pill(player!=null&&playerReady&&player.isPlaying()?"❚❚":"▶",()->togglePlay());playerBar.addView(button);
        root.addView(playerBar,Math.min(1,root.getChildCount()),lp(-1,62));
    }
    private void togglePlay(){
        if(player==null){if(selected!=null)play(selected);else showTab("Radio");return;}
        if(!playerReady){Toast.makeText(this,"Audio ပြင်ဆင်နေသည်…",Toast.LENGTH_SHORT).show();return;}
        try{if(player.isPlaying())player.pause();else player.start();drawPlayerAgain();}catch(Exception e){showError("Player အမှား");}
    }
    private void drawPlayerAgain(){if(playerBar!=null&&playerBar.getParent()==root){root.removeView(playerBar);drawPlayer();}}
    private void play(Track t){
        selected=t;playerReady=false;release();nowText.setText("♫  Loading: "+t.title);
        try{
            player=new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setDataSource(this,Uri.parse(t.url));
            player.setOnPreparedListener(mp->{playerReady=true;try{mp.start();}catch(Exception ignored){}drawPlayerAgain();});
            player.setOnErrorListener((mp,what,extra)->{playerReady=false;main.post(()->showError("Streaming မရပါ — မူရင်း MP3 Link ကို ဖွင့်စမ်းနိုင်သည်။"));return true;});
            player.setOnCompletionListener(mp->drawPlayerAgain());
            player.prepareAsync();drawPlayerAgain();
        }catch(Exception ex){showError("Audio မဖွင့်နိုင်ပါ — မူရင်း Website တွင် စမ်းပါ။");}
    }
    private void showError(String message){
        new AlertDialog.Builder(this).setTitle("DAMA RADIO").setMessage(message)
          .setPositiveButton("မူရင်း MP3 ဖွင့်ရန်",(d,w)->{if(selected!=null)openUrl(selected.url);})
          .setNegativeButton("ပယ်ဖျက်",null).show();
    }
    private void release(){try{if(player!=null){player.reset();player.release();}}catch(Exception ignored){}player=null;playerReady=false;}
    private void drawBottomBar(){
        LinearLayout bar=horizontal();bar.setPadding(dp(2),dp(9),dp(2),dp(5));bar.setBackgroundColor(Color.rgb(8,34,34));
        String[] names={"Home","Radio","Library","TV","Worship"};String[] icons={"⌂","♫","▤","▶","✿"};
        for(int i=0;i<names.length;i++){
            final String target=names[i];LinearLayout item=vertical();item.setGravity(Gravity.CENTER);
            boolean active=tab.equals(target);
            TextView ic=text(icons[i],22,active?GOLD:MUTED,true);ic.setGravity(Gravity.CENTER);item.addView(ic);
            TextView tx=text(names[i],10,active?GOLD:MUTED,active);tx.setGravity(Gravity.CENTER);item.addView(tx);
            bar.addView(item,new LinearLayout.LayoutParams(0,dp(60),1));item.setOnClickListener(v->showTab(target));
        }
        root.addView(bar,lp(-1,64));
    }
    private void syncCatalog(){
        if(syncing)return;
        syncing=true;
        if(progress!=null)progress.setText("DhammaDownload ဆရာတော်စာမျက်နှာများ ရယူနေပါသည်…");
        io.execute(()->{
            int successful=0,failed=0,found=0;
            for(int i=0;i<monks.size();i++){
                Monk monk=monks.get(i);
                try{
                    String html=fetchHtml(monk.source);
                    List<Track> tracks=CatalogParser.parse(html,monk.source,monk.id,monk.name);
                    int prior=records.size();for(Track t:tracks){t.category=group(t.title);addTrack(t);}
                    found+=records.size()-prior;successful++;
                }catch(Exception ex){failed++;android.util.Log.w("DAMA","Failed catalog: "+monk.source,ex);}
                final int current=i+1,existing=records.size(),nfound=found,ok=successful,errors=failed;
                runOnUiThread(()->{
                    if(progress!=null&&"Radio".equals(tab))progress.setText("Index "+current+" / "+monks.size()+" · "+existing+" MP3 လင့်ခ် · စာမျက်နှာအောင်မြင် "+ok+" · Error "+errors);
                    if(stats!=null&&"Radio".equals(tab))stats.setText("စုစုပေါင်း "+existing+" MP3 လင့်ခ်");
                });
                try{Thread.sleep(1050);}catch(InterruptedException ex){Thread.currentThread().interrupt();break;}
            }
            saveCache();syncing=false;
            final int ok=successful,errors=failed,added=found;
            runOnUiThread(()->{
                if("Radio".equals(tab)){
                    filterTracks();
                    if(progress!=null)progress.setText("Sync ပြီးပါပြီ · "+records.size()+" MP3 Link · အသစ် "+added+" · Page Error "+errors);
                }
                Toast.makeText(this,"DAMA RADIO · "+records.size()+" တရားလင့်ခ် စာရင်း (Playback မစစ်ရသေး)",Toast.LENGTH_LONG).show();
            });
        });
    }
    private String fetchHtml(String urlText) throws IOException {
        URL url=new URL(urlText);String host=url.getHost().toLowerCase(Locale.ROOT);
        if(!host.equals("dhammadownload.com")&&!host.equals("www.dhammadownload.com"))throw new IOException("Untrusted catalog host");
        HttpURLConnection conn=(HttpURLConnection)url.openConnection();
        conn.setConnectTimeout(15000);conn.setReadTimeout(20000);conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent","DAMA-RADIO/1.0 (+Dhamma catalog indexing; source attribution)");
        try{
            if(conn.getResponseCode()!=200)throw new IOException("HTTP "+conn.getResponseCode());
            ByteArrayOutputStream buf=new ByteArrayOutputStream();
            try(InputStream in=conn.getInputStream()){
                byte[] b=new byte[8192];int n;
                while((n=in.read(b))!=-1){if(buf.size()+n>3_000_000)throw new IOException("HTML too large");buf.write(b,0,n);}
            }
            byte[] data=buf.toByteArray();String ct=conn.getContentType();String cs="UTF-8";
            if(ct!=null&&ct.toLowerCase(Locale.ROOT).contains("charset="))cs=ct.replaceAll("(?i).*charset=\\s*([A-Za-z0-9_-]+).*","$1");
            String html;
            try{html=new String(data,Charset.forName(cs));}catch(Exception e){html=new String(data,StandardCharsets.UTF_8);}
            return html;
        }finally{conn.disconnect();}
    }
    @Override public void onDestroy(){release();io.shutdownNow();super.onDestroy();}
}