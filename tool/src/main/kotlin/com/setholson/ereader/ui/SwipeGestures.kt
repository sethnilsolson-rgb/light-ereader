package com.setholson.ereader.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

fun Modifier.swipeToBack(onBack: () -> Unit): Modifier = composed {
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    this.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { dragAccumulator = 0f },
            onDragEnd = {
                if (dragAccumulator > 70f) {
                    onBack()
                }
                dragAccumulator = 0f
            },
            onDragCancel = { dragAccumulator = 0f },
            onHorizontalDrag = { _, dragAmount ->
                dragAccumulator += dragAmount
            },
        )
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
