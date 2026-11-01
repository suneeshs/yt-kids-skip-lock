package com.example.skiplock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlin.math.abs

private const val TAG = "SkipLock"
private const val KIDS_PACKAGE = "com.google.android.apps.youtube.kids"
private const val ID_PREFIX = "$KIDS_PACKAGE:id/"

// Video tiles' descriptions end with the video length, e.g. "... 32 minutes, 47 seconds".
private val VIDEO_TILE = Regex("""\d+ (seconds?|minutes?|hours?)$""")

// Controls inside the player that can leave or skip through the video; only play/pause stays usable.
private val BLOCKED_IDS = listOf("next", "title_bar", "back_button_container", "watch_fragment_menu_buttons", "time_bar")

// Progress bar description, e.g. "video player progress bar, 4 minutes 36 seconds of 25 minutes 35 seconds, adjustable".
private val TIME_PART = Regex("""(\d+) (hour|minute|second)""")

private fun parseMs(text: String) = TIME_PART.findAll(text).sumOf {
    it.groupValues[1].toLong() * when (it.groupValues[2]) {
        "hour" -> 3_600_000L
        "minute" -> 60_000L
        else -> 1000L
    }
}

// A position this far off from normal playback counts as scrubbing; smaller gaps are buffering or rounding.
private const val SCRUB_MS = 5_000L

private class BarReading(val position: Long, val duration: Long, val playing: Boolean, val at: Long)

