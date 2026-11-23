package com.aybrkaknc.songshare.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val HALF_RATIO = 0.5f
private const val FULL_RATIO = 0.95f
private const val FLING_PX = 800f
private const val SNAP_TOLERANCE_DP = 1f
private const val OPEN_MS = 300
private const val SETTLE_MS = 200

// Height of the shared bottom panel in dp. Drags, flings and animations all write heightDp, so there is one source of truth.
// In auto mode the layout decides the height (the search page wraps its content) and heightDp only mirrors it.
class PanelSheetState(private val scope: CoroutineScope, val screenHeightDp: Float) {
    val halfHeight = screenHeightDp * HALF_RATIO
    val fullHeight = screenHeightDp * FULL_RATIO

    var heightDp by mutableFloatStateOf(0f)
        private set

    // True while the layout sets the height; any drag or fit hands control back to heightDp.
    var auto by mutableStateOf(false)
        private set

    private var job: Job? = null

    // Only the search page can be dragged by hand; the other pages keep the size they were given.
    var dragEnabled by mutableStateOf(false)

    // Only the search results may pass a leftover list drag to the panel; settings and log scroll on their own.
    var listHandoff = true

    // Leaving settings returns the panel to its half-open height.
    // Settings hold the panel at full height; nothing else may resize it until the user leaves them.
    private var locked = false

    fun openSettings() {
        locked = true
        animateTo(fullHeight, OPEN_MS)
    }

    // Leaving settings lifts the lock; the caller then sets the height for the page it returns to.
    fun unlock() {
        locked = false
    }

    // For pages with a fixed size: the panel goes to the given height, at most full.
    fun fitTo(heightDp: Float) {
        if (!locked) animateTo(minOf(heightDp, fullHeight), OPEN_MS)
    }

    // The last height the layout produced in auto mode; a fixed page that hands over to auto eases down to it.
    private var lastAutoDp: Float? = null

    // The search page lets its content decide the height. Coming from a taller fixed page, the panel first eases down
    // to the height the start page had last time, then hands control to the layout, so nothing jumps.
    fun useAuto() {
        if (locked) return
        job?.cancel()
        val target = lastAutoDp
        if (target == null || heightDp <= target) {
            auto = true
            return
        }
        auto = false
        job = scope.launch {
            animate(heightDp, target, animationSpec = tween(OPEN_MS)) { v, _ -> heightDp = v }
            auto = true
        }
    }

    // Auto mode reports the height the layout produced, so a later drag or fit starts from it.
    fun mirror(actualDp: Float) {
        if (auto) {
            heightDp = actualDp
            lastAutoDp = actualDp
        }
    }

    // A drag takes over from any running animation or from auto mode.
    fun dragBy(deltaDp: Float) {
        job?.cancel()
        auto = false
        heightDp = (heightDp - deltaDp).coerceIn(0f, fullHeight)
    }

    // A quick flick moves one stop in its direction, a slow release snaps to the nearest one.
    // Reaching the closed stop calls onClose, so the caller can show a message before closing.
    fun settle(velocityPx: Float, onClose: () -> Unit) {
        val stops = listOf(0f, halfHeight, fullHeight)
        val chosen = when {
            velocityPx < -FLING_PX -> stops.firstOrNull { it > heightDp } ?: fullHeight
            velocityPx > FLING_PX -> stops.lastOrNull { it < heightDp } ?: 0f
            else -> stops.minBy { abs(it - heightDp) }
        }
        if (chosen == 0f) onClose() else animateTo(chosen, SETTLE_MS)
    }

    fun close(onClosed: () -> Unit) {
        job?.cancel()
        auto = false
        job = scope.launch {
            animate(heightDp, 0f, animationSpec = tween(SETTLE_MS)) { value, _ -> heightDp = value }
            onClosed()
        }
    }

    private fun animateTo(value: Float, durationMs: Int) {
        job?.cancel()
        auto = false
        job = scope.launch {
            animate(heightDp, value, animationSpec = tween(durationMs)) { v, _ -> heightDp = v }
        }
    }
}
