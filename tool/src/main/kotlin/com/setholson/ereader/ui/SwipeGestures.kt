package com.setholson.ereader.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Reliable horizontal swipe-to-go-back that works even when starting on clickable rows,
 * scrollable lists, or tap/long-press text widgets (observes [PointerEventPass.Initial] without
 * consuming press/vertical scroll, locking only once a clear horizontal drag exceeds touch slop).
 */
fun Modifier.swipeToBack(
    swipeThreshold: Dp = 42.dp,
    onBack: () -> Unit,
): Modifier = composed {
    val currentOnBack by rememberUpdatedState(onBack)
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { swipeThreshold.toPx() }

    this.pointerInput(swipeThresholdPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var totalDx = 0f
            var totalDy = 0f
            var horizontalLocked = false
            var verticalLocked = false
            val slop = viewConfiguration.touchSlop

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } > 1) {
                    horizontalLocked = false
                    break
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (horizontalLocked) change.consume()
                    break
                }

                val delta = change.position - change.previousPosition
                totalDx += delta.x
                totalDy += delta.y

                if (!horizontalLocked && !verticalLocked) {
                    val absX = abs(totalDx)
                    val absY = abs(totalDy)
                    if (absX > slop && absX > absY * 1.25f) {
                        horizontalLocked = true
                    } else if (absY > slop && absY >= absX) {
                        verticalLocked = true
                    }
                }

                if (horizontalLocked) {
                    change.consume()
                }
            }

            if (horizontalLocked && totalDx >= swipeThresholdPx) {
                currentOnBack()
            }
        }
    }
}

/**
 * Reader-wide horizontal swipe handler that coexists seamlessly with:
 * - child [detectTapGestures] (double-tap Full Screen & press-and-hold dictionary lookup),
 * - vertical [LightScrollView] scrolling, and
 * - far-left-edge swipe-to-go-back vs. swipe-on-words page/chapter turning.
 */
fun Modifier.readerHorizontalSwipes(
    enabled: Boolean = true,
    edgeBackWidth: Dp = 44.dp,
    swipeThreshold: Dp = 42.dp,
    onEdgeSwipeBack: () -> Unit,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
): Modifier = composed {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentEdgeBack by rememberUpdatedState(onEdgeSwipeBack)
    val currentSwipeRight by rememberUpdatedState(onSwipeRight)
    val currentSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val density = LocalDensity.current
    val edgeBackPx = with(density) { edgeBackWidth.toPx() }
    val swipeThresholdPx = with(density) { swipeThreshold.toPx() }

    this.pointerInput(edgeBackPx, swipeThresholdPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!currentEnabled) return@awaitEachGesture

            val startX = down.position.x
            var totalDx = 0f
            var totalDy = 0f
            var horizontalLocked = false
            var verticalLocked = false
            val slop = viewConfiguration.touchSlop

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } > 1) {
                    horizontalLocked = false
                    break
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (horizontalLocked) change.consume()
                    break
                }

                val delta = change.position - change.previousPosition
                totalDx += delta.x
                totalDy += delta.y

                if (!horizontalLocked && !verticalLocked) {
                    val absX = abs(totalDx)
                    val absY = abs(totalDy)
                    if (absX > slop && absX > absY * 1.25f) {
                        horizontalLocked = true
                    } else if (absY > slop && absY >= absX) {
                        verticalLocked = true
                    }
                }

                if (horizontalLocked) {
                    change.consume()
                }
            }

            if (horizontalLocked && abs(totalDx) >= swipeThresholdPx) {
                if (totalDx > 0f) {
                    if (startX <= edgeBackPx) {
                        currentEdgeBack()
                    } else {
                        currentSwipeRight()
                    }
                } else {
                    currentSwipeLeft()
                }
            }
        }
    }
}

fun createBookmarkFilledVector(color: Color): ImageVector =
    ImageVector.Builder(
        name = "BookmarkFilled",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = listOf(
            PathNode.MoveTo(17f, 3f),
            PathNode.LineTo(7f, 3f),
            PathNode.CurveTo(5.9f, 3f, 5f, 3.9f, 5f, 5f),
            PathNode.VerticalTo(21f),
            PathNode.LineTo(12f, 18f),
            PathNode.LineTo(19f, 21f),
            PathNode.VerticalTo(5f),
            PathNode.CurveTo(19f, 3.9f, 18.1f, 3f, 17f, 3f),
            PathNode.Close,
        ),
        fill = SolidColor(color),
    ).build()

fun createBookmarkOutlineVector(color: Color): ImageVector =
    ImageVector.Builder(
        name = "BookmarkOutline",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = listOf(
            PathNode.MoveTo(17f, 3f),
            PathNode.LineTo(7f, 3f),
            PathNode.CurveTo(5.9f, 3f, 5f, 3.9f, 5f, 5f),
            PathNode.VerticalTo(21f),
            PathNode.LineTo(12f, 18f),
            PathNode.LineTo(19f, 21f),
            PathNode.VerticalTo(5f),
            PathNode.CurveTo(19f, 3.9f, 18.1f, 3f, 17f, 3f),
            PathNode.Close,
            PathNode.MoveTo(17f, 18f),
            PathNode.LineTo(12f, 15.82f),
            PathNode.LineTo(7f, 18f),
            PathNode.VerticalTo(5f),
            PathNode.HorizontalTo(17f),
            PathNode.VerticalTo(18f),
            PathNode.Close,
        ),
        fill = SolidColor(color),
    ).build()

fun formatFileSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024L -> "${bytes} B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
