package com.tvracer.lite;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;

/**
 * Fully synthesized audio (engine, music, effects): zero audio files, zero extra storage.
 * 11 kHz mono 16-bit keeps CPU and RAM tiny.
 */
@SuppressWarnings("deprecation")
final class SoundEngine implements Runnable {
    static final int CRASH = 1, BEEP = 2, GO = 3, LAP = 4;

    private static final int RATE = 11025;
    private static final int STEP_LEN = RATE * 60 / 132 / 4;   // 16th note at 132 BPM

    private static final int[] BASS = {33, 33, 45, 33, 31, 31, 43, 31, 29, 29, 41, 29, 31, 31, 43, 38};
    private static final int[] LEAD = {69, 72, 76, 72, 67, 71, 74, 71, 65, 69, 72, 69, 67, 71, 74, 79};
    private static final float[] HZ = new float[128];

    static {
        for (int i = 0; i < 128; i++) HZ[i] = (float) (440.0 * Math.pow(2.0, (i - 69) / 12.0));
    }

    volatile float speedPct, throttle;
    volatile boolean engineOn, musicOn, offroad;

    private AudioTrack track;
    private Thread thread;
    private volatile boolean running;
    private volatile int sfxType;
    private int sfxPos, sfxLen;
    private int rng = 12345;

    synchronized void start() {
        if (running) return;
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 2048), AudioTrack.MODE_STREAM);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                track = null;
                return;
            }
            track.play();
            running = true;
            thread = new Thread(this, "snd");
            thread.start();
        } catch (Throwable e) {
            track = null;
        }
    }

    synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
        if (track != null) {
            try {
                track.stop();
            } catch (Exception ignored) {
            }
            track.release();
            track = null;
        }
    }

    void play(int type) {
        int len;
        switch (type) {
            case CRASH: len = RATE * 45 / 100; break;
            case BEEP: len = RATE * 14 / 100; break;
            case GO: len = RATE * 35 / 100; break;
            default: len = RATE * 40 / 100; break;
        }
        sfxPos = 0;
        sfxLen = len;
        sfxType = type;
    }

    private float noise() {
        rng ^= rng << 13;
        rng ^= rng >>> 17;
        rng ^= rng << 5;
        return (rng & 0xFFFF) / 32768f - 1f;
    }

    private static float sq(int pos, float freq) {
        float ph = pos * freq / RATE;
        return (ph - (int) ph) < 0.5f ? 1f : -1f;
    }

    private float sfx() {
        int ty = sfxType;
        if (ty == 0) return 0f;
        if (sfxPos >= sfxLen) {
            sfxType = 0;
            return 0f;
        }
        float t = (float) sfxPos / sfxLen;
        float v;
        switch (ty) {
            case CRASH:
                v = noise() * 0.55f * (1f - t) * (1f - t)
                        + (float) Math.sin(sfxPos * 2.0 * Math.PI * 70.0 / RATE) * 0.4f * (1f - t);
                break;
            case BEEP:
                v = sq(sfxPos, 440f) * 0.22f * (1f - t * 0.5f);
                break;
            case GO:
                v = sq(sfxPos, 880f) * 0.24f * (1f - t * 0.6f);
                break;
            default:
                v = sq(sfxPos, t < 0.5f ? 660f : 990f) * 0.22f * (1f - t * 0.5f);
                break;
        }
        sfxPos++;
        return v;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioTrack t = track;
        if (t == null) return;
        short[] buf = new short[512];
        float phase = 0f, lp = 0f, bPh = 0f, lPh = 0f;
        int step = 0, stepPos = 0;
        while (running) {
            float sp = speedPct, th = throttle;
            boolean eon = engineOn, mon = musicOn, off = offroad;
            float g = sp * 4.999f;                      // 5 "gears": pitch rises then drops
            float rpm = g - (int) g;
            float freq = 42f + rpm * 70f + sp * 30f;
            for (int i = 0; i < buf.length; i++) {
                float s = 0f;
                if (eon) {
                    phase += freq / RATE;
                    if (phase >= 1f) phase -= 1f;
                    float raw = (2f * phase - 1f) * 0.6f + (phase < 0.5f ? 0.4f : -0.4f);
                    lp += 0.22f * (raw - lp);
                    s += lp * (0.20f + 0.14f * th);
                    s += noise() * (off ? 0.10f : 0.015f) * sp;
                }
                if (mon) {
                    if (++stepPos >= STEP_LEN) {
                        stepPos = 0;
                        step = (step + 1) & 15;
                    }
                    float env = 1f - (float) stepPos / STEP_LEN;
                    bPh += HZ[BASS[step]] / RATE;
                    if (bPh >= 1f) bPh -= 1f;
                    lPh += HZ[LEAD[step]] / RATE;
                    if (lPh >= 1f) lPh -= 1f;
                    s += (bPh < 0.5f ? 1f : -1f) * 0.07f * env;
                    if ((step & 1) == 1) s += (lPh < 0.25f ? 1f : -1f) * 0.035f * env;
                }
                s += sfx();
                if (s > 1f) s = 1f; else if (s < -1f) s = -1f;
                buf[i] = (short) (s * 24000f);
            }
            if (t.write(buf, 0, buf.length) < 0) break;
        }
    }
}
