package com.bryce.grainlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.ScrollView;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** On-camera JPEG editor; camera settings and originals are never written. */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final File card = Environment.getExternalStorageDirectory();
    private List<File> files = new ArrayList<File>();
    private final LinkedHashMap<String, Bitmap> thumbnails = new LinkedHashMap<String, Bitmap>(16, .75f, true);
    private final Set<String> pendingThumbnails = new HashSet<String>(), badThumbnails = new HashSet<String>();
    private volatile int generation, thumbnailGeneration;
    private volatile boolean cancelRequested;
    private Screen screen;
    private int index, row, preset;
    private int[] effects = FilmSettings.defaults();
    private boolean editing, copies, original, detail, saving, loading, resumed;
    private Bitmap bitmap;
    private String status = "";
    private double[] lastTimings;
    private String lastBackend="";
    private String lastGallery="";
    private volatile boolean indexing;
    private boolean probing;
    private final Runnable requestPreview = new Runnable() { public void run() { loadPreview(); } };
    private final Runnable requestThumbnails = new Runnable() { public void run() { loadThumbnails(); } };
    private final Runnable progress = new Runnable() { public void run() {
        if (!saving || !resumed) return;
        int stage=NativeGrain.stage();
        status=indexing?t(R.string.indexing):t(stage==1?R.string.preparing:stage==3?R.string.flushing:R.string.saving,NativeGrain.progress());
        screen.invalidate(); handler.postDelayed(this, 200);
    }};
    private static final int[] LABELS = {R.string.strength, R.string.size, R.string.seed,
        R.string.halation, R.string.halation_radius, R.string.highlight_threshold, R.string.bloom,
        R.string.bloom_radius, R.string.diffusion, R.string.vignette, R.string.light_leak,
        R.string.dust, R.string.scratches, R.string.aberration, R.string.distortion, R.string.edge_softness,
        R.string.output_size,R.string.compare,R.string.preset,R.string.store_preset,R.string.save_timings,R.string.sony_test,R.string.save};
    private String t(int id, Object... args) { return Lang.t(this, id, args); }
    private String buildLabel(){return t(R.string.build_stamp,BuildInfo.NUMBER,BuildInfo.BUILT_AT);}

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SharedPreferences p = getPreferences(0);
        for (int i = 0; i < effects.length; i++) effects[i] = p.getInt("effect." + i, effects[i]);
        if (!FilmSettings.valid(effects)) effects = FilmSettings.defaults();
        preset = -1;
        String record=p.getString("lastTimings","");
        if(record.length()>0){
            try{String[] parts=record.split(",");if(parts.length==6){lastTimings=new double[6];for(int i=0;i<6;i++)lastTimings[i]=Double.parseDouble(parts[i]);}}
            catch(RuntimeException e){lastTimings=null;}
        }
        lastBackend=p.getString("lastBackend","");
        lastGallery=p.getString("lastGallery","");
        if (state != null && FilmSettings.valid(state.getIntArray("effects"))) effects = state.getIntArray("effects");
        screen = new Screen(); setContentView(screen);
    }
    @Override protected void onResume() { super.onResume(); resumed = true; if (!saving) scan(); }
    @Override protected void onPause() {
        resumed = false; generation++; thumbnailGeneration++;
        handler.removeCallbacks(requestPreview); handler.removeCallbacks(requestThumbnails); handler.removeCallbacks(progress);
        if (NativeGrain.AVAILABLE) NativeGrain.cancel();
        if (saving) cancelRequested = true;
        if(indexing)cancelIndexing();
        SharedPreferences.Editor p = getPreferences(0).edit();
        for (int i = 0; i < effects.length; i++) p.putInt("effect." + i, effects[i]); p.commit();
        super.onPause();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null); worker.shutdown(); clearThumbnails();
        if (bitmap != null) { bitmap.recycle(); bitmap = null; }
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putIntArray("effects", effects); super.onSaveInstanceState(state); }
    private void scan() {
        final int token = ++generation; final boolean showCopies = copies;
        final String selected = editing && !files.isEmpty() ? files.get(index).getPath() : null;
        loading = true; status = t(R.string.loading); clearThumbnails(); screen.invalidate();
        worker.execute(new Runnable() { public void run() {
            List<File> found = new ArrayList<File>(); String error = "";
            try { found = GrainFiles.list(card, showCopies); } catch (Exception e) { error = e.getMessage(); }
            final List<File> result = found; final String message = error;
            handler.post(new Runnable() { public void run() {
                if (!resumed || token != generation || isFinishing()) return;
                files = result; index = Math.min(index, Math.max(0, files.size() - 1));
                if (selected != null) {
                    int match = -1; for (int n = 0; n < files.size(); n++) if (selected.equals(files.get(n).getPath())) match = n;
                    if (match >= 0) index = match; else editing = false;
                }
                if (files.isEmpty()) editing = false;
                loading = false; status = message; screen.invalidate();
                if (editing) schedulePreview(); else scheduleThumbnails();
            }});
        }});
    }
    private String thumbKey(File file, int limit) { return file.getPath() + ":" + file.length() + ":" + file.lastModified() + ":" + limit; }
    private void clearThumbnails() {
        thumbnailGeneration++; handler.removeCallbacks(requestThumbnails);
        for (Bitmap b : thumbnails.values()) b.recycle(); thumbnails.clear(); pendingThumbnails.clear(); badThumbnails.clear();
    }
    private void scheduleThumbnails() {
        thumbnailGeneration++; pendingThumbnails.clear();
        handler.removeCallbacks(requestThumbnails); handler.postDelayed(requestThumbnails, 80);
    }
    private void loadThumbnails() {
        if (editing || saving || files.isEmpty() || !resumed) return;
        final int token = thumbnailGeneration;
        requestThumbnail(files.get(index), 384, token);
        requestThumbnail(files.get(index), 160, token);
        int start = FilmSettings.gridStart(index);
        for (int n = start; n < Math.min(files.size(), start + 6); n++) requestThumbnail(files.get(n), 160, token);
    }
    private void requestThumbnail(final File file, final int limit, final int token) {
        final String key = thumbKey(file, limit);
        if (thumbnails.containsKey(key) || pendingThumbnails.contains(key) || badThumbnails.contains(key)) return;
        pendingThumbnails.add(key);
        worker.execute(new Runnable() { public void run() {
            if (token != thumbnailGeneration) return;
            Bitmap decoded = null;
            try { decoded = PhotoBitmaps.thumbnail(file, limit); } catch (Exception e) {} catch (OutOfMemoryError e) {}
            final Bitmap result = decoded;
            handler.post(new Runnable() { public void run() {
                if (!resumed || token != thumbnailGeneration || isFinishing()) { if (result != null) result.recycle(); return; }
                pendingThumbnails.remove(key);
                if (result == null) badThumbnails.add(key);
                else {
                    Bitmap old = thumbnails.put(key, result); if (old != null) old.recycle();
                    while (thumbnails.size() > 12) {
                        Iterator<Map.Entry<String, Bitmap>> it = thumbnails.entrySet().iterator();
                        Map.Entry<String, Bitmap> entry = it.next(); entry.getValue().recycle(); it.remove();
                    }
                }
                screen.invalidate();
            }});
        }});
    }
    private void schedulePreview() {
        generation++; handler.removeCallbacks(requestPreview);
        if (NativeGrain.AVAILABLE) NativeGrain.cancel();
        loading = true; status = t(R.string.loading); screen.invalidate(); handler.postDelayed(requestPreview, 150);
    }
    private void loadPreview() {
        if (!editing || saving || files.isEmpty()) return;
        final int token = generation; final int[] settings = original ? FilmSettings.off() : effects.clone();
        final File source = files.get(index); final boolean crop = detail;
        worker.execute(new Runnable() { public void run() {
            if (token != generation) return;
            Bitmap decoded = null; String error = null;
            try {
                if (!NativeGrain.AVAILABLE) throw new IOException(t(R.string.native_missing));
                NativeGrain.reset(); int[] rendered = NativeGrain.preview(source.getPath(), settings, crop);
                if (rendered == null) throw new IOException("Preview unavailable");
                decoded = Bitmap.createBitmap(rendered, 2, rendered[0], rendered[0], rendered[1], Bitmap.Config.ARGB_8888);
                decoded = PhotoBitmaps.orient(decoded, PhotoBitmaps.orientation(source));
            } catch (Exception e) { error = e.getMessage(); } catch (OutOfMemoryError e) { error = "Not enough memory for preview"; }
            final Bitmap result = decoded; final String message = error;
            handler.post(new Runnable() { public void run() {
                if (!resumed || token != generation || isFinishing()) { if (result != null) result.recycle(); return; }
                loading = false; if (bitmap != null) bitmap.recycle(); bitmap = message == null ? result : null;
                if (message != null && result != null) result.recycle();
                status = message == null ? "" : t(R.string.failed, message); screen.invalidate();
            }});
        }});
    }
    private void save() {
        if(saving||probing||loading||bitmap==null||files.isEmpty()||!NativeGrain.AVAILABLE||SonyCameraSave.busy()||SonyProbe.busy())return;
        final File source = files.get(index); final int[] settings = effects.clone();
        saving = true; cancelRequested = false; generation++; NativeGrain.reset();
        status = t(R.string.saving, 0); handler.post(progress); screen.invalidate();
        worker.execute(new Runnable() { public void run() {
            File output = null, temporary = null; String error = null; boolean complete = false, temporaryOwned = false;
            SonyGallery.Result registration=null;
            try {
                if (cancelRequested) throw new IOException("Cancelled");
                output = GrainFiles.reserve(card); temporary = new File(output.getParentFile(), output.getName().replace(".JPG", ".TMP"));
                if (!temporary.createNewFile()) throw new IOException("Temporary file already exists"); temporaryOwned = true;
                NativeGrain.process(source.getPath(), temporary.getPath(), settings);
                if (cancelRequested) throw new IOException("Cancelled");
                if (!output.delete() || !temporary.renameTo(output)) throw new IOException("Could not finalize effect copy"); complete = true;
                indexing=true;
                String identity="Build "+BuildInfo.NUMBER+" - "+BuildInfo.BUILT_AT;
                try{android.content.pm.PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),0);identity+="; installed "+info.versionName+" ("+info.versionCode+")";}catch(Exception e){}
                registration=SonyCameraSave.save(getContentResolver(),card,source,output,identity);
            } catch (Exception e) { error = e.getMessage(); }
              catch(LinkageError e){error=e.toString();}
            finally {
                indexing=false;
                if (!complete && output != null) output.delete();
                if (temporaryOwned && temporary != null && temporary.exists()) temporary.delete();
                if(complete&&registration==null)SonyCameraSave.report(output,"Processed JPEG saved, but Sony export could not complete: "+error+"\n");
            }
            String path="";try{if(output!=null)path=GrainFiles.relative(card,output);}catch(IOException e){path=output.getPath();}
            final String name=path,message=error;
            final SonyGallery.Result indexed=registration;
            final boolean cancelled = !complete && (cancelRequested || "Cancelled".equals(message));
            final double[] measured=NativeGrain.timings();final String backend=NativeGrain.backend();
            if(complete&&measured!=null){
                String record=String.format(Locale.US,"%.3f,%.3f,%.3f,%.3f,%.3f,%.3f",measured[0],measured[1],measured[2],measured[3],measured[4],measured[5]);
                String report=indexed==null?"Not requested":indexed.detail+" ("+indexed.millis+" ms)";
                getPreferences(0).edit().putString("lastTimings",record).putString("lastBackend",backend).putString("lastGallery",report).commit();
            }
            handler.post(new Runnable() { public void run() {
                saving = false; handler.removeCallbacks(progress); if (isFinishing()) return;
                status=cancelled?t(R.string.cancelled):message!=null?t(R.string.failed,message):indexed!=null&&indexed.indexed?t(R.string.saved,name):indexed!=null&&indexed.saved?t(R.string.saved_accepted,name):t(R.string.saved_pending,name);
                if(message==null&&!cancelled){lastTimings=measured;lastBackend=backend;lastGallery=indexed==null?"Not requested":indexed.detail+" ("+indexed.millis+" ms)";}
                screen.invalidate(); if (resumed && bitmap == null) schedulePreview();
            }});
        }});
    }
    private void cancelIndexing(){
        // Sony's export has no cancellation API. Keep its image alive until completion.
    }
    private void showTimings(){
        String message=lastTimings==null?t(R.string.no_timings):t(R.string.timings_message,lastTimings[0],lastTimings[1],lastTimings[2],lastTimings[3],lastTimings[4],lastTimings[5])+"\n\n"+lastBackend+"\n"+t(R.string.gallery_report,lastGallery);
        showReport(R.string.save_timings,buildLabel()+"\n\n"+message);
    }
    private void showReport(int titleId,String message){
        TextView text=new TextView(this);text.setTextColor(Color.WHITE);text.setTextSize(16);text.setPadding(18,12,18,12);text.setTypeface(screen.paint.getTypeface());text.setText(message);
        ScrollView scroll=new ScrollView(this);scroll.addView(text);
        TextView title=new TextView(this);title.setText(t(titleId));title.setTextSize(19);title.setTextColor(Color.WHITE);title.setPadding(18,12,18,4);title.setTypeface(screen.paint.getTypeface());
        AlertDialog dialog=new AlertDialog.Builder(this).setCustomTitle(title).setView(scroll).setPositiveButton(t(R.string.close),null).create();
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener(){public boolean onKey(DialogInterface dialog,int code,KeyEvent event){
            int scan=event.getScanCode();
            if(scan==514||scan==229||scan==232||code==KeyEvent.KEYCODE_BACK||code==KeyEvent.KEYCODE_MENU||code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER){
                if(event.getAction()==KeyEvent.ACTION_DOWN)dialog.dismiss();return true;
            }return false;
        }});
        dialog.show();dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTypeface(screen.paint.getTypeface());
    }
    private void testSony(){
        if(saving||probing||files.isEmpty()||SonyCameraSave.busy()||SonyProbe.busy())return;
        final File source=files.get(index);probing=true;status=t(R.string.sony_testing);screen.invalidate();
        worker.execute(new Runnable(){public void run(){
            final String report=SonyProbe.run(getContentResolver(),source,card,"Build "+BuildInfo.NUMBER+" - "+BuildInfo.BUILT_AT);
            getPreferences(0).edit().putString("sonyProbe",report).commit();
            handler.post(new Runnable(){public void run(){probing=false;if(!resumed||isFinishing())return;status=t(R.string.sony_test_done);screen.invalidate();showReport(R.string.sony_test,t(R.string.sony_test_note)+"\n\n"+report);}});
        }});
    }
    private void storePreset() {
        final int[] snapshot = effects.clone();
        worker.execute(new Runnable() { public void run() {
            String error = null; try { FilmPresets.save(getFilesDir(), snapshot); } catch (IOException e) { error = e.getMessage(); }
            final String message = error;
            handler.post(new Runnable() { public void run() {
                if (isFinishing()) return; status = message == null ? t(R.string.preset_stored) : t(R.string.failed, message); screen.invalidate();
            }});
        }});
    }
    private void choosePreset(int direction) {
        preset = (preset + direction + 5) % 5;
        try { effects = preset == 4 ? FilmPresets.load(getFilesDir()) : FilmPresets.builtIn(preset); }
        catch (IOException e) { status = t(R.string.preset_missing); preset = -1; screen.invalidate(); return; }
        schedulePreview();
    }
    private void move(int amount) {
        if (saving) return;
        if (!editing) { index = FilmSettings.gridMove(index, amount, files.size()); scheduleThumbnails(); }
        else if (row < FilmSettings.COUNT) { FilmSettings.adjust(effects, row, amount); preset = -1; schedulePreview(); }
        else if (row == FilmSettings.VIEW) { original = !original; schedulePreview(); }
        else if (row == FilmSettings.PRESET) choosePreset(amount);
        screen.invalidate();
    }
    private void activate() {
        if (saving) return;
        if (!editing && !loading && !files.isEmpty()) { editing = true; row = 0; original = false; clearThumbnails(); schedulePreview(); }
        else if (editing) {
            if(row==FilmSettings.SAVE)save();else if(row==FilmSettings.STORE)storePreset();else if(row==FilmSettings.TIMINGS)showTimings();else if(row==FilmSettings.SONY_TEST)testSony();else move(1);
        }
    }
    private void back() {
        if(saving){cancelRequested=true;NativeGrain.cancel();if(indexing)cancelIndexing();status=t(indexing?R.string.index_cancel:R.string.cancelling);screen.invalidate();return;}
        if (editing) {
            editing = false; generation++; handler.removeCallbacks(requestPreview); loading = false; status = "";
            if (NativeGrain.AVAILABLE) NativeGrain.cancel();
            if (bitmap != null) { bitmap.recycle(); bitmap = null; }
            scheduleThumbnails(); screen.invalidate();
        } else finish();
    }
    private void toggle() {
        if (saving) return;
        if (editing) { detail = !detail; schedulePreview(); } else { copies = !copies; index = 0; scan(); }
    }
    @Override public boolean onKeyDown(int code, KeyEvent event) {
        int scan = event.getScanCode();
        if (scan == 514 || scan == 229 || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) { if (event.getRepeatCount() == 0) back(); return true; }
        if (saving) return true;
        if (scan == 595 || scan == 513 || code == KeyEvent.KEYCODE_DEL) { if (event.getRepeatCount() == 0) toggle(); return true; }
        if (scan == 232 || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) { if (event.getRepeatCount() == 0) activate(); return true; }
        if (scan == 522 || scan == 525 || scan == 105 || code == KeyEvent.KEYCODE_DPAD_LEFT) { move(-1); return true; }
        if (scan == 523 || scan == 526 || scan == 106 || code == KeyEvent.KEYCODE_DPAD_RIGHT) { move(1); return true; }
        if (scan == 103 || code == KeyEvent.KEYCODE_DPAD_UP) { if (editing) row = (row + FilmSettings.ROWS - 1) % FilmSettings.ROWS; else move(-2); screen.invalidate(); return true; }
        if (scan == 108 || code == KeyEvent.KEYCODE_DPAD_DOWN) { if (editing) row = (row + 1) % FilmSettings.ROWS; else move(2); screen.invalidate(); return true; }
        return super.onKeyDown(code, event);
    }
    @Override public boolean onKeyUp(int code, KeyEvent event) { return true; }

    private String value(int setting) {
        if (setting < FilmSettings.COUNT) {
            if(setting==FilmSettings.OUTPUT)return t(effects[setting]==0?R.string.full_resolution:R.string.half_resolution);
            int v = effects[setting]; if (setting == FilmSettings.SIZE || setting == FilmSettings.SEED) return String.valueOf(v);
            if (setting == FilmSettings.THRESHOLD) return v + "%";
            return v == 0 ? t(R.string.off) : String.valueOf(v);
        }
        if (setting == FilmSettings.VIEW) return t(original ? R.string.original : R.string.effects);
        if (setting == FilmSettings.PRESET) {
            int[] names = {R.string.preset_grain, R.string.preset_soft, R.string.preset_night, R.string.preset_aged, R.string.preset_user};
            return t(preset < 0 ? R.string.preset_custom : names[preset]);
        }
        return t(R.string.press_centre);
    }
    private final class Screen extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Screen() {
            super(MainActivity.this); setFocusable(true); setFocusableInTouchMode(true);
            Locale locale = getResources().getConfiguration().locale;
            if ("zh".equals(locale.getLanguage())) {
                boolean traditional = "TW".equals(locale.getCountry()) || "HK".equals(locale.getCountry()) || "MO".equals(locale.getCountry());
                paint.setTypeface(Typeface.createFromAsset(getAssets(), traditional ? "fonts/GrainLabCJKtc.ttf" : "fonts/GrainLabCJKsc.ttf"));
            }
        }
        private void text(Canvas c, String s, float x, float y, float size, int ink) { paint.setColor(ink); paint.setTextSize(size); c.drawText(s, x, y, paint); }
        private String fit(String s, float width, float size) {
            paint.setTextSize(size); if (paint.measureText(s) <= width) return s;
            while (s.length() > 0 && paint.measureText(s + "…") > width) s = s.substring(0, s.length() - 1); return s + "…";
        }
        private void image(Canvas c, Bitmap image, RectF area) {
            paint.setColor(0xff292b2e); c.drawRect(area, paint);
            if (image == null) return;
            float scale = Math.min(area.width() / image.getWidth(), area.height() / image.getHeight());
            float w = image.getWidth() * scale, h = image.getHeight() * scale;
            c.drawBitmap(image, null, new RectF(area.centerX() - w / 2, area.centerY() - h / 2, area.centerX() + w / 2, area.centerY() + h / 2), paint);
        }
        private int editorStart() { return Math.min(Math.max(0, row - 2), FilmSettings.ROWS - 6); }
        @Override protected void onDraw(Canvas c) {
            c.save(); c.scale(getWidth() / 640f, getHeight() / 480f); c.drawColor(Color.rgb(23, 24, 26));
            text(c, t(R.string.app_name), 14, 30, 22, 0xffd6b47a);
            text(c, buildLabel(), 14, 46, 11, Color.LTGRAY);
            text(c, fit(t(editing ? (detail ? R.string.detail : R.string.fit) : (copies ? R.string.copies : R.string.originals)), 274, 15), 352, 30, 15, Color.LTGRAY);
            if (!editing) {
                if (files.isEmpty() && !loading) { text(c, t(R.string.empty), 18, 120, 24, Color.WHITE); text(c, fit(t(R.string.empty_hint), 600, 16), 18, 156, 16, Color.LTGRAY); }
                int start = FilmSettings.gridStart(index);
                for (int i = start; i < Math.min(files.size(), start + 6); i++) {
                    int cell = i - start; float x = 12 + cell % 2 * 162, y = 52 + cell / 2 * 108;
                    paint.setColor(i == index ? 0xffd6b47a : 0xff424549); c.drawRect(x - 2, y - 2, x + 152, y + 101, paint);
                    String key = thumbKey(files.get(i), 160);
                    image(c, thumbnails.get(key), new RectF(x, y, x + 150, y + 78));
                    if (!thumbnails.containsKey(key)) text(c, fit(t(badThumbnails.contains(key) ? R.string.preview_unavailable : R.string.loading), 140, 12), x + 5, y + 43, 12, Color.LTGRAY);
                    text(c, fit(files.get(i).getName(), 142, 14), x + 4, y + 96, 14, i == index ? 0xff17181a : Color.WHITE);
                }
                if (!files.isEmpty()) {
                    File selected = files.get(index);
                    String key = thumbKey(selected, 384);
                    image(c, thumbnails.get(key), new RectF(352, 52, 626, 316));
                    if (!thumbnails.containsKey(key)) text(c, fit(t(badThumbnails.contains(key) ? R.string.preview_unavailable : R.string.loading), 256, 15), 362, 190, 15, Color.LTGRAY);
                    text(c, fit(selected.getName(), 270, 20), 352, 347, 20, Color.WHITE);
                    text(c, fit(selected.getParentFile().getName(), 270, 15), 352, 371, 15, Color.LTGRAY);
                    text(c, (index + 1) + " / " + files.size(), 352, 400, 16, Color.LTGRAY);
                }
                text(c, fit(status, 602, 15), 14, 429, 15, 0xffd6b47a);
                text(c, fit(t(R.string.browser_hint), 612, 13), 14, 465, 13, Color.LTGRAY);
            } else {
                image(c, bitmap, new RectF(12, 52, 376, 324));
                text(c, fit(files.get(index).getName(), 362, 16), 14, 349, 16, Color.WHITE);
                text(c, fit(t(R.string.detail_hint), 362, 13), 14, 371, 13, Color.LTGRAY);
                int start = editorStart();
                for (int i = start; i < start + 6; i++) {
                    float y = 52 + (i - start) * 52;
                    paint.setColor(row == i ? 0xffd6b47a : 0xff343638); c.drawRect(392, y, 626, y + 46, paint);
                    int ink = row == i ? 0xff17181a : Color.WHITE;
                    text(c, fit(t(LABELS[i]), 216, 16), 402, y + 20, 16, ink);
                    text(c, fit(value(i), 216, 14), 402, y + 39, 14, ink);
                }
                text(c, (row + 1) + " / " + FilmSettings.ROWS, 402, 380, 13, Color.LTGRAY);
                String note = status.length() > 0 ? status : !saving && row == FilmSettings.THRESHOLD ? t(R.string.threshold_hint) : t(R.string.processing_note);
                text(c, fit(note, 604, 14), 14, 401, 14, 0xffd6b47a);
                if (saving) { paint.setColor(0xffd6b47a); c.drawRect(14, 409, 14 + 612 * NativeGrain.progress() / 100f, 413, paint); }
                int[] actions = {original ? R.string.original : R.string.effects, R.string.preset, R.string.save};
                for (int i = 0; i < 3; i++) { float x = 12 + i * 208; paint.setColor(0xff40372a); c.drawRect(x, 420, x + 200, 447, paint); text(c, fit(t(actions[i]), 184, 15), x + 8, 439, 15, Color.WHITE); }
                text(c, fit(t(saving ? R.string.cancel_hint : R.string.editor_hint), 612, 13), 14, 470, 13, Color.LTGRAY);
            }
            c.restore();
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            float x = e.getX() * 640 / getWidth(), y = e.getY() * 480 / getHeight();
            if (saving) { if (y > 447) back(); return true; }
            if (y < 42) { if (x > 320) toggle(); else back(); return true; }
            if (!editing) {
                if (x >= 12 && x < 336 && y >= 52 && y < 376) {
                    int hit = FilmSettings.gridStart(index) + (int)((y - 52) / 108) * 2 + (int)((x - 12) / 162);
                    if (hit < files.size()) { index = hit; scheduleThumbnails(); }
                } else if (x >= 352 && y < 400) activate();
            } else if (x >= 392 && y >= 52 && y < 364) {
                row = editorStart() + (int)((y - 52) / 52);
                if(row>=FilmSettings.STORE)activate();else move(x<509?-1:1);
            } else if (y >= 420 && y <= 447) {
                int action = Math.min(2, Math.max(0, (int)((x - 12) / 208)));
                if (action == 0) { original = !original; schedulePreview(); }
                else if (action == 1) { row = FilmSettings.PRESET; choosePreset(1); } else save();
            } else if (x < 376 && y > 324 && y < 380) toggle();
            screen.invalidate(); return true;
        }
    }
}
