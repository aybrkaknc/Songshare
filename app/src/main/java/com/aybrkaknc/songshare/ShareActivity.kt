package com.aybrkaknc.songshare

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.aybrkaknc.songshare.ui.PanelSheetState

// Opened from the share menu: a bottom panel over the app the link came from. Dragging it up fills the screen.
class ShareActivity : PanelActivity() {
    // Closing the panel only hides it: the activity stays in memory so reopening resumes the same state.
    private val sheetOpen = mutableStateOf(true)

    override fun onNewIntent(intent: Intent) {
        sheetOpen.value = true
        super.onNewIntent(intent)
    }

    @Composable
    override fun Root() {
        val open by sheetOpen
        if (open) PanelSheet()
    }

    // Puts the app behind the current one; the work and the activity stay alive.
    private fun hidePanel() {
        if (!sheetOpen.value) return
        sheetOpen.value = false
        moveTaskToBack(true)
    }

    // Drawn by hand instead of ModalBottomSheet so that the handle and the list can move it with the finger.
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PanelSheet() {
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current.density
        val screenHeight = LocalConfiguration.current.screenHeightDp.toFloat()
        val sheet = remember { PanelSheetState(scope, screenHeight) }
        // One place decides the panel height. The search page wraps its content (auto); every other page has a fixed height.
        // New search results hand control back to auto, even after the user dragged the panel.
        val step by Session.step.collectAsState()
        val searchKey = (step as? Step.Search)?.let { listOf(it.loading, it.candidates, it.error) }
        val fixed = heightFor(step)
        LaunchedEffect(searchKey, fixed) {
            if (fixed == null) sheet.useAuto() else sheet.fitTo(fixed)
        }

        // Closing from the handle, the scrim, Back or a flick: the work keeps running and shows in the notification.
        val dismiss = {
            if (Session.active.value != null) Toast.makeText(this, R.string.moved_to_notification, Toast.LENGTH_SHORT).show()
            sheet.close(::hidePanel)
        }

        val tracker = remember { VelocityTracker() }

        // The list hands over leftover drags once it cannot scroll further, so the panel moves on the same gesture.
        var panelDragged by remember { mutableStateOf(false) }
        val listDrag = remember {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (!sheet.listHandoff || !sheet.dragEnabled || source != NestedScrollSource.UserInput || available.y == 0f) {
                        return Offset.Zero
                    }
                    panelDragged = true
                    sheet.dragBy(available.y / density)
                    return Offset(0f, available.y)
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (!sheet.listHandoff || !panelDragged) return Velocity.Zero
                    panelDragged = false
                    sheet.settle(available.y, dismiss)
                    return available
                }
            }
        }

        // Back on the share page returns to the trim page with the edits kept; anywhere else it closes the panel.
        BackHandler { if (step is Step.Share) Session.backToTrim() else dismiss() }

        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = SCRIM_ALPHA * (sheet.heightDp / sheet.halfHeight).coerceIn(0f, 1f)))
                    .pointerInput(Unit) { detectTapGestures { dismiss() } },
            )
            // The keyboard lifts the whole panel, so its bottom edge stays above the keyboard.
            Box(Modifier.fillMaxSize().imePadding()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    // Auto: the content decides, up to a share of the screen, and size changes ease in.
                    .then(
                        if (sheet.auto) Modifier.heightIn(max = (screenHeight * RESULTS_RATIO).dp).animateContentSize()
                        else Modifier.height(sheet.heightDp.dp),
                    )
                    .onSizeChanged { sheet.mirror(it.height / density) },
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            ) {
                Column(
                    (if (sheet.auto) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                        .navigationBarsPadding()
                        .nestedScroll(listDrag),
                ) {
                    Box(
                        Modifier.fillMaxWidth().pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { tracker.resetTracking() },
                                onVerticalDrag = { change, amount ->
                                    if (sheet.dragEnabled) {
                                        tracker.addPosition(change.uptimeMillis, change.position)
                                        sheet.dragBy(amount / density)
                                    }
                                },
                                onDragEnd = {
                                    if (sheet.dragEnabled) sheet.settle(tracker.calculateVelocity().y, dismiss)
                                },
                            )
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        // Hidden but kept, so the header stays where it is on the other pages.
                        BottomSheetDefaults.DragHandle(modifier = Modifier.alpha(if (sheet.dragEnabled) 1f else 0f))
                    }
                    PanelContent(close = { sheet.close(::hidePanel) }, sheet = sheet)
                }
            }
            }
        }
    }
}

private const val SCRIM_ALPHA = 0.32f
// The search page grows with its content up to this share of the screen height.
private const val RESULTS_RATIO = 0.75f
// Fixed panel height in dp per page; null means the page wraps its content (start, search and download pages).
internal fun heightFor(step: Step): Float? = when (step) {
    Step.Home -> null
    is Step.Search -> null
    is Step.Downloading -> null
    // The trim page holds the waveform and the lyrics, so it takes the full height.
    is Step.Trim -> Float.MAX_VALUE
    is Step.Share -> Float.MAX_VALUE
    is Step.Failed -> Float.MAX_VALUE
}
