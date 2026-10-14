package dev.hoduj.iotpasslite

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnticipateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/** Black indicator shown while a door reader stays in range.
 *
 *  Collapsed: a rounded notch that grows out of the top edge (corners curve out of the wall), with a pulsing
 *  green dot and "연결됨". Pull down to expand it; the expanded panel lists the readers currently detected
 *  and their signal strength. Swipe up to close it. It stays up as long as sightings keep arriving, and
 *  closes after [HOLD_MS] without one. */
object SendOverlay {

    /** Same window as [DoorPresence]: the overlay closes once no reader counts as nearby any more. */
    private const val HOLD_MS = DoorPresence.NEARBY_WINDOW_MS
    /** How long a settings preview stays on screen. */
    private const val PREVIEW_MS = 2_500L
    /** How long the "watcher ready" state stays on screen after the background scan starts. */
    private const val READY_MS = 2_500L
    private const val POP_IN_MS = 420L
    private const val POP_OUT_MS = 280L
    private const val EXPAND_MS = 420L
    private const val COLLAPSE_MS = 300L
    private const val SNAP_MS = 260L
    private const val PULSE_MS = 1_600L

    private const val NOTCH_W_DP = 104f
    private const val NOTCH_H_DP = 22f
    private const val NOTCH_FLARE_DP = 12f
    private const val NOTCH_RADIUS_DP = 13f
    private const val EXPANDED_W_DP = 184f
    private const val HEADER_DP = 22f
    private const val ROW_DP = 28f
    private const val EXPANDED_PAD_DP = 14f
    private const val EXPANDED_RADIUS_DP = 20f

    private const val PILL_W_DP = 112f
    private const val PILL_H_DP = 22f
    private const val FLOAT_TOP_DP = 28
    private const val PILL_MARGIN_DP = 4

    /** Window height as a multiple of the shape height, leaving room for the overshoot. */
    private const val ROOM = 1.2f

    /** Pulling down this far fully expands the notch. */
    private const val PULL_DP = 56f
    /** Swiping up this far closes it. */
    private const val SWIPE_DP = 36
    private const val FLING_PX_PER_S = 700f

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { shrinkAndRemove() }

    private var windowManager: WindowManager? = null
    private var root: View? = null
    private var island: IslandDrawable? = null
    private var params: WindowManager.LayoutParams? = null
    private var attachedMode = true
    private var moveAnimator: ValueAnimator? = null
    private var growAnimator: ValueAnimator? = null
    private var expandAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var density = 1f
    private var baseY = 0
    private var downRawY = 0f
    private var downY = 0
    private var downExpand = 0f
    private var tracker: VelocityTracker? = null

    /** Set after the user swipes the overlay away; cleared once the readers have been quiet for [HOLD_MS]. */
    private var dismissed = false

    private var appContext: Context? = null

    private fun raw(message: String) {
        appContext?.let { EventLog.raw(it, EventLog.Category.OVERLAY, message) }
    }

    /** True while the shrink runs, so a sighting during it does not add a second window. */
    private var exiting = false

