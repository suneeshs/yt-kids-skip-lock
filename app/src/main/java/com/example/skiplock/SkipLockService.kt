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

// player: only present on the video screen. playerTap: clicked to reveal the controls.
// playPause: stays usable during a lock. progress: only present while the controls show.
private enum class App(
    val pkg: String,
    val idPrefix: String,
    val player: String,
    val playerTap: String,
    val playPause: String,
    val progress: String,
) {
    KIDS("com.google.android.apps.youtube.kids", "com.google.android.apps.youtube.kids:id/",
        "watch_player", "watch_fragment_window", "pause", "time_bar"),
    // Netflix's IDs are Compose test tags without a package.
    NETFLIX("com.netflix.mediaclient", "",
        "playerTappableContainerTestTag", "playerTappableContainerTestTag", "playPauseButtonTestTag",
        "durationTextButtonTestTag"),
}

// Video tiles' descriptions end with the video length, e.g. "... 32 minutes, 47 seconds".
private val VIDEO_TILE = Regex("""\d+ (seconds?|minutes?|hours?)$""")

// YouTube Kids controls that can leave or skip through the video; only play/pause stays usable.
// watch_next_and_control_footer is the suggestion row that slides in under the player with the controls.
private val BLOCKED_IDS = listOf(
    "next", "previous", "title_bar", "back_button_container", "watch_fragment_menu_buttons", "time_bar",
    "watch_next_and_control_footer",
)

// 4 rects around the player, each blocked control, and the subscribe button.
private val POOL_SIZE = 4 + BLOCKED_IDS.size + 1

// e.g. "video player progress bar, 4 minutes 36 seconds of 25 minutes 35 seconds, adjustable" (YouTube Kids)
// or "Time remaining 19 minutes 3 seconds" (Netflix).
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

// Netflix's UI thread is busy for 2-3 s after a seek, so a jump is only seen that late and more ±10 s taps
// get through meanwhile. The buttons are covered instead: the first tap is passed on, a later one locks.
private val SEEK_BUTTONS = listOf("forwardSeekButtonTestTag", "backwardSeekButtonTestTag")

// The seek that a passed-on tap causes shows up as a jump a few seconds later; it isn't a second seek.
private const val PASSED_SEEK_MS = 4_000L

// Leaving Netflix's player this close to (or past) the end counts as having finished the video, so the
// next pick is free: watching to the end isn't skipping, whatever he picks on the end screen.
private const val FINISHED_MARGIN_MS = 60_000L

// video tells videos apart, so a reading from the next video isn't taken for a scrub.
private class BarReading(val video: String, val remaining: Long, val playing: Boolean, val at: Long)

