package com.alldebrid.mobile;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.cast.CastPlayer;
import androidx.media3.cast.SessionAvailabilityListener;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.mediarouter.app.MediaRouteButton;

import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;

import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Built-in video player.
 *  - double-tap left / right = -10s / +10s
 *  - swipe up/down on the left half = brightness, on the right half = volume
 *  - mute button, aspect ratio, speed, subtitles, audio tracks, quality
 *  - resume (remembers where you stopped)
 *  - Chromecast
 *  - FFmpeg software decoders with automatic fallback
 */
public class PlayerActivity extends AppCompatActivity {

    private static final int REQ_SUB = 2001;
    private static final String API = "https://api.alldebrid.com";
    private static final long RESUME_MIN_MS = 15000;

    // ---- players
    private Player player;              // the active player (local or cast)
    private ExoPlayer exo;              // local player
    private CastPlayer castPlayer;      // may be null (no Google Play services)
    private CastContext castContext;
    private Player.Listener exoListener, castListener;
    private boolean softwareFirst = false;

    // ---- data
    private JSONArray items;
    private String apiKey;
    private SharedPreferences prefs;
    private AudioManager audio;
    private int lastIndex = -1;
    private boolean resumePromptOpen = false;
    private boolean muted = false;
    private int aspectMode = 0;
    private int tick = 0;

    // ---- views
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout rootView, controls;
    private PlayerView playerView;
    private TextView titleView, posView, durView, castLabel;
    private Ico playBtn, prevBtn, nextBtn, muteBtn;
    private Ico seekLeft, seekRight;
    private Hud volHud, briHud;
    private SeekBar seek;
    private ProgressBar spinner;
    private MediaRouteButton routeButton;
    private boolean seeking = false;

    // ---- gestures
    private int gestureMode = 0;        // 0 undecided, -1 ignore, 1 volume, 2 brightness
    private float gestureStartVol, gestureStartBri;

