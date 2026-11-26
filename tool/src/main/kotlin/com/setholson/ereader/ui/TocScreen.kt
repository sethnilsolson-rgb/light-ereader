package com.setholson.ereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

class TocScreen(
    sealedActivity: SealedLightActivity,
    private val bookTitle: String,
    private val chapterTitles: List<String>,
    private val currentChapterIndex: Int,
) : SimpleLightScreen<Int>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .swipeToBack { goBack() },
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    BatteryTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                        ),
                        center = LightTopBarCenter.Text("Contents"),
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )

                    LightText(
                        text = bookTitle,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(
                            start = 1.5f.gridUnitsAsDp(),
                            end = 1.5f.gridUnitsAsDp(),
                            bottom = 8.dp,
                        ),
                    )

                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 1.5f.gridUnitsAsDp()),
                    ) {
                        Spacer(modifier = Modifier.height(6.dp))

                        chapterTitles.forEachIndexed { index, title ->
                            val isCurrent = index == currentChapterIndex
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = 1.5f.gridUnitsAsDp(), top = 9.dp, bottom = 9.dp)
                                    .lightClickable {
                                        goBack(index)
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LightText(
                                    text = if (isCurrent) "• $title" else title,
                                    variant = if (isCurrent) LightTextVariant.Subheading else LightTextVariant.Paragraph,
                                    lighten = !isCurrent,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}
