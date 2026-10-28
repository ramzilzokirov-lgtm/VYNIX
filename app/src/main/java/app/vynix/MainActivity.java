package app.vynix;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.os.Build;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.os.Handler;
import android.os.Looper;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(24, 14, 31), PANEL = Color.rgb(48, 31, 57), PANEL2 = Color.rgb(65, 42, 75);
    private int GOLD = Color.rgb(220, 166, 255);
    private static final int TEXT = Color.rgb(255, 246, 255), MUTED = Color.rgb(202, 178, 211);
    private final List<Track> tracks = new ArrayList<>();
    private ExoPlayer player;
    private static final String PLAYBACK_CHANNEL = "vynix_playback";
    private static final int PLAYBACK_NOTIFICATION_ID = 4701;
    private android.media.session.MediaSession mediaSession;
    private NotificationManager notificationManager;
    private LinearLayout root, content, mini;
    private TextView miniTitle, playButton;
    private String activeTab = "Главная";
    private int currentIndex = -1;
    private final ExecutorService apiExecutor = Executors.newSingleThreadExecutor();
    private String accountId;
    private String accountName = "VYNIX Listener";
    private String accountStatus = "Подключаем аккаунт…";
    private EditText searchInput;
    private LinearLayout searchResults;
    private String lastSearch = "";
    private static final String API = "https://vynix-api.onrender.com";
    private ActivityResultLauncher<String[]> audioPicker;

    private final Handler visualizerHandler = new Handler(Looper.getMainLooper());
    private final Runnable visualizerTick = new Runnable() {
        @Override public void run() {
            if (content != null && !isFinishing()) {
                if (content.findViewWithTag("vynix_live_visualizer") != null) {
                    View v = content.findViewWithTag("vynix_live_visualizer");
                    if (v != null) v.invalidate();
                }
                visualizerHandler.postDelayed(this, 40);
            }
        }
    };

    private class LiveMusicBackdrop extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final long started = System.currentTimeMillis();
        LiveMusicBackdrop() { super(MainActivity.this); setTag("vynix_live_visualizer"); setLayerType(View.LAYER_TYPE_HARDWARE, null); }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float w=getWidth(), h=getHeight();
            if(w<=0||h<=0)return;
            float t=(System.currentTimeMillis()-started)/1000f;
            boolean active=player!=null&&player.isPlaying();
            paint.setShader(new android.graphics.LinearGradient(0,0,w,h,
                new int[]{0xFF41234F,0xFFB65B9F,0xFF35204A},null,android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRoundRect(0,0,w,h,dp(28),dp(28),paint);
            paint.setShader(null);
            for(int i=0;i<4;i++){
                float phase=t*(active?0.75f:0.18f)+i*1.65f;
                float radius=dp(34)+(float)Math.sin(phase)*dp(active?19:5);
                int color=new int[]{0x55FF83D0,0x556FA8FF,0x66D8A0FF,0x44FFD08A}[i];
                paint.setColor(color);
                canvas.drawCircle(w*(0.18f+i*0.23f)+(float)Math.sin(phase*.7f)*dp(active?20:5),
                    h*.46f+(float)Math.cos(phase)*dp(active?18:4),radius,paint);
            }
            paint.setColor(0x22FFFFFF); canvas.drawRoundRect(0,0,w,h,dp(28),dp(28),paint);
            int bars=38; float gap=w/(bars*1.65f); float barW=Math.max(dp(2),gap*.65f);
            for(int i=0;i<bars;i++){
                float wave=(float)Math.abs(Math.sin(i*.43+t*(active?4.2f:.45f))*(.35+.65*Math.abs(Math.sin(i*.17-t*(active?1.7f:.12f)))));
                float bh=dp(5)+(h*.48f*wave)*(active?1f:.22f);
                paint.setColor(i%4==0?0xFFFFB7E5:0xCCFFFFFF);
                float x=i*gap+dp(8);
                canvas.drawRoundRect(x,(h-bh)/2f,x+barW,(h+bh)/2f,barW,barW,paint);
            }
            if(active) postInvalidateDelayed(40);
        }
    }

    private static class Track {
        Uri uri; String title;
        Track(Uri u, String t) { uri = u; title = t; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        applySavedAccent();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        notificationManager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26) notificationManager.createNotificationChannel(new NotificationChannel(PLAYBACK_CHANNEL,"VYNIX — воспроизведение",NotificationManager.IMPORTANCE_LOW));
        mediaSession=new android.media.session.MediaSession(this,"VYNIX");
        mediaSession.setFlags(android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS|android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new android.media.session.MediaSession.Callback(){
            @Override public void onPlay(){if(player!=null)player.play();}
            @Override public void onPause(){if(player!=null)player.pause();}
            @Override public void onSkipToNext(){if(player!=null&&player.hasNextMediaItem())player.seekToNextMediaItem();else if(currentIndex+1<tracks.size())playTrack(currentIndex+1);}
            @Override public void onSkipToPrevious(){if(player!=null&&player.hasPreviousMediaItem())player.seekToPreviousMediaItem();else if(currentIndex>0)playTrack(currentIndex-1);}
            @Override public void onSeekTo(long pos){if(player!=null)player.seekTo(pos);}
        });
        mediaSession.setActive(true);
        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) { refreshMini(); syncPlaybackButtons(); refreshMediaNotification(); }
            @Override public void onPlaybackStateChanged(int playbackState) { refreshMini(); syncPlaybackButtons(); refreshMediaNotification(); }
            @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) { refreshMini(); syncPlaybackButtons(); refreshMediaNotification(); }
            @Override public void onMediaItemTransition(MediaItem item, int reason) { refreshMini(); syncPlaybackButtons(); refreshMediaNotification(); }
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
            saveLocalTracks(); render(); syncLibrary(); Toast.makeText(this, "Добавлено: " + uris.size(), Toast.LENGTH_SHORT).show();
        });
        android.content.SharedPreferences accountPrefs = getSharedPreferences("vynix_account", MODE_PRIVATE);
        accountId = accountPrefs.getString("account_id", null);
        if (accountId == null || accountId.trim().isEmpty()) {
            accountId = UUID.randomUUID().toString();
            accountPrefs.edit().putString("account_id", accountId).apply();
        }
        accountName = accountPrefs.getString("display_name", "VYNIX Listener");
        loadLocalTracks();

        buildShell();
        render();
        loadAccount();
    }

    private void applySavedAccent() {
        int accent=getSharedPreferences("vynix_settings",MODE_PRIVATE).getInt("accent",1);
        if(accent==0)GOLD=Color.rgb(232,190,92);
        else if(accent==2)GOLD=Color.rgb(100,224,174);
        else GOLD=Color.rgb(184,156,255);
    }

    private void buildShell() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG); root.setClipToPadding(false);
        setContentView(root);
        FrameLayout top = new FrameLayout(this);
        TextView brand = label("VYNIX ✦", 18, TEXT, true); brand.setLetterSpacing(.22f);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, dp(58), Gravity.TOP|Gravity.START);
        bp.leftMargin = dp(22); brand.setGravity(Gravity.CENTER_VERTICAL); top.addView(brand, bp);
        TextView add = label("＋", 25, Color.WHITE, false); add.setGravity(Gravity.CENTER); add.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x44FFFFFF),round(0xFFFF78C8,17,0x88FFD5F3,1),null)); add.setOnClickListener(v->{v.animate().rotation(90f).setDuration(180).withEndAction(()->v.animate().rotation(0f).setDuration(160).start()).start();chooseAudio();});
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP|Gravity.END); ap.rightMargin=dp(12); top.addView(add, ap); add.setOnClickListener(v -> chooseAudio());
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(50)));
        ScrollView scroller = new ScrollView(this); scroller.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(18), dp(2), dp(18), dp(22));
        scroller.addView(content); root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        mini = new LinearLayout(this); mini.setGravity(Gravity.CENTER_VERTICAL); mini.setPadding(dp(12), dp(8), dp(8), dp(8)); mini.setBackground(round(0xEE6D4C7D, 38, 0x88E4B7FF, 1));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, dp(66)); mp.setMargins(dp(12), dp(5), dp(12), dp(8)); mini.setVisibility(View.GONE); root.addView(mini, mp);
        TextView logo = label("♫", 25, GOLD, true); logo.setGravity(Gravity.CENTER); logo.setBackground(round(BG, 14, 0, 0)); mini.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        miniTitle = label("Выбери свою музыку", 13, TEXT, true); miniTitle.setSingleLine(true); miniTitle.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE); miniTitle.setMarqueeRepeatLimit(-1); LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL); info.setPadding(dp(10),0,0,0); info.addView(miniTitle); TextView sub=label("VYNIX • локальная библиотека",10,MUTED,false); info.addView(sub); mini.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        playButton=label("▶",22,GOLD,true); playButton.setGravity(Gravity.CENTER); mini.addView(playButton,new LinearLayout.LayoutParams(dp(46),dp(46)));
        playButton.setOnClickListener(v -> togglePlayback()); mini.setOnClickListener(v -> showPlayer()); mini.setElevation(dp(8));
        LinearLayout nav = new LinearLayout(this); nav.setGravity(Gravity.CENTER); nav.setPadding(dp(5),dp(4),dp(5),dp(4)); nav.setBackground(round(0xF02B242F, 34, 0x22E0B9F5, 1)); nav.setElevation(dp(8));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, dp(66)); np.setMargins(dp(12),0,dp(12),dp(12)); root.addView(nav,np);
        String[][] tabs={{"⌂","Главная"},{"▤","Медиатека"},{"⌕","Поиск"},{"●","Профиль"}};
        for (String[] t:tabs) {
            LinearLayout item=new LinearLayout(this); item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER); item.setPadding(0,dp(5),0,dp(3));
            TextView ico=label(t[0],21,activeTab.equals(t[1])?GOLD:MUTED,true); ico.setGravity(Gravity.CENTER); TextView txt=label(t[1],9,activeTab.equals(t[1])?GOLD:MUTED,false); txt.setGravity(Gravity.CENTER);
            item.addView(ico); item.addView(txt); item.setForeground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22FFFFFF),null,round(0x55FFFFFF,28,0,0))); nav.addView(item,new LinearLayout.LayoutParams(0,-1,1));
            item.setOnClickListener(v->{if(activeTab.equals(t[1])){v.animate().scaleX(.94f).scaleY(.94f).setDuration(80).withEndAction(()->v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()).start();return;}activeTab=t[1];render();buildNavSelection();});
        }
        buildNavSelection();
    }

    private LinearLayout navBar() { return (LinearLayout) root.getChildAt(root.getChildCount()-1); }
    private void buildNavSelection() {
        LinearLayout nav=navBar();
        String[][] tabs={{"⌂","Главная"},{"▤","Медиатека"},{"⌕","Поиск"},{"●","Профиль"}};
        for(int i=0;i<nav.getChildCount();i++){LinearLayout item=(LinearLayout)nav.getChildAt(i); boolean sel=tabs[i][1].equals(activeTab); ((TextView)item.getChildAt(0)).setTextColor(sel?GOLD:MUTED); ((TextView)item.getChildAt(1)).setTextColor(sel?GOLD:MUTED); item.setBackground(sel?round(0x332F2944,19,0x33B89CFF,1):null); item.animate().cancel(); item.setScaleX(sel?0.96f:1f); item.setScaleY(sel?0.96f:1f); item.animate().scaleX(1f).scaleY(1f).setDuration(sel?220:140).setInterpolator(new DecelerateInterpolator(1.5f)).start();}
    }

    private void render() {
        if(content==null)return;
        searchResults = null;
        searchInput = null;
        content.removeAllViews();
        if(activeTab.equals("Главная")) {
            addText("Моя", 38, TEXT, true);
            addText("волна", 38, TEXT, true);
            TextView bell=label("♧",25,GOLD,true);bell.setGravity(Gravity.CENTER);LinearLayout.LayoutParams bellp=new LinearLayout.LayoutParams(dp(42),dp(42));bellp.gravity=Gravity.END;bellp.topMargin=-dp(86);content.addView(bell,bellp);bell.setOnClickListener(v->showProfileOption("Уведомления"));
            gap(12);
            LiveMusicBackdrop liveBackdrop=new LiveMusicBackdrop();
            LinearLayout.LayoutParams liveParams=new LinearLayout.LayoutParams(-1,dp(116));liveParams.bottomMargin=dp(14);content.addView(liveBackdrop,liveParams);
            TextView liveCaption=label("✦  "+(player!=null&&player.isPlaying()?"СЕЙЧАС ЗВУЧИТ":"ТВОЯ ВОЛНА"),11,0xFFFFC7F4,true);liveCaption.setLetterSpacing(.12f);liveCaption.setPadding(dp(4),0,0,dp(8));content.addView(liveCaption);
            TextView welcome=label("ТВОЙ МУЗЫКАЛЬНЫЙ МИР",10,0xFFE5B9FF,true);welcome.setLetterSpacing(.16f);LinearLayout.LayoutParams welp=new LinearLayout.LayoutParams(-1,dp(22));welp.bottomMargin=dp(2);content.addView(welcome,welp);LinearLayout collage=new LinearLayout(this);collage.setGravity(Gravity.CENTER_VERTICAL);collage.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout left=new LinearLayout(this);left.setOrientation(LinearLayout.VERTICAL);left.setGravity(Gravity.CENTER_VERTICAL);
            View coverA=albumTile("the autocollants","apple vines",0xFFB55DAD,0xFF3B1B55,"♫");left.addView(coverA,new LinearLayout.LayoutParams(dp(148),dp(184)));
            TextView mood=label("✦  Твоя музыкальная волна",13,0xFFFFC7F4,true);LinearLayout.LayoutParams moodp=new LinearLayout.LayoutParams(-1,dp(40));moodp.topMargin=dp(12);left.addView(mood,moodp);
            collage.addView(left,new LinearLayout.LayoutParams(0,-2,1.1f));
            LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);right.setGravity(Gravity.CENTER_HORIZONTAL);
            right.addView(albumTile("the autocollants","",0xFFFFA6C9,0xFF7D3F7B,"♫"),new LinearLayout.LayoutParams(dp(104),dp(104)));
            View gapr=new View(this);right.addView(gapr,new LinearLayout.LayoutParams(1,dp(28)));
            right.addView(albumTile("VYNIX","after dark",0xFFFFC66D,0xFF9C426E,"✦"),new LinearLayout.LayoutParams(dp(92),dp(92)));
            TextView bigPlay=label(player!=null&&player.isPlaying()?"Ⅱ":"▶",34,Color.WHITE,true);bigPlay.setGravity(Gravity.CENTER);bigPlay.setTextColor(0xFF3A1537);bigPlay.setBackground(round(0xFFFF78C8,100,0,0));bigPlay.setElevation(dp(10));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(106),dp(106));bp.topMargin=dp(8);right.addView(bigPlay,bp);bigPlay.setOnClickListener(v->{if(tracks.isEmpty())chooseAudio();else togglePlayback();bigPlay.setText(player!=null&&player.isPlaying()?"Ⅱ":"▶");});
            collage.addView(right,new LinearLayout.LayoutParams(0,-2,0.9f));content.addView(collage);gap(18);
            LinearLayout quick=new LinearLayout(this);quick.setOrientation(LinearLayout.HORIZONTAL);
            quick.addView(shortcutCard("◴","Недавние",tracks.size()+" треков",0),new LinearLayout.LayoutParams(0,dp(76),1));
            View qgap=new View(this);quick.addView(qgap,new LinearLayout.LayoutParams(dp(8),1));
            quick.addView(shortcutCard("♥","Любимые","Скоро в медиатеке",1),new LinearLayout.LayoutParams(0,dp(76),1));content.addView(quick);gap(28);
            sectionTitle("Для вас","Обновить",()->{render();});
            LinearLayout recs=new LinearLayout(this);recs.setOrientation(LinearLayout.HORIZONTAL);
            recs.addView(albumTile("VYNIX","Ночной режим",0xFF30243B,0xFF825D91,"☾"),new LinearLayout.LayoutParams(0,dp(148),1));
            View rg=new View(this);recs.addView(rg,new LinearLayout.LayoutParams(dp(12),1));
            recs.addView(albumTile("LOCAL","Твоя коллекция",0xFF34333D,0xFF8E7B98,"♫"),new LinearLayout.LayoutParams(0,dp(148),1));
            content.addView(recs);gap(16);
            sectionTitle("Недавно добавлено","Все",()->{activeTab="Медиатека";render();buildNavSelection();});
            if(tracks.isEmpty()) empty("Здесь пока тихо","Добавь локальные аудиофайлы, чтобы начать."); else for(int i=tracks.size()-1;i>=0;i--)trackRow(tracks.get(i),i);
        } else if(activeTab.equals("Недавние")) {
            sectionTitle("Недавние",null,null);
            TextView recentSubtitle=label("Треки, которые доступны в этой сессии",13,MUTED,false);recentSubtitle.setPadding(0,dp(4),0,dp(12));content.addView(recentSubtitle);
            if(currentIndex>=0&&currentIndex<tracks.size()){sectionTitle("Продолжить слушать",null,null);trackRow(tracks.get(currentIndex),currentIndex);gap(12);}
            sectionTitle("Твоя музыка","Обновить",()->render());
            if(tracks.isEmpty())empty("Пока нет недавних треков","Найди музыку или добавь аудиофайл.");else for(int i=tracks.size()-1;i>=0;i--)trackRow(tracks.get(i),i);
        } else if(activeTab.equals("Медиатека")) {
            addText("Медиатека",30,TEXT,true); addText("ТВОЯ КОЛЛЕКЦИЯ",10,GOLD,true); gap(14);
            LinearLayout cards=new LinearLayout(this); cards.setOrientation(LinearLayout.HORIZONTAL);
            cards.addView(smallCard("♡","Избранное","Сохраняй любимые треки"),new LinearLayout.LayoutParams(0,dp(112),1)); View spacer=new View(this); cards.addView(spacer,new LinearLayout.LayoutParams(dp(10),1)); cards.addView(smallCard("↓","Локальные",tracks.size()+" треков"),new LinearLayout.LayoutParams(0,dp(112),1)); content.addView(cards); gap(20);
            sectionTitle("Треки","+ Добавить",this::chooseAudio);
            if(tracks.isEmpty())empty("Библиотека пуста","Выбери музыку на устройстве."); else for(int i=0;i<tracks.size();i++)trackRow(tracks.get(i),i);
        } else if(activeTab.equals("Поиск")) {
            addText("Поиск",30,TEXT,true);
            addText("Выбирай источник. Ищи музыку в одном месте.",13,MUTED,false); gap(12);
            LinearLayout searchBox = new LinearLayout(this); searchBox.setGravity(Gravity.CENTER_VERTICAL); searchBox.setPadding(dp(12),dp(4),dp(5),dp(4)); searchBox.setBackground(round(0xEE191821,20,0x44B89CFF,1));
            searchInput = new EditText(this); searchInput.setSingleLine(true); searchInput.setTextColor(TEXT); searchInput.setHintTextColor(MUTED); searchInput.setTextSize(15); searchInput.setHint("Песня, исполнитель, альбом"); searchInput.setBackgroundColor(Color.TRANSPARENT); searchInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            searchBox.addView(searchInput,new LinearLayout.LayoutParams(0,dp(48),1));
            TextView go=label("⌕",22,BG,true); go.setGravity(Gravity.CENTER); go.setPadding(dp(15),dp(8),dp(15),dp(8)); go.setBackground(round(GOLD,15,0,0)); searchBox.addView(go); content.addView(searchBox);
            go.setOnClickListener(v->runUnifiedSearch(searchInput.getText().toString().trim()));
            searchInput.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){runUnifiedSearch(searchInput.getText().toString().trim());return true;}return false;});
            gap(12);
            LinearLayout sourcePicker = new LinearLayout(this); sourcePicker.setGravity(Gravity.CENTER_VERTICAL); sourcePicker.setPadding(dp(12),dp(12),dp(12),dp(12)); sourcePicker.setBackground(round(0xAA1B1925,19,0x44B89CFF,1));
            TextView sourceIcon=label("◉",22,GOLD,true); sourceIcon.setGravity(Gravity.CENTER); sourceIcon.setBackground(round(0x33251F3A,14,0x22B89CFF,1)); sourcePicker.addView(sourceIcon); LinearLayout sourceText=new LinearLayout(this);sourceText.setOrientation(LinearLayout.VERTICAL);sourceText.setPadding(dp(10),0,0,0);sourceText.addView(label("Где искать музыку",14,TEXT,true));sourceText.addView(label("Выбрать сервис • 7 источников",11,MUTED,false));sourcePicker.addView(sourceText,new LinearLayout.LayoutParams(0,-2,1));sourcePicker.addView(label("⌄",20,GOLD,true));LinearLayout.LayoutParams sourceParams=new LinearLayout.LayoutParams(-1,-2);sourceParams.bottomMargin=dp(8);content.addView(sourcePicker,sourceParams);sourcePicker.setOnClickListener(v->showSourcePicker());
            addText("РЕЗУЛЬТАТЫ",10,GOLD,true);
            searchResults = new LinearLayout(this); searchResults.setOrientation(LinearLayout.VERTICAL); content.addView(searchResults);
            if(!lastSearch.isEmpty()){
                searchInput.setText(lastSearch);
                searchInput.post(() -> { if ("Поиск".equals(activeTab) && searchResults != null) runUnifiedSearch(lastSearch); });
            } else addSearchMessage("Результаты Deezer и Apple Music появятся здесь. Другие сервисы откроются отдельно.");
            if(!tracks.isEmpty()){gap(8);addText("ТВОЯ МУЗЫКА НА УСТРОЙСТВЕ",11,GOLD,true);for(int i=0;i<tracks.size();i++)trackRow(tracks.get(i),i);}
        } else if(activeTab.equals("Радио")) {
            addText("Радио",30,TEXT,true); addText("СЛУШАЙ В СВОЁМ РИТМЕ",10,GOLD,true); gap(12); LinearLayout card=panel(true); addTextTo(card,"VYNIX RADIO",12,GOLD,true); addTextTo(card,"Твой поток. Твои правила.",22,TEXT,true); addTextTo(card,"Персональное онлайн-радио появится после подключения источников.",13,MUTED,false); content.addView(card); gap(16); empty("Радио готовится","Сейчас можно слушать локальные аудиофайлы.");
        } else {
            addText("Профиль",30,TEXT,true); addText("ТВОЙ VYNIX",10,GOLD,true); gap(12); LinearLayout profile=panel(false); addTextTo(profile,"V",42,GOLD,true); addTextTo(profile,accountName,20,TEXT,true); addTextTo(profile,"Аккаунт VYNIX • "+accountStatus,13,MUTED,false); addTextTo(profile,"ID: "+(accountId==null?"создаётся":accountId),10,MUTED,false);
            TextView rename=button("Изменить имя профиля"); profile.addView(rename); rename.setOnClickListener(v->{android.widget.EditText input=new android.widget.EditText(this);input.setSingleLine(true);input.setText(accountName);input.setTextColor(TEXT);input.setHintTextColor(MUTED);new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Имя аккаунта").setView(input).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",(dialog,which)->{String n=input.getText().toString().trim();if(!n.isEmpty()){accountName=n;getSharedPreferences("vynix_account",MODE_PRIVATE).edit().putString("display_name",n).apply();render();saveAccountName(n);}}).show();});
            content.addView(profile); gap(12);
            for(String option:new String[]{"Внешний вид","Плеер и звук","Эквалайзер","Уведомления","Интеграции","О приложении"}){TextView row=label("◈    "+option+"     ›",15,TEXT,false);row.setPadding(dp(15),dp(17),dp(15),dp(17));row.setBackground(round(0xEE17161F,17,0x22B89CFF,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(8);content.addView(row,p);row.setOnClickListener(v->showProfileOption(option));}
        }
        animateScreenContent();
    }

    private void animateScreenContent() {
        if (content == null) return;
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            child.animate().cancel();
            child.setAlpha(0f);
            child.setTranslationY(dp(12));
            child.animate().alpha(1f).translationY(0f)
                    .setStartDelay(Math.min(i * 24L, 180L))
                    .setDuration(260L)
                    .setInterpolator(new DecelerateInterpolator(1.7f))
                    .start();
        }
    }
    private View albumTile(String title,String subtitle,int top,int bottom,String glyph) {
        LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setGravity(Gravity.BOTTOM);tile.setPadding(dp(10),dp(10),dp(10),dp(10));
        GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{top,bottom});bg.setCornerRadius(dp(22));tile.setBackground(bg);tile.setElevation(dp(5));
        TextView symbol=label(glyph,30,Color.WHITE,true);symbol.setGravity(Gravity.CENTER);tile.addView(symbol,new LinearLayout.LayoutParams(-1,0,1));
        TextView name=label(title,11,Color.WHITE,true);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);tile.addView(name);
        if(subtitle!=null&&!subtitle.isEmpty()){TextView sub=label(subtitle,9,0xFFE5D9E9,false);sub.setMaxLines(1);tile.addView(sub);}
        tile.setForeground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22FFFFFF),null,round(0x55FFFFFF,22,0,0)));
        tile.setOnClickListener(v->{v.animate().scaleX(.97f).scaleY(.97f).setDuration(90).withEndAction(()->v.animate().scaleX(1f).scaleY(1f).setDuration(170).start()).start();if(!tracks.isEmpty())showPlayer();else chooseAudio();});
        return tile;
    }

    private View shortcutCard(String icon,String title,String subtitle,int index) {
        LinearLayout card=new LinearLayout(this);card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(10),dp(8),dp(8),dp(8));card.setBackground(round(0xFF251F2B,22,0x443E3545,1));
        TextView glyph=label(icon,19,GOLD,true);glyph.setGravity(Gravity.CENTER);card.addView(glyph,new LinearLayout.LayoutParams(dp(30),dp(42)));
        LinearLayout texts=new LinearLayout(this);texts.setOrientation(LinearLayout.VERTICAL);texts.setPadding(dp(5),0,0,0);texts.addView(label(title,13,TEXT,true));texts.addView(label(subtitle,10,MUTED,false));card.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        card.setOnClickListener(v->{if(index==0){activeTab="Недавние";render();buildNavSelection();}else Toast.makeText(this,"Избранное появится вместе с сохранением любимых треков",Toast.LENGTH_SHORT).show();});
        return card;
    }

    private void gapInside(LinearLayout parent,int height) { View spacer=new View(this); parent.addView(spacer,new LinearLayout.LayoutParams(1,height)); }

    private void showSourcePicker() {
        final String[][] providers={
            {"Deezer","Открытый каталог • поиск внутри VYNIX","https://www.deezer.com/search/","D"},
            {"Apple Music","Каталог iTunes • поиск внутри VYNIX","https://music.apple.com/search?term=","A"},
            {"Spotify","Поиск в приложении или на сайте","https://open.spotify.com/search/","S"},
            {"SoundCloud","Поиск независимой музыки","https://soundcloud.com/search?q=","♫"},
            {"VK Музыка","Музыка и аудиозаписи VK","https://vk.com/audio?q=","VK"},
            {"YouTube Music","Клипы, альбомы и музыка","https://music.youtube.com/search?q=","▶"},
            {"Яндекс Музыка","Каталог Яндекс Музыки","https://music.yandex.ru/search?text=","Я"}
        };
        android.app.Dialog dialog=new android.app.Dialog(this);
        LinearLayout sheet=new LinearLayout(this);sheet.setOrientation(LinearLayout.VERTICAL);sheet.setPadding(dp(20),dp(10),dp(20),dp(18));
        sheet.setBackground(round(0xF015141E,30,0x44B89CFF,1));
        View handle=new View(this);handle.setBackground(round(0x88FFFFFF,5,0,0));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(42),dp(4));hp.gravity=Gravity.CENTER;hp.bottomMargin=dp(18);sheet.addView(handle,hp);
        sheet.addView(label("Источник музыки",23,TEXT,true));
        TextView subtitle=label("ВЫБЕРИ, ГДЕ ИСКАТЬ",10,MUTED,true);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=dp(5);sp.bottomMargin=dp(16);sheet.addView(subtitle,sp);
        ScrollView scroll=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);
        for(int i=0;i<providers.length;i++){
            final String[] provider=providers[i];
            LinearLayout tile=new LinearLayout(this);tile.setGravity(Gravity.CENTER_VERTICAL);tile.setPadding(dp(12),dp(11),dp(12),dp(11));
            tile.setBackground(round(i<2?0xCC282239:0x771C1B25,18,i<2?0x66B89CFF:0x22FFFFFF,1));
            LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);tp.bottomMargin=dp(8);list.addView(tile,tp);
            TextView logo=label(provider[3],19,GOLD,true);logo.setGravity(Gravity.CENTER);logo.setBackground(round(0x33211D32,14,0x33D9CCFF,1));tile.addView(logo,new LinearLayout.LayoutParams(dp(46),dp(46)));
            LinearLayout details=new LinearLayout(this);details.setOrientation(LinearLayout.VERTICAL);details.setPadding(dp(12),0,dp(4),0);details.addView(label(provider[0],15,TEXT,true));details.addView(label(provider[1],10,MUTED,false));tile.addView(details,new LinearLayout.LayoutParams(0,-2,1));
            tile.addView(label(i<2?"VYNIX":"↗",9,i<2?GOLD:MUTED,true));
            tile.setOnClickListener(v->{
                String q=searchInput==null?"":searchInput.getText().toString().trim();
                if(q.isEmpty()){dialog.dismiss();Toast.makeText(this,"Сначала введи песню или исполнителя",Toast.LENGTH_SHORT).show();return;}
                dialog.dismiss();
                if("Deezer".equals(provider[0])||"Apple Music".equals(provider[0]))runUnifiedSearch(q);
                else openProvider(provider[2],q);
            });
        }
        sheet.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView note=label("Часть каталогов открывается внутри VYNIX. Остальные сервисы используют свой поиск.",10,MUTED,false);note.setPadding(dp(3),dp(12),dp(3),0);sheet.addView(note);
        sheet.setElevation(dp(18)); dialog.setContentView(sheet);
        dialog.show();
        if(dialog.getWindow()!=null){
            android.view.Window w=dialog.getWindow();
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams a=w.getAttributes();a.dimAmount=.62f;
            if(android.os.Build.VERSION.SDK_INT>=31){
                a.setBlurBehindRadius(dp(28));
                a.flags|=android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            }
            w.setAttributes(a);
            w.setLayout(-1,Math.min(getResources().getDisplayMetrics().heightPixels-dp(90),dp(610)));
            w.setGravity(Gravity.BOTTOM);
        }
    }

    private void showProfileOption(String option) {
        android.content.SharedPreferences prefs=getSharedPreferences("vynix_settings",MODE_PRIVATE);
        if ("О приложении".equals(option)) {
            new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("VYNIX")
                .setMessage("VYNIX 1.2.6-beta\nМузыкальный плеер с локальной библиотекой и поиском по открытым каталогам.\n\nВоспроизведение сторонних сервисов зависит от их официальных приложений и доступных API.")
                .setPositiveButton("Понятно",null).show();
            return;
        }
        if ("Интеграции".equals(option)) {
            String[] names={"Spotify","SoundCloud","VK Музыка","YouTube Music","Apple Music","Deezer","Яндекс Музыка"};
            String[] urls={"https://open.spotify.com/","https://soundcloud.com/","https://vk.com/music","https://music.youtube.com/","https://music.apple.com/","https://www.deezer.com/","https://music.yandex.ru/"};
            new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Музыкальные сервисы").setItems(names,(dialog,which)->openProvider(urls[which],"")).show();
            return;
        }
        if ("Эквалайзер".equals(option)) {
            try {
                android.media.audiofx.Equalizer eq=new android.media.audiofx.Equalizer(0,player.getAudioSessionId());
                short bands=eq.getNumberOfBands();
                String[] labels=new String[bands];
                short[] range=eq.getBandLevelRange();
                for(short i=0;i<bands;i++) labels[i]=(eq.getCenterFreq(i)/1000)+" Hz";
                eq.release();
                new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Эквалайзер")
                    .setMessage("Доступность и настройки эквалайзера зависят от аудиодвижка устройства. Открой системные настройки звука, если они поддерживают эквалайзер.")
                    .setPositiveButton("Понятно",null).show();
            } catch(Exception e) {
                new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Эквалайзер")
                    .setMessage("На этом устройстве системный эквалайзер недоступен. Воспроизведение продолжит работать без изменений.")
                    .setPositiveButton("Понятно",null).show();
            }
            return;
        }
        if ("Внешний вид".equals(option)) {
            String[] choices={"Золотой","Лавандовый","Изумрудный"};
            int selected=prefs.getInt("accent",0);
            new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Акцентный цвет").setSingleChoiceItems(choices,selected,(dialog,which)->{
                prefs.edit().putInt("accent",which).apply();
                applySavedAccent();
                dialog.dismiss();
                render(); buildNavSelection();
            }).setNegativeButton("Отмена",null).show();
            return;
        }
        if ("Плеер и звук".equals(option)) {
            boolean autoplay=prefs.getBoolean("autoplay",false);
            new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Плеер и звук")
                .setMultiChoiceItems(new String[]{"Автоматически продолжать воспроизведение"},new boolean[]{autoplay},(dialog,which,checked)->prefs.edit().putBoolean("autoplay",checked).apply())
                .setPositiveButton("Готово",null).show();
            return;
        }
        if ("Уведомления".equals(option)) {
            boolean enabled=prefs.getBoolean("notifications",true);
            new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Уведомления")
                .setMessage("Управление уведомлениями плеера будет доступно после подключения системной медиасессии. Здесь можно сохранить предпочтение.")
                .setPositiveButton(enabled?"Отключить предпочтение":"Включить предпочтение",(dialog,which)->prefs.edit().putBoolean("notifications",!enabled).apply())
                .setNegativeButton("Отмена",null).show();
        }
    }

    private void openProvider(String base,String query){try{Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse(base+Uri.encode(query)));startActivity(i);}catch(Exception e){Toast.makeText(this,"Не удалось открыть источник",Toast.LENGTH_SHORT).show();}}
    private void addSearchMessage(String message){if(searchResults==null)return;TextView t=label(message,13,MUTED,false);t.setPadding(dp(14),dp(14),dp(14),dp(14));t.setBackground(round(PANEL,16,0,0));searchResults.addView(t);}
    private void runUnifiedSearch(String query){
        if(query==null||query.trim().isEmpty()){Toast.makeText(this,"Введи название песни или исполнителя",Toast.LENGTH_SHORT).show();return;}
        lastSearch=query.trim();if(searchResults!=null){searchResults.removeAllViews();addSearchMessage("Ищем «"+lastSearch+"» в открытых музыкальных каталогах…");}
        final String q=lastSearch;
        final LinearLayout targetResults=searchResults;
        apiExecutor.execute(()->{
            List<JSONObject> found=new ArrayList<>();List<String> sources=new ArrayList<>();
            try{JSONObject deezer=getJson("https://api.deezer.com/search?q="+Uri.encode(q));JSONArray a=deezer.optJSONArray("data");if(a!=null)for(int i=0;i<a.length()&&i<15;i++){JSONObject x=a.optJSONObject(i);if(x!=null){x.put("_source","Deezer");x.put("_url",x.optString("link","https://www.deezer.com/search/"+Uri.encode(q)));found.add(x);}}}catch(Exception ignored){}
            try{JSONObject itunes=getJson("https://itunes.apple.com/search?media=music&entity=song&limit=15&term="+Uri.encode(q));JSONArray a=itunes.optJSONArray("results");if(a!=null)for(int i=0;i<a.length()&&i<15;i++){JSONObject x=a.optJSONObject(i);if(x!=null){x.put("_source","Apple Music");x.put("_url",x.optString("trackViewUrl","https://music.apple.com/search?term="+Uri.encode(q)));found.add(x);}}}catch(Exception ignored){}
            try {
                JSONObject archive = getJson("https://archive.org/advancedsearch.php?q=" + Uri.encode("title:(" + q + ") AND mediatype:audio") + "&fl[]=identifier&fl[]=title&fl[]=creator&rows=8&page=1&output=json");
                JSONObject response = archive.optJSONObject("response");
                JSONArray docs = response == null ? null : response.optJSONArray("docs");
                if (docs != null) {
                    for (int i = 0; i < docs.length(); i++) {
                        JSONObject doc = docs.optJSONObject(i);
                        if (doc == null) continue;
                        String identifier = doc.optString("identifier", "");
                        if (identifier.isEmpty()) continue;
                        try {
                            JSONObject metadata = getJson("https://archive.org/metadata/" + Uri.encode(identifier));
                            JSONArray files = metadata.optJSONArray("files");
                            if (files == null) continue;
                            for (int k = 0; k < files.length(); k++) {
                                JSONObject file = files.optJSONObject(k);
                                if (file == null) continue;
                                String name = file.optString("name", "");
                                String lower = name.toLowerCase(java.util.Locale.US);
                                String format = file.optString("format", "").toLowerCase(java.util.Locale.US);
                                boolean audioName = lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".m4a");
                                boolean audioFormat = format.contains("mp3") || format.contains("ogg") || format.contains("aac") || format.contains("m4a") || format.contains("audio");
                                if (audioName && audioFormat) {
                                    JSONObject item = new JSONObject();
                                    String creator = doc.optString("creator", "");
                                    item.put("title", doc.optString("title", "Без названия"));
                                    item.put("artist", new JSONObject().put("name", creator));
                                    item.put("album", new JSONObject().put("title", "Internet Archive"));
                                    item.put("_source", "Internet Archive");
                                    item.put("_full_audio", true);
                                    item.put("_url", "https://archive.org/download/" + Uri.encode(identifier) + "/" + Uri.encode(name));
                                    found.add(item);
                                    break;
                                }
                            }
                        } catch (Exception ignored) { }
                    }
                }
            } catch (Exception ignored) { }
            runOnUiThread(()->{
                if(!"Поиск".equals(activeTab)||targetResults==null||searchResults!=targetResults||!q.equals(lastSearch))return;targetResults.removeAllViews();
                if(found.isEmpty()){addSearchMessage("Не удалось получить результаты из открытых каталогов. Проверь интернет или выбери сервис выше.");return;}
                TextView count=label("Найдено: "+found.size()+" • Deezer и Apple Music",11,MUTED,false);count.setPadding(0,0,0,dp(8));targetResults.addView(count);
                for(JSONObject x:found){
                    boolean deezer="Deezer".equals(x.optString("_source"));
                    boolean archiveTrack="Internet Archive".equals(x.optString("_source"));String title=archiveTrack?x.optString("title","Без названия"):deezer?x.optString("title","Без названия"):x.optString("trackName","Без названия");
                    String artist=archiveTrack?(x.optJSONObject("artist")!=null?x.optJSONObject("artist").optString("name",""):""):deezer?x.optJSONObject("artist")!=null?x.optJSONObject("artist").optString("name",""): "":x.optString("artistName","");
                    String album=archiveTrack?(x.optJSONObject("album")!=null?x.optJSONObject("album").optString("title",""):""):deezer?x.optJSONObject("album")!=null?x.optJSONObject("album").optString("title",""): "":x.optString("collectionName","");
                    String preview=archiveTrack?x.optString("_url",""):x.optString("previewUrl",deezer?x.optString("preview",""):"");
                    LinearLayout row=new LinearLayout(MainActivity.this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(10),dp(10),dp(10),dp(10));row.setBackground(round(PANEL,16,0,0));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.bottomMargin=dp(7);targetResults.addView(row,rp);
                    String artwork=deezer?(x.optJSONObject("album")!=null?x.optJSONObject("album").optString("cover_medium",""): ""):x.optString("artworkUrl100","");
                    ImageView icon=new ImageView(MainActivity.this);icon.setScaleType(ImageView.ScaleType.CENTER_CROP);icon.setBackground(round(0xFF302638,13,0x33B89CFF,1));icon.setClipToOutline(true);icon.setImageDrawable(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,new int[]{0xFF705174,0xFF29212F}));row.addView(icon,new LinearLayout.LayoutParams(dp(56),dp(56)));loadSearchArtwork(artwork,icon);
                    LinearLayout info=new LinearLayout(MainActivity.this);info.setOrientation(LinearLayout.VERTICAL);info.setPadding(dp(11),0,dp(6),0);info.addView(label(title,14,TEXT,true));info.addView(label(artist+(album.isEmpty()?"":" • "+album),11,MUTED,false));info.addView(label(x.optString("_source")+" • "+(preview.isEmpty()?"Прослушивание недоступно":archiveTrack?"▶ Полный трек":"▶ Превью в VYNIX"),10,GOLD,false));row.addView(info,new LinearLayout.LayoutParams(0,-2,1));TextView play=label(preview.isEmpty()?"—":"▶",19,GOLD,true);row.addView(play);row.setAlpha(preview.isEmpty()?.72f:1f);row.setOnClickListener(v->{if(preview.isEmpty()){Toast.makeText(MainActivity.this,"Для этого результата нет доступного аудиофрагмента. Попробуй другую песню.",Toast.LENGTH_LONG).show();return;}Track onlineTrack=new Track(Uri.parse(preview),title+(artist.isEmpty()?"":" — "+artist));tracks.add(onlineTrack);playTrack(tracks.size()-1);});
                }
            });
        });
    }
    private void loadSearchArtwork(String address, ImageView target) {
        if (address == null || address.trim().isEmpty() || target == null) return;
        target.setTag(address);
        Thread worker = new Thread(() -> {
            android.graphics.Bitmap bitmap = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(9000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "VYNIX-Android/0.1");
                try (InputStream input = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(input);
                }
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
            final android.graphics.Bitmap result = bitmap;
            if (result != null) runOnUiThread(() -> {
                if (target.getTag() != null && address.equals(target.getTag())) {
                    target.setImageBitmap(result);
                    target.setAlpha(0f);
                    target.animate().alpha(1f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
                }
            });
        }, "vynix-search-artwork");
        worker.setDaemon(true);
        worker.start();
    }

    private JSONObject getJson(String address)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
        try {
            c.setRequestMethod("GET");c.setConnectTimeout(9000);c.setReadTimeout(12000);c.setRequestProperty("Accept","application/json");
            int code=c.getResponseCode();
            java.io.InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();
            StringBuilder b=new StringBuilder();
            if(in!=null)try(BufferedReader r=new BufferedReader(new InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)b.append(line);}
            if(code<200||code>=300)throw new java.io.IOException("HTTP "+code);
            return new JSONObject(b.toString());
        } finally { c.disconnect(); }
    }
    private void sectionTitle(String title,String action,Runnable r){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);TextView a=label(title,19,TEXT,true);row.addView(a,new LinearLayout.LayoutParams(0,-2,1));TextView b=label(action,12,GOLD,true);row.addView(b);b.setOnClickListener(v->r.run());content.addView(row);gap(10);}
    private void trackRow(Track t,int index){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(11),dp(10),dp(10),dp(10));row.setBackground(round(0xE8171721,19,0x22B89CFF,1));row.setElevation(dp(1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(8);content.addView(row,p);TextView art=label("♫",22,Color.WHITE,true);art.setGravity(Gravity.CENTER);int[] coverColors={0xFF705174,0xFF4D6466,0xFF7B657C,0xFF464052,0xFF8A6C91,0xFF5D4B6C};art.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{coverColors[Math.abs(t.title.hashCode())%coverColors.length],0xFF2B202F}));art.setElevation(dp(2));row.addView(art,new LinearLayout.LayoutParams(dp(48),dp(48)));LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.setPadding(dp(12),0,dp(4),0);TextView title=label(t.title,14,TEXT,true);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);info.addView(title);info.addView(label("НА УСТРОЙСТВЕ  •  ЛОКАЛЬНЫЙ ФАЙЛ",9,MUTED,false));row.addView(info,new LinearLayout.LayoutParams(0,-2,1));TextView more=label(index==currentIndex&&player!=null&&player.isPlaying()?"Ⅱ":"▶",16,GOLD,true);more.setGravity(Gravity.CENTER);more.setBackground(round(0x33251F3A,13,0x22B89CFF,1));row.addView(more,new LinearLayout.LayoutParams(dp(38),dp(38)));row.setOnClickListener(v->playTrack(index));row.setOnTouchListener((v,event)->{if(event.getAction()==android.view.MotionEvent.ACTION_DOWN){v.animate().scaleX(.985f).scaleY(.985f).setDuration(90).start();}else if(event.getAction()==android.view.MotionEvent.ACTION_UP||event.getAction()==android.view.MotionEvent.ACTION_CANCEL){v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();}return false;});}
    private LinearLayout smallCard(String icon,String title,String sub){LinearLayout c=panel(false);c.setPadding(dp(15),dp(15),dp(12),dp(12));c.setBackground(round(0xFF191722,21,0x33B89CFF,1));TextView symbol=label(icon,24,GOLD,true);symbol.setGravity(Gravity.CENTER);symbol.setBackground(round(0x33251F3A,14,0x22B89CFF,1));c.addView(symbol,new LinearLayout.LayoutParams(dp(42),dp(42)));gapInside(c,dp(9));addTextTo(c,title,15,TEXT,true);addTextTo(c,sub,10,MUTED,false);return c;}
    private LinearLayout panel(boolean hero){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(18),dp(18),dp(18),dp(18));c.setBackground(round(hero?0xFF211C31:0xFF15141D,24,hero?0x66B89CFF:0x22FFFFFF,1));c.setElevation(dp(hero?3:1));return c;}
    private void empty(String title,String body){LinearLayout c=panel(false);addTextTo(c,title,17,TEXT,true);addTextTo(c,body,13,MUTED,false);content.addView(c);}
    private TextView button(String text){TextView b=label(text,14,Color.rgb(18,15,25),true);b.setGravity(Gravity.CENTER);b.setLetterSpacing(.015f);b.setPadding(dp(18),dp(15),dp(18),dp(15));b.setBackground(round(GOLD,18,0,0));b.setElevation(dp(2));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(14);b.setLayoutParams(p);b.setOnTouchListener((v,event)->{if(event.getAction()==android.view.MotionEvent.ACTION_DOWN)v.animate().scaleX(.98f).scaleY(.98f).setDuration(80).start();else if(event.getAction()==android.view.MotionEvent.ACTION_UP||event.getAction()==android.view.MotionEvent.ACTION_CANCEL)v.animate().scaleX(1f).scaleY(1f).setDuration(130).start();return false;});b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF),round(GOLD,18,0,0),null));return b;}
    private void addText(String text,int size,int color,boolean bold){TextView t=label(text,size,color,bold);content.addView(t);}
    private void addTextTo(LinearLayout target,String text,int size,int color,boolean bold){TextView t=label(text,size,color,bold);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(7);target.addView(t,p);}
    private TextView label(String text,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));return t;}
    private void gap(int h){View v=new View(this);content.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    private GradientDrawable round(int color,int radius,int stroke,int width){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(width>0)d.setStroke(dp(width),stroke);return d;}
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    private void chooseAudio(){audioPicker.launch(new String[]{"audio/*"});}
    private void playTrack(int index){if(index<0||index>=tracks.size()||player==null)return;currentIndex=index;player.stop();player.clearMediaItems();player.setMediaItem(MediaItem.fromUri(tracks.get(index).uri));refreshMini();refreshMediaNotification();player.prepare();player.play();refreshMini();if(content!=null){content.postDelayed(()->{refreshMini();refreshMediaNotification();},180);content.postDelayed(()->{refreshMini();refreshMediaNotification();},650);}}
    private void playAdjacentTrack(int direction) {
        if(tracks.isEmpty())return;
        int base=currentIndex<0?0:currentIndex;
        int nextIndex=(base+direction+tracks.size())%tracks.size();
        playTrack(nextIndex);
    }

    private void togglePlayback(){if(currentIndex<0){if(!tracks.isEmpty())playTrack(0);else chooseAudio();return;}if(player.isPlaying()||player.getPlayWhenReady())player.pause();else player.play();refreshMini();refreshMediaNotification();}
    private void refreshMediaNotification() {
        if(player==null||notificationManager==null||mediaSession==null)return;
        if(player.getMediaItemCount()==0||currentIndex<0||currentIndex>=tracks.size()){notificationManager.cancel(PLAYBACK_NOTIFICATION_ID);return;}
        Track track=tracks.get(currentIndex); boolean playing=player.isPlaying();
        mediaSession.setPlaybackState(new android.media.session.PlaybackState.Builder()
            .setActions(android.media.session.PlaybackState.ACTION_PLAY|android.media.session.PlaybackState.ACTION_PAUSE|android.media.session.PlaybackState.ACTION_PLAY_PAUSE|android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT|android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS|android.media.session.PlaybackState.ACTION_SEEK_TO)
            .setState(playing?android.media.session.PlaybackState.STATE_PLAYING:(player.getPlayWhenReady()?android.media.session.PlaybackState.STATE_BUFFERING:android.media.session.PlaybackState.STATE_PAUSED),player.getCurrentPosition(),playing?1f:0f).build());
        mediaSession.setMetadata(new android.media.MediaMetadata.Builder().putString(android.media.MediaMetadata.METADATA_KEY_TITLE,track.title).putString(android.media.MediaMetadata.METADATA_KEY_ARTIST,"VYNIX • Музыка").putLong(android.media.MediaMetadata.METADATA_KEY_DURATION,Math.max(0,player.getDuration())).build());
        PendingIntent open=PendingIntent.getActivity(this,4702,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,PLAYBACK_CHANNEL):new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(track.title).setContentText("VYNIX • "+(playing?"Сейчас играет":"На паузе")).setContentIntent(open).setShowWhen(false).setOnlyAlertOnce(true).setOngoing(playing).setVisibility(Notification.VISIBILITY_PUBLIC)
          .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous,"Предыдущий",mediaAction("PREVIOUS",4703)).build())
          .addAction(new Notification.Action.Builder(playing?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play,playing?"Пауза":"Воспроизвести",mediaAction("TOGGLE",4704)).build())
          .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next,"Следующий",mediaAction("NEXT",4705)).build())
          .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0,1,2));
        notificationManager.notify(PLAYBACK_NOTIFICATION_ID,b.build());
    }
    private PendingIntent mediaAction(String action,int code){
        Intent i=new Intent(this,PlaybackActionReceiver.class).setAction("app.vynix.MEDIA_"+action);
        return PendingIntent.getBroadcast(this,code,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    public static class PlaybackActionReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context,Intent intent){
            Intent i=new Intent(context,MainActivity.class).setAction(intent.getAction()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(i);
        }
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);if(intent==null||intent.getAction()==null)return;
        if("app.vynix.MEDIA_TOGGLE".equals(intent.getAction()))togglePlayback();
        else if("app.vynix.MEDIA_NEXT".equals(intent.getAction())){if(player!=null&&player.hasNextMediaItem())player.seekToNextMediaItem();else if(currentIndex+1<tracks.size())playTrack(currentIndex+1);}
        else if("app.vynix.MEDIA_PREVIOUS".equals(intent.getAction())){if(player!=null&&player.hasPreviousMediaItem())player.seekToPreviousMediaItem();else if(currentIndex>0)playTrack(currentIndex-1);}
    }

    private void refreshMini(){
        if(miniTitle==null||mini==null||playButton==null)return;
        boolean hasTrack=player!=null&&currentIndex>=0&&currentIndex<tracks.size()&&player.getMediaItemCount()>0;
        boolean playing=player!=null&&player.isPlaying();
        boolean shouldShow=hasTrack&&player.getPlaybackState()!=androidx.media3.common.Player.STATE_IDLE&&player.getPlaybackState()!=androidx.media3.common.Player.STATE_ENDED;
        mini.animate().cancel();
        if(shouldShow){
            if(mini.getVisibility()!=View.VISIBLE){
                mini.setVisibility(View.VISIBLE);mini.setAlpha(0f);mini.setTranslationY(dp(10));
                mini.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(new DecelerateInterpolator(1.4f)).start();
            }
        }else if(mini.getVisibility()==View.VISIBLE){
            mini.animate().alpha(0f).translationY(dp(7)).setDuration(140).withEndAction(()->{
                if(mini!=null&&!(player!=null&&player.getPlayWhenReady()&&player.getMediaItemCount()>0&&player.getPlaybackState()!=androidx.media3.common.Player.STATE_ENDED)){
                    mini.setVisibility(View.GONE);mini.setAlpha(1f);mini.setTranslationY(0f);
                }
            }).start();
        }
        if(currentIndex>=0&&currentIndex<tracks.size()){
            String nextTitle=tracks.get(currentIndex).title;
            if(!nextTitle.contentEquals(miniTitle.getText())){
                miniTitle.animate().cancel();miniTitle.setAlpha(.55f);miniTitle.setTranslationY(dp(4));miniTitle.setText(nextTitle);
                miniTitle.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(new DecelerateInterpolator(1.3f)).start();
            }
        }
        playButton.setText(playing?"Ⅱ":"▶");
        playButton.animate().cancel();playButton.setScaleX(.88f);playButton.setScaleY(.88f);
        playButton.animate().scaleX(1f).scaleY(1f).setDuration(170).setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
    }
    private void syncPlaybackButtons(){
        if(root!=null)updatePlayLabels(root,player!=null&&player.isPlaying());
        boolean playing=player!=null&&player.isPlaying();
        if(playButton!=null){
            String icon=playing?"Ⅱ":"▶";
            if(!icon.contentEquals(playButton.getText())){playButton.setText(icon);playButton.animate().cancel();playButton.setScaleX(.78f);playButton.setScaleY(.78f);playButton.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(new android.view.animation.OvershootInterpolator(1.5f)).start();}
        }
        if(content!=null){
            updatePlayLabels(content,playing);
        }
    }
    private void updatePlayLabels(ViewGroup group,boolean playing){
        for(int i=0;i<group.getChildCount();i++){
            View child=group.getChildAt(i);
            if(child instanceof TextView){
                TextView tv=(TextView)child;
                CharSequence value=tv.getText();
                if(value!=null&&("▶".contentEquals(value)||"Ⅱ".contentEquals(value))){
                    tv.setText(playing?"Ⅱ":"▶");
                    tv.animate().cancel();tv.setScaleX(.86f);tv.setScaleY(.86f);
                    tv.animate().scaleX(1f).scaleY(1f).setDuration(190).setInterpolator(new android.view.animation.OvershootInterpolator(1.3f)).start();
                }
            }else if(child instanceof ViewGroup){updatePlayLabels((ViewGroup)child,playing);}
        }
    }
    private String formatTime(long millis){if(millis<0||millis==androidx.media3.common.C.TIME_UNSET)return "0:00";long seconds=millis/1000;return (seconds/60)+":"+String.format(java.util.Locale.ROOT,"%02d",seconds%60);}

    private void showPlayer(){if(currentIndex<0){chooseAudio();return;}android.app.Dialog d=new android.app.Dialog(this);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(22),dp(12),dp(22),dp(24));box.setBackground(round(0xF515141E,30,0x44B89CFF,1));View handle=new View(this);handle.setBackground(round(0x66FFFFFF,4,0,0));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(42),dp(4));hp.gravity=Gravity.CENTER;hp.bottomMargin=dp(15);box.addView(handle,hp);TextView close=label("⌄",24,MUTED,true);close.setGravity(Gravity.CENTER);box.addView(close,new LinearLayout.LayoutParams(-1,dp(28)));TextView overline=label("V Y N I X     /     NOW PLAYING",10,GOLD,true);overline.setGravity(Gravity.CENTER);LinearLayout.LayoutParams olp=new LinearLayout.LayoutParams(-1,-2);olp.topMargin=dp(8);box.addView(overline,olp);ImageView art=new ImageView(this);art.setScaleType(ImageView.ScaleType.CENTER_CROP);GradientDrawable playerCover=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xFF80658B,0xFF322437});playerCover.setCornerRadius(dp(28));art.setBackground(playerCover);art.setClipToOutline(true);art.setElevation(dp(4));LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(300));ap.setMargins(0,dp(16),0,dp(20));box.addView(art,ap);loadPlayerArtwork(tracks.get(currentIndex).uri,art);TextView title=label(tracks.get(currentIndex).title,22,TEXT,true);title.setGravity(Gravity.CENTER);title.setMaxLines(2);LinearLayout.LayoutParams titlep=new LinearLayout.LayoutParams(-1,-2);titlep.bottomMargin=dp(7);box.addView(title,titlep);TextView artist=label("СЕЙЧАС В VYNIX",10,MUTED,true);artist.setGravity(Gravity.CENTER);box.addView(artist);LinearLayout timeRow=new LinearLayout(this);timeRow.setGravity(Gravity.CENTER_VERTICAL);timeRow.setPadding(dp(2),dp(8),dp(2),0);SeekBar seekBar=new SeekBar(this);seekBar.setMax(1000);seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(GOLD));seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(GOLD));seekBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF34313F));LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(-1,dp(34));slp.setMargins(-dp(8),dp(12),-dp(8),0);box.addView(seekBar,slp);TextView time=label("0:00  /  0:00",11,MUTED,false);time.setGravity(Gravity.CENTER);box.addView(time);Handler progressHandler=new Handler(Looper.getMainLooper());Runnable progressTick=new Runnable(){@Override public void run(){if(!d.isShowing()||player==null)return;long duration=player.getDuration(),position=player.getCurrentPosition();if(duration>0&&!seekBar.isPressed())seekBar.setProgress((int)Math.min(1000,position*1000/duration));time.setText(formatTime(position)+"  /  "+formatTime(duration));progressHandler.postDelayed(this,500);}};seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){if(fromUser){long duration=player.getDuration();if(duration>0)time.setText(formatTime(duration*progress/1000)+"  /  "+formatTime(duration));}}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){long duration=player.getDuration();if(duration>0)player.seekTo(duration*bar.getProgress()/1000);}});LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER);controls.setPadding(0,dp(18),0,dp(12));box.addView(controls,new LinearLayout.LayoutParams(-1,dp(76)));
        TextView repeat=label("⟳",23,MUTED,true);repeat.setGravity(Gravity.CENTER);controls.addView(repeat,new LinearLayout.LayoutParams(dp(42),dp(60)));
        TextView previous=label("◀",24,TEXT,true);previous.setGravity(Gravity.CENTER);controls.addView(previous,new LinearLayout.LayoutParams(dp(48),dp(60)));
        TextView play=label(player.isPlaying()?"Ⅱ":"▶",32,BG,true);play.setGravity(Gravity.CENTER);play.setBackground(round(Color.WHITE,100,0,0));controls.addView(play,new LinearLayout.LayoutParams(dp(84),dp(84)));
        TextView next=label("▶",24,TEXT,true);next.setGravity(Gravity.CENTER);controls.addView(next,new LinearLayout.LayoutParams(dp(48),dp(60)));
        TextView shuffle=label("⤨",23,MUTED,true);shuffle.setGravity(Gravity.CENTER);controls.addView(shuffle,new LinearLayout.LayoutParams(dp(42),dp(60)));
        repeat.setOnClickListener(v->{player.setRepeatMode(player.getRepeatMode()==Player.REPEAT_MODE_ONE?Player.REPEAT_MODE_OFF:Player.REPEAT_MODE_ONE);repeat.setText(player.getRepeatMode()==Player.REPEAT_MODE_ONE?"⟳¹":"⟳");repeat.setTextColor(player.getRepeatMode()==Player.REPEAT_MODE_ONE?GOLD:MUTED);});
        shuffle.setOnClickListener(v->{player.setShuffleModeEnabled(!player.getShuffleModeEnabled());shuffle.setTextColor(player.getShuffleModeEnabled()?GOLD:MUTED);});
        previous.setOnClickListener(v->{playAdjacentTrack(-1);d.dismiss();});
        next.setOnClickListener(v->{playAdjacentTrack(1);d.dismiss();});
        play.setOnClickListener(v->{togglePlayback();play.setText(player.isPlaying()?"Ⅱ":"▶");refreshMini();});
        close.setOnClickListener(v->d.dismiss());d.setOnDismissListener(x->progressHandler.removeCallbacks(progressTick));d.setContentView(box);d.setOnShowListener(x->{progressHandler.post(progressTick);if(d.getWindow()!=null){android.view.Window w=d.getWindow();w.setBackgroundDrawableResource(android.R.color.transparent);w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);android.view.WindowManager.LayoutParams a=w.getAttributes();a.dimAmount=.72f;if(android.os.Build.VERSION.SDK_INT>=31){a.setBlurBehindRadius(dp(24));a.flags|=android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND;}w.setAttributes(a);w.setLayout(-1,Math.min(getResources().getDisplayMetrics().heightPixels-dp(50),dp(650)));w.setGravity(Gravity.BOTTOM);}});d.show();}

    private void loadPlayerArtwork(Uri uri, ImageView target) {
        if(uri==null||target==null)return;
        String key=uri.toString(); target.setTag(key);
        Thread worker=new Thread(()->{
            android.graphics.Bitmap bitmap=null;
            try {
                if("content".equals(uri.getScheme())||"file".equals(uri.getScheme())){
                    android.media.MediaMetadataRetriever retriever=new android.media.MediaMetadataRetriever();
                    try{retriever.setDataSource(this,uri);byte[] data=retriever.getEmbeddedPicture();if(data!=null)bitmap=BitmapFactory.decodeByteArray(data,0,data.length);}finally{retriever.release();}
                }
            }catch(Exception ignored){}
            final android.graphics.Bitmap result=bitmap;
            if(result!=null)runOnUiThread(()->{if(key.equals(target.getTag())){target.setImageBitmap(result);target.setAlpha(0f);target.animate().alpha(1f).setDuration(260).start();}});
        },"vynix-player-artwork");worker.setDaemon(true);worker.start();
    }

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
    private void loadLocalTracks() {
        android.content.SharedPreferences prefs=getSharedPreferences("vynix_library",MODE_PRIVATE);
        String raw=prefs.getString("tracks","[]");
        try {
            JSONArray saved=new JSONArray(raw);
            for(int i=0;i<saved.length();i++) {
                JSONObject item=saved.optJSONObject(i);
                if(item==null)continue;
                Uri uri=Uri.parse(item.optString("uri",""));
                String title=item.optString("title","Без названия");
                if(uri.toString().isEmpty())continue;
                try {
                    getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch(Exception ignored) {}
                tracks.add(new Track(uri,title));
            }
            currentIndex=tracks.isEmpty()?-1:0;
        } catch(Exception ignored) {
            prefs.edit().putString("tracks","[]").apply();
        }
    }

    private void saveLocalTracks() {
        JSONArray saved=new JSONArray();
        for(Track track:tracks) {
            JSONObject item=new JSONObject();
            try { item.put("uri",track.uri.toString()); item.put("title",track.title); saved.put(item); }
            catch(Exception ignored) {}
        }
        getSharedPreferences("vynix_library",MODE_PRIVATE).edit().putString("tracks",saved.toString()).apply();
    }

    private void syncLibrary(){
        apiExecutor.execute(()->{try{JSONArray items=new JSONArray();for(Track t:tracks){JSONObject item=new JSONObject();item.put("item_id",t.uri.toString());item.put("title",t.title);item.put("artist","");item.put("source","local");item.put("favorite",false);items.put(item);}JSONObject body=new JSONObject();body.put("items",items);apiRequest("/v1/library/"+accountId,"PUT",body);}catch(Exception ignored){}});
    }

    @Override protected void onDestroy(){apiExecutor.shutdownNow();if(player!=null)player.release();if(mediaSession!=null){mediaSession.setActive(false);mediaSession.release();}if(notificationManager!=null)notificationManager.cancel(PLAYBACK_NOTIFICATION_ID);super.onDestroy();}
}
