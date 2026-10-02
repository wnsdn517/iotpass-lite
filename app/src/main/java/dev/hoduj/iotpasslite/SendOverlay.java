package dev.hoduj.iotpasslite;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Toast;

/** Calm, light-based status pill with a card-glow edge: several soft blobs of coloured light
 *  drift around the capsule independently (different colours, speeds, directions), each swelling
 *  and dimming on its own so the glow looks mottled and alive. On completion the glow settles to
 *  green, the icon becomes a check badge and the label reads "sent"; then everything fades
 *  slowly. It only reports that the signal was transmitted, not that the door opened. Needs the
 *  optional "draw over other apps" permission; without it a plain toast is shown instead.
 *  Lives ~3.2s, GPU-drawn, and the animator is cancelled on removal. */
final class SendOverlay {

    private static final long DURATION_MS = 3200L;
    private static final int PILL_W_DP = 152;
    private static final int PILL_H_DP = 48;
    private static final int GLOW_MARGIN_DP = 36;
    private static final int TOP_OFFSET_DP = 6;

    private SendOverlay() {
    }

    static boolean canOverlay(Context context) {
        return Settings.canDrawOverlays(context);
    }

    static void show(Context context) {
        Context app = context.getApplicationContext();
        try {
            if (!canOverlay(app)) {
                Toast.makeText(app, R.string.toast_opening, Toast.LENGTH_SHORT).show();
                return;
            }
            WindowManager wm = app.getSystemService(WindowManager.class);
            PillView view = new PillView(app);
            float d = app.getResources().getDisplayMetrics().density;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    (int) ((PILL_W_DP + 2 * GLOW_MARGIN_DP) * d),
                    (int) ((PILL_H_DP + 2 * GLOW_MARGIN_DP) * d),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            // Non-activity windows are software-drawn unless asked: keep the
                            // animation on the GPU so it can't starve the UI thread.
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = (int) (TOP_OFFSET_DP * d);
            // Android 12+ drops touches to apps beneath an overlay more opaque than 0.8
            // ("untrusted touch" protection); stay under it so taps always reach the app below.
            lp.alpha = 0.79f;
            wm.addView(view, lp);
            view.start();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                view.stop();
                try {
                    wm.removeView(view);
                } catch (IllegalArgumentException ignored) {
                    // Already gone.
                }
            }, DURATION_MS);
        } catch (Exception e) {
            // Best-effort feedback only; never let a UI hiccup break the open sequence.
            try {
                Toast.makeText(app, R.string.toast_opening, Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
                // Nothing more to do.
            }
        }
    }

    private static final class PillView extends View {
        private static final int GREEN = 0xFF30D158;
        private static final int WHITE = 0xFFFFFFFF;
        private static final int GRAY = 0xFF8E8E93;

        // Flowing-gradient layers (like the Gemini assistant glow): each layer is a colour gradient
        // laid across the pill that slides along the edge in its own direction and speed. Colours
        // carry their own alpha, so brightness is mottled, and the layers add up where they cross.
        private static final int BLUE = 0xFF8AB4F8;
        private static final int VIOLET = 0xFFB9A3E3;
        private static final int ROSE = 0xFFE59AA3;
        private static final int AMBER = 0xFFF6C969;
        private static final int CYAN = 0xFF8DD8EE;
        private static final int[][] LAYER_COLORS = {
                {BLUE, 0x40B9A3E3, ROSE, 0x40F6C969, CYAN, BLUE},
                {ROSE, 0x388DD8EE, BLUE, 0x38B9A3E3, AMBER, ROSE},
                {0x388DD8EE, BLUE, VIOLET, 0x38E59AA3, CYAN, 0x388DD8EE}};
        private static final float[] LAYER_SPEED_X = {46f, -62f, 30f};  // dp/s
        private static final float[] LAYER_SPEED_Y = {6f, -8f, 10f};    // dp/s
        private static final float[] LAYER_FREQ = {1.9f, 2.7f, 2.2f};   // breathing, rad/s
        private static final int LAYERS = LAYER_COLORS.length;

        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wide = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint greenWide = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint greenMid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint greenRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint status = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path check = new Path();
        private final Path checkPart = new Path();
        private final PathMeasure checkMeasure = new PathMeasure();
        private final Matrix rotate = new Matrix();
        private final RectF rect = new RectF();
        private final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        private final OvershootInterpolator spring = new OvershootInterpolator(0.6f);
        private final OvershootInterpolator pop = new OvershootInterpolator(1.4f);
        private final DecelerateInterpolator ease = new DecelerateInterpolator(1.8f);
        private final LinearGradient[] layerShader = new LinearGradient[LAYERS];
        private final float d;
        private LinearGradient bodyShader;
        private float t;

        PillView(Context context) {
            super(context);
            d = context.getResources().getDisplayMetrics().density;
            setLayerType(LAYER_TYPE_HARDWARE, null);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(1f * d);
            border.setColor(WHITE);
            // Overlapping blobs add their light together, like real glow.
            PorterDuffXfermode add = new PorterDuffXfermode(PorterDuff.Mode.ADD);
            soft(wide, 14f, 26f, add);
            soft(mid, 7f, 11f, add);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.6f * d);
            rim.setXfermode(add);
            soft(greenWide, 14f, 26f, null);
            soft(greenMid, 7f, 11f, null);
            greenWide.setColor(GREEN);
            greenMid.setColor(GREEN);
            greenRim.setStyle(Paint.Style.STROKE);
            greenRim.setStrokeWidth(1.6f * d);
            greenRim.setColor(GREEN);
            iconRing.setStyle(Paint.Style.STROKE);
            iconRing.setStrokeWidth(1.8f * d);
            iconRing.setColor(WHITE);
            checkPaint.setStyle(Paint.Style.STROKE);
            checkPaint.setStrokeWidth(2.2f * d);
            checkPaint.setStrokeCap(Paint.Cap.ROUND);
            checkPaint.setStrokeJoin(Paint.Join.ROUND);
            checkPaint.setColor(WHITE);
            title.setTextSize(10f * d);
            title.setColor(GRAY);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            status.setTextSize(14f * d);
            status.setColor(WHITE);
            status.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            animator.setDuration(DURATION_MS);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(a -> {
                t = (float) a.getAnimatedValue();
                invalidate();
            });
        }

        private void soft(Paint p, float widthDp, float blurDp, PorterDuffXfermode mode) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(widthDp * d);
            p.setMaskFilter(new BlurMaskFilter(blurDp * d, BlurMaskFilter.Blur.NORMAL));
            if (mode != null) {
                p.setXfermode(mode);
            }
        }

        void start() {
            animator.start();
        }

        void stop() {
            animator.cancel();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;
            final float fullW = PILL_W_DP * d;
            final float fullH = PILL_H_DP * d;
            final float sec = t * DURATION_MS / 1000f;

            // Timeline (of 3.2s): ease in 0-0.22 | glow swells 0.06-0.4 | send |
            // done 0.62-0.78 | slow fade 0.8-1.
            float in = clamp(t / 0.22f);
            float out = clamp((t - 0.8f) / 0.2f);
            float master = clamp(in * 3f) * (1f - out * out * (3f - 2f * out)); // smooth fade
            float swell = smooth(clamp((t - 0.06f) / 0.34f));
            float done = smooth(clamp((t - 0.62f) / 0.16f));

            float e = spring.getInterpolation(in);
            float curW = lerp(80f * d, fullW, e);
            float curH = lerp(30f * d, fullH, e);
            float scale = 1f - 0.03f * out;
            canvas.save();
            canvas.translate(0f, -(1f - ease.getInterpolation(in)) * 14f * d);
            canvas.scale(scale, scale, cx, cy);
            rect.set(cx - curW / 2f, cy - curH / 2f, cx + curW / 2f, cy + curH / 2f);
            float radius = curH / 2f;

            // Flowing glow: each gradient layer slides along the edge on its own and breathes on its
            // own beat, so bright and dim patches travel around the pill.
            float calm = swell * master * 0.6f * (1f - 0.7f * done);
            float left = cx - fullW / 2f;
            float top = cy - fullH / 2f;
            for (int i = 0; i < LAYERS; i++) {
                if (layerShader[i] == null) {
                    layerShader[i] = new LinearGradient(0f, 0f, fullW * 0.9f, fullH * 0.9f,
                            LAYER_COLORS[i], null, Shader.TileMode.MIRROR);
                }
                rotate.setTranslate(left + LAYER_SPEED_X[i] * d * sec, top + LAYER_SPEED_Y[i] * d * sec);
                layerShader[i].setLocalMatrix(rotate);
                float a = clamp(calm * layerBeat(i, sec));
                wide.setShader(layerShader[i]);
                wide.setAlpha((int) (255 * a));
                canvas.drawRoundRect(rect, radius, radius, wide);
                mid.setShader(layerShader[i]);
                mid.setAlpha((int) (255 * a));
                canvas.drawRoundRect(rect, radius, radius, mid);
            }

            // On completion the glow settles to an even green.
            greenWide.setAlpha((int) (255 * clamp(0.45f * done * master)));
            canvas.drawRoundRect(rect, radius, radius, greenWide);
            greenMid.setAlpha((int) (255 * clamp(0.55f * done * master)));
            canvas.drawRoundRect(rect, radius, radius, greenMid);

            // Glass body.
            if (bodyShader == null) {
                bodyShader = new LinearGradient(0f, cy - fullH / 2f, 0f, cy + fullH / 2f,
                        0xFF2A2A30, 0xFF050507, Shader.TileMode.CLAMP);
                body.setShader(bodyShader);
            }
            body.setAlpha((int) (255 * master));
            canvas.drawRoundRect(rect, radius, radius, body);

            // Crisp light rims over the body: the same layers, so the edge glints where light passes.
            for (int i = 0; i < LAYERS; i++) {
                rim.setShader(layerShader[i]);
                rim.setAlpha((int) (255 * clamp(calm * layerBeat(i, sec))));
                canvas.drawRoundRect(rect, radius, radius, rim);
            }
            greenRim.setAlpha((int) (255 * done * master));
            canvas.drawRoundRect(rect, radius, radius, greenRim);
            border.setAlpha((int) (40 * master));
            canvas.drawRoundRect(rect, radius, radius, border);

            float contentA = clamp((e - 0.7f) / 0.3f) * master;
            if (contentA > 0f) {
                float rcx = cx - fullW / 2f + 14f * d + 11f * d;
                drawIcon(canvas, rcx, cy, contentA, done, sec);
                float tx = rcx + 11f * d + 10f * d;
                title.setAlpha((int) (255 * contentA));
                canvas.drawText(getContext().getString(R.string.overlay_title), tx, cy - 2f * d, title);
                status.setAlpha((int) (255 * contentA * (1f - done)));
                canvas.drawText(getContext().getString(R.string.overlay_sending), tx, cy + 14f * d, status);
                if (done > 0f) {
                    status.setAlpha((int) (255 * contentA * done));
                    canvas.drawText(getContext().getString(R.string.overlay_done), tx, cy + 14f * d, status);
                }
            }
            canvas.restore();
        }

        /** Sending: a dot with two slowly pulsing signal rings. Done: a green badge easing in with
         *  a check that draws itself. */
        private void drawIcon(Canvas canvas, float x, float y, float alpha, float done, float sec) {
            if (done < 1f) {
                float a = alpha * (1f - done);
                iconFill.setColor(WHITE);
                iconFill.setAlpha((int) (255 * a));
                canvas.drawCircle(x, y, 2.6f * d, iconFill);
                for (int i = 1; i <= 2; i++) {
                    float wave = 0.5f + 0.5f * (float) Math.sin(sec * 4f - i * 1.1f);
                    iconRing.setAlpha((int) (255 * a * (0.25f + 0.75f * wave)));
                    canvas.drawCircle(x, y, (3f + i * 4.2f) * d, iconRing);
                }
            }
            if (done > 0f) {
                float r = 11f * d * pop.getInterpolation(done);
                iconFill.setColor(GREEN);
                iconFill.setAlpha((int) (255 * alpha));
                canvas.drawCircle(x, y, r, iconFill);
                float k = 11f * d * 0.45f;
                check.rewind();
                check.moveTo(x - k * 0.9f, y + k * 0.05f);
                check.lineTo(x - k * 0.2f, y + k * 0.7f);
                check.lineTo(x + k * 0.95f, y - k * 0.65f);
                checkMeasure.setPath(check, false);
                checkPart.rewind();
                checkMeasure.getSegment(0f, checkMeasure.getLength() * smooth(clamp((t - 0.64f) / 0.14f)),
                        checkPart, true);
                checkPaint.setAlpha((int) (255 * alpha));
                canvas.drawPath(checkPart, checkPaint);
            }
        }

        private static float layerBeat(int i, float sec) {
            return 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(sec * LAYER_FREQ[i] + i * 2.1f));
        }

        private static float clamp(float v) {
            return v < 0f ? 0f : Math.min(v, 1f);
        }

        private static float smooth(float v) {
            return v * v * (3f - 2f * v);
        }

        private static float lerp(float a, float b, float f) {
            return a + (b - a) * f;
        }
    }
}