    fun canOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Shows the overlay, or keeps it up if already shown. Each call restarts the hold timer and refreshes
     *  the list of detected readers. */
    /** Briefly shows "감시 준비됨" once the background scan is registered. Does nothing if the overlay is already
     *  up, so it never replaces a live reader list. */
    fun showReady(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!canOverlay(app) || island != null || dismissed) return
        addView(app, app.getString(R.string.overlay_ready))
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, READY_MS)
    }

    /** Shows the overlay in the saved style for a few seconds, so a style change can be seen at once. A visible
     *  overlay is removed first so the new style is drawn. Returns false when the overlay permission is missing. */
    fun preview(context: Context): Boolean {
        val app = context.applicationContext
        if (!canOverlay(app)) return false
        if (root != null) removeView()
        show(app)
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, PREVIEW_MS)
        return true
    }

    fun show(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!canOverlay(app)) return
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, HOLD_MS)
        val drawable = island
        if (drawable != null) {
            drawable.text = app.getString(R.string.overlay_connected)
            drawable.lines = readerLines(app)
            if (exiting && !dismissed) reappear()
            return
        }
        if (!dismissed) addView(app, app.getString(R.string.overlay_connected))
    }

    /** Cancels a shrink-out in progress and slides the window back down. */
    private fun reappear() {
        raw("reappear while hiding")
        exiting = false
        pulseAnimator?.start()
        moveTo(baseY, POP_IN_MS, OvershootInterpolator(1.1f), null)
    }

    /** One row per detected reader: (name or MAC, signal strength). */
    private fun readerLines(context: Context): List<Pair<String, String>> {
        val names = Prefs.getDoors(context).associate { it.mac to it.name }
        return DoorPresence.recent().map { (mac, rssi) ->
            (names[mac]?.ifEmpty { null } ?: mac) to "$rssi dBm"
        }
    }

    private fun addView(app: Context, label: String) {
        density = app.resources.displayMetrics.density
        attachedMode = Prefs.isOverlayTopAttached(app)
        val drawable = IslandDrawable(attachedMode, density, label, 0f)
        drawable.lines = readerLines(app)
        island = drawable

        val view = View(app).apply {
            background = drawable
            if (!attachedMode) setOnTouchListener { _, e -> onTouch(e) }
        }

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val lp: WindowManager.LayoutParams
        if (attachedMode) {
            // Attached to the top wall at a fixed size. Display only: touches pass through the notch.
            flags = flags or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            lp = WindowManager.LayoutParams(
                ((NOTCH_W_DP + 2 * NOTCH_FLARE_DP) * density).toInt(),
                (NOTCH_H_DP * density).toInt(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT,
            )
            lp.y = 0
        } else {
            lp = WindowManager.LayoutParams(
                (PILL_W_DP * density).toInt() + 2 * PILL_MARGIN_DP,
                (PILL_H_DP * ROOM * density).toInt() + 2 * PILL_MARGIN_DP,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT,
            )
            lp.y = (FLOAT_TOP_DP * density).toInt()
        }
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params = lp
        root = view
        baseY = lp.y

        // Start above the screen edge so the first frame is already off-screen; no flash before the slide-in.
        lp.y = baseY - lp.height
        drawable.progress = 1f
        windowManager = app.getSystemService(WindowManager::class.java)
        windowManager?.addView(view, lp)
        raw("shown (lines=${drawable.lines.size})")
        moveTo(baseY, POP_IN_MS, OvershootInterpolator(1.1f), null)
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { drawable.pulse = it.animatedValue as Float }
            start()
        }
    }

    private fun onTouch(e: MotionEvent): Boolean {
        val lp = params ?: return false
        val drawable = island ?: return false
        if (exiting) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelMove()
                cancelExpand()
                downRawY = e.rawY
                downY = lp.y
                downExpand = drawable.expand
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                val dy = e.rawY - downRawY
                val pull = PULL_DP * density
                if (dy > 0) return true
                if (downExpand > 0f) {
                    // Pull down (or pull back up while expanded) changes the expansion.
                    if (drawable.expand == 0f && dy > 0) prepareExpandWindow(drawable)
                    drawable.expand = (downExpand + dy / pull).coerceIn(0f, 1f)
                } else {
                    // Swipe up while collapsed moves the whole window with the finger.
                    lp.y = (downY + dy).toInt()
                    updateLayout(lp)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val t = tracker
                t?.addMovement(e)
                t?.computeCurrentVelocity(1000)
                val velocity = t?.yVelocity ?: 0f
                t?.recycle()
                tracker = null
                val dy = e.rawY - downRawY
                when {
                    drawable.expand > 0f || downExpand > 0f -> {
                        if (drawable.expand > 0.5f) expandTo(1f) else expandTo(0f)
                    }
                    dy < -SWIPE_DP * density || velocity < -FLING_PX_PER_S -> {
                        dismissed = true
                        shrinkAndRemove()
                    }
                    else -> moveTo(baseY, SNAP_MS, OvershootInterpolator(1.0f), null)
                }
                return true
            }
        }
        return false
    }

    /** Makes the window tall enough for the expanded panel before it starts to open. */
    private fun prepareExpandWindow(drawable: IslandDrawable) {
        val lp = params ?: return
        val rows = maxOf(1, drawable.lines.size)
        lp.width = ((EXPANDED_W_DP + 2 * NOTCH_FLARE_DP) * density).toInt()
        lp.height = ((EXPANDED_PAD_DP * 2 + HEADER_DP + rows * ROW_DP) * density * ROOM).toInt()
        updateLayout(lp)
    }

    private fun expandTo(target: Float) {
        val drawable = island ?: return
        cancelExpand()
        expandAnimator = ValueAnimator.ofFloat(drawable.expand, target).apply {
            duration = if (target > 0f) EXPAND_MS else COLLAPSE_MS
            interpolator = if (target > 0f) OvershootInterpolator(0.9f) else DecelerateInterpolator(1.5f)
            addUpdateListener { drawable.expand = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (target == 0f) restoreCollapsedWindow()
                }
            })
            start()
        }
    }

    private fun restoreCollapsedWindow() {
        val lp = params ?: return
        lp.width = if (attachedMode) {
            ((NOTCH_W_DP + 2 * NOTCH_FLARE_DP) * density).toInt()
        } else {
            (PILL_W_DP * density).toInt() + 2 * PILL_MARGIN_DP
        }
        lp.height = if (attachedMode) {
            (NOTCH_H_DP * ROOM * density).toInt()
        } else {
            (PILL_H_DP * ROOM * density).toInt() + 2 * PILL_MARGIN_DP
        }
        updateLayout(lp)
    }

    private fun cancelExpand() {
        expandAnimator?.cancel()
        expandAnimator = null
    }

    private fun updateLayout(lp: WindowManager.LayoutParams) {
        val view = root ?: return
        windowManager?.updateViewLayout(view, lp)
    }

    private fun moveTo(
        targetY: Int,
        durationMs: Long,
        interpolator: TimeInterpolator,
        onEnd: (() -> Unit)?,
    ) {
        val lp = params ?: return
        cancelMove()
        moveAnimator = ValueAnimator.ofInt(lp.y, targetY).apply {
            duration = durationMs
            this.interpolator = interpolator
            addUpdateListener {
                lp.y = it.animatedValue as Int
                updateLayout(lp)
            }
            if (onEnd != null) {
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        onEnd()
                    }
                })
            }
            start()
        }
    }

    /** Slides back up into the wall, then removes the window. */
    private fun shrinkAndRemove() {
        val lp = params ?: return
        if (root == null || exiting) return
        raw("hide (dismissed=$dismissed, lines=${island?.lines?.size ?: 0})")
        exiting = true
        handler.removeCallbacks(hideRunnable)
        pulseAnimator?.cancel()
        cancelExpand()
        moveTo(baseY - lp.height, POP_OUT_MS, AnticipateInterpolator(0.8f)) { removeView() }
    }

    private fun cancelMove() {
        moveAnimator?.cancel()
        moveAnimator = null
    }

    private fun removeView() {
        cancelMove()
        cancelExpand()
        growAnimator?.cancel()
        pulseAnimator?.cancel()
        growAnimator = null
        pulseAnimator = null
        root?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (ignored: IllegalArgumentException) {
                // Already removed.
            }
        }
        root = null
        island = null
        params = null
        exiting = false
        dismissed = false
    }

    /** The shape and its contents, drawn in one pass.
     *  [progress]: 0 hidden, 1 settled, slightly above 1 while overshooting.
     *  [expand]: 0 collapsed notch, 1 expanded panel listing [lines].
     *  [pulse]: 0..1 breathing of the dot. */
    private class IslandDrawable(
        private val attached: Boolean,
        private val density: Float,
        text: String,
        private val barPx: Float,
    ) : Drawable() {
        var text: String = text
            set(value) {
                field = value
                invalidateSelf()
            }

        var progress = 0f
            set(value) {
                field = value
                invalidateSelf()
            }
        var expand = 0f
            set(value) {
                field = value
                invalidateSelf()
            }
        var pulse = 0f
            set(value) {
                field = value
                invalidateSelf()
            }
        var lines: List<Pair<String, String>> = emptyList()
            set(value) {
                field = value
                invalidateSelf()
            }

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = 255
            style = Paint.Style.FILL
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f * density
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val detail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0xCC, 0xFF, 0xFF, 0xFF)
            textSize = 12f * density
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN }
        private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12f * density
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val rowName = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f * density
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        private val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0x80, 0xFF, 0xFF, 0xFF)
            textSize = 12f * density
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        private val mutedRight = Paint(muted).apply { textAlign = Paint.Align.RIGHT }
        private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0x1F, 0xFF, 0xFF, 0xFF)
            strokeWidth = density
        }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0x26, 0xFF, 0xFF, 0xFF)
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        private val path = Path()
        private val rect = RectF()
        private val arcRight = RectF()
        private val arcLeft = RectF()
        private val arcBottomRight = RectF()
        private val arcBottomLeft = RectF()

        override fun draw(canvas: Canvas) {
            val p = progress
            if (p <= 0.001f) return
            val e = expand.coerceIn(0f, 1f)
            val b = bounds
            val cx = b.exactCenterX()

            val rows = maxOf(1, lines.size)
            val collapsedH = if (attached) barPx + NOTCH_H_DP * density else PILL_H_DP * density
            val expandedH = (EXPANDED_PAD_DP * 2 + HEADER_DP + rows * ROW_DP) * density
            val bodyW = lerp(if (attached) NOTCH_W_DP else PILL_W_DP, EXPANDED_W_DP, e) * density
            val bodyH = lerp(collapsedH, expandedH, e) * p
            val top = if (attached) b.top.toFloat() else b.top + PILL_MARGIN_DP * density
            val contentTop = top
            val left = cx - bodyW / 2
            val right = cx + bodyW / 2
            val bottom = top + bodyH
            val radius = minOf(lerp(NOTCH_RADIUS_DP, EXPANDED_RADIUS_DP, e) * density, bodyH / 2)

            if (attached) {
                // Concave fillets (radius f) where the top edge meets the wall, then convex bottom corners.
                val f = minOf(NOTCH_FLARE_DP * density * p * (1f - e * 0.6f), bodyH / 2)
                path.reset()
                path.moveTo(left - f, top)
                path.lineTo(right + f, top)
                arcRight.set(right, top, right + 2 * f, top + 2 * f)
                path.arcTo(arcRight, 270f, -90f, false)
                path.lineTo(right, bottom - radius)
                arcBottomRight.set(right - 2 * radius, bottom - 2 * radius, right, bottom)
                path.arcTo(arcBottomRight, 0f, 90f, false)
                path.lineTo(left + radius, bottom)
                arcBottomLeft.set(left, bottom - 2 * radius, left + 2 * radius, bottom)
                path.arcTo(arcBottomLeft, 90f, 90f, false)
                path.lineTo(left, top + f)
                arcLeft.set(left - 2 * f, top, left, top + 2 * f)
                path.arcTo(arcLeft, 0f, -90f, false)
                path.close()
                canvas.drawPath(path, fill)
            } else {
                rect.set(left, top, right, bottom)
                canvas.drawRoundRect(rect, radius, radius, fill)
            }

            val contentAlpha = ((p - 0.45f) / 0.55f).coerceIn(0f, 1f)
            if (contentAlpha <= 0f) return

            // Collapsed content: dot and label, centered.
            val collapsedAlpha = (contentAlpha * (1f - e * 2f)).coerceIn(0f, 1f)
            if (collapsedAlpha > 0f) {
                val midY = (contentTop + bottom) / 2
                val dotR = 3f * density
                val gap = 6f * density
                val totalW = dotR * 2 + gap + label.measureText(text)
                val startX = cx - totalW / 2
                dot.alpha = (255 * collapsedAlpha * (0.45f + 0.55f * (1f - pulse))).toInt()
                canvas.drawCircle(startX + dotR, midY, dotR, dot)
                label.alpha = (255 * collapsedAlpha).toInt()
                val fm = label.fontMetrics
                canvas.drawText(text, startX + dotR * 2 + gap, midY - (fm.ascent + fm.descent) / 2, label)
            }

            // Expanded panel: header with count, then one aligned row per reader, hairlines between rows.
            val expandedAlpha = ((e - 0.35f) / 0.65f).coerceIn(0f, 1f) * contentAlpha
            if (expandedAlpha > 0f) {
                val pad = EXPANDED_PAD_DP * density
                val headerH = HEADER_DP * density
                val rowH = ROW_DP * density
                val x0 = left + pad
                val x1 = right - pad
                val headerMid = contentTop + pad + headerH / 2
                val headerBaseline = headerMid + title.textSize * 0.35f

                dot.alpha = (255 * expandedAlpha).toInt()
                canvas.drawCircle(x0 + 3f * density, headerMid, 3f * density, dot)
                title.alpha = (255 * expandedAlpha).toInt()
                canvas.drawText("연결된 리더기", x0 + 12f * density, headerBaseline, title)
                mutedRight.alpha = (255 * expandedAlpha).toInt()
                canvas.drawText("${lines.size}대", x1, headerBaseline, mutedRight)

                val listTop = contentTop + pad + headerH
                if (lines.isEmpty()) {
                    muted.alpha = (255 * expandedAlpha).toInt()
                    canvas.drawText("감지된 리더기 없음", x0, listTop + rowH / 2 + muted.textSize * 0.35f, muted)
                }
                val maxNameW = (x1 - x0) * 0.62f
                lines.forEachIndexed { i, (name, dbm) ->
                    val y = listTop + rowH * i
                    if (i > 0) {
                        hairline.alpha = (255 * expandedAlpha).toInt()
                        canvas.drawLine(x0, y, x1, y, hairline)
                    }
                    val baseline = y + rowH / 2 + rowName.textSize * 0.35f
                    rowName.alpha = (255 * expandedAlpha).toInt()
                    canvas.drawText(fit(name, maxNameW), x0, baseline, rowName)
                    mutedRight.alpha = (255 * expandedAlpha).toInt()
                    canvas.drawText(dbm, x1, baseline, mutedRight)
                }
            }
        }

        /** Shortens [text] with an ellipsis so it fits in [maxWidth] pixels. */
        private fun fit(text: String, maxWidth: Float): String {
            if (rowName.measureText(text) <= maxWidth) return text
            var end = text.length
            while (end > 1 && rowName.measureText(text.substring(0, end) + "…") > maxWidth) end--
            return text.substring(0, end) + "…"
        }

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        override fun setAlpha(alpha: Int) {
            // Ignored on purpose: the notch is always solid black.
            fill.alpha = 255
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fill.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        private companion object {
            val GREEN: Int = Color.rgb(0x30, 0xD1, 0x58)
        }
    }
}
