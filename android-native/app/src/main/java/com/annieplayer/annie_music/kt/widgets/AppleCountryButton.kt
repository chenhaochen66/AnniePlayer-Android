package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.annieplayer.annie_music.kt.data.sources.apple.AppleStorefront
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius

/**
 * Apple Music 国家/地区选择：模式按钮同一行最右侧的胶囊。
 *
 * 弹出面板顶部是**固定搜索框**（不随列表滚动），下面是可滚动地区列表。
 */
@Composable
fun AppleCountryButton(
    code: String,
    storefronts: List<AppleStorefront>,
    loading: Boolean,
    /** 非空 = 上次加载失败（面板里给「重试」，而不是假装「没有匹配的地区」） */
    error: String?,
    onSelect: (String) -> Unit,
    /** 面板打开时回调：宿主据此补拉（冷启动时没人触发过加载） */
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val filtered = remember(storefronts, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            storefronts
        } else {
            storefronts.filter {
                it.name.contains(q, ignoreCase = true) || it.code.contains(q, ignoreCase = true)
            }
        }
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .height(36.dp)
                .background(AppColors.whiteA05, RoundedCornerShape(AppRadius.r10))
                .clickable {
                    expanded = true
                    onOpen()
                }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = code.uppercase(),
                color = AppColors.white,
                fontSize = AppFontSize.sm,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "▾", color = AppColors.white54, fontSize = AppFontSize.sm)
        }

        if (expanded) {
            val y = with(LocalDensity.current) { 44.dp.roundToPx() }
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, y),
                onDismissRequest = {
                    expanded = false
                    query = ""
                },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    modifier = Modifier
                        .width(264.dp)
                        .heightIn(max = 470.dp)
                        .background(AppColors.surface, RoundedCornerShape(AppRadius.r16))
                        .border(1.dp, AppColors.whiteA06, RoundedCornerShape(AppRadius.r16))
                        .padding(10.dp),
                ) {
                    // 固定搜索框：不放进 LazyColumn，列表滚动时保持不动
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = AppColors.white, fontSize = AppFontSize.base),
                        cursorBrush = SolidColor(AppColors.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .background(AppColors.whiteA05, RoundedCornerShape(AppRadius.r10))
                            .padding(horizontal = 10.dp)
                            .focusRequester(focusRequester),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text(
                                        text = "搜索国家/地区",
                                        color = AppColors.textDisabled,
                                        fontSize = AppFontSize.base,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (loading && storefronts.isEmpty()) {
                        Text(
                            text = "加载地区…",
                            color = AppColors.white54,
                            fontSize = AppFontSize.base,
                            modifier = Modifier.padding(10.dp),
                        )
                    } else if (storefronts.isEmpty() && error != null) {
                        // 失败要说清楚，并且**当场可重试** —— 以前这里吞成空列表，
                        // 用户只看到「没有匹配的地区」，只能靠切换平台碰运气
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "地区表加载失败：$error",
                                color = AppColors.danger,
                                fontSize = AppFontSize.base,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "重试",
                                color = AppColors.primary,
                                fontSize = AppFontSize.base,
                                modifier = Modifier
                                    .clickable { onRetry() }
                                    .padding(vertical = 4.dp),
                            )
                        }
                    } else if (filtered.isEmpty()) {
                        Text(
                            text = "没有匹配的地区",
                            color = AppColors.white54,
                            fontSize = AppFontSize.base,
                            modifier = Modifier.padding(10.dp),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                            items(filtered, key = { it.code }) { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            expanded = false
                                            query = ""
                                            onSelect(item.code)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${item.name} · ${item.code.uppercase()}",
                                        color = AppColors.white,
                                        fontSize = AppFontSize.base,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (item.code == code) {
                                        Text("✓", color = AppColors.primary, fontSize = AppFontSize.base)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            LaunchedEffect(expanded) {
                runCatching { focusRequester.requestFocus() }
            }
        }
    }
}