class SkipLockService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lockUntil = 0L
    private var lockStartedAt = 0L
    private var videoEndsAt: Long? = null
    private var onBrowseScreen = false
    private var playerRect: Rect? = null
    private var lastBar: BarReading? = null
    private val controlRects = mutableMapOf<String, Rect>()
    private val blocks = mutableListOf<View>()
    private var badge: TextView? = null

    private val refresh = Runnable { refreshOverlay() }
    private val hideBadge = Runnable { removeBadge() }

    // Videos opened from home start with controls hidden, so show them once to read the video length.
    private val revealControls = Runnable {
        if (!isLocked() || videoEndsAt != null || findKidsNode("time_bar") != null) return@Runnable
        val clicked = findKidsNode("watch_fragment_window")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "Revealing controls to read video length: $clicked")
        handler.postDelayed(refresh, 500)
    }

    private val tick = object : Runnable {
        override fun run() {
            videoEndsAt?.let {
                if (SystemClock.elapsedRealtime() >= it) {
                    Log.i(TAG, "Video finished, lock ended")
                    lockUntil = 0
                    videoEndsAt = null
                }
            }
            refreshOverlay()
            if (isLocked()) handler.postDelayed(this, 1000)
        }
    }

    // A video playing doesn't generate accessibility events, so poll for the player.
    private val poll = object : Runnable {
        override fun run() {
            if (kidsOnTop()) {
                scanPlayer()
                if (!isLocked()) refreshOverlay() // keeps the subscribe block in step outside a lock
            } else {
                onBrowseScreen = false
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() = poll.run()

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> refreshOverlay()
            // Controls showing/hiding or going full screen moves what needs blocking.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (pkg == KIDS_PACKAGE && (isLocked() || blockSubscribe)) {
                handler.removeCallbacks(refresh)
                handler.postDelayed(refresh, 50)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (pkg == KIDS_PACKAGE) onKidsClick(event)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        hideOverlay()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // Every picked video must play 3 minutes (or to its end) before the next pick; autoplay involves no tap.
    private fun onKidsClick(event: AccessibilityEvent) {
        val node = event.source
        val id = node?.viewIdResourceName?.removePrefix(ID_PREFIX)
        val desc = (node?.contentDescription ?: event.contentDescription)?.toString().orEmpty()
        val picksVideo = id == "next" || VIDEO_TILE.containsMatchIn(desc)
        Log.i(TAG, "Click id=$id desc='$desc' (picksVideo=$picksVideo)")
        if (picksVideo) startLock()
    }

    // Controls hide after a few seconds, so remember where they last were for the current player layout.
    private fun scanPlayer(): Rect? {
        val player = findKidsNode("watch_player")?.screenBounds()
        if (player != playerRect) controlRects.clear()
        playerRect = player
        if (player == null) {
            onBrowseScreen = true
            lastBar = null
            return null
        }
        // Home-screen tiles don't report a usable label, so going from browsing to the player is the pick.
        if (onBrowseScreen) {
            onBrowseScreen = false
            Log.i(TAG, "Video opened from a browse screen")
            startLock()
        }
        for (id in BLOCKED_IDS) findKidsNode(id)?.screenBounds()?.let { controlRects[id] = it }
        if (isLocked()) readProgress() else watchScrub()
        return player
    }

    // The progress bar only exists while controls show, so project the end time from the last reading.
    private fun readProgress() {
        val now = SystemClock.elapsedRealtime()
        if (now - lockStartedAt < 2000) return // the bar may still show the previous video
        val bar = readBar() ?: return
        if (videoEndsAt == null && bar.playing) Log.i(TAG, "Video progress read: ${bar.position}ms of ${bar.duration}ms")
        videoEndsAt = if (bar.playing) now + bar.duration - bar.position else null
    }

    // Skipping around with the progress bar is as good as picking another video, so it starts a lock.
    private fun watchScrub() {
        val bar = readBar() ?: return
        val prev = lastBar
        lastBar = bar
        if (prev == null || prev.duration != bar.duration) return // first reading, or a new video
        val expected = prev.position + if (prev.playing) bar.at - prev.at else 0
        if (abs(bar.position - expected) > SCRUB_MS) {
            Log.i(TAG, "Scrubbed from ${expected}ms to ${bar.position}ms")
            startLock()
        }
    }

    private fun readBar(): BarReading? {
        val parts = findKidsNode("time_bar")?.contentDescription?.toString()?.split(" of ") ?: return null
        if (parts.size != 2) return null
        val duration = parseMs(parts[1])
        if (duration <= 0) return null
        val playing = findKidsNode("pause")?.contentDescription?.startsWith("Pause") ?: return null
        return BarReading(parseMs(parts[0]), duration, playing, SystemClock.elapsedRealtime())
    }

    // Searches every window: while the lock overlay is up, the active window may not be YouTube Kids.
    private fun findKidsNode(id: String): AccessibilityNodeInfo? =
        windows.mapNotNull { it.root }
            .filter { it.packageName == KIDS_PACKAGE }
            .firstNotNullOfOrNull { it.findAccessibilityNodeInfosByViewId(ID_PREFIX + id).firstOrNull() }

    private fun AccessibilityNodeInfo.screenBounds() = Rect().also { getBoundsInScreen(it) }

    private fun startLock() {
        lockStartedAt = SystemClock.elapsedRealtime()
        lockUntil = lockStartedAt + lockMinutes * 60_000L
        videoEndsAt = null
        lastBar = null
        handler.removeCallbacks(revealControls)
        handler.postDelayed(revealControls, 2500)
        handler.removeCallbacks(tick)
        tick.run()
    }

    private fun isLocked() = SystemClock.elapsedRealtime() < lockUntil

    // The lock screen and notification shade belong to System UI, so they count as not on top.
    private fun kidsOnTop() =
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            windows.firstOrNull { it.isActive }?.root?.packageName == KIDS_PACKAGE

    private fun refreshOverlay() {
        val locked = isLocked()
        if (!kidsOnTop() || (!locked && !blockSubscribe)) {
            hideOverlay()
            return
        }
        if (!locked) {
            showBlocks(subscribeRects())
            return
        }
        showBlocks(blockedRects())
        if (alwaysShowTimer && badge == null) showBadge()
        updateBadgeText()
    }

    private fun updateBadgeText() {
        val lockEnd = videoEndsAt?.let { minOf(it, lockUntil) } ?: lockUntil
        val secondsLeft = (lockEnd - SystemClock.elapsedRealtime() + 999) / 1000
        badge?.text = "New video in %d:%02d".format(secondsLeft / 60, secondsLeft % 60)
    }

    // Unless the timer is set to always show, the countdown only appears briefly when a blocked area is tapped.
    private fun flashBadge() {
        if (!isLocked()) return // a tap on the subscribe block outside a lock has no countdown to show
        if (badge == null) showBadge()
        updateBadgeText()
        handler.removeCallbacks(hideBadge)
        if (!alwaysShowTimer) handler.postDelayed(hideBadge, 3000)
    }

    // Off the video screen everything is blocked; on it, everything except the player's own controls.
    private fun blockedRects(): List<Rect> {
        val screen = screenRect()
        val player = scanPlayer() ?: return listOf(screen)
        return listOf(
            Rect(screen.left, screen.top, screen.right, player.top),
            Rect(screen.left, player.bottom, screen.right, screen.bottom),
            Rect(screen.left, player.top, player.left, player.bottom),
            Rect(player.right, player.top, screen.right, player.bottom),
        ).filterNot { it.isEmpty } + controlRects.values + subscribeRects()
    }

    private fun subscribeRects(): List<Rect> =
        if (!blockSubscribe) emptyList()
        else windows.mapNotNull { it.root }
            .filter { it.packageName == KIDS_PACKAGE }
            .flatMap { it.findAccessibilityNodeInfosByViewId(ID_PREFIX + "subscribe_button_layout") }
            .filter { it.isVisibleToUser }
            .map { it.screenBounds() }

    // Stops short of the navigation bar (API 30+) so Home and Back stay usable during a lock.
    @Suppress("DEPRECATION")
    private fun screenRect(): Rect {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val max = wm.maximumWindowMetrics
            val nav = max.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
            return Rect(max.bounds).apply { inset(nav.left, nav.top, nav.right, nav.bottom) }
        }
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun showBlocks(rects: List<Rect>) {
        val wm = getSystemService(WindowManager::class.java)
        while (blocks.size > rects.size) wm.removeView(blocks.removeAt(blocks.lastIndex))
        rects.forEachIndexed { i, r ->
            val params = overlayParams(r.width(), r.height()).apply {
                x = r.left
                y = r.top
            }
            if (i < blocks.size) {
                wm.updateViewLayout(blocks[i], params)
            } else {
                val block = View(this).apply {
                    setOnTouchListener { _, e ->
                        if (e.action == MotionEvent.ACTION_DOWN) flashBadge()
                        true
                    }
                }
                wm.addView(block, params)
                blocks += block
            }
        }
    }

    private fun showBadge() {
        val dp = resources.displayMetrics.density
        val pad = (10 * dp).toInt()
        val view = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(pad * 2, pad, pad * 2, pad)
            background = GradientDrawable().apply {
                setColor(0x4D000000.toInt())
                cornerRadius = 24 * dp
            }
        }
        val params = overlayParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (8 * dp).toInt()
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE // keep taps reaching the player underneath
        }
        getSystemService(WindowManager::class.java).addView(view, params)
        badge = view
    }

    private fun hideOverlay() {
        val wm = getSystemService(WindowManager::class.java)
        blocks.forEach(wm::removeView)
        blocks.clear()
        removeBadge()
    }

    private fun removeBadge() {
        handler.removeCallbacks(hideBadge)
        badge?.let(getSystemService(WindowManager::class.java)::removeView)
        badge = null
    }
}