class SkipLockService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lockUntil = 0L
    private var lockStartedAt = 0L
    private var lockApp = App.KIDS
    private var videoEndsAt: Long? = null
    private var browsing: App? = null
    private var scannedApp: App? = null
    private var controlsScreen: Rect? = null
    private var lastBar: BarReading? = null
    private var freeSeekVideo: String? = null
    private var netflixPickUsed = false
    private var finishedVideo = false
    private var seekButtons = mapOf<String, Rect>()
    private var seekBar: Rect? = null
    private var passedSeekAt = 0L
    private var revealApp = App.KIDS
    private val controlRects = mutableMapOf<String, Rect>()
    private var pauseRect: Rect? = null
    private val blocks = mutableListOf<View>()
    private val blockRects = mutableListOf<Rect?>() // null while parked
    private var badge: TextView? = null

    private val refresh = Runnable { refreshOverlay() }
    private val scanNetflix = Runnable {
        if (topApp() != App.NETFLIX) return@Runnable
        scanPlayer(App.NETFLIX)
        refreshOverlay() // the seek button blocks follow the controls showing and hiding
    }
    private val hideBadge = Runnable { removeBadge() }

    // Videos opened from a browse screen start with controls hidden, so show them once to read the length.
    private val revealControls = Runnable {
        val app = revealApp
        if (lastBar != null || findNode(app, app.progress) != null) return@Runnable
        val clicked = findNode(app, app.playerTap)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
            val app = topApp()
            if (app != null) {
                scanPlayer(app)
                if (!isLocked()) refreshOverlay() // keeps the subscribe block in step outside a lock
            } else {
                browsing = null
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() = poll.run()

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        val app = enabledApp(pkg) ?: run {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) refreshOverlay()
            return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> refreshOverlay()
            // Controls showing/hiding or going full screen moves what needs blocking in YouTube Kids.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (app == App.KIDS && (isLocked() || blockSubscribe)) {
                handler.removeCallbacks(refresh)
                handler.postDelayed(refresh, 50)
            // Catch the controls showing, or a seek bar jump, right away rather than at the next poll.
            } else if (app == App.NETFLIX && !isLocked() && browsing != app) {
                handler.removeCallbacks(scanNetflix)
                handler.postDelayed(scanNetflix, 100)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onClick(app, event)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        hideOverlay()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // Every picked video must play 3 minutes (or to its end) before the next pick; autoplay involves no tap.
    private fun onClick(app: App, event: AccessibilityEvent) {
        val node = event.source
        val id = node?.viewIdResourceName?.removePrefix(app.idPrefix)
        val desc = (node?.contentDescription ?: event.contentDescription)?.toString().orEmpty()
        // Netflix's player doesn't report taps, so its picks are caught by the player reappearing.
        val picksVideo = app == App.KIDS && (id == "next" || VIDEO_TILE.containsMatchIn(desc))
        Log.i(TAG, "Click in $app id=$id desc='$desc' (picksVideo=$picksVideo)")
        if (picksVideo) onPick(app)
    }

    // On Netflix one pick between locks is free, so one episode he doesn't like can be skipped.
    private fun onPick(app: App) {
        if (app == App.NETFLIX && !isLocked() && !netflixPickUsed) {
            netflixPickUsed = true
            Log.i(TAG, "Free pick on Netflix")
            scheduleReveal(app)
            return
        }
        startLock(app)
    }

    // YouTube Kids controls hide after a few seconds, so remember where they last were.
    // The player shrinks whenever the controls show, so only a rotation invalidates the remembered rects.
    private fun scanPlayer(app: App): Rect? {
        val screen = screenRect()
        if (screen != controlsScreen) controlRects.clear()
        if (screen != controlsScreen || app != scannedApp) {
            pauseRect = null
            lastBar = null
        }
        controlsScreen = screen
        scannedApp = app
        val player = findNode(app, app.player)?.screenBounds()
        if (app == App.NETFLIX) {
            seekButtons = if (player == null) emptyMap()
            else SEEK_BUTTONS.mapNotNull { id -> findNode(app, id)?.screenBounds()?.let { id to it } }.toMap()
            seekBar = player?.let { findNode(app, "playerSeekbar")?.screenBounds() }
        }
        if (player == null) {
            if (browsing != app) finishedVideo = app == App.NETFLIX && nearEnd()
            browsing = app
            lastBar = null
            return null
        }
        // Home-screen tiles don't report a usable label, so going from browsing to the player is the pick.
        // Netflix's episode list also replaces the player, so picking an episode counts the same way.
        if (browsing == app) {
            browsing = null
            if (finishedVideo) {
                Log.i(TAG, "Picked after finishing a video in $app, not counted")
                finishedVideo = false
                scheduleReveal(app)
            // During a lock from the other app this one is fully covered, so it can't be a pick.
            } else if (!isLocked() || app == lockApp) {
                Log.i(TAG, "Video opened from a browse screen in $app")
                onPick(app)
            }
        }
        // Union, because a reading taken while the suggestion row slides in or out covers only part of it.
        if (app == App.KIDS) for (id in BLOCKED_IDS) findNode(app, id)?.screenBounds()?.let { r ->
            controlRects.getOrPut(id) { Rect() }.union(r)
        }
        findNode(app, app.playPause)?.screenBounds()?.let { pauseRect = it }
        if (!isLocked()) watchScrub(app) else if (app == lockApp) readProgress(app)
        return player
    }

    private fun nearEnd(): Boolean {
        val bar = lastBar ?: return false
        val left = bar.remaining - if (bar.playing) SystemClock.elapsedRealtime() - bar.at else 0
        return left < FINISHED_MARGIN_MS
    }

    // The progress bar only exists while controls show, so project the end time from the last reading.
    private fun readProgress(app: App) {
        val now = SystemClock.elapsedRealtime()
        if (now - lockStartedAt < 2000) return // the bar may still show the previous video
        val bar = readBar(app) ?: return
        lastBar = bar
        if (videoEndsAt == null && bar.playing) Log.i(TAG, "Video progress read: ${bar.remaining}ms left")
        videoEndsAt = if (bar.playing) now + bar.remaining else null
    }

    // Skipping around is as good as picking another video, so it starts a lock.
    private fun watchScrub(app: App) {
        val bar = readBar(app) ?: return
        val prev = lastBar
        lastBar = bar
        if (prev == null || prev.video != bar.video) return // first reading, or a new video
        val expected = prev.remaining - if (prev.playing) bar.at - prev.at else 0
        if (abs(bar.remaining - expected) <= SCRUB_MS) return
        Log.i(TAG, "Scrubbed in $app: expected ${expected}ms left, now ${bar.remaining}ms")
        if (bar.at - passedSeekAt < PASSED_SEEK_MS) return
        // On Netflix the first seek in each video is free, e.g. to skip an intro.
        if (app == App.NETFLIX) {
            if (freeSeekVideo != bar.video) {
                Log.i(TAG, "Free seek used for '${bar.video}'")
                freeSeekVideo = bar.video
                return
            }
        }
        startLock(app)
    }

    private fun readBar(app: App): BarReading? {
        val now = SystemClock.elapsedRealtime()
        return when (app) {
            App.KIDS -> {
                val parts = findNode(app, "time_bar")?.contentDescription?.toString()?.split(" of ") ?: return null
                if (parts.size != 2) return null
                val duration = parseMs(parts[1])
                if (duration <= 0) return null
                val playing = findNode(app, "pause")?.contentDescription?.startsWith("Pause") ?: return null
                BarReading(duration.toString(), duration - parseMs(parts[0]), playing, now)
            }
            App.NETFLIX -> {
                val remaining = findNode(app, "durationTextButtonTestTag")?.contentDescription ?: return null
                val title = findNode(app, "playerTopBarTitleTestTag")?.contentDescription?.toString().orEmpty()
                val playing = findNode(app, "pauseButtonTestTag") != null // "playButtonTestTag" while paused
                BarReading(title, parseMs(remaining.toString()), playing, now)
            }
        }
    }

    // Searches every window: while the lock overlay is up, the active window may not be the app.
    private fun findNode(app: App, id: String): AccessibilityNodeInfo? {
        val roots = windows.mapNotNull { it.root }.filter { it.packageName == app.pkg }
        // findAccessibilityNodeInfosByViewId doesn't find Compose test tags on Android 12, so walk the tree.
        if (app == App.NETFLIX) return roots.firstNotNullOfOrNull { it.findById(id) }
        return roots.firstNotNullOfOrNull { it.findAccessibilityNodeInfosByViewId(app.idPrefix + id).firstOrNull() }
    }

    private fun AccessibilityNodeInfo.findById(id: String): AccessibilityNodeInfo? {
        if (viewIdResourceName == id) return this
        for (i in 0 until childCount) getChild(i)?.findById(id)?.let { return it }
        return null
    }

    private fun AccessibilityNodeInfo.screenBounds() = Rect().also { getBoundsInScreen(it) }

    private fun startLock(app: App) {
        Log.i(TAG, "Lock started in $app")
        lockApp = app
        netflixPickUsed = false // picks are blocked during the lock, so this frees the first one after it
        lockStartedAt = SystemClock.elapsedRealtime()
        lockUntil = lockStartedAt + lockMinutes * 60_000L
        videoEndsAt = null
        lastBar = null
        scheduleReveal(app)
        handler.removeCallbacks(tick)
        // Cover what's known first and let that layout happen before the full refresh, which looks up nodes:
        // ~0.2 s in YouTube Kids, and ~2 s after a Netflix seek, whose UI thread is busy meanwhile.
        // Netflix is known to be on top here, and checking would itself wait on it.
        when (app) {
            App.KIDS -> if (topApp() == app) showBlocks(controlRects.values.toList())
            App.NETFLIX -> showBlocks(minusPause(listOf(screenRect())))
        }
        handler.post(tick)
    }

    // A seek control tapped outside a lock: the first seek in a video goes through, any later one locks.
    private fun onSeekTap(x: Int, y: Int): Boolean {
        val onButton = seekButtons.entries.firstOrNull { it.value.contains(x, y) }?.key
        if (onButton == null && seekBar?.contains(x, y) != true) return false
        val video = findNode(App.NETFLIX, "playerTopBarTitleTestTag")?.contentDescription?.toString().orEmpty()
        if (onButton != null && freeSeekVideo != video) {
            Log.i(TAG, "Free seek used for '$video'")
            freeSeekVideo = video
            passedSeekAt = SystemClock.elapsedRealtime()
            findNode(App.NETFLIX, onButton)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            startLock(App.NETFLIX)
        }
        return true
    }

    private fun scheduleReveal(app: App) {
        revealApp = app
        handler.removeCallbacks(revealControls)
        handler.postDelayed(revealControls, 2500)
    }

    private fun isLocked() = SystemClock.elapsedRealtime() < lockUntil

    // The lock screen and notification shade belong to System UI, so they count as not on top.
    private fun topApp(): App? {
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return null
        val pkg = windows.firstOrNull { it.isActive }?.root?.packageName ?: return null
        return enabledApp(pkg)
    }

    // An app switched off in settings is treated like any other app: nothing in it is blocked or watched.
    private fun enabledApp(pkg: CharSequence?): App? = when (pkg) {
        App.KIDS.pkg -> App.KIDS.takeIf { blockKids }
        App.NETFLIX.pkg -> App.NETFLIX.takeIf { blockNetflix }
        else -> null
    }

    private fun refreshOverlay() {
        val app = topApp()
        if (app == null) {
            hideOverlay()
            return
        }
        if (!isLocked()) {
            if (!blockSubscribe) removeBadge()
            showBlocks(if (app == App.KIDS) subscribeRects() else netflixSeekBlocks())
            return
        }
        // A lock started in one app fully covers the other until it ends.
        showBlocks(if (app == lockApp) blockedRects(app) else listOf(screenRect()))
        if (alwaysShowTimer && badge == null) showBadge()
        updateBadgeText()
    }

    // The seek bar can't be passed on like a button tap, so it's only covered once the free seek is used.
    private fun netflixSeekBlocks(): List<Rect> {
        val freeSeekUsed = freeSeekVideo != null && freeSeekVideo == lastBar?.video
        return seekButtons.values + listOfNotNull(seekBar.takeIf { freeSeekUsed })
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
    // Remembered rects can come from another layout with the same screen size (e.g. full screen) and land on
    // the pause button, so it is always cut out.
    private fun blockedRects(app: App): List<Rect> {
        val screen = screenRect()
        val player = scanPlayer(app) ?: return listOf(screen)
        val rects = when (app) {
            // Remembered rects first: their order never changes, so the rects around the player coming and
            // going with the controls doesn't reassign the window over the suggestion row.
            App.KIDS -> controlRects.values + listOf(
                Rect(screen.left, screen.top, screen.right, player.top),
                Rect(screen.left, player.bottom, screen.right, screen.bottom),
                Rect(screen.left, player.top, player.left, player.bottom),
                Rect(player.right, player.top, screen.right, player.bottom),
            ).filterNot { it.isEmpty } + subscribeRects()
            // The controls float over a full-screen video, so everything but play/pause is covered.
            // A tap on play/pause while the controls are hidden reveals them.
            App.NETFLIX -> listOf(screen)
        }
        return minusPause(rects)
    }

    private fun minusPause(rects: List<Rect>): List<Rect> {
        val pause = pauseRect ?: return rects
        return rects.flatMap { it.minus(pause) }
    }

    private fun Rect.minus(hole: Rect): List<Rect> {
        if (!Rect.intersects(this, hole)) return listOf(this)
        val bandTop = maxOf(top, hole.top)
        val bandBottom = minOf(bottom, hole.bottom)
        return listOf(
            Rect(left, top, right, hole.top),
            Rect(left, hole.bottom, right, bottom),
            Rect(left, bandTop, hole.left, bandBottom),
            Rect(hole.right, bandTop, right, bandBottom),
        ).filterNot { it.isEmpty }
    }

    private fun subscribeRects(): List<Rect> =
        if (!blockSubscribe) emptyList()
        else windows.mapNotNull { it.root }
            .filter { it.packageName == App.KIDS.pkg }
            .flatMap { it.findAccessibilityNodeInfosByViewId(App.KIDS.idPrefix + "subscribe_button_layout") }
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

    // Nodes can report bounds behind the navigation bar, so clip to keep it usable.
    private fun showBlocks(unclipped: List<Rect>) {
        val screen = screenRect()
        val rects = unclipped.mapNotNull { r -> Rect(r).takeIf { it.intersect(screen) } }
        val wm = getSystemService(WindowManager::class.java)
        // A new or 0x0 window takes up to a second to get a real size, and taps get through until then,
        // so windows stay attached at 1x1 while a supported app is on top.
        while (blocks.size < maxOf(rects.size, POOL_SIZE)) {
            val block = View(this).apply {
                setOnTouchListener { _, e ->
                    if (e.action == MotionEvent.ACTION_DOWN && (isLocked() || !onSeekTap(e.rawX.toInt(), e.rawY.toInt()))) {
                        flashBadge()
                    }
                    true
                }
            }
            wm.addView(block, parkedParams())
            blocks += block
            blockRects += null
        }
        // Each update makes the window manager lay the window out again, which takes seconds over Netflix.
        blocks.forEachIndexed { i, block ->
            val r = rects.getOrNull(i)
            if (r == blockRects[i]) return@forEachIndexed
            blockRects[i] = r
            val params = if (r == null) parkedParams() else overlayParams(r.width(), r.height()).apply {
                x = r.left
                y = r.top
            }
            wm.updateViewLayout(block, params)
        }
    }

    private fun parkedParams() = overlayParams(1, 1).apply {
        flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
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
        blockRects.clear()
        removeBadge()
    }

    private fun removeBadge() {
        handler.removeCallbacks(hideBadge)
        badge?.let(getSystemService(WindowManager::class.java)::removeView)
        badge = null
    }
}