    private final Runnable hideRunnable = this::hideControls;
    private final Runnable hideIndicators = () -> {
        volHud.setVisibility(View.GONE);
        briHud.setVisibility(View.GONE);
    };
    private final Runnable hideSeekHints = () -> {
        seekLeft.setVisibility(View.GONE);
        seekRight.setVisibility(View.GONE);
    };
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (++tick % 10 == 0) saveProgress();
            ui.postDelayed(this, 500);
        }
    };

    // ====================================================================== lifecycle
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            items = new JSONArray(getIntent().getStringExtra("items"));
        } catch (Exception e) {
            finish();
            return;
        }
        if (items.length() == 0) {
            finish();
            return;
        }
        apiKey = getIntent().getStringExtra("key");
        int start = getIntent().getIntExtra("start", 0);
        prefs = getSharedPreferences("player", MODE_PRIVATE);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        aspectMode = prefs.getInt("aspect", 0);

        initCast();   // creates the cast button (if Google Play services are available)

        rootView = new FrameLayout(this);
        rootView.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        rootView.addView(playerView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        castLabel = text("", 18);
        castLabel.setVisibility(View.GONE);
        rootView.addView(castLabel, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        View touch = new View(this);
        setupGestures(touch);
        rootView.addView(touch, new FrameLayout.LayoutParams(-1, -1));

        buildOverlays();

        spinner = new ProgressBar(this);
        rootView.addView(spinner, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));

        controls = buildControls();
        rootView.addView(controls, new FrameLayout.LayoutParams(-1, -1));

        setContentView(rootView);
        rootView.addOnLayoutChangeListener((v, l, t, r, bt, oL, oT, oR, oB) -> applyAspect());
        hideSystemUi();

        initPlayer(start);
        if (castPlayer != null && castPlayer.isCastSessionAvailable()) switchToCast();

        if (!prefs.getBoolean("gesture_hint", false)) {
            prefs.edit().putBoolean("gesture_hint", true).apply();
            toast("Double-tap left/right: \u00B110s \u00B7 Swipe left side: brightness \u00B7 Swipe right side: volume");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProgress();
        if (player != null && player != castPlayer) player.pause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        saveProgress();
        try {
            if (castPlayer != null) {
                castPlayer.setSessionAvailabilityListener(null);
                if (player == castPlayer) {
                    castPlayer.stop();
                    castContext.getSessionManager().endCurrentSession(true);
                }
                castPlayer.release();
            }
        } catch (Exception ignored) { }
        if (exo != null) {
            exo.release();
            exo = null;
        }
        super.onDestroy();
    }

    // ====================================================================== players
    private boolean casting() {
        return castPlayer != null && player == castPlayer;
    }

    private static String guessMime(String title, String url) {
        String n = (title == null ? "" : title).toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return MimeTypes.VIDEO_MP4;
        if (n.endsWith(".mkv")) return MimeTypes.VIDEO_MATROSKA;
        if (n.endsWith(".webm")) return MimeTypes.VIDEO_WEBM;
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".avi")) return "video/x-msvideo";
        if (n.endsWith(".ts") || n.endsWith(".m2ts")) return "video/mp2t";
        if (n.endsWith(".mp3")) return MimeTypes.AUDIO_MPEG;
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".aac")) return MimeTypes.AUDIO_AAC;
        if (n.endsWith(".flac")) return MimeTypes.AUDIO_FLAC;
        if (n.endsWith(".ogg")) return MimeTypes.AUDIO_OGG;
        if (n.endsWith(".opus")) return MimeTypes.AUDIO_OPUS;
        if (n.endsWith(".wav")) return "audio/wav";
        if (url != null && url.contains(".m3u8")) return MimeTypes.APPLICATION_M3U8;
        return null;
    }

    private void initPlayer(int start) {
        List<MediaItem> list = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            MediaItem.Builder mb = new MediaItem.Builder()
                    .setUri(o.optString("url"))
                    .setMediaMetadata(new MediaMetadata.Builder().setTitle(o.optString("title")).build());
            String mime = guessMime(o.optString("title"), o.optString("url"));
            if (mime != null) mb.setMimeType(mime);
            list.add(mb.build());
        }
        int startIndex = Math.max(0, Math.min(start, list.size() - 1));
        boolean ask = savedPosition(startIndex) >= RESUME_MIN_MS;
        createPlayer(list, startIndex, C.TIME_UNSET, !ask);
        lastIndex = startIndex;
        ui.post(ticker);
        scheduleHide();
        if (ask) promptResume(startIndex);
    }

    private Player.Listener newListener(final Player owner) {
        return new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (owner != player) return;
                spinner.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_ENDED) clearSaved(player.getCurrentMediaItemIndex());
                refreshButtons();
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                if (owner != player) return;
                playBtn.setKind(playing ? Ico.PAUSE : Ico.PLAY);
                if (playing) scheduleHide(); else showControls();
            }

            @Override
            public void onMediaItemTransition(MediaItem item, int reason) {
                if (owner != player) return;
                int idx = player.getCurrentMediaItemIndex();
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && lastIndex >= 0 && lastIndex != idx) {
                    clearSaved(lastIndex);          // the previous item was finished
                }
                lastIndex = idx;
                updateTitle();
                refreshButtons();
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
                        && savedPosition(idx) >= RESUME_MIN_MS) {
                    promptResume(idx);
                }
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                if (owner != player) return;
                if (owner == exo && !softwareFirst && isDecoderError(e)) {
                    toast("Switching to software decoding\u2026");
                    ui.post(() -> rebuildPlayer(true));
                } else {
                    showPlaybackError(e);
                }
            }
        };
    }

    private static boolean isDecoderError(PlaybackException e) {
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED:
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED:
                return true;
            default:
                return false;
        }
    }

    private void createPlayer(List<MediaItem> list, int index, long positionMs, boolean play) {
        if (exo != null) {
            exo.removeListener(exoListener);
            exo.release();
            exo = null;
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("Mozilla/5.0 (Linux; Android) AllDebridMobile");

        // NextLib = FFmpeg software decoders (audio: DTS, AC3, E-AC3, TrueHD, FLAC, Opus ...;
        // video: H.264, HEVC, VP8, VP9). ON = phone decoders first, FFmpeg as fallback; PREFER = FFmpeg first.
        NextRenderersFactory renderers = new NextRenderersFactory(this);
        renderers.setExtensionRendererMode(softwareFirst
                ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        renderers.setEnableDecoderFallback(true);

        exo = new ExoPlayer.Builder(this, renderers, new DefaultMediaSourceFactory(http))
                .setSeekBackIncrementMs(10000)
                .setSeekForwardIncrementMs(10000)
                .build();
        exoListener = newListener(exo);
        exo.addListener(exoListener);
        player = exo;
        playerView.setPlayer(exo);
        exo.setMediaItems(list, index, positionMs);
        exo.prepare();
        exo.setPlayWhenReady(play);
        if (muted) exo.setVolume(0f);
        updateTitle();
        refreshButtons();
        applyAspect();
    }

    private List<MediaItem> snapshot(Player p) {
        List<MediaItem> list = new ArrayList<>();
        for (int i = 0; i < p.getMediaItemCount(); i++) list.add(p.getMediaItemAt(i));
        return list;
    }

    /** Re-creates the local player with the other decoder preference, keeping the playlist and position. */
    private void rebuildPlayer(boolean software) {
        if (player == null || casting()) return;
        softwareFirst = software;
        createPlayer(snapshot(player), player.getCurrentMediaItemIndex(), player.getCurrentPosition(), true);
    }

    // ====================================================================== Chromecast
    private void initCast() {
        try {
            castContext = CastContext.getSharedInstance(this);
            castPlayer = new CastPlayer(castContext);
            castListener = newListener(castPlayer);
            castPlayer.addListener(castListener);
            castPlayer.setSessionAvailabilityListener(new SessionAvailabilityListener() {
                @Override
                public void onCastSessionAvailable() {
                    runOnUiThread(PlayerActivity.this::switchToCast);
                }

                @Override
                public void onCastSessionUnavailable() {
                    runOnUiThread(PlayerActivity.this::switchToLocal);
                }
            });
            routeButton = new MediaRouteButton(this);
            CastButtonFactory.setUpMediaRouteButton(getApplicationContext(), routeButton);
        } catch (Throwable t) {
            // Google Play services / Cast not available on this phone: hide the cast button
            castContext = null;
            castPlayer = null;
            routeButton = null;
        }
    }

    private void switchToCast() {
        if (castPlayer == null || player == null || player == castPlayer) return;
        List<MediaItem> list = snapshot(player);
        int idx = player.getCurrentMediaItemIndex();
        long pos = player.getCurrentPosition();
        saveProgress();
        playerView.setPlayer(null);
        if (exo != null) {
            exo.removeListener(exoListener);
            exo.release();
            exo = null;
        }
        player = castPlayer;
        castPlayer.setMediaItems(list, idx, pos);
        castPlayer.setPlayWhenReady(true);
        castPlayer.prepare();
        String name = "your TV";
        try {
            CastSession s = castContext.getSessionManager().getCurrentCastSession();
            if (s != null && s.getCastDevice() != null) name = s.getCastDevice().getFriendlyName();
        } catch (Exception ignored) { }
        castLabel.setText("Casting to " + name + "\n\nWorks best with MP4 (H.264/AAC), WebM and HLS.");
        castLabel.setVisibility(View.VISIBLE);
        setMuted(muted);
        updateTitle();
        refreshButtons();
        showControls();
    }

    private void switchToLocal() {
        if (castPlayer == null || player != castPlayer) return;
        List<MediaItem> list = snapshot(castPlayer);
        int idx = castPlayer.getCurrentMediaItemIndex();
        long pos = castPlayer.getCurrentPosition();
        boolean play = castPlayer.getPlayWhenReady();
        try {
            castPlayer.stop();
        } catch (Exception ignored) { }
        castLabel.setVisibility(View.GONE);
        createPlayer(list, idx, pos, play);
        toast("Back on this phone");
    }

    // ====================================================================== UI building
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String s, float sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView iconBtn(String s, float sp, View.OnClickListener l) {
        TextView t = text(s, sp);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setOnClickListener(v -> {
            l.onClick(v);
            scheduleHide();
        });
        return t;
    }

    private TextView chip(String s, View.OnClickListener l) {
        TextView t = text(s, 13);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0x44FFFFFF);
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(4), 0, dp(4), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> {
            l.onClick(v);
            scheduleHide();
        });
        return t;
    }

    /** Small icons drawn with Canvas (no emoji or font needed, so they look the same on every phone). */
    private static class Ico extends View {
        static final int BACK = 0, PLAY = 1, PAUSE = 2, PREV = 3, NEXT = 4, VOL = 5, MUTE = 6,
                SUN = 7, MORE = 8, FWD = 9, REW = 10;
        private int kind;
        private boolean disc = false;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Ico(Context c, int kind) {
            super(c);
            this.kind = kind;
            fill.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);
            stroke.setColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            txt.setColor(Color.WHITE);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTypeface(Typeface.DEFAULT_BOLD);
            discPaint.setColor(0x77000000);
        }

        void setKind(int k) {
            if (k != kind) {
                kind = k;
                invalidate();
            }
        }

        void setDisc(boolean d) {
            disc = d;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (disc) c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, discPaint);
            float pw = w - getPaddingLeft() - getPaddingRight();
            float ph = h - getPaddingTop() - getPaddingBottom();
            float size = Math.min(pw, ph);
            c.save();
            c.translate(getPaddingLeft() + (pw - size) / 2f, getPaddingTop() + (ph - size) / 2f);
            paintIcon(c, size, kind);
            c.restore();
        }

        /** Draws the icon inside a size x size square at the canvas origin. */
        void paintIcon(Canvas c, float size, int k) {
            c.save();
            c.scale(size / 24f, size / 24f);
            stroke.setStrokeWidth(2.1f);
            switch (k) {
                case PLAY: {
                    Path p = new Path();
                    p.moveTo(7f, 4.5f);
                    p.lineTo(20f, 12f);
                    p.lineTo(7f, 19.5f);
                    p.close();
                    c.drawPath(p, fill);
                    break;
                }
                case PAUSE:
                    c.drawRoundRect(new RectF(5.5f, 4.5f, 10f, 19.5f), 1.2f, 1.2f, fill);
                    c.drawRoundRect(new RectF(14f, 4.5f, 18.5f, 19.5f), 1.2f, 1.2f, fill);
                    break;
                case PREV: {
                    c.drawRoundRect(new RectF(4.5f, 5f, 7f, 19f), 1f, 1f, fill);
                    Path p = new Path();
                    p.moveTo(19.5f, 5f);
                    p.lineTo(19.5f, 19f);
                    p.lineTo(9f, 12f);
                    p.close();
                    c.drawPath(p, fill);
                    break;
                }
                case NEXT: {
                    c.drawRoundRect(new RectF(17f, 5f, 19.5f, 19f), 1f, 1f, fill);
                    Path p = new Path();
                    p.moveTo(4.5f, 5f);
                    p.lineTo(4.5f, 19f);
                    p.lineTo(15f, 12f);
                    p.close();
                    c.drawPath(p, fill);
                    break;
                }
                case BACK: {
                    c.drawLine(20f, 12f, 5f, 12f, stroke);
                    Path p = new Path();
                    p.moveTo(11f, 6f);
                    p.lineTo(5f, 12f);
                    p.lineTo(11f, 18f);
                    c.drawPath(p, stroke);
                    break;
                }
                case VOL:
                case MUTE: {
                    Path p = new Path();
                    p.moveTo(3.5f, 9.5f);
                    p.lineTo(7.5f, 9.5f);
                    p.lineTo(12.5f, 5f);
                    p.lineTo(12.5f, 19f);
                    p.lineTo(7.5f, 14.5f);
                    p.lineTo(3.5f, 14.5f);
                    p.close();
                    c.drawPath(p, fill);
                    if (k == VOL) {
                        c.drawArc(new RectF(9f, 8f, 17f, 16f), -50f, 100f, false, stroke);
                        c.drawArc(new RectF(6f, 5f, 20f, 19f), -50f, 100f, false, stroke);
                    } else {
                        c.drawLine(16f, 9f, 21f, 15f, stroke);
                        c.drawLine(21f, 9f, 16f, 15f, stroke);
                    }
                    break;
                }
                case SUN: {
                    c.drawCircle(12f, 12f, 4f, fill);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.toRadians(i * 45);
                        float cx = (float) Math.cos(a), cy = (float) Math.sin(a);
                        c.drawLine(12f + 6.7f * cx, 12f + 6.7f * cy, 12f + 9.4f * cx, 12f + 9.4f * cy, stroke);
                    }
                    break;
                }
                case MORE:
                    c.drawCircle(12f, 5f, 2f, fill);
                    c.drawCircle(12f, 12f, 2f, fill);
                    c.drawCircle(12f, 19f, 2f, fill);
                    break;
                case FWD:
                case REW: {
                    c.save();
                    if (k == REW) c.scale(-1f, 1f, 12f, 12f);
                    stroke.setStrokeWidth(1.9f);
                    c.drawArc(new RectF(4f, 4.5f, 20f, 20.5f), -60f, 300f, false, stroke);
                    // arrow head at the end of the arc (angle -120 deg), pointing along the motion
                    float ex = 12f + 8f * (float) Math.cos(Math.toRadians(-120));
                    float ey = 12.5f + 8f * (float) Math.sin(Math.toRadians(-120));
                    float tx = 0.866f, ty = -0.5f, nx = 0.5f, ny = 0.866f;
                    Path head = new Path();
                    head.moveTo(ex + tx * 3.4f, ey + ty * 3.4f);
                    head.lineTo(ex - tx * 0.9f + nx * 3.2f, ey - ty * 0.9f + ny * 3.2f);
                    head.lineTo(ex - tx * 0.9f - nx * 3.2f, ey - ty * 0.9f - ny * 3.2f);
                    head.close();
                    c.drawPath(head, fill);
                    c.restore();
                    txt.setTextSize(8.6f);
                    c.drawText("10", 12f, 15.5f, txt);
                    break;
                }
                default:
                    break;
            }
            c.restore();
        }
    }

    /** Compact volume / brightness indicator: icon, thin bar and percentage, all drawn with Canvas. */
    private static class Hud extends View {
        private int kind;
        private float level;
        private final Ico painter;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;

        Hud(Context c, int kind) {
            super(c);
            this.kind = kind;
            painter = new Ico(c, kind);
            d = c.getResources().getDisplayMetrics().density;
            bg.setColor(0x88000000);
            track.setColor(0x55FFFFFF);
            bar.setColor(0xFF22D3EE);
            txt.setColor(Color.WHITE);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTypeface(Typeface.DEFAULT_BOLD);
            txt.setTextSize(12f * d);
        }

        void set(int k, float l) {
            kind = k;
            level = Math.max(0f, Math.min(1f, l));
            invalidate();
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension((int) (46 * d), (int) (170 * d));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawRoundRect(new RectF(0, 0, w, h), w / 2f, w / 2f, bg);
            float icon = 22f * d;
            c.save();
            c.translate((w - icon) / 2f, 12f * d);
            painter.paintIcon(c, icon, kind);
            c.restore();
            float barW = 5f * d, top = 12f * d + icon + 10f * d, bottom = h - 34f * d;
            float x = (w - barW) / 2f;
            c.drawRoundRect(new RectF(x, top, x + barW, bottom), barW / 2f, barW / 2f, track);
            float fillTop = bottom - (bottom - top) * level;
            c.drawRoundRect(new RectF(x, fillTop, x + barW, bottom), barW / 2f, barW / 2f, bar);
            c.drawText(Math.round(level * 100) + "%", w / 2f, h - 13f * d, txt);
        }
    }

    private void buildOverlays() {
        briHud = new Hud(this, Ico.SUN);
        briHud.setVisibility(View.GONE);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL);
        bl.leftMargin = dp(28);
        rootView.addView(briHud, bl);

        volHud = new Hud(this, Ico.VOL);
        volHud.setVisibility(View.GONE);
        FrameLayout.LayoutParams vl = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        vl.rightMargin = dp(28);
        rootView.addView(volHud, vl);

        seekLeft = new Ico(this, Ico.REW);
        seekRight = new Ico(this, Ico.FWD);
        for (Ico t : new Ico[]{seekLeft, seekRight}) {
            t.setDisc(true);
            t.setPadding(dp(8), dp(8), dp(8), dp(8));
            t.setVisibility(View.GONE);
        }
        FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.START | Gravity.CENTER_VERTICAL);
        l.leftMargin = dp(110);
        FrameLayout.LayoutParams r = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.END | Gravity.CENTER_VERTICAL);
        r.rightMargin = dp(110);
        rootView.addView(seekLeft, l);
        rootView.addView(seekRight, r);
    }

    /** Icon button with a larger touch area than the drawn icon. */
    private Ico icoBtn(int kind, int totalDp, int padDp, View.OnClickListener l) {
        Ico v = new Ico(this, kind);
        v.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(totalDp), dp(totalDp)));
        v.setOnClickListener(x -> {
            l.onClick(x);
            scheduleHide();
        });
        return v;
    }

    private FrameLayout buildControls() {
        FrameLayout c = new FrameLayout(this);

        // ----- top bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), dp(4), dp(6), dp(4));
        top.setBackgroundColor(0x99000000);
        top.addView(icoBtn(Ico.BACK, 44, 10, v -> finish()));
        titleView = text("", 15);
        titleView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1f));
        if (routeButton != null) top.addView(routeButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        top.addView(chip("CC", v -> showSubtitles()));
        top.addView(chip("Audio", v -> showAudio()));
        top.addView(chip("Quality", v -> showQuality()));
        top.addView(icoBtn(Ico.MORE, 44, 12, v -> showMore()));
        c.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // ----- centre buttons (small white icons)
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.CENTER);
        prevBtn = icoBtn(Ico.PREV, 52, 14, v -> {
            if (player.getCurrentPosition() > 3000 || !player.hasPreviousMediaItem()) player.seekTo(0);
            else player.seekToPreviousMediaItem();
        });
        playBtn = icoBtn(Ico.PLAY, 64, 16, v -> {
            if (player.isPlaying()) player.pause(); else player.play();
        });
        nextBtn = icoBtn(Ico.NEXT, 52, 14, v -> {
            if (player.hasNextMediaItem()) player.seekToNextMediaItem();
        });
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(dp(64), dp(64));
        gap.setMargins(dp(22), 0, dp(22), 0);
        playBtn.setLayoutParams(gap);
        mid.addView(prevBtn);
        mid.addView(playBtn);
        mid.addView(nextBtn);
        c.addView(mid, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // ----- bottom bar
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(8), dp(4), dp(10), dp(8));
        bottom.setBackgroundColor(0x99000000);
        muteBtn = icoBtn(Ico.VOL, 44, 10, v -> setMuted(!muted));
        posView = text("0:00", 13);
        durView = text("0:00", 13);
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) posView.setText(fmt(p));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                seeking = true;
                ui.removeCallbacks(hideRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                if (player != null) player.seekTo(s.getProgress());
                scheduleHide();
            }
        });
        bottom.addView(muteBtn);
        bottom.addView(posView);
        bottom.addView(seek, new LinearLayout.LayoutParams(0, -2, 1f));
        bottom.addView(durView);
        bottom.addView(chip("Aspect", v -> showAspect()));
        c.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return c;
    }

    // ====================================================================== gestures
    private void setupGestures(View touch) {
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                gestureMode = 0;
                gestureStartVol = currentVolumeLevel();
                gestureStartBri = currentBrightnessLevel();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                boolean right = e.getX() > touch.getWidth() / 2f;
                seekBy(right ? 10000 : -10000);
                showSeekHint(right);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e1 == null || e2 == null) return false;
                if (gestureMode == 0) {
                    float ax = Math.abs(e2.getX() - e1.getX()), ay = Math.abs(e2.getY() - e1.getY());
                    if (ay < dp(10) && ax < dp(10)) return true;
                    if (ay > ax * 1.3f) gestureMode = e1.getX() < touch.getWidth() / 2f ? 2 : 1;
                    else gestureMode = -1;
                }
                if (gestureMode < 0) return true;
                float h = Math.max(1, touch.getHeight());
                float delta = (e1.getY() - e2.getY()) / h * 1.3f;   // swipe up = positive
                if (gestureMode == 1) adjustVolume(delta); else adjustBrightness(delta);
                return true;
            }
        });
        touch.setOnTouchListener((v, ev) -> {
            gd.onTouchEvent(ev);
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                gestureMode = 0;
                ui.removeCallbacks(hideIndicators);
                ui.postDelayed(hideIndicators, 900);
            }
            return true;
        });
    }

    private void seekBy(long delta) {
        if (player == null) return;
        long pos = player.getCurrentPosition() + delta;
        long dur = player.getDuration();
        if (pos < 0) pos = 0;
        if (dur > 0 && dur != C.TIME_UNSET && pos > dur) pos = dur;
        player.seekTo(pos);
    }

    private void showSeekHint(boolean right) {
        seekLeft.setVisibility(right ? View.GONE : View.VISIBLE);
        seekRight.setVisibility(right ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(hideSeekHints);
        ui.postDelayed(hideSeekHints, 650);
    }

    private float currentVolumeLevel() {
        if (casting()) {
            try {
                CastSession s = castContext.getSessionManager().getCurrentCastSession();
                if (s != null) return (float) s.getVolume();
            } catch (Exception ignored) { }
            return 0.5f;
        }
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max <= 0 ? 0f : audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
    }

    private float currentBrightnessLevel() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b >= 0) return b;
        try {
            return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Exception e) {
            return 0.5f;
        }
    }

    private void adjustVolume(float delta) {
        float level = Math.max(0f, Math.min(1f, gestureStartVol + delta));
        if (casting()) {
            try {
                CastSession s = castContext.getSessionManager().getCurrentCastSession();
                if (s != null) s.setVolume(level);
            } catch (Exception ignored) { }
        } else {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(level * max), 0);
        }
        if (muted && level > 0f) setMuted(false);
        volHud.set(level <= 0f ? Ico.MUTE : Ico.VOL, level);
        volHud.setVisibility(View.VISIBLE);
        briHud.setVisibility(View.GONE);
    }

    private void adjustBrightness(float delta) {
        float level = Math.max(0.01f, Math.min(1f, gestureStartBri + delta));
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = level;
        getWindow().setAttributes(lp);
        briHud.set(Ico.SUN, level);
        briHud.setVisibility(View.VISIBLE);
        volHud.setVisibility(View.GONE);
    }

    private void setMuted(boolean m) {
        muted = m;
        muteBtn.setKind(m ? Ico.MUTE : Ico.VOL);
        try {
            if (casting()) {
                CastSession s = castContext.getSessionManager().getCurrentCastSession();
                if (s != null) s.setMute(m);
            } else if (player != null) {
                player.setVolume(m ? 0f : 1f);
            }
        } catch (Exception ignored) { }
    }

    // ====================================================================== aspect ratio
    private void showAspect() {
        List<String> labels = new ArrayList<>();
        labels.add("Fit (original)");
        labels.add("Fill (stretch)");
        labels.add("Zoom (crop)");
        labels.add("16:9");
        labels.add("4:3");
        labels.add("21:9");
        pickSingle("Aspect ratio", labels, aspectMode, which -> {
            aspectMode = which;
            prefs.edit().putInt("aspect", which).apply();
            applyAspect();
        });
    }

    private void applyAspect() {
        if (rootView == null || playerView == null) return;
        int w = rootView.getWidth(), h = rootView.getHeight();
        if (w == 0 || h == 0) return;
        int resize = AspectRatioFrameLayout.RESIZE_MODE_FIT;
        float ratio = 0f;
        switch (aspectMode) {
            case 1: resize = AspectRatioFrameLayout.RESIZE_MODE_FILL; break;
            case 2: resize = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; break;
            case 3: resize = AspectRatioFrameLayout.RESIZE_MODE_FILL; ratio = 16f / 9f; break;
            case 4: resize = AspectRatioFrameLayout.RESIZE_MODE_FILL; ratio = 4f / 3f; break;
            case 5: resize = AspectRatioFrameLayout.RESIZE_MODE_FILL; ratio = 21f / 9f; break;
            default: break;
        }
        int pw = -1, ph = -1;
        if (ratio > 0f) {
            if (w / (float) h > ratio) {
                ph = h;
                pw = Math.round(h * ratio);
            } else {
                pw = w;
                ph = Math.round(w / ratio);
            }
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) playerView.getLayoutParams();
        if (lp.width != pw || lp.height != ph) {
            lp.width = pw;
            lp.height = ph;
            lp.gravity = Gravity.CENTER;
            playerView.setLayoutParams(lp);
        }
        playerView.setResizeMode(resize);
    }

    // ====================================================================== resume
    private String resumeKey(int index) {
        JSONObject o = items.optJSONObject(index);
        if (o == null) return null;
        String s = o.optString("source");
        if (s.isEmpty()) s = o.optString("title");
        return s.isEmpty() ? null : "pos:" + s;
    }

    private long savedPosition(int index) {
        String k = resumeKey(index);
        return k == null ? 0 : prefs.getLong(k, 0);
    }

    private void clearSaved(int index) {
        String k = resumeKey(index);
        if (k != null) prefs.edit().remove(k).apply();
    }

    private void saveProgress() {
        if (player == null || prefs == null || resumePromptOpen) return;
        int idx = player.getCurrentMediaItemIndex();
        String k = resumeKey(idx);
        if (k == null) return;
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        boolean finished = player.getPlaybackState() == Player.STATE_ENDED
                || (dur > 0 && dur != C.TIME_UNSET && pos > dur * 0.95);
        if (finished) prefs.edit().remove(k).apply();
        else if (pos > RESUME_MIN_MS) prefs.edit().putLong(k, pos).apply();
    }

    private void promptResume(final int index) {
        final long saved = savedPosition(index);
        if (saved < RESUME_MIN_MS || player == null) return;
        player.setPlayWhenReady(false);
        resumePromptOpen = true;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Resume playback?")
                .setMessage("You already watched this video until " + fmt(saved) + ".")
                .setCancelable(false)
                .setPositiveButton("Resume from " + fmt(saved), (d, w) -> {
                    resumePromptOpen = false;
                    if (player != null) {
                        if (player.getCurrentMediaItemIndex() == index) player.seekTo(index, saved);
                        player.play();
                    }
                })
                .setNegativeButton("Start over", (d, w) -> {
                    resumePromptOpen = false;
                    clearSaved(index);
                    if (player != null) {
                        if (player.getCurrentMediaItemIndex() == index) player.seekTo(index, 0);
                        player.play();
                    }
                })
                .setOnDismissListener(x -> hideSystemUi())
                .show();
    }

    // ====================================================================== helpers
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void toggleControls() {
        if (controls.getVisibility() == View.VISIBLE) hideControls(); else showControls();
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        scheduleHide();
    }

    private void hideControls() {
        if (player != null && player.isPlaying()) controls.setVisibility(View.GONE);
    }

    private void scheduleHide() {
        ui.removeCallbacks(hideRunnable);
        ui.postDelayed(hideRunnable, 4000);
    }

    private void updateTitle() {
        if (player == null) return;
        int i = player.getCurrentMediaItemIndex();
        JSONObject o = items.optJSONObject(i);
        String t = o == null ? "" : o.optString("title");
        if (items.length() > 1) t = (i + 1) + "/" + items.length() + "  " + t;
        titleView.setText(t);
    }

    private void refreshButtons() {
        if (player == null) return;
        prevBtn.setAlpha(player.hasPreviousMediaItem() ? 1f : 0.35f);
        nextBtn.setAlpha(player.hasNextMediaItem() ? 1f : 0.35f);
    }

    private void updateProgress() {
        if (player == null) return;
        long dur = player.getDuration();
        long pos = player.getCurrentPosition();
        if (dur > 0 && dur != C.TIME_UNSET) {
            seek.setMax((int) Math.min(dur, Integer.MAX_VALUE));
            durView.setText(fmt(dur));
        }
        if (!seeking) {
            seek.setProgress((int) Math.min(pos, Integer.MAX_VALUE));
            posView.setText(fmt(pos));
        }
        seek.setSecondaryProgress((int) Math.min(player.getBufferedPosition(), Integer.MAX_VALUE));
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%d:%02d", m, sec);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void pickSingle(String title, List<String> labels, int checked, IntConsumer onPick) {
        CharSequence[] arr = labels.toArray(new CharSequence[0]);
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setSingleChoiceItems(arr, checked, (dialog, which) -> {
                    dialog.dismiss();
                    onPick.accept(which);
                })
                .create();
        d.setOnDismissListener(x -> {
            hideSystemUi();
            scheduleHide();
        });
        ui.removeCallbacks(hideRunnable);
        d.show();
    }

    private void pickAction(String title, List<String> labels, IntConsumer onPick) {
        CharSequence[] arr = labels.toArray(new CharSequence[0]);
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setItems(arr, (dialog, which) -> onPick.accept(which))
                .create();
        d.setOnDismissListener(x -> {
            hideSystemUi();
            scheduleHide();
        });
        ui.removeCallbacks(hideRunnable);
        d.show();
    }

    private static String langName(String code) {
        if (code == null || code.isEmpty() || "und".equals(code)) return null;
        try {
            String n = new Locale(code).getDisplayLanguage();
            return (n == null || n.isEmpty()) ? code : n;
        } catch (Exception e) {
            return code;
        }
    }

    private static String codecName(String mime) {
        if (mime == null) return "";
        switch (mime) {
            case MimeTypes.AUDIO_AAC: return "AAC";
            case MimeTypes.AUDIO_AC3: return "AC3";
            case MimeTypes.AUDIO_E_AC3: return "E-AC3";
            case MimeTypes.AUDIO_DTS: return "DTS";
            case MimeTypes.AUDIO_OPUS: return "Opus";
            case MimeTypes.AUDIO_MPEG: return "MP3";
            case MimeTypes.AUDIO_FLAC: return "FLAC";
            case MimeTypes.VIDEO_H264: return "H.264";
            case MimeTypes.VIDEO_H265: return "HEVC";
            case MimeTypes.APPLICATION_SUBRIP: return "SRT";
            case MimeTypes.TEXT_SSA: return "ASS";
            case MimeTypes.TEXT_VTT: return "VTT";
            default: return mime.substring(mime.indexOf('/') + 1);
        }
    }

    private static String describe(Format f, String fallback) {
        List<String> parts = new ArrayList<>();
        String lang = langName(f.language);
        if (f.label != null && !f.label.isEmpty()) parts.add(f.label);
        else if (lang != null) parts.add(lang);
        else parts.add(fallback);
        if (f.channelCount > 0) parts.add(f.channelCount == 2 ? "stereo" : f.channelCount + "ch");
        String codec = codecName(f.sampleMimeType);
        if (!codec.isEmpty()) parts.add(codec);
        return TextUtils.join(" \u00B7 ", parts);
    }

    private void applyOverride(int trackType, Tracks.Group g, int index) {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(trackType, false)
                .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), index))
                .build());
    }

    // ====================================================================== settings menu
    private void showMore() {
        List<String> labels = new ArrayList<>();
        labels.add("Media info");
        labels.add("Playback speed");
        labels.add("Decoder (hardware / software)");
        labels.add("Open in another player");
        pickAction("Options", labels, which -> {
            if (which == 0) showInfo();
            else if (which == 1) showSpeed();
            else if (which == 2) showDecoder();
            else openExternal();
        });
    }

    // ---------------------------------------------------------------- media info
    private static String human(long b) {
        if (b >= 1073741824L) return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
        if (b >= 1048576L) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return (b / 1024) + " KB";
    }

    private static String bitrateText(int bps) {
        if (bps <= 0) return null;
        return bps >= 1000000 ? String.format(Locale.US, "%.1f Mbps", bps / 1000000.0) : (bps / 1000) + " kbps";
    }

    private static String channelsText(int ch) {
        switch (ch) {
            case 1: return "mono";
            case 2: return "stereo";
            case 6: return "5.1";
            case 8: return "7.1";
            default: return ch + " ch";
        }
    }

    private static String containerName(String mime, String title) {
        String m = mime == null ? "" : mime;
        String n = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (m.contains("matroska") || n.endsWith(".mkv")) return "Matroska (MKV)";
        if (m.equals("video/webm") || n.endsWith(".webm")) return "WebM";
        if (m.equals("video/mp4") || n.endsWith(".mp4") || n.endsWith(".m4v")) return "MP4";
        if (m.equals("video/mp2t") || n.endsWith(".ts") || n.endsWith(".m2ts")) return "MPEG-TS";
        if (n.endsWith(".avi")) return "AVI";
        if (n.endsWith(".mov")) return "QuickTime (MOV)";
        if (!m.isEmpty()) return m;
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot + 1).toUpperCase(Locale.ROOT) : "-";
    }

    private static String videoLine(Format f) {
        List<String> parts = new ArrayList<>();
        String codec = codecName(f.sampleMimeType);
        if (!codec.isEmpty()) parts.add(codec);
        if (f.width > 0 && f.height > 0) parts.add(f.width + "x" + f.height);
        if (f.frameRate > 0) parts.add(String.format(Locale.US, "%.2f fps", f.frameRate));
        String br = bitrateText(f.bitrate > 0 ? f.bitrate : f.averageBitrate);
        if (br != null) parts.add(br);
        ColorInfo ci = f.colorInfo;
        if (ci != null && (ci.colorTransfer == C.COLOR_TRANSFER_ST2084 || ci.colorTransfer == C.COLOR_TRANSFER_HLG)) {
            parts.add("HDR");
        }
        return TextUtils.join(" \u00B7 ", parts);
    }

    private static String audioLine(Format f) {
        List<String> parts = new ArrayList<>();
        String lang = langName(f.language);
        if (f.label != null && !f.label.isEmpty()) parts.add(f.label);
        else if (lang != null) parts.add(lang);
        String codec = codecName(f.sampleMimeType);
        if (!codec.isEmpty()) parts.add(codec);
        if (f.channelCount > 0) parts.add(channelsText(f.channelCount));
        if (f.sampleRate > 0) parts.add(String.format(Locale.US, "%.1f kHz", f.sampleRate / 1000.0));
        String br = bitrateText(f.bitrate > 0 ? f.bitrate : f.averageBitrate);
        if (br != null) parts.add(br);
        return TextUtils.join(" \u00B7 ", parts);
    }

    private void showInfo() {
        if (player == null) return;
        JSONObject cur = items.optJSONObject(player.getCurrentMediaItemIndex());
        String title = cur == null ? "" : cur.optString("title");
        long size = cur == null ? 0 : cur.optLong("size", 0);
        long dur = player.getDuration();

        String video = null, container = null;
        List<String> audioSel = new ArrayList<>(), audioAll = new ArrayList<>();
        List<String> subSel = new ArrayList<>(), subAll = new ArrayList<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            for (int i = 0; i < g.length; i++) {
                Format f = g.getTrackFormat(i);
                if (container == null && f.containerMimeType != null) container = f.containerMimeType;
                boolean sel = g.isTrackSelected(i);
                if (g.getType() == C.TRACK_TYPE_VIDEO) {
                    if (sel || video == null) video = videoLine(f);
                } else if (g.getType() == C.TRACK_TYPE_AUDIO) {
                    String l = audioLine(f);
                    audioAll.add(l);
                    if (sel) audioSel.add(l);
                } else if (g.getType() == C.TRACK_TYPE_TEXT) {
                    String l = describe(f, "Subtitle");
                    subAll.add(l);
                    if (sel) subSel.add(l);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("FILE\n");
        sb.append("Name: ").append(title.isEmpty() ? "-" : title).append('\n');
        sb.append("Size: ").append(size > 0 ? human(size) : "-").append('\n');
        sb.append("Duration: ").append(dur > 0 && dur != C.TIME_UNSET ? fmt(dur) : "-").append('\n');
        sb.append("Container: ").append(containerName(container, title)).append('\n');
        String src = cur == null ? "" : cur.optString("source");
        if (!src.isEmpty()) {
            try {
                String host = Uri.parse(src).getHost();
                if (host != null) sb.append("Source: ").append(host).append('\n');
            } catch (Exception ignored) { }
        }

        sb.append("\nVIDEO\n").append(video == null || video.isEmpty() ? "No video track (audio only)" : video).append('\n');

        sb.append("\nAUDIO\n");
        if (audioAll.isEmpty()) sb.append("No audio track\n");
        else {
            sb.append(audioSel.isEmpty() ? audioAll.get(0) : audioSel.get(0)).append('\n');
            if (audioAll.size() > 1) {
                sb.append("\nAll audio tracks (").append(audioAll.size()).append("):\n");
                for (String a : audioAll) sb.append("\u2022 ").append(a).append('\n');
            }
        }

        sb.append("\nSUBTITLES\n");
        if (subAll.isEmpty()) sb.append("None in the file (you can load a file with CC)\n");
        else {
            sb.append(subSel.isEmpty() ? "Off" : "On: " + subSel.get(0)).append('\n');
            sb.append("Available (").append(subAll.size()).append("):\n");
            for (String a : subAll) sb.append("\u2022 ").append(a).append('\n');
        }

        sb.append("\nPLAYBACK\n");
        sb.append("Mode: ").append(casting() ? "Chromecast"
                : (softwareFirst ? "Software decoding first (FFmpeg)" : "Hardware decoding first")).append('\n');
        sb.append("Aspect ratio: ").append(new String[]{"Fit", "Fill", "Zoom", "16:9", "4:3", "21:9"}[Math.max(0, Math.min(5, aspectMode))]);

        ui.removeCallbacks(hideRunnable);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Media info")
                .setMessage(sb.toString())
                .setPositiveButton("Close", null)
                .setOnDismissListener(x -> {
                    hideSystemUi();
                    scheduleHide();
                })
                .show();
    }

    private void showDecoder() {
        if (casting()) {
            toast("Not available while casting");
            return;
        }
        List<String> labels = new ArrayList<>();
        labels.add("Hardware first (saves battery)");
        labels.add("Software first (FFmpeg: plays almost everything)");
        pickSingle("Decoder", labels, softwareFirst ? 1 : 0, which -> {
            boolean sw = which == 1;
            if (sw != softwareFirst) {
                rebuildPlayer(sw);
                toast(sw ? "Software decoding on" : "Hardware decoding first");
            }
        });
    }

    private void showSpeed() {
        final float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        List<String> labels = new ArrayList<>();
        int checked = 2;
        float cur = player.getPlaybackParameters().speed;
        for (int i = 0; i < speeds.length; i++) {
            labels.add(speeds[i] == 1f ? "Normal (1x)" : speeds[i] + "x");
            if (Math.abs(speeds[i] - cur) < 0.01f) checked = i;
        }
        pickSingle("Playback speed", labels, checked, which -> player.setPlaybackSpeed(speeds[which]));
    }

    // ====================================================================== audio
    private void showAudio() {
        List<String> labels = new ArrayList<>();
        List<Tracks.Group> groups = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        int checked = -1, hidden = 0;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) {
                    hidden++;
                    continue;
                }
                if (g.isTrackSelected(i)) checked = labels.size();
                labels.add(describe(g.getTrackFormat(i), "Audio " + (labels.size() + 1)));
                groups.add(g);
                idx.add(i);
            }
        }
        if (labels.isEmpty()) {
            toast(hidden > 0 ? "Audio format not supported here. Try Options > Decoder." : "No audio tracks found");
            return;
        }
        String title = hidden > 0 ? "Audio (" + hidden + " unsupported hidden)" : "Audio";
        pickSingle(title, labels, checked, which -> applyOverride(C.TRACK_TYPE_AUDIO, groups.get(which), idx.get(which)));
    }

    // ====================================================================== subtitles
    private void showSubtitles() {
        List<String> labels = new ArrayList<>();
        List<Tracks.Group> groups = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        labels.add("Off");
        groups.add(null);
        idx.add(-1);
        int checked = 0;
        boolean disabled = player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                if (!disabled && g.isTrackSelected(i)) checked = labels.size();
                labels.add(describe(g.getTrackFormat(i), "Subtitle " + labels.size()));
                groups.add(g);
                idx.add(i);
            }
        }
        labels.add("Load subtitle file\u2026");
        final int last = labels.size() - 1;
        pickSingle("Subtitles", labels, checked, which -> {
            if (which == 0) {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            } else if (which == last) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Choose subtitle file (.srt, .vtt, .ass)"), REQ_SUB);
                } catch (Exception e) {
                    toast("No file picker available");
                }
            } else {
                applyOverride(C.TRACK_TYPE_TEXT, groups.get(which), idx.get(which));
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SUB || res != RESULT_OK || data == null || data.getData() == null || player == null) return;
        Uri u = data.getData();
        String name = "Subtitle";
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception ignored) { }
        String lower = name.toLowerCase(Locale.ROOT);
        String mime = MimeTypes.APPLICATION_SUBRIP;
        if (lower.endsWith(".vtt")) mime = MimeTypes.TEXT_VTT;
        else if (lower.endsWith(".ass") || lower.endsWith(".ssa")) mime = MimeTypes.TEXT_SSA;
        else if (lower.endsWith(".ttml") || lower.endsWith(".xml")) mime = MimeTypes.APPLICATION_TTML;

        MediaItem.SubtitleConfiguration sc = new MediaItem.SubtitleConfiguration.Builder(u)
                .setMimeType(mime)
                .setLanguage("und")
                .setLabel(name)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build();
        int index = player.getCurrentMediaItemIndex();
        long pos = player.getCurrentPosition();
        MediaItem n = player.getCurrentMediaItem().buildUpon()
                .setSubtitleConfigurations(Collections.singletonList(sc)).build();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
        player.replaceMediaItem(index, n);
        player.seekTo(index, pos);
        player.play();
        toast("Subtitle loaded: " + name);
    }

    // ====================================================================== quality
    private void showQuality() {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        int checked = 0;

        labels.add("Auto / original");
        actions.add(() -> {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build());
            if (originalUrl() != null) switchSource(originalUrl());
        });

        // video tracks inside the stream (adaptive streams)
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO || g.length < 2) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                final Tracks.Group gg = g;
                final int ii = i;
                Format f = g.getTrackFormat(i);
                String l = (f.height > 0 ? f.height + "p" : "Video " + (i + 1));
                if (f.bitrate > 0) l += " \u00B7 " + String.format(Locale.US, "%.1f Mbps", f.bitrate / 1000000.0);
                if (g.isTrackSelected(i)) checked = labels.size();
                labels.add(l);
                actions.add(() -> applyOverride(C.TRACK_TYPE_VIDEO, gg, ii));
            }
        }

        // alternative qualities provided by AllDebrid for this link
        JSONObject cur = items.optJSONObject(player.getCurrentMediaItemIndex());
        JSONArray streams = cur == null ? null : cur.optJSONArray("streams");
        final String gen = cur == null ? "" : cur.optString("id");
        if (streams != null && streams.length() > 1 && !gen.isEmpty()) {
            for (int i = 0; i < streams.length(); i++) {
                JSONObject s = streams.optJSONObject(i);
                if (s == null) continue;
                final String sid = s.optString("id");
                String q = s.optString("quality");
                String l = q.matches("\\d+") ? q + "p" : q;
                l += " \u00B7 " + s.optString("ext");
                long size = s.optLong("size", 0);
                if (size > 0) l += " \u00B7 " + (size / 1048576) + " MB";
                labels.add(l);
                actions.add(() -> resolveAndSwitch(gen, sid));
            }
        }

        if (labels.size() <= 1) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Quality")
                    .setMessage("Only the original quality is available for this file.")
                    .setPositiveButton("OK", null)
                    .setOnDismissListener(x -> hideSystemUi())
                    .show();
            return;
        }
        pickSingle("Quality", labels, checked, which -> actions.get(which).run());
    }

    private String originalUrl() {
        JSONObject cur = items.optJSONObject(player.getCurrentMediaItemIndex());
        String u = cur == null ? "" : cur.optString("url");
        return u.isEmpty() ? null : u;
    }

    private void resolveAndSwitch(final String gen, final String stream) {
        toast("Preparing quality\u2026");
        new Thread(() -> {
            try {
                final String url = resolveStream(gen, stream);
                runOnUiThread(() -> switchSource(url));
            } catch (final Exception e) {
                runOnUiThread(() -> toast("Could not switch quality: " + e.getMessage()));
            }
        }).start();
    }

    private void switchSource(String url) {
        if (player == null) return;
        int index = player.getCurrentMediaItemIndex();
        long pos = player.getCurrentPosition();
        boolean was = player.isPlaying() || player.getPlayWhenReady();
        MediaItem n = player.getMediaItemAt(index).buildUpon().setUri(url).build();
        player.replaceMediaItem(index, n);
        player.seekTo(index, pos);
        player.setPlayWhenReady(was);
    }

    private String resolveStream(String gen, String stream) throws Exception {
        JSONObject r = post(API + "/v4/link/streaming",
                "id=" + URLEncoder.encode(gen, "UTF-8") + "&stream=" + URLEncoder.encode(stream, "UTF-8"));
        if (!"success".equals(r.optString("status"))) {
            JSONObject err = r.optJSONObject("error");
            throw new Exception(err != null ? err.optString("message") : "API error");
        }
        JSONObject d = r.getJSONObject("data");
        String link = d.optString("link", "");
        if (!link.isEmpty()) return link;
        long delayed = d.optLong("delayed", 0);
        for (int i = 0; i < 24; i++) {
            Thread.sleep(3000);
            JSONObject q = post(API + "/v4/link/delayed", "id=" + delayed);
            JSONObject qd = q.optJSONObject("data");
            if (qd == null) continue;
            if (qd.optInt("status") == 2) return qd.getString("link");
            if (qd.optInt("status") == 3) throw new Exception("could not generate the link");
        }
        throw new Exception("timed out");
    }

    private JSONObject post(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] d = body.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(d.length);
            OutputStream os = c.getOutputStream();
            os.write(d);
            os.close();
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (is != null && (n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    // ====================================================================== external player / errors
    private void openExternal() {
        String url = null;
        if (player != null) {
            MediaItem m = player.getCurrentMediaItem();
            if (m != null && m.localConfiguration != null) url = m.localConfiguration.uri.toString();
        }
        if (url == null) url = originalUrl();
        if (url == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(url), "video/*");
            startActivity(Intent.createChooser(i, "Open with"));
            if (player != null && !casting()) player.pause();
        } catch (Exception e) {
            toast("No other player found");
        }
    }

    private void showPlaybackError(PlaybackException e) {
        String extra = casting()
                ? "The TV could not play this file. Chromecast supports MP4 (H.264/AAC), WebM and HLS best. "
                + "Stop casting (cast button) to play it on this phone."
                : "The built-in player could not decode this video or audio. Try Options > Decoder (Software first), "
                + "pick another audio track, or open the file in another player (VLC, MX Player...).";
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Playback problem")
                .setMessage(e.getErrorCodeName() + "\n\n" + extra)
                .setPositiveButton("Open in other player", (d, w) -> openExternal())
                .setNegativeButton("Close", null)
                .setOnDismissListener(x -> hideSystemUi())
                .show();
    }
}
