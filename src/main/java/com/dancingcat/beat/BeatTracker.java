package com.dancingcat.beat;

/**
 * Beat tracking from live audio.
 *
 * 1. Onsets: the audio is split into 10 ms frames and each frame's spectrum (FFT) is compared
 *    with the previous one. A sudden rise in energy (drums, bass) gives a high value.
 *    The kick-drum range gets extra weight.
 * 2. Tempo: the onset curve of the last 8 seconds is autocorrelated; the lag at which the curve
 *    repeats itself most is the beat period. Tempos around 120 BPM are slightly preferred.
 * 3. Phase: a "comb" with that period is slid across the onset curve; where it lines up best is
 *    where the beats are.
 * 4. The beat counter the cat uses is nudged toward these estimates gradually (it never runs
 *    backwards), so the dance locks onto the rhythm without stuttering.
 *
 * Without audio / when silent / before locking on, it falls back to the fixed-tempo source.
 */
public final class BeatTracker implements BeatSource {

    private static final int SAMPLE_RATE = 44100;
    private static final int HOP = 441;            // 10 ms → saniyede 100 kare
    private static final int FFT_SIZE = 1024;
    private static final double FPS = (double) SAMPLE_RATE / HOP;
    private static final int HISTORY = 800;        // 8 saniye
    private static final int MIN_FRAMES = 300;     // need at least 3 seconds before estimating
    private static final int ESTIMATE_EVERY = 25;  // re-estimate every 0.25 s
    private static final double MIN_BPM = 70, MAX_BPM = 190;
    private static final long SILENCE_NS = 2_000_000_000L;

    private final BeatSource fallback;
    private final FFT fft = new FFT(FFT_SIZE);
    private final double[] hann = new double[FFT_SIZE];
    private final double[] frame = new double[FFT_SIZE];
    private final double[] re = new double[FFT_SIZE];
    private final double[] im = new double[FFT_SIZE];
    private final double[] prevLogMag = new double[FFT_SIZE / 2];

    private final double[] onset = new double[HISTORY];
    private final long[] onsetTime = new long[HISTORY];
    private long frameCount;
    private int filled;                            // new samples since the last analysis
    private int ringPos;
    private long samplesReceived;

    // Tempo tahmini
    private double periodFrames = FPS / 2;         // 120 BPM
    private double candidatePeriod;
    private int candidateVotes;
    private volatile boolean locked;
    private volatile long lastLoudNanos;
    private volatile long lastAudioNanos;

    // Energy / mood / drop (written by the audio thread only)
    private static final int DROP_SHORT = 25;      // 0.25 s
    private static final int DROP_LONG = 400;      // the 4 s before that
    private static final long DROP_COOLDOWN_NS = 12_000_000_000L;
    private static final long MOOD_HOLD_NS = 3_000_000_000L;
    private final double[] rmsHistory = new double[HISTORY];
    private double loudShort, peakLoud = 1e-4, punch, peakPunch = 1e-4;
    private long lastDropNanos;
    private volatile Mood mood = Mood.GROOVE;
    private Mood moodCandidate = Mood.GROOVE;
    private long moodCandidateSince;
    private volatile Runnable onDrop = () -> { };

    // The beat counter the cat sees (guarded by this)
    private double anchorBeat;
    private long anchorNanos = System.nanoTime();
    private double periodNanos = 500_000_000;
    private double lastReturned;

