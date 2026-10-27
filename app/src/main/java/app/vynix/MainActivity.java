package app.vynix;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(8, 9, 11), PANEL = Color.rgb(24, 25, 29), PANEL2 = Color.rgb(32, 33, 38);
    private static final int GOLD = Color.rgb(255, 205, 94), TEXT = Color.rgb(248, 247, 243), MUTED = Color.rgb(160, 162, 168);
    private final List<Track> tracks = new ArrayList<>();
    private ExoPlayer player;
    private LinearLayout root, content, mini;
    private TextView miniTitle, playButton;
    private String activeTab = "Главная";
    private int currentIndex = -1;\n    private final ExecutorService apiExecutor = Executors.newSingleThreadExecutor();\n    private String accountId;\n    private String accountName = "VYNIX Listener";\n    private String accountStatus = "Подключаем аккаунт…";\n    private static final String API = "https://vynix-api.onrender.com";
    private ActivityResultLauncher<String[]> audioPicker;

    private static class Track {
        Uri uri; String title;
        Track(Uri u, String t) { uri = u; title = t; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) { refreshMini(); }
            @Override public void onMediaItemTransition(MediaItem item, int reason) { refreshMini(); }
        });
        audioPicker = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
            if (uris == null) return;
            for (Uri uri : uris) {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                String name = "Без названия";
                try (android.database.Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst()) name = c.getString(0);
                } catch (Exception ignored) {}
                name = name.lastIndexOf('.') > 0 ? name.substring(0, name.lastIndexOf('.')) : name;
                boolean exists = false; for (Track t : tracks) if (t.uri.equals(uri)) exists = true;
                if (!exists) tracks.add(new Track(uri, name));
            }
            if (currentIndex < 0 && !tracks.isEmpty()) currentIndex = 0;
            render(); syncLibrary(); Toast.makeText(this, "Добавлено: " + uris.size(), Toast.LENGTH_SHORT).show();
        });
        buildShell();
        render();
    }

    private void buildShell() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        setContentView(root);
        FrameLayout top = new FrameLayout(this);
        TextView brand = label("VYNIX", 15, GOLD, true); brand.setLetterSpacing(.32f);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, dp(52), Gravity.TOP|Gravity.START);
        bp.leftMargin = dp(22); brand.setGravity(Gravity.CENTER_VERTICAL); top.addView(brand, bp);
        TextView add = label("+", 28, GOLD, false); add.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP|Gravity.END); ap.rightMargin=dp(12); top.addView(add, ap); add.setOnClickListener(v -> chooseAudio());
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(52)));
        ScrollView scroller = new ScrollView(this); scroller.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(18), dp(6), dp(18), dp(18));
        scroller.addView(content); root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        mini = new LinearLayout(this); mini.setGravity(Gravity.CENTER_VERTICAL); mini.setPadding(dp(12), dp(8), dp(8), dp(8)); mini.setBackground(round(PANEL2, 24, GOLD, 1));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, dp(66)); mp.setMargins(dp(12), dp(5), dp(12), dp(8)); root.addView(mini, mp);
        TextView logo = label("♫", 25, GOLD, true); logo.setGravity(Gravity.CENTER); logo.setBackground(round(BG, 14, 0, 0)); mini.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        miniTitle = label("Выбери свою музыку", 13, TEXT, true); LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL); info.setPadding(dp(10),0,0,0); info.addView(miniTitle); TextView sub=label("VYNIX • локальная библиотека",10,MUTED,false); info.addView(sub); mini.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        playButton=label("▶",22,GOLD,true); playButton.setGravity(Gravity.CENTER); mini.addView(playButton,new LinearLayout.LayoutParams(dp(46),dp(46)));
        playButton.setOnClickListener(v -> togglePlayback()); mini.setOnClickListener(v -> showPlayer());
        LinearLayout nav = new LinearLayout(this); nav.setGravity(Gravity.CENTER); nav.setPadding(dp(5),dp(4),dp(5),dp(4)); nav.setBackground(round(PANEL, 30, 0, 0));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, dp(62)); np.setMargins(dp(12),0,dp(12),dp(12)); root.addView(nav,np);
        String[][] tabs={{"⌂","Главная"},{"▤","Медиатека"},{"⌕","Поиск"},{"◉","Радио"},{"●","Профиль"}};
        for (String[] t:tabs) {
            LinearLayout item=new LinearLayout(this); item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER); item.setPadding(0,dp(5),0,dp(3));
            TextView ico=label(t[0],21,activeTab.equals(t[1])?GOLD:MUTED,true); ico.setGravity(Gravity.CENTER); TextView txt=label(t[1],9,activeTab.equals(t[1])?GOLD:MUTED,false); txt.setGravity(Gravity.CENTER);
            item.addView(ico); item.addView(txt); nav.addView(item,new LinearLayout.LayoutParams(0,-1,1));
            item.setOnClickListener(v->{activeTab=t[1];render();buildNavSelection();});
        }
        buildNavSelection();
    }

    private LinearLayout navBar() { return (LinearLayout) root.getChildAt(root.getChildCount()-1); }
    private void buildNavSelection() {
        LinearLayout nav=navBar();
        String[][] tabs={{"⌂","Главная"},{"▤","Медиатека"},{"⌕","Поиск"},{"◉","Радио"},{"●","Профиль"}};
        for(int i=0;i<nav.getChildCount();i++){LinearLayout item=(LinearLayout)nav.getChildAt(i); boolean sel=tabs[i][1].equals(activeTab); ((TextView)item.getChildAt(0)).setTextColor(sel?GOLD:MUTED); ((TextView)item.getChildAt(1)).setTextColor(sel?GOLD:MUTED); item.setBackground(sel?round(0x332A2416,20,0,0):null);}
    }

    private void render() {
        if(content==null)return; content.removeAllViews();
        if(activeTab.equals("Главная")) {
            addText("Твоя волна",29,TEXT,true); addText("Музыка под твоё настроение",14,MUTED,false); gap(18);
            LinearLayout hero=panel(true); addTextTo(hero,"VYNIX RADIO  /  01",11,GOLD,true); addTextTo(hero,"Слушай своё.",26,TEXT,true); addTextTo(hero,tracks.isEmpty()?"Добавь музыку с устройства":tracks.size()+" треков в твоей библиотеке",13,MUTED,false);
            TextView action=button(tracks.isEmpty()?"Добавить музыку":"Продолжить слушать"); hero.addView(action); action.setOnClickListener(v->{if(tracks.isEmpty())chooseAudio();else showPlayer();}); content.addView(hero); gap(22);
            sectionTitle("Недавно добавлено","Все",()->{activeTab="Медиатека";render();buildNavSelection();});
            if(tracks.isEmpty()) empty("Здесь пока тихо","Добавь локальные аудиофайлы, чтобы начать."); else for(int i=tracks.size()-1;i>=0;i--)trackRow(tracks.get(i),i);
        } else if(activeTab.equals("Медиатека")) {
            addText("Медиатека",29,TEXT,true); gap(14);
            LinearLayout cards=new LinearLayout(this); cards.setOrientation(LinearLayout.HORIZONTAL);
            cards.addView(smallCard("♡","Избранное","Сохраняй любимые треки"),new LinearLayout.LayoutParams(0,dp(112),1)); View spacer=new View(this); cards.addView(spacer,new LinearLayout.LayoutParams(dp(10),1)); cards.addView(smallCard("↓","Локальные",tracks.size()+" треков"),new LinearLayout.LayoutParams(0,dp(112),1)); content.addView(cards); gap(20);
            sectionTitle("Треки","+ Добавить",this::chooseAudio);
            if(tracks.isEmpty())empty("Библиотека пуста","Выбери музыку на устройстве."); else for(int i=0;i<tracks.size();i++)trackRow(tracks.get(i),i);
        } else if(activeTab.equals("Поиск")) {
            addText("Поиск",29,TEXT,true); TextView hint=label("⌕   Поиск в локальной библиотеке",14,MUTED,false); hint.setPadding(dp(15),dp(15),dp(15),dp(15)); hint.setBackground(round(PANEL,18,0,0)); content.addView(hint); gap(16);
            for(int i=0;i<tracks.size();i++)trackRow(tracks.get(i),i);
            if(tracks.isEmpty())empty("Нечего искать","Добавь музыку — поиск по библиотеке появится здесь.");
        } else if(activeTab.equals("Радио")) {
            addText("Радио",29,TEXT,true); gap(12); LinearLayout card=panel(true); addTextTo(card,"VYNIX RADIO",12,GOLD,true); addTextTo(card,"Твой поток. Твои правила.",22,TEXT,true); addTextTo(card,"Персональное онлайн-радио появится после подключения источников.",13,MUTED,false); content.addView(card); gap(16); empty("Радио готовится","Сейчас можно слушать локальные аудиофайлы.");
        } else {
            addText("Профиль",29,TEXT,true); gap(12); LinearLayout profile=panel(false); addTextTo(profile,"V",42,GOLD,true); addTextTo(profile,accountName,20,TEXT,true); addTextTo(profile,"Аккаунт VYNIX • "+accountStatus,13,MUTED,false); addTextTo(profile,"ID: "+(accountId==null?"создаётся":accountId),10,MUTED,false);
            TextView rename=button("Изменить имя профиля"); profile.addView(rename); rename.setOnClickListener(v->{android.widget.EditText input=new android.widget.EditText(this);input.setSingleLine(true);input.setText(accountName);input.setTextColor(TEXT);input.setHintTextColor(MUTED);new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Имя аккаунта").setView(input).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",(dialog,which)->{String n=input.getText().toString().trim();if(!n.isEmpty()){accountName=n;getSharedPreferences("vynix_account",MODE_PRIVATE).edit().putString("display_name",n).apply();render();saveAccountName(n);}}).show();});
            content.addView(profile); gap(12);
            for(String s:new String[]{"Внешний вид","Плеер и звук","Эквалайзер","Уведомления","Интеграции","О приложении"}){TextView row=label("◈    "+s+"     ›",15,TEXT,false);row.setPadding(dp(15),dp(17),dp(15),dp(17));row.setBackground(round(PANEL,17,0,0));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(8);content.addView(row,p);}
        }
    }
    private void sectionTitle(String title,String action,Runnable r){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);TextView a=label(title,19,TEXT,true);row.addView(a,new LinearLayout.LayoutParams(0,-2,1));TextView b=label(action,12,GOLD,true);row.addView(b);b.setOnClickListener(v->r.run());content.addView(row);gap(10);}
    private void trackRow(Track t,int index){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(10),dp(9),dp(8),dp(9));row.setBackground(round(PANEL,17,0,0));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(7);content.addView(row,p);TextView art=label("♫",22,GOLD,true);art.setGravity(Gravity.CENTER);art.setBackground(round(0xFF27251F,13,0,0));row.addView(art,new LinearLayout.LayoutParams(dp(45),dp(45)));LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.setPadding(dp(11),0,0,0);info.addView(label(t.title,14,TEXT,true));info.addView(label("Локальный файл",11,MUTED,false));row.addView(info,new LinearLayout.LayoutParams(0,-2,1));row.addView(label("⋮",22,MUTED,false));row.setOnClickListener(v->playTrack(index));}
    private LinearLayout smallCard(String icon,String title,String sub){LinearLayout c=panel(false);addTextTo(c,icon,25,GOLD,true);addTextTo(c,title,15,TEXT,true);addTextTo(c,sub,11,MUTED,false);return c;}
    private LinearLayout panel(boolean hero){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(18),dp(18),dp(18),dp(18));c.setBackground(round(hero?0xFF211F19:PANEL,24,hero?0xFF5A4927:0,hero?1:0));return c;}
    private void empty(String title,String body){LinearLayout c=panel(false);addTextTo(c,title,17,TEXT,true);addTextTo(c,body,13,MUTED,false);content.addView(c);}
    private TextView button(String text){TextView b=label(text,14,BG,true);b.setGravity(Gravity.CENTER);b.setPadding(dp(18),dp(13),dp(18),dp(13));b.setBackground(round(GOLD,18,0,0));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(14);b.setLayoutParams(p);return b;}
    private void addText(String text,int size,int color,boolean bold){TextView t=label(text,size,color,bold);content.addView(t);}
    private void addTextTo(LinearLayout target,String text,int size,int color,boolean bold){TextView t=label(text,size,color,bold);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(7);target.addView(t,p);}
    private TextView label(String text,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));return t;}
    private void gap(int h){View v=new View(this);content.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    private GradientDrawable round(int color,int radius,int stroke,int width){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(width>0)d.setStroke(dp(width),stroke);return d;}
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    private void chooseAudio(){audioPicker.launch(new String[]{"audio/*"});}
    private void playTrack(int index){if(index<0||index>=tracks.size())return;currentIndex=index;player.setMediaItem(MediaItem.fromUri(tracks.get(index).uri));player.prepare();player.play();refreshMini();showPlayer();}
    private void togglePlayback(){if(currentIndex<0){if(!tracks.isEmpty())playTrack(0);else chooseAudio();return;}if(player.isPlaying())player.pause();else player.play();refreshMini();}
    private void refreshMini(){if(miniTitle==null)return;if(currentIndex>=0&&currentIndex<tracks.size())miniTitle.setText(tracks.get(currentIndex).title);playButton.setText(player!=null&&player.isPlaying()?"Ⅱ":"▶");}
    private void showPlayer(){if(currentIndex<0){chooseAudio();return;}android.app.Dialog d=new android.app.Dialog(this);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(26),dp(24),dp(24));box.setBackground(round(PANEL,28,0,0));TextView close=label("⌄",24,MUTED,true);close.setGravity(Gravity.CENTER);box.addView(close);TextView art=label("♫",88,GOLD,true);art.setGravity(Gravity.CENTER);art.setBackground(round(0xFF211F19,28,0,0));LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(230));ap.setMargins(0,dp(12),0,dp(20));box.addView(art,ap);TextView title=label(tracks.get(currentIndex).title,22,TEXT,true);title.setGravity(Gravity.CENTER);box.addView(title);TextView artist=label("Локальная музыка",13,MUTED,false);artist.setGravity(Gravity.CENTER);box.addView(artist);TextView controls=label("  |◀          "+(player.isPlaying()?"Ⅱ":"▶")+"          ▶|  ",27,GOLD,true);controls.setGravity(Gravity.CENTER);controls.setPadding(0,dp(24),0,dp(24));box.addView(controls);controls.setOnClickListener(v->{togglePlayback();controls.setText("  |◀          "+(player.isPlaying()?"Ⅱ":"▶")+"          ▶|  ");});close.setOnClickListener(v->d.dismiss());d.setContentView(box);if(d.getWindow()!=null)d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);d.show();d.getWindow().setLayout(-1,-2);}

    private JSONObject apiRequest(String path, String method, JSONObject body) throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL(API+path).openConnection();
        conn.setRequestMethod(method); conn.setConnectTimeout(12000); conn.setReadTimeout(12000);
        conn.setRequestProperty("Accept","application/json");
        if(body!=null){conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream os=conn.getOutputStream()){os.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
        int code=conn.getResponseCode();
        java.io.InputStream stream=code>=200&&code<300?conn.getInputStream():conn.getErrorStream();
        StringBuilder out=new StringBuilder(); if(stream!=null)try(BufferedReader br=new BufferedReader(new InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8))){String line;while((line=br.readLine())!=null)out.append(line);}
        conn.disconnect(); if(code<200||code>=300)throw new java.io.IOException("HTTP "+code+" "+out);
        return out.length()==0?new JSONObject():new JSONObject(out.toString());
    }
    private void loadAccount(){
        apiExecutor.execute(()->{try{JSONObject p=apiRequest("/v1/profile/"+accountId,"GET",null);String n=p.optString("display_name","VYNIX Listener");runOnUiThread(()->{accountName=n;accountStatus="Синхронизирован";getSharedPreferences("vynix_account",MODE_PRIVATE).edit().putString("display_name",n).apply();if("Профиль".equals(activeTab))render();});syncLibrary();}catch(Exception e){runOnUiThread(()->{accountStatus="сервер недоступен";if("Профиль".equals(activeTab))render();});}});
    }
    private void saveAccountName(String name){
        apiExecutor.execute(()->{try{JSONObject body=new JSONObject();body.put("display_name",name);apiRequest("/v1/profile/"+accountId,"PUT",body);runOnUiThread(()->{accountStatus="Синхронизирован";if("Профиль".equals(activeTab))render();});}catch(Exception e){runOnUiThread(()->Toast.makeText(this,"Не удалось синхронизировать профиль. Проверь подключение сервера.",Toast.LENGTH_LONG).show());}});
    }
    private void syncLibrary(){
        apiExecutor.execute(()->{try{JSONArray items=new JSONArray();for(Track t:tracks){JSONObject item=new JSONObject();item.put("item_id",t.uri.toString());item.put("title",t.title);item.put("artist","");item.put("source","local");item.put("favorite",false);items.put(item);}JSONObject body=new JSONObject();body.put("items",items);apiRequest("/v1/library/"+accountId,"PUT",body);}catch(Exception ignored){}});
    }
\n    @Override protected void onDestroy(){apiExecutor.shutdownNow();if(player!=null)player.release();super.onDestroy();}
}
