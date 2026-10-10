package com.tvracer.lite;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.Random;

/**
 * Pseudo-3D racer drawn with plain Canvas shapes (no bitmaps, no engine, no libraries).
 * Renders at a fixed 960x540 surface that Android scales to the TV: low RAM, low CPU.
 */
final class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    // ---- render / road constants -------------------------------------------------------
    static final int W = 960, H = 540;              // internal resolution (change for sharper/faster)
    static final int SEG = 200;                      // segment length
    static final int ROAD_W = 2000;                  // road half width (world units)
    static final int LANES = 3;
    static final int RUMBLE = 3;                     // segments per colour stripe
    static final float CAM_H = 1000f;
    static final float CAM_DEPTH = (float) (1.0 / Math.tan(Math.toRadians(50)));   // fov 100
    static final int DRAW = 60;                      // draw distance in segments
    static final float STEP = 1f / 60f;              // physics step
    static final long FRAME_MS = 33;                 // ~30 fps cap saves CPU on weak TV boxes
    static final float PLAYER_Z = CAM_H * CAM_DEPTH;
    static final float CAR_W = 0.3f;                 // car width in road units
    static final float PROP_W = 0.45f;
    static final int MAXSEG = 4000;
    static final int LAPS = 3;
    static final int CARS = 16;

    // ---- physics -------------------------------------------------------------------------
    static final float MAX_SPEED = SEG / STEP;
    static final float ACCEL = MAX_SPEED / 5f;
    static final float BRAKE = -MAX_SPEED;
    static final float DECEL = -MAX_SPEED / 5f;
    static final float OFFROAD_DECEL = -MAX_SPEED / 2f;
    static final float OFFROAD_LIMIT = MAX_SPEED / 4f;
    static final float CENTRIFUGAL = 0.3f;

    // ---- states ---------------------------------------------------------------------------
    static final int S_LOADING = 0, S_TITLE = 1, S_COUNT = 2, S_RACE = 3, S_PAUSE = 4, S_FINISH = 5;

    private final Activity act;
    private final SharedPreferences prefs;
    private final Object lock = new Object();
    private final ThemeStore store;
    private ThemeStore.Theme theme;
    private int themeIdx;
    private final SoundEngine snd = new SoundEngine();

    private Thread thread;
    private volatile boolean running, surfaceReady;
    private volatile int state = S_LOADING;
    private volatile boolean loadDone;
    private volatile String loadMsg = "";

    // input
    private boolean left, right, up, brake, auto;
    private float steer;

    // track (primitive arrays = tiny memory)
    private final float[] segCurve = new float[MAXSEG];
    private final float[] segY1 = new float[MAXSEG];
    private final float[] segY2 = new float[MAXSEG];
    private final float[] propX = new float[MAXSEG];
    private int segCount;
    private float trackLength;

    // player / race
    private float dist, position, playerX, speed;
    private int lapsDone, crashes;
    private float raceTime, lapTime, bestLap;
    private float countT;
    private int lastCount = -1;
    private float goFlash, crashFx, crashCool, time, skyScroll;
    private int frame;
    private boolean musicOn;

    // traffic
    private final float[] carZ = new float[CARS];
    private final float[] carX = new float[CARS];
    private final float[] carSpd = new float[CARS];
    private final int[] carCol = new int[CARS];
    private final int[] carSeg = new int[CARS];

    // render scratch
    private final float[] sx1 = new float[DRAW], sy1 = new float[DRAW], sc1 = new float[DRAW];
    private final float[] sx2 = new float[DRAW], sy2 = new float[DRAW], sc2 = new float[DRAW];
    private final boolean[] vis = new boolean[DRAW];
    private final float[] fogTab = new float[DRAW];
    private final Paint p = new Paint();
    private final Paint tp = new Paint();
    private final Paint skyPaint = new Paint();
    private final Path path = new Path();
    private final float[] stars = new float[80];
    private final float[] mount = new float[16];
    private final StringBuilder sb = new StringBuilder(48);

    GameView(Activity a) {
        super(a);
        act = a;
        prefs = a.getSharedPreferences("tvracer", 0);
        getHolder().addCallback(this);
        getHolder().setFormat(PixelFormat.RGB_565);
        getHolder().setFixedSize(W, H);
        setFocusable(true);

        musicOn = prefs.getBoolean("music", true);
        bestLap = prefs.getFloat("best", 0f);
        for (int i = 0; i < DRAW; i++) {
            fogTab[i] = 1f - 1f / (float) Math.exp((double) i * i / (DRAW * DRAW) * 3.0);
        }
        Random r = new Random(5);
        for (int i = 0; i < mount.length; i++) mount[i] = 0.35f + 0.65f * r.nextFloat();
        for (int i = 0; i < stars.length / 2; i++) {
            stars[2 * i] = r.nextFloat() * W;
            stars[2 * i + 1] = r.nextFloat() * H * 0.45f;
        }
        tp.setAntiAlias(true);
        tp.setTypeface(Typeface.DEFAULT_BOLD);

        buildTrack();
        store = new ThemeStore(a);
        themeIdx = Math.max(0, prefs.getInt("theme", 0)) % store.count();
        applyTheme();
        resetCars();
    }

    // =========================================================================================
    //  Setup
    // =========================================================================================

    /** First launch: download the theme pack (the "install time" download). Later launches: use cache. */
    void startThemeSetup(final String url) {
        final boolean first = store.needsDownload();
        final boolean stale = store.isStale();
        if (!first) {
            state = S_TITLE;
        } else {
            loadMsg = "Downloading theme pack...";
        }
        if (first || stale) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean ok = store.download(url);
                    synchronized (lock) {
                        if (ok) {
                            themeIdx = Math.min(themeIdx, store.count() - 1);
                            applyTheme();
                            loadMsg = "Theme pack ready";
                        } else {
                            loadMsg = "Offline - using built-in theme";
                        }
                        loadDone = true;
                    }
                }
            }, "themes").start();
        } else {
            loadDone = true;
        }
    }

    private void applyTheme() {
        theme = store.get(themeIdx);
        skyPaint.setShader(new LinearGradient(0, 0, 0, H * 0.5f, theme.skyTop, theme.skyBottom, Shader.TileMode.CLAMP));
        for (int i = 0; i < CARS; i++) carCol[i] = theme.traffic[i % theme.traffic.length];
    }

    // ---- track building (Jake Gordon style road sections) ------------------------------------
    private static final int LOWH = 20, MEDH = 40, HIGHH = 60;
    private static final float EASY = 2f, MED = 4f, HARD = 6f;

    private static float easeIn(float a, float b, float pc) {
        return a + (b - a) * pc * pc;
    }

    private static float easeInOut(float a, float b, float pc) {
        return a + (b - a) * ((float) (-Math.cos(pc * Math.PI) / 2.0) + 0.5f);
    }

    private float lastY() {
        return segCount == 0 ? 0f : segY2[segCount - 1];
    }

    private void addSeg(float curve, float endY) {
        if (segCount >= MAXSEG) return;
        int i = segCount++;
        segCurve[i] = curve;
        segY1[i] = i == 0 ? 0f : segY2[i - 1];
        segY2[i] = endY;
    }

    private void addRoad(int enter, int hold, int leave, float curve, float hill) {
        float startY = lastY();
        float endY = startY + hill * SEG;
        int total = enter + hold + leave;
        for (int n = 0; n < enter; n++)
            addSeg(easeIn(0, curve, (float) n / enter), easeInOut(startY, endY, (float) n / total));
        for (int n = 0; n < hold; n++)
            addSeg(curve, easeInOut(startY, endY, (float) (enter + n) / total));
        for (int n = 0; n < leave; n++)
            addSeg(easeInOut(curve, 0, (float) n / leave), easeInOut(startY, endY, (float) (enter + hold + n) / total));
    }

    private void straight(int n) { addRoad(n, n, n, 0, 0); }

    private void hill(int n, float h) { addRoad(n, n, n, 0, h); }

    private void curve(int n, float c, float h) { addRoad(n, n, n, c, h); }

    private void buildTrack() {
        segCount = 0;
        straight(30);
        hill(25, MEDH);
        curve(40, MED, LOWH);
        straight(15);
        curve(40, -MED, -LOWH);
        curve(25, -EASY, 0);                // S curves
        curve(25, MED, 20);
        curve(25, -MED, -20);
        curve(25, HARD, 0);
        hill(30, HIGHH);
        curve(45, HARD, -MEDH);
        straight(25);
        hill(20, LOWH);                      // rolling hills
        hill(20, -LOWH);
        hill(20, LOWH);
        hill(20, -LOWH);
        curve(55, -HARD, 0);
        curve(50, MED, MEDH);
        curve(50, -MED, -MEDH);
        straight(30);
        curve(40, EASY, LOWH);
        hill(25, -HIGHH);
        curve(45, -HARD, 0);
        straight(20);
        addRoad(40, 40, 40, -EASY, -lastY() / SEG);   // downhill back to height 0 so the lap loops

        segY2[segCount - 1] = 0f;
        trackLength = segCount * (float) SEG;
        for (int i = 14, k = 0; i < segCount; i += 7, k++) {
            propX[i] = (k % 2 == 0 ? -1f : 1f) * (1.55f + (k % 3) * 0.3f);
        }
    }

    private void resetCars() {
        Random r = new Random(7);
        for (int i = 0; i < CARS; i++) {
            carX[i] = (r.nextBoolean() ? -1f : 1f) * r.nextFloat() * 0.8f;
            carZ[i] = (30 + r.nextInt(segCount - 30)) * (float) SEG;
            carSpd[i] = MAX_SPEED / 4f + r.nextFloat() * MAX_SPEED / 2f;
            carSeg[i] = (int) (carZ[i] / SEG);
        }
    }

    // =========================================================================================
    //  Lifecycle / thread
    // =========================================================================================

    void resume() {
        synchronized (lock) {
            if (state == S_RACE) state = S_PAUSE;
            left = right = up = brake = false;
        }
        snd.start();
        running = true;
        thread = new Thread(this, "game");
        thread.start();
    }

    void pause() {
        running = false;
        if (thread != null) {
            try {
                thread.join(700);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
        snd.stop();
    }

    @Override public void surfaceCreated(SurfaceHolder h) { surfaceReady = true; }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }

    @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; }

    @Override
    public void run() {
        long last = System.nanoTime();
        float acc = 0f;
        while (running) {
            long now = System.nanoTime();
            float dt = (now - last) / 1e9f;
            last = now;
            if (dt > 0.1f) dt = 0.1f;
            if (surfaceReady) {
                synchronized (lock) {
                    acc += dt;
                    int steps = 0;
                    while (acc >= STEP && steps < 4) {
                        update(STEP);
                        acc -= STEP;
                        steps++;
                    }
                    if (acc > STEP) acc = 0f;
                    frame++;
                    updateSound();
                }
                SurfaceHolder h = getHolder();
                Canvas c = null;
                try {
                    c = h.lockCanvas();
                    if (c != null) {
                        synchronized (lock) {
                            render(c);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (c != null) {
                        try {
                            h.unlockCanvasAndPost(c);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            long spent = (System.nanoTime() - now) / 1000000L;
            long sleep = FRAME_MS - spent;
            if (sleep > 1) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void updateSound() {
        int s = state;
        snd.speedPct = speed / MAX_SPEED;
        snd.throttle = (up || auto) && s == S_RACE ? 1f : 0f;
        snd.engineOn = s == S_COUNT || s == S_RACE || s == S_FINISH;
        snd.offroad = (playerX < -1f || playerX > 1f) && s == S_RACE;
        snd.musicOn = musicOn && s != S_PAUSE;
    }

    // =========================================================================================
    //  Input (TV remote)
    // =========================================================================================

    boolean onKey(int code, boolean down, int repeat) {
        synchronized (lock) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_A:
                    left = down;
                    if (down && repeat == 0) menuMove(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_D:
                    right = down;
                    if (down && repeat == 0) menuMove(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_W:
                    up = down;
                    if (down && repeat == 0 && state == S_TITLE) {
                        musicOn = !musicOn;
                        prefs.edit().putBoolean("music", musicOn).apply();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_S:
                    brake = down;
                    return true;
                case KeyEvent.KEYCODE_BUTTON_A:
                    up = down;
                    if (down && repeat == 0 && state != S_RACE) confirm();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_B:
                    brake = down;
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down && repeat == 0) confirm();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_BUTTON_START:
                case KeyEvent.KEYCODE_P:
                    if (down && repeat == 0) {
                        if (state == S_RACE) state = S_PAUSE;
                        else if (state == S_PAUSE) state = S_RACE;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (down && repeat == 0) back();
                    return true;
                default:
                    return false;
            }
        }
    }

    private void menuMove(int dir) {
        if (state != S_TITLE) return;
        themeIdx = ((themeIdx + dir) % store.count() + store.count()) % store.count();
        prefs.edit().putInt("theme", themeIdx).apply();
        applyTheme();
    }

    private void confirm() {
        switch (state) {
            case S_LOADING:
                state = S_TITLE;
                break;
            case S_TITLE:
            case S_FINISH:
                startRace();
                break;
            case S_PAUSE:
                state = S_RACE;
                break;
            case S_RACE:
                auto = !auto;           // OK = cruise / auto-throttle toggle
                break;
            default:
                break;
        }
    }

    private void back() {
        switch (state) {
            case S_RACE:
                state = S_PAUSE;
                break;
            case S_COUNT:
            case S_PAUSE:
            case S_FINISH:
                state = S_TITLE;
                break;
            default:
                act.finish();
                break;
        }
    }

    private void startRace() {
        resetCars();
        dist = 0f;
        position = 0f;
        playerX = 0f;
        speed = 0f;
        steer = 0f;
        lapsDone = 0;
        crashes = 0;
        raceTime = 0f;
        lapTime = 0f;
        auto = false;
        countT = 3f;
        lastCount = -1;
        state = S_COUNT;
    }

    // =========================================================================================
    //  Update
    // =========================================================================================

    private void update(float dt) {
        time += dt;
        if (crashFx > 0f) crashFx -= dt;
        if (crashCool > 0f) crashCool -= dt;
        if (goFlash > 0f) goFlash -= dt;
        switch (state) {
            case S_LOADING:
                demo(dt);
                if (loadDone && time > 1.2f) state = S_TITLE;
                break;
            case S_TITLE:
                demo(dt);
                break;
            case S_COUNT:
                countT -= dt;
                int n = (int) Math.ceil(countT);
                if (n != lastCount) {
                    lastCount = n;
                    if (n > 0) snd.play(SoundEngine.BEEP);
                }
                if (countT <= 0f) {
                    state = S_RACE;
                    goFlash = 0.9f;
                    snd.play(SoundEngine.GO);
                }
                break;
            case S_RACE:
                updateRace(dt);
                break;
            case S_FINISH:
                speed = Math.max(0f, speed - 3500f * dt);
                dist += dt * speed;
                position = dist % trackLength;
                stepCars(dt);
                updateSky(dt);
                break;
            default:
                break;
        }
    }

    private void demo(float dt) {
        speed = MAX_SPEED * 0.45f;
        dist += dt * speed;
        if (dist >= trackLength) dist -= trackLength;
        position = dist;
        playerX = (float) Math.sin(time * 0.5f) * 0.35f;
        stepCars(dt);
        updateSky(dt);
    }

    private void updateSky(float dt) {
        skyScroll += segCurve[segAt(position + PLAYER_Z)] * (speed / MAX_SPEED) * dt * W * 0.05f;
    }

    private int segAt(float z) {
        z %= trackLength;
        if (z < 0f) z += trackLength;
        int s = (int) (z / SEG);
        return s >= segCount ? segCount - 1 : s;
    }

    private void stepCars(float dt) {
        for (int i = 0; i < CARS; i++) {
            carZ[i] += dt * carSpd[i];
            if (carZ[i] >= trackLength) carZ[i] -= trackLength;
            carSeg[i] = segAt(carZ[i]);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void crash(float newSpeed, float moveBack) {
        speed = newSpeed;
        dist = Math.max(0f, dist - moveBack);
        position = dist % trackLength;
        if (crashCool <= 0f) {
            crashes++;
            crashFx = 0.5f;
            crashCool = 0.6f;
            snd.play(SoundEngine.CRASH);
        }
    }

    private void updateRace(float dt) {
        raceTime += dt;
        lapTime += dt;
        stepCars(dt);

        float sp = speed / MAX_SPEED;
        dist += dt * speed;
        position = dist % trackLength;

        int pIdx = segAt(position + PLAYER_Z);
        float dxp = dt * 2f * sp;
        float target = (left ? -1f : 0f) + (right ? 1f : 0f);
        steer += (target - steer) * Math.min(1f, dt * 9f);
        playerX += dxp * steer;
        playerX -= dxp * sp * segCurve[pIdx] * CENTRIFUGAL;

        if (brake) speed += BRAKE * dt;
        else if (up || auto) speed += ACCEL * dt;
        else speed += DECEL * dt;
        if ((playerX < -1f || playerX > 1f) && speed > OFFROAD_LIMIT) speed += OFFROAD_DECEL * dt;

        // roadside objects
        if (theme.props != ThemeStore.PROPS_NONE && (playerX < -0.9f || playerX > 0.9f)) {
            float px = propX[pIdx];
            if (px != 0f && Math.abs(playerX - px) < (CAR_W + 0.25f) * 0.5f) {
                float back = (position + PLAYER_Z) % trackLength - pIdx * (float) SEG;
                crash(Math.min(speed, MAX_SPEED / 6f), clamp(back + SEG * 0.5f, 0f, SEG * 1.5f));
                playerX = px > 0f ? px - 0.35f : px + 0.35f;
            }
        }

        // traffic
        float pz = (position + PLAYER_Z) % trackLength;
        for (int i = 0; i < CARS; i++) {
            float d = carZ[i] - pz;
            if (d > trackLength / 2f) d -= trackLength;
            else if (d < -trackLength / 2f) d += trackLength;
            if (Math.abs(d) < SEG * 0.8f && speed > carSpd[i]
                    && Math.abs(playerX - carX[i]) < CAR_W * 0.8f) {
                crash(carSpd[i] * (carSpd[i] / speed), clamp(60f - d, 0f, 260f));
                break;
            }
        }

        playerX = clamp(playerX, -2.4f, 2.4f);
        speed = clamp(speed, 0f, MAX_SPEED);
        updateSky(dt);

        int laps = (int) (dist / trackLength);
        if (laps > lapsDone) {
            lapsDone = laps;
            if (bestLap <= 0f || lapTime < bestLap) {
                bestLap = lapTime;
                prefs.edit().putFloat("best", bestLap).apply();
            }
            lapTime = 0f;
            snd.play(SoundEngine.LAP);
            if (lapsDone >= LAPS) state = S_FINISH;
        }
    }

    // =========================================================================================
    //  Rendering
    // =========================================================================================

    static int mix(int a, int b, float t) {
        if (t <= 0f) return a;
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    private static int darker(int c) {
        return 0xFF000000 | ((((c >> 16) & 255) * 3 / 4) << 16) | ((((c >> 8) & 255) * 3 / 4) << 8) | ((c & 255) * 3 / 4);
    }

    private void quad(Canvas c, float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy) {
        path.reset();
        path.moveTo(ax, ay);
        path.lineTo(bx, by);
        path.lineTo(cx, cy);
        path.lineTo(dx, dy);
        path.close();
        c.drawPath(path, p);
    }

    private void tri(Canvas c, float ax, float ay, float bx, float by, float cx, float cy) {
        path.reset();
        path.moveTo(ax, ay);
        path.lineTo(bx, by);
        path.lineTo(cx, cy);
        path.close();
        c.drawPath(path, p);
    }

    private void render(Canvas c) {
        final ThemeStore.Theme t = theme;
        c.drawRect(0, 0, W, H * 0.5f + 1f, skyPaint);
        if (t.stars) {
            p.setColor(0xFFFFFFFF);
            p.setStrokeWidth(2f);
            c.drawPoints(stars, p);
        }
        if (t.sun != 0) {
            p.setColor(t.sun);
            c.drawCircle(W * 0.72f, H * 0.2f, H * 0.07f, p);
        }
        drawMountains(c, t);
        p.setColor(mix(t.grassA, t.fog, 0.7f));
        c.drawRect(0, H * 0.5f, W, H, p);
        drawRoad(c, t);

        // player car
        float bounce = speed > 100f ? ((frame & 2) == 0 ? 0f : 1.5f) * (speed / MAX_SPEED) : 0f;
        float pw = (CAM_DEPTH / PLAYER_Z) * (W / 2f) * ROAD_W * CAR_W;
        drawCar(c, W / 2f, H - 16f - bounce, pw, t.player, 0f, brake && speed > 0f, steer);

        if (crashFx > 0f) {
            p.setColor(((int) (clamp(crashFx, 0f, 0.5f) * 300f) << 24) | 0xFF2020);
            c.drawRect(0, 0, W, H, p);
        }

        switch (state) {
            case S_LOADING: drawLoading(c); break;
            case S_TITLE: drawTitle(c); break;
            case S_COUNT: drawHud(c); drawCount(c); break;
            case S_RACE: drawHud(c); if (goFlash > 0f) bigText(c, "GO!", 0xFF7CFC00); break;
            case S_PAUSE: drawHud(c); drawPause(c); break;
            case S_FINISH: drawFinish(c); break;
            default: break;
        }
    }

    private void drawMountains(Canvas c, ThemeStore.Theme t) {
        float sp = W / 5f;
        float hmax = H * 0.22f;
        float base = H * 0.5f + 2f;
        int b = (int) Math.floor(skyScroll / sp);
        float fr = skyScroll - b * sp;
        path.reset();
        path.moveTo(-sp - fr, base);
        for (int i = -1; i <= 6; i++) {
            float x = i * sp - fr;
            int idx = (((b + i) % mount.length) + mount.length) % mount.length;
            float hh = (idx & 1) == 0 ? mount[idx] * hmax : mount[idx] * hmax * 0.3f;
            path.lineTo(x, base - hh);
        }
        path.lineTo(6 * sp - fr, base);
        path.close();
        p.setColor(t.mountain);
        c.drawPath(path, p);
    }

    private void drawRoad(Canvas c, ThemeStore.Theme t) {
        int baseIdx = (int) (position / SEG);
        if (baseIdx >= segCount) baseIdx = segCount - 1;
        float basePct = (position - baseIdx * (float) SEG) / SEG;
        float pz = (position + PLAYER_Z) % trackLength;
        int pIdx = (int) (pz / SEG);
        if (pIdx >= segCount) pIdx = segCount - 1;
        float pPct = (pz - pIdx * (float) SEG) / SEG;
        float playerY = segY1[pIdx] + (segY2[pIdx] - segY1[pIdx]) * pPct;
        float camY = playerY + CAM_H;
        float shake = crashFx > 0f ? ((frame & 1) == 0 ? 1f : -1f) * crashFx * 250f : 0f;
        float camX = playerX * ROAD_W + shake;
        float x = 0f;
        float dx = -segCurve[baseIdx] * basePct;
        float maxy = H;

        for (int n = 0; n < DRAW; n++) {
            int idx = baseIdx + n;
            boolean looped = false;
            if (idx >= segCount) {
                idx -= segCount;
                looped = true;
            }
            vis[n] = false;
            float z1 = idx * (float) SEG - position + (looped ? trackLength : 0f);
            float z2 = z1 + SEG;
            float cx1 = x - camX;
            float cx2 = x + dx - camX;
            x += dx;
            dx += segCurve[idx];
            if (z1 <= 50f) continue;

            float s1 = CAM_DEPTH / z1, s2 = CAM_DEPTH / z2;
            float yy1 = H / 2f - s1 * (segY1[idx] - camY) * (H / 2f);
            float yy2 = H / 2f - s2 * (segY2[idx] - camY) * (H / 2f);
            if (yy2 >= yy1 || yy2 >= maxy) continue;
            float xx1 = W / 2f + s1 * cx1 * (W / 2f);
            float xx2 = W / 2f + s2 * cx2 * (W / 2f);
            float ww1 = s1 * ROAD_W * (W / 2f);
            float ww2 = s2 * ROAD_W * (W / 2f);

            sx1[n] = xx1; sy1[n] = yy1; sc1[n] = s1;
            sx2[n] = xx2; sy2[n] = yy2; sc2[n] = s2;
            vis[n] = true;

            boolean clip = yy1 > maxy + 0.5f;
            if (clip) {
                c.save();
                c.clipRect(0, 0, W, maxy);
            }
            drawSegment(c, t, idx, fogTab[n], xx1, yy1, ww1, xx2, yy2, ww2);
            if (clip) c.restore();
            maxy = yy1;
        }

        // sprites, far to near
        for (int n = DRAW - 1; n >= 0; n--) {
            if (!vis[n]) continue;
            int idx = (baseIdx + n) % segCount;
            float f = fogTab[n];
            float px = propX[idx];
            if (px != 0f && t.props != ThemeStore.PROPS_NONE) {
                float sc = sc1[n];
                float sx = sx1[n] + sc * px * ROAD_W * (W / 2f);
                drawProp(c, t.props, sx, sy1[n], sc * (W / 2f) * ROAD_W * PROP_W, f);
            }
            for (int i = 0; i < CARS; i++) {
                if (carSeg[i] != idx) continue;
                float pct = (carZ[i] % SEG) / SEG;
                float sc = sc1[n] + (sc2[n] - sc1[n]) * pct;
                float sx = sx1[n] + (sx2[n] - sx1[n]) * pct + sc * carX[i] * ROAD_W * (W / 2f);
                float sy = sy1[n] + (sy2[n] - sy1[n]) * pct;
                drawCar(c, sx, sy, sc * (W / 2f) * ROAD_W * CAR_W, carCol[i], f, false, 0f);
            }
        }
    }

    private void drawSegment(Canvas c, ThemeStore.Theme t, int idx, float f,
                             float x1, float ya, float w1, float x2, float yb, float w2) {
        boolean a = ((idx / RUMBLE) & 1) == 0;
        int grass = mix(a ? t.grassA : t.grassB, t.fog, f);
        int road = a ? t.roadA : t.roadB;
        int rum = a ? t.rumbleA : t.rumbleB;
        if (idx == 4) road = 0xFFEDEDED;           // start / finish line
        else if (idx == 5) road = 0xFF1A1A1A;
        road = mix(road, t.fog, f);
        rum = mix(rum, t.fog, f);

        p.setColor(grass);
        c.drawRect(0, yb, W, ya, p);

        float r1 = w1 / Math.max(6, 2 * LANES), r2 = w2 / Math.max(6, 2 * LANES);
        p.setColor(rum);
        quad(c, x1 - w1 - r1, ya, x1 - w1, ya, x2 - w2, yb, x2 - w2 - r2, yb);
        quad(c, x1 + w1 + r1, ya, x1 + w1, ya, x2 + w2, yb, x2 + w2 + r2, yb);
        p.setColor(road);
        quad(c, x1 - w1, ya, x1 + w1, ya, x2 + w2, yb, x2 - w2, yb);

        if (a) {
            p.setColor(mix(t.lane, t.fog, f));
            float l1 = w1 / Math.max(32, 8 * LANES), l2 = w2 / Math.max(32, 8 * LANES);
            float lw1 = w1 * 2f / LANES, lw2 = w2 * 2f / LANES;
            float lx1 = x1 - w1 + lw1, lx2 = x2 - w2 + lw2;
            for (int l = 1; l < LANES; l++) {
                quad(c, lx1 - l1 / 2f, ya, lx1 + l1 / 2f, ya, lx2 + l2 / 2f, yb, lx2 - l2 / 2f, yb);
                lx1 += lw1;
                lx2 += lw2;
            }
        }
    }

    private void drawCar(Canvas c, float cx, float by, float w, int body, float f, boolean braking, float tilt) {
        if (w < 3f) return;
        final ThemeStore.Theme t = theme;
        float h = w * 0.55f;
        p.setColor(0x55000000);
        c.drawRect(cx - w * 0.55f, by - h * 0.06f, cx + w * 0.55f, by + h * 0.05f, p);
        p.setColor(mix(0xFF151515, t.fog, f));
        c.drawRect(cx - w * 0.52f, by - h * 0.34f, cx - w * 0.34f, by, p);
        c.drawRect(cx + w * 0.34f, by - h * 0.34f, cx + w * 0.52f, by, p);
        int b = mix(body, t.fog, f);
        int dk = mix(darker(body), t.fog, f);
        p.setColor(b);
        c.drawRect(cx - w * 0.5f, by - h * 0.62f, cx + w * 0.5f, by - h * 0.2f, p);
        float sh = tilt * w * 0.04f;
        p.setColor(dk);
        quad(c, cx - w * 0.40f, by - h * 0.62f, cx + w * 0.40f, by - h * 0.62f,
                cx + w * 0.28f + sh, by - h, cx - w * 0.28f + sh, by - h);
        p.setColor(mix(0xFF1B2633, t.fog, f));
        quad(c, cx - w * 0.32f, by - h * 0.66f, cx + w * 0.32f, by - h * 0.66f,
                cx + w * 0.23f + sh, by - h * 0.93f, cx - w * 0.23f + sh, by - h * 0.93f);
        p.setColor(dk);
        c.drawRect(cx - w * 0.5f, by - h * 0.28f, cx + w * 0.5f, by - h * 0.18f, p);
        p.setColor(braking ? 0xFFFF2A2A : mix(0xFF9A1B1B, t.fog, f));
        c.drawRect(cx - w * 0.47f, by - h * 0.56f, cx - w * 0.30f, by - h * 0.40f, p);
        c.drawRect(cx + w * 0.30f, by - h * 0.56f, cx + w * 0.47f, by - h * 0.40f, p);
        p.setColor(mix(0xFFEEEEEE, t.fog, f));
        c.drawRect(cx - w * 0.09f, by - h * 0.46f, cx + w * 0.09f, by - h * 0.36f, p);
    }

    private void drawProp(Canvas c, int type, float sx, float sy, float w, float f) {
        if (w < 3f) return;
        final int fog = theme.fog;
        switch (type) {
            case ThemeStore.PROPS_PINE:
                p.setColor(mix(0xFF5A3B1E, fog, f));
                c.drawRect(sx - w * 0.05f, sy - w * 0.22f, sx + w * 0.05f, sy, p);
                p.setColor(mix(0xFF1F6B3A, fog, f));
                tri(c, sx - w * 0.28f, sy - w * 0.18f, sx + w * 0.28f, sy - w * 0.18f, sx, sy - w * 0.62f);
                tri(c, sx - w * 0.22f, sy - w * 0.45f, sx + w * 0.22f, sy - w * 0.45f, sx, sy - w * 0.88f);
                p.setColor(mix(0xFF2C8A4B, fog, f));
                tri(c, sx - w * 0.15f, sy - w * 0.72f, sx + w * 0.15f, sy - w * 0.72f, sx, sy - w * 1.1f);
                break;
            case ThemeStore.PROPS_CACTUS:
                p.setColor(mix(0xFF2E7D32, fog, f));
                c.drawRect(sx - w * 0.07f, sy - w * 0.9f, sx + w * 0.07f, sy, p);
                c.drawRect(sx - w * 0.28f, sy - w * 0.6f, sx - w * 0.07f, sy - w * 0.5f, p);
                c.drawRect(sx - w * 0.28f, sy - w * 0.8f, sx - w * 0.2f, sy - w * 0.5f, p);
                c.drawRect(sx + w * 0.07f, sy - w * 0.45f, sx + w * 0.26f, sy - w * 0.35f, p);
                c.drawRect(sx + w * 0.18f, sy - w * 0.7f, sx + w * 0.26f, sy - w * 0.35f, p);
                break;
            case ThemeStore.PROPS_LAMP:
                p.setColor(mix(0xFF6B6B7A, fog, f));
                c.drawRect(sx - w * 0.025f, sy - w * 1.3f, sx + w * 0.025f, sy, p);
                c.drawRect(sx - w * 0.12f, sy - w * 1.35f, sx + w * 0.08f, sy - w * 1.28f, p);
                p.setColor(0x44FFE9A0);
                c.drawCircle(sx - w * 0.1f, sy - w * 1.25f, w * 0.25f, p);
                p.setColor(mix(0xFFFFF1B0, fog, f));
                c.drawCircle(sx - w * 0.1f, sy - w * 1.28f, w * 0.05f, p);
                break;
            default:
                p.setColor(mix(0xFF5A3B1E, fog, f));
                c.drawRect(sx - w * 0.035f, sy - w * 0.3f, sx + w * 0.035f, sy, p);
                p.setColor(mix(0xFF2E8B3A, fog, f));
                c.drawCircle(sx, sy - w * 0.55f, w * 0.3f, p);
                p.setColor(mix(0xFF45A64F, fog, f));
                c.drawCircle(sx - w * 0.08f, sy - w * 0.62f, w * 0.17f, p);
                break;
        }
    }

    // ---- HUD / overlays --------------------------------------------------------------------

    private void txt(Canvas c, CharSequence s, float x, float y, int color) {
        tp.setColor(0xAA000000);
        c.drawText(s, 0, s.length(), x + 2, y + 2, tp);
        tp.setColor(color);
        c.drawText(s, 0, s.length(), x, y, tp);
    }

    private void time(StringBuilder s, float t) {
        int d = (int) (t * 10f);
        int m = d / 600, sec = (d / 10) % 60;
        s.append(m).append(':');
        if (sec < 10) s.append('0');
        s.append(sec).append('.').append(d % 10);
    }

    private void drawHud(Canvas c) {
        tp.setTextSize(30f);
        tp.setTextAlign(Paint.Align.LEFT);
        sb.setLength(0);
        sb.append((int) (speed / MAX_SPEED * 220f)).append(" km/h");
        txt(c, sb, 20, 44, 0xFFFFFFFF);
        p.setColor(0x80000000);
        c.drawRect(20, 54, 232, 66, p);
        float sp = speed / MAX_SPEED;
        p.setColor(sp > 0.85f ? 0xFFFF5252 : 0xFF69F0AE);
        c.drawRect(22, 56, 22 + 208 * sp, 64, p);
        if (auto) {
            tp.setTextSize(22f);
            txt(c, "AUTO GAS", 20, 92, 0xFFFFEB3B);
        }
        tp.setTextSize(30f);
        tp.setTextAlign(Paint.Align.CENTER);
        sb.setLength(0);
        sb.append("LAP ").append(Math.min(lapsDone + 1, LAPS)).append('/').append(LAPS);
        txt(c, sb, W / 2f, 44, 0xFFFFFFFF);
        tp.setTextAlign(Paint.Align.RIGHT);
        sb.setLength(0);
        time(sb, lapTime);
        txt(c, sb, W - 20, 44, 0xFFFFFFFF);
        tp.setTextSize(22f);
        sb.setLength(0);
        sb.append("BEST ");
        if (bestLap > 0f) time(sb, bestLap); else sb.append("--");
        txt(c, sb, W - 20, 72, 0xFFFFEB3B);
    }

    private void panel(Canvas c, float l, float t, float r, float b) {
        p.setColor(0xB0000000);
        c.drawRect(l, t, r, b, p);
    }

    private void bigText(Canvas c, CharSequence s, int color) {
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(120f);
        txt(c, s, W / 2f, H * 0.42f, color);
    }

    private void drawCount(Canvas c) {
        sb.setLength(0);
        sb.append(Math.max(1, (int) Math.ceil(countT)));
        bigText(c, sb, 0xFFFFFFFF);
    }

    private void drawPause(Canvas c) {
        panel(c, W * 0.25f, H * 0.3f, W * 0.75f, H * 0.7f);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(52f);
        txt(c, "PAUSED", W / 2f, H * 0.46f, 0xFFFFFFFF);
        tp.setTextSize(26f);
        txt(c, "OK = resume", W / 2f, H * 0.57f, 0xFFFFEB3B);
        txt(c, "BACK = quit to menu", W / 2f, H * 0.65f, 0xFFCCCCCC);
    }

    private void drawLoading(Canvas c) {
        panel(c, W * 0.15f, H * 0.3f, W * 0.85f, H * 0.7f);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(44f);
        txt(c, "TV RACER LITE", W / 2f, H * 0.43f, 0xFFFFFFFF);
        tp.setTextSize(26f);
        txt(c, loadMsg, W / 2f, H * 0.54f, 0xFFFFEB3B);
        tp.setTextSize(20f);
        txt(c, "First start only - OK to skip", W / 2f, H * 0.63f, 0xFFCCCCCC);
        float w = 300f, x = W / 2f - w / 2f, y = H * 0.58f;
        p.setColor(0x80FFFFFF);
        c.drawRect(x, y, x + w, y + 6, p);
        p.setColor(0xFF69F0AE);
        float pos = (time * 0.6f) % 1f;
        c.drawRect(x + pos * w * 0.7f, y, x + pos * w * 0.7f + w * 0.3f, y + 6, p);
    }

    private void drawTitle(Canvas c) {
        panel(c, W * 0.1f, H * 0.12f, W * 0.9f, H * 0.88f);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(62f);
        txt(c, "TV RACER LITE", W / 2f, H * 0.30f, 0xFFFFFFFF);
        tp.setTextSize(30f);
        sb.setLength(0);
        sb.append("<   ").append(theme.name).append("   >");
        txt(c, sb, W / 2f, H * 0.42f, 0xFFFFEB3B);
        tp.setTextSize(32f);
        txt(c, "Press OK to start", W / 2f, H * 0.55f, 0xFF69F0AE);
        tp.setTextSize(22f);
        txt(c, "UP = gas    DOWN = brake    LEFT / RIGHT = steer", W / 2f, H * 0.65f, 0xFFFFFFFF);
        txt(c, "OK in race = auto gas on/off    BACK = pause", W / 2f, H * 0.72f, 0xFFFFFFFF);
        sb.setLength(0);
        sb.append("Music ").append(musicOn ? "ON" : "OFF").append(" (UP here)   Best lap ");
        if (bestLap > 0f) time(sb, bestLap); else sb.append("--");
        txt(c, sb, W / 2f, H * 0.81f, 0xFFCCCCCC);
    }

    private void drawFinish(Canvas c) {
        panel(c, W * 0.2f, H * 0.2f, W * 0.8f, H * 0.8f);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(60f);
        txt(c, "FINISH!", W / 2f, H * 0.36f, 0xFF7CFC00);
        tp.setTextSize(30f);
        sb.setLength(0);
        sb.append("Total ");
        time(sb, raceTime);
        txt(c, sb, W / 2f, H * 0.48f, 0xFFFFFFFF);
        sb.setLength(0);
        sb.append("Best lap ");
        time(sb, bestLap);
        txt(c, sb, W / 2f, H * 0.57f, 0xFFFFEB3B);
        sb.setLength(0);
        sb.append("Crashes ").append(crashes);
        txt(c, sb, W / 2f, H * 0.66f, 0xFFFFFFFF);
        tp.setTextSize(24f);
        txt(c, "OK = race again    BACK = menu", W / 2f, H * 0.75f, 0xFF69F0AE);
    }
}