    public BeatTracker(BeatSource fallback) {
        this.fallback = fallback;
        for (int i = 0; i < FFT_SIZE; i++) hann[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1));
    }

    /** Is the live beat currently being used? */
    public boolean isLive() {
        long now = System.nanoTime();
        return locked && now - lastAudioNanos < SILENCE_NS && now - lastLoudNanos < SILENCE_NS;
    }

    /** The song's mood (GROOVE when there is no live beat). */
    public Mood mood() {
        return isLive() ? mood : Mood.GROOVE;
    }

    /** Called when a drop (sudden burst of energy) is detected — from the audio thread! */
    public void setOnDrop(Runnable onDrop) {
        this.onDrop = onDrop;
    }

    /** Estimated tempo (-1 when not live). */
    public double bpm() {
        return isLive() ? 60e9 / periodNanos : -1;
    }

    // ---------------------------------------------------------------- BeatSource

    @Override
    public synchronized double beatPosition(long nowMillis) {
        if (!isLive()) return fallback.beatPosition(nowMillis);
        double p = rawBeat(System.nanoTime());
        p = Math.max(p, lastReturned); // never go backwards
        lastReturned = p;
        return p;
    }

    private double rawBeat(long nanos) {
        return anchorBeat + (nanos - anchorNanos) / periodNanos;
    }

    // ---------------------------------------------------------------- audio input

    /**
     * Processes audio samples. arrivalNanos: when the last sample arrived (System.nanoTime).
     * Must be called from a single thread.
     */
    public void process(float[] samples, int count, long arrivalNanos) {
        lastAudioNanos = arrivalNanos;
        long firstSampleIndex = samplesReceived;
        samplesReceived += count;
        for (int i = 0; i < count; i++) {
            // Ring buffer: frame[] holds the last FFT_SIZE samples, ringPos points at the oldest one
            frame[ringPos] = samples[i];
            ringPos = (ringPos + 1) % FFT_SIZE;
            if (++filled >= HOP) {
                filled = 0;
                long samplesAfter = samplesReceived - (firstSampleIndex + i + 1);
                long frameNanos = arrivalNanos - (long) (samplesAfter * 1e9 / SAMPLE_RATE);
                analyzeFrame(frameNanos);
            }
        }
    }

    private void analyzeFrame(long nanos) {
        double energy = 0;
        for (int i = 0; i < FFT_SIZE; i++) {
            double s = frame[(ringPos + i) % FFT_SIZE]; // oldest to newest
            re[i] = s * hann[i];
            im[i] = 0;
            energy += s * s;
        }
        if (Math.sqrt(energy / FFT_SIZE) > 0.002) lastLoudNanos = nanos;

        fft.transform(re, im);
        // Average the rise of the bass band (~40-260 Hz: kick) and of the other bands separately.
        // If we just summed them, broadband hi-hats (hundreds of bins) would drown out the kick
        // and the cat would lock onto the off-beats.
        double lowFlux = 0, highFlux = 0;
        int lowBins = 0, highBins = 0;
        for (int k = 1; k < FFT_SIZE / 2; k++) {
            double mag = Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            double lm = Math.log1p(100 * mag);
            double diff = Math.max(0, lm - prevLogMag[k]);
            prevLogMag[k] = lm;
            if (k <= 6) { lowFlux += diff; lowBins++; } else { highFlux += diff; highBins++; }
        }
        double flux = 0.75 * lowFlux / lowBins + 0.25 * highFlux / highBins;
        int idx = (int) (frameCount % HISTORY);
        onset[idx] = flux;
        onsetTime[idx] = nanos;
        rmsHistory[idx] = Math.sqrt(energy / FFT_SIZE);
        frameCount++;
        updateEnergy(rmsHistory[idx], flux, nanos);

        if (frameCount >= MIN_FRAMES && frameCount % ESTIMATE_EVERY == 0) estimate();
    }

    // ---------------------------------------------------------------- energy, mood, drop

    private void updateEnergy(double rms, double flux, long nanos) {
        // To be independent of the volume, everything is measured relative to the song's peak so far.
        // Peaks decay slowly (~95% per minute) so they adapt when the song changes.
        loudShort += (rms - loudShort) / 30;
        punch += (flux - punch) / 150;
        peakLoud = Math.max(peakLoud * 0.9995, loudShort);
        peakPunch = Math.max(peakPunch * 0.9995, punch);

        if (frameCount % ESTIMATE_EVERY == 0) updateMood(nanos);
        if (frameCount > DROP_SHORT + DROP_LONG) detectDrop(nanos);
    }

    private void updateMood(long nanos) {
        // Relative (to the song's own peak) + absolute loudness. With only the relative part, a
        // song's quiet intro would look like its loudest moment. Spotify normalizes loudness, so
        // the absolute level is a reliable hint too (~0.12 RMS = really loud).
        double absolute = Math.min(1, loudShort / 0.12);
        double intensity = 0.35 * (loudShort / peakLoud) + 0.25 * (punch / peakPunch) + 0.4 * absolute;
        double bpm = 60e9 / periodNanos;
        Mood m;
        if (intensity > 0.78 && bpm >= 108) m = Mood.HYPE;
        else if (intensity < 0.5 || bpm < 88) m = Mood.CHILL;
        else m = Mood.GROOVE;
        // Only switch mood after it has been stable for 3 s (so it doesn't flip back and forth)
        if (m != moodCandidate) {
            moodCandidate = m;
            moodCandidateSince = nanos;
        } else if (m != mood && nanos - moodCandidateSince > MOOD_HOLD_NS) {
            mood = m;
        }
    }

    /** If the last 0.25 s is ~2x louder than the 4 s before and close to the song's peak: drop! */
    private void detectDrop(long nanos) {
        double recent = averageRms(0, DROP_SHORT);
        double before = averageRms(DROP_SHORT, DROP_SHORT + DROP_LONG);
        boolean wasPlaying = before > 0.08 * peakLoud;   // a song starting from silence is not a drop
        boolean bigJump = recent > 1.9 * before && recent > 0.6 * peakLoud;
        if (wasPlaying && bigJump && nanos - lastDropNanos > DROP_COOLDOWN_NS && locked) {
            lastDropNanos = nanos;
            mood = Mood.HYPE;
            moodCandidate = Mood.HYPE;
            moodCandidateSince = nanos;
            onDrop.run();
        }
    }

    /** Average loudness of frames [from, to) counting back from the latest frame. */
    private double averageRms(int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) sum += rmsHistory[(int) ((frameCount - 1 - i) % HISTORY)];
        return sum / (to - from);
    }

    // ---------------------------------------------------------------- estimation

    private void estimate() {
        int n = (int) Math.min(frameCount, HISTORY);
        double[] o = new double[n];
        long[] t = new long[n];
        for (int i = 0; i < n; i++) {
            int idx = (int) ((frameCount - n + i) % HISTORY);
            o[i] = onset[idx];
            t[i] = onsetTime[idx];
        }
        double[] env = normalize(o);

        // --- Tempo: autocorrelation
        int minLag = (int) Math.floor(FPS * 60 / MAX_BPM);
        int maxLag = (int) Math.ceil(FPS * 60 / MIN_BPM);
        double ac0 = autocorr(env, 0);
        if (ac0 <= 0) { locked = false; return; }
        double[] score = new double[maxLag + 2];
        int best = -1;
        for (int lag = minLag; lag <= maxLag + 1; lag++) {
            double ac = autocorr(env, lag);
            if (2 * lag < n / 2) ac += 0.5 * autocorr(env, 2 * lag); // two beats later should match too
            double bpm = FPS * 60 / lag;
            double octave = Math.log(bpm / 120) / Math.log(2);
            score[lag] = ac * Math.exp(-0.5 * (octave / 0.9) * (octave / 0.9));
            if (lag <= maxLag && (best < 0 || score[lag] > score[best])) best = lag;
        }
        double confidence = autocorr(env, best) / ac0;
        if (confidence < 0.08) { locked = false; return; }

        // Parabolic interpolation: find the fractional period
        double period = best;
        if (best > minLag && best < maxLag) {
            double a = score[best - 1], b = score[best], c = score[best + 1];
            double denom = a - 2 * b + c;
            if (denom < 0) period = best + 0.5 * (a - c) / denom;
        }
        updatePeriod(period);

        // --- Phase: comb matching
        double p = periodFrames;
        double bestScore = -1;
        int bestPhase = 0;
        for (int phase = 0; phase < (int) Math.ceil(p); phase++) {
            double s = 0, w = 1;
            for (double pos = n - 1 - phase; pos >= 1; pos -= p) {
                s += w * interp(env, pos);
                w *= 0.92; // older beats matter less
            }
            if (s > bestScore) {
                bestScore = s;
                bestPhase = phase;
            }
        }
        long lastBeatNanos = t[n - 1] - (long) (bestPhase * 1e9 / FPS);
        correctClock(lastBeatNanos, p * 1e9 / FPS);
        locked = true;
    }

    /** Keep the tempo stable: average close estimates, switch to a different one only after 3 votes in a row. */
    private void updatePeriod(double period) {
        if (Math.abs(period / periodFrames - 1) < 0.05) {
            periodFrames = 0.8 * periodFrames + 0.2 * period;
            candidateVotes = 0;
        } else if (candidateVotes > 0 && Math.abs(period / candidatePeriod - 1) < 0.05) {
            if (++candidateVotes >= 3) {
                periodFrames = period;
                candidateVotes = 0;
            }
        } else {
            candidatePeriod = period;
            candidateVotes = 1;
            if (!locked) periodFrames = period; // don't wait on the first lock
        }
    }

    /** Gently pulls the beat counter toward the detected phase and tempo. */
    private synchronized void correctClock(long lastBeatNanos, double newPeriodNanos) {
        long now = System.nanoTime();
        if (!locked) { // first lock: align the counter directly, continuing from where it was
            double base = Math.floor(Math.max(lastReturned, fallback.beatPosition(System.currentTimeMillis())));
            periodNanos = newPeriodNanos;
            anchorBeat = base + 1 + (now - lastBeatNanos) / periodNanos;
            anchorNanos = now;
            return;
        }
        double atBeat = rawBeat(lastBeatNanos);
        double error = Math.rint(atBeat) - atBeat; // -0.5..0.5 beats
        double current = rawBeat(now);
        periodNanos = newPeriodNanos;
        anchorBeat = current + error * 0.35;
        anchorNanos = now;
    }

    // ---------------------------------------------------------------- helpers

    /** Subtract the local mean, clip negatives to zero, scale to 0..1. */
    private static double[] normalize(double[] o) {
        int n = o.length, w = 16;
        double[] out = new double[n];
        double sum = 0, max = 0;
        double[] prefix = new double[n + 1];
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + o[i];
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - w), b = Math.min(n, i + w + 1);
            double mean = (prefix[b] - prefix[a]) / (b - a);
            out[i] = Math.max(0, o[i] - mean);
            sum += out[i];
            max = Math.max(max, out[i]);
        }
        if (max > 0) for (int i = 0; i < n; i++) out[i] /= max;
        return out;
    }

    private static double autocorr(double[] x, int lag) {
        double s = 0;
        for (int i = lag; i < x.length; i++) s += x[i] * x[i - lag];
        return s / (x.length - lag);
    }

    private static double interp(double[] x, double pos) {
        int i = (int) pos;
        double f = pos - i;
        if (i + 1 >= x.length) return x[x.length - 1];
        return x[i] * (1 - f) + x[i + 1] * f;
    }
}
