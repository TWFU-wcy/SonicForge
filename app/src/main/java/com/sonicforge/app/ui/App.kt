package com.sonicforge.app.ui

import android.content.res.Configuration
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import com.sonicforge.app.root.RootAudioManager
import com.sonicforge.app.root.RootState
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.sonicforge.app.audio.AudioState
import com.sonicforge.app.audio.EngineMode
import com.sonicforge.app.audio.Preset
import com.sonicforge.app.ui.theme.SonicForgeTheme
import kotlin.math.cos
import kotlin.math.sin

/** 底部 Dock 的三个页面 */
private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("主页", Icons.Filled.Home),
    EQ("调节", Icons.Filled.Tune),
    SETTINGS("设置", Icons.Filled.Settings),
}

/** 与 AudioEngine.CustomPresetId 保持一致：用户手动改过任一参数后预设就变成「自定义」 */
private const val CustomPresetId = "custom"

// ─────────────────────────────────────────────────────────────────────────────
// 玻璃拟态基础件
// ─────────────────────────────────────────────────────────────────────────────

// Dock 采用高不透明度磨砂质感：背景内容不会透出来干扰图标识别
/**
 * 毛玻璃质感：半透明渐变填充 + 1dp 高光描边 + 柔和投影。
 * 依赖页面底部的彩色光斑背景，才能看出「透过玻璃」的层次。
 */
@Composable
private fun Modifier.glass(
    shape: Shape = RoundedCornerShape(24.dp),
    fillTop: Float = 0.60f,
    fillBottom: Float = 0.34f,
    strokeTop: Float = 0.90f,
    strokeBottom: Float = 0.40f,
    elevation: Int = 10,
    darkScale: Float = 0.16f,
    darkTint: Color = Color.White,
): Modifier {
    // 跟随实际生效的主题（用户可在设置里强制浅色/深色），不能用 isSystemInDarkTheme
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val top = if (dark) darkTint.copy(alpha = fillTop * darkScale) else Color.White.copy(alpha = fillTop)
    val bottom = if (dark) darkTint.copy(alpha = fillBottom * darkScale) else Color.White.copy(alpha = fillBottom)
    val sTop = if (dark) Color.White.copy(alpha = strokeTop * 0.22f) else Color.White.copy(alpha = strokeTop)
    val sBottom = if (dark) Color.White.copy(alpha = strokeBottom * 0.18f) else Color.White.copy(alpha = strokeBottom)
    return this
        .shadow(
            elevation = elevation.dp,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = if (dark) 0.30f else 0.10f),
            spotColor = Color.Black.copy(alpha = if (dark) 0.30f else 0.10f),
        )
        .clip(shape)
        .background(Brush.verticalGradient(listOf(top, bottom)))
        .border(1.dp, Brush.verticalGradient(listOf(sTop, sBottom)), shape)
}

@Composable
private fun PaddedGlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    horizontal: Int = 20,
    vertical: Int = 20,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier.glass(shape)
    val interactive = if (onClick != null) base.clickable { onClick() } else base
    Column(
        modifier = interactive.padding(horizontal = horizontal.dp, vertical = vertical.dp),
        content = content,
    )
}

/** 背景：底色 + 三团柔光色斑，为玻璃卡片提供可透出的颜色 */
@Composable
private fun AppBackground() {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val base = if (dark) Color(0xFF080A10) else Color(0xFFF2F4FB)
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary
    val blobAlpha = if (dark) 0.42f else 0.30f

    Box(Modifier.fillMaxSize().background(base)) {
        Box(
            Modifier
                .size(460.dp)
                .offset(x = (-140).dp, y = (-180).dp)
                .background(
                    Brush.radialGradient(
                        listOf(primary.copy(alpha = blobAlpha), Color.Transparent)
                    )
                )
        )
        Box(
            Modifier
                .size(420.dp)
                .offset(x = 200.dp, y = 60.dp)
                .background(
                    Brush.radialGradient(
                        listOf(tertiary.copy(alpha = blobAlpha * 0.85f), Color.Transparent)
                    )
                )
        )
        Box(
            Modifier
                .size(520.dp)
                .offset(x = (-60).dp, y = 480.dp)
                .background(
                    Brush.radialGradient(
                        listOf(secondary.copy(alpha = blobAlpha * 0.7f), Color.Transparent)
                    )
                )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 根界面
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SonicForgeApp(
    state: AudioState,
    initialPresetId: String?,
    onToggle: (Boolean, String) -> Unit,
    onPresetSelected: (Preset) -> Unit,
    onBoostChange: (Int) -> Unit,
    onEqBandChange: (Int, Int) -> Unit,
    onBassChange: (Int) -> Unit,
    onLevelerChange: (Int) -> Unit,
    onMonoChange: (Boolean) -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    onDiagnose: () -> String,
    root: RootState = RootState(checking = false),
    onRootRefresh: () -> Unit = {},
    onRootGainChange: (Int) -> Unit = {},
    onRootRestore: () -> Unit = {},
    onRootVendorChange: (Boolean) -> Unit = {},
) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    var preset by remember {
        mutableStateOf(Preset.All.firstOrNull { it.id == initialPresetId } ?: Preset.Default)
    }

    Box(Modifier.fillMaxSize()) {
        AppBackground()
        // 修复深色模式下无显式 color 的 Text 变成纯黑（透明容器导致 contentColor 丢失）
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = tab,
                    // 左右平移：新页从侧边滑入，旧页向反方向退出一小段（视差），
                    // 同时叠一点淡入淡出，避免两页半透明玻璃卡片重叠时文字发虚。
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val dir = if (forward) 1 else -1
                        (
                            slideInHorizontally(
                                animationSpec = tween(
                                    durationMillis = 300,
                                    easing = FastOutSlowInEasing,
                                )
                            ) { width -> width * dir } +
                                fadeIn(animationSpec = tween(durationMillis = 200, delayMillis = 60))
                            ) togetherWith (
                            slideOutHorizontally(
                                animationSpec = tween(
                                    durationMillis = 300,
                                    easing = FastOutSlowInEasing,
                                )
                            ) { width -> -width * dir / 4 } +
                                fadeOut(animationSpec = tween(durationMillis = 160))
                            )
                    },
                    label = "page",
                ) { current ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.statusBars)
                    ) {
                        when (current) {
                            Tab.HOME -> HomePage(
                                state = state,
                                preset = preset,
                                root = root,
                                onPresetChange = { preset = it },
                                onToggle = onToggle,
                                onPresetSelected = onPresetSelected,
                                onOpenCustom = { tab = Tab.EQ },
                            )
                            Tab.EQ -> TunePage(
                                state = state,
                                onBoostChange = onBoostChange,
                                onEqBandChange = onEqBandChange,
                                onBassChange = onBassChange,
                                onLevelerChange = onLevelerChange,
                            )
                            Tab.SETTINGS -> SettingsPage(
                                state = state,
                                onMonoChange = onMonoChange,
                                themeMode = themeMode,
                                onThemeModeChange = onThemeModeChange,
                                onDiagnose = onDiagnose,
                                root = root,
                                onRootRefresh = onRootRefresh,
                                onRootGainChange = onRootGainChange,
                                onRootRestore = onRootRestore,
                                onRootVendorChange = onRootVendorChange,
                            )
                        }
                    }
                }
            }
            // Dock 悬浮在内容之上，内容从它下方穿过，玻璃才真的“透”出来
            // 底部渐变遮罩：让内容在到达 Dock 之前淡出，Dock 上不会有文字残影
            BottomScrim(Modifier.align(Alignment.BottomCenter))
            GlassDock(
                modifier = Modifier.align(Alignment.BottomCenter),
                selected = tab,
                onSelect = { tab = it },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Dock
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BottomScrim(modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxWidth()
            .height(230.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.30f to base.copy(alpha = 0.55f),
                    0.55f to base.copy(alpha = 0.94f),
                    0.78f to base,
                    1f to base,
                )
            )
    )
}

@Composable
private fun GlassDock(
    modifier: Modifier = Modifier,
    selected: Tab,
    onSelect: (Tab) -> Unit,
) {
    // 外层圆角 28dp，内部选中态胶囊用 16dp 圆角 —— 二者同一套圆角语言，不会出现「圆角框里套一个正圆」
    val shape = RoundedCornerShape(28.dp)

    Row(
        modifier
            .padding(horizontal = 26.dp, vertical = 14.dp)
            // 触摸拦截层：正好覆盖整块玻璃（含图标之间的空隙），空白处也吞掉点击，
            // 不会穿透到底下的开关/预设卡片。子项 clickable 先消费事件，父层只兜底。
            .pointerInput(Unit) { detectTapGestures { } }
            .glass(
                shape = shape,
                fillTop = 0.78f,
                fillBottom = 0.66f,
                strokeTop = 0.85f,
                strokeBottom = 0.35f,
                elevation = 12,
                darkScale = 0.90f,
                darkTint = Color(0xFF12151D),
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { item ->
            DockItem(
                tab = item,
                selected = item == selected,
                onClick = { onSelect(item) },
            )
        }
    }
}

/**
 * 纯图标 Dock：尺寸固定，没有文字，就不会被文字撑高、也不会留空。
 * 点击热区（68×52）明显大于视觉胶囊（52×42），不用瞄准也能点中。
 */
@Composable
private fun DockItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val sel by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(220),
        label = "dockSel",
    )
    val tint = lerp(
        MaterialTheme.colorScheme.onSurfaceVariant,
        MaterialTheme.colorScheme.primary,
        sel,
    )

    // 按下反馈全部自己画：图标轻微缩小 + 圆角胶囊泛起一点底色。
    // 关键是把 Material 默认水波纹关掉（indication = null）——水波纹按 68×52 的
    // 矩形节点裁剪，点下去就是一块方形遮罩，和圆角玻璃完全不是一个语言。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (pressed) 90 else 260,
            easing = FastOutSlowInEasing,
        ),
        label = "dockPress",
    )
    val pressScale = 1f - 0.08f * press
    val pill = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f * sel + 0.09f * press)

    Box(
        modifier = Modifier
            .size(width = 68.dp, height = 52.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 42.dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(RoundedCornerShape(18.dp))
                .background(pill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier.size((22f + 2f * sel).dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 主页
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HomePage(
    state: AudioState,
    preset: Preset,
    root: RootState,
    onPresetChange: (Preset) -> Unit,
    onToggle: (Boolean, String) -> Unit,
    onPresetSelected: (Preset) -> Unit,
    onOpenCustom: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        PageHeader(
            title = "SonicForge",
            subtitle = when {
                state.pausedForHeadset -> "已连接${state.routeLabel} · 增强已暂停"
                state.routedToBluetooth -> "已连接蓝牙输出"
                root.available -> "ROOT 已授权 · 硬件增益可用"
                else -> "需要 ROOT 才能开启"
            },
        )
        Spacer(Modifier.height(20.dp))

        // 主控卡片：环形增益 + 开关
        PaddedGlassCard(
            modifier = Modifier.fillMaxWidth(),
            vertical = 24,
            horizontal = 20,
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    GainDial(
                        progress = if (state.active && !state.pausedForHeadset) (state.effectiveBoostMillibel / state.maxBoostMillibel.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f) else 0f,
                        active = state.active && !state.pausedForHeadset,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (state.active && !state.pausedForHeadset) "+${state.gainPercent}" else "0",
                            fontSize = 46.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (state.active && !state.pausedForHeadset) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "% 响度",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.active && !state.pausedForHeadset) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "等效 +%.1f dB".format(state.gainDb),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (state.pausedForHeadset) {
                    StatusPill(active = false, text = "耳机输出 · 已暂停")
                } else if (state.active) {
                    ActiveInfoRow(state = state, preset = preset, hwGainDb = root.appliedDb)
                } else {
                    StatusPill(active = false)
                }

                // 增益模型摘要：最大补偿 + 当前档位实际补偿，都收进主卡片
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            text = "当前最大补偿",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "+%d%%".format(Preset.perceivedPercent(state.boostMillibel)),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            text = "实际补偿",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (state.active && !state.pausedForHeadset)
                                "+${state.gainPercent}% · ${"%.1f".format(state.gainDb)} dB"
                            else "—",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (state.active && !state.pausedForHeadset) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "增强开关",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (state.pausedForHeadset) "耳机输出时不处理，拔出后自动恢复"
                        else if (state.active) "正在实时处理系统音频" else "打开后立即生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.active,
                    onCheckedChange = { enable ->
                        if (enable) {
                            // 用户手动调过参数（自定义）时，打开开关不能把参数重置回某个预设
                            if (state.presetId != CustomPresetId) onPresetSelected(preset)
                            onToggle(true, if (state.presetId == CustomPresetId) CustomPresetId else preset.id)
                        } else {
                            onToggle(false, preset.id)
                        }
                    },
                )
            }
        }

        // 错误提示
        val message = state.error
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            PaddedGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                horizontal = 16,
                vertical = 12,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("预设场景")
        Spacer(Modifier.height(10.dp))
        Preset.All.forEach { item ->
            PresetRow(
                item = item,
                selected = state.presetId != CustomPresetId && item.id == preset.id,
                onClick = {
                    onPresetChange(item)
                    onPresetSelected(item)
                },
            )
        }
        CustomPresetRow(
            selected = state.presetId == CustomPresetId,
            onClick = onOpenCustom,
        )

        Spacer(Modifier.height(6.dp))
        Text(
            text = if (state.active) "切换预设会立即生效" else "当前未开启增强，切换后需打开总开关",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(120.dp))
    }
}

/** 主页里的一张预设卡 */
@Composable
private fun PresetRow(
    item: Preset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    PaddedGlassCard(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        shape = RoundedCornerShape(22.dp),
        horizontal = 18,
        vertical = 14,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when {
                        item.boostMillibel >= 550 -> Icons.Filled.Speaker
                        item.boostMillibel >= 400 -> Icons.Filled.GraphicEq
                        else -> Icons.Filled.Headphones
                    },
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "+%d%% ~ +%d%% · 低音 %d%%".format(
                        Preset.perceivedPercent(item.minBoostMillibel),
                        Preset.perceivedPercent(item.boostMillibel),
                        item.bassBoostStrength / 10,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/**
 * 「自定义」预设入口：点一下跳到「调节」页自己拖。
 * 和预设卡长得一样，只是右侧是箭头而不是选中勾。
 */
@Composable
private fun CustomPresetRow(
    selected: Boolean,
    onClick: () -> Unit,
) {
    PaddedGlassCard(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        shape = RoundedCornerShape(22.dp),
        horizontal = 18,
        vertical = 14,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "自定义",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "自己调增益上限、均衡与低音",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 开启后显示的处理细节：低音、响度均衡、限幅、当前预设。
 * 与下方「主音量增益」滑杆区分开，避免只重复展示 dB。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveInfoRow(state: AudioState, preset: Preset, hwGainDb: Int = 0) {
    val chips = buildList {
        if (hwGainDb > 0) add("硬件 +${hwGainDb}dB")
        if (state.mode == EngineMode.ROOT) add("ROOT 全局音效")
        add("低音 ${state.bassBoostStrength / 10}%")
        if (state.levelerStrength > 0) add("响度均衡 ${state.levelerStrength / 10}%")
        if (state.limiterActive) add("限幅保护")
        add(preset.label)
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { InfoChip(it) }
    }
}

@Composable
private fun InfoChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun StatusPill(active: Boolean, text: String? = null) {
    val t by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(240),
        label = "pillT",
    )
    val bg = lerp(
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        t,
    )
    val fg = lerp(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.primary, t)
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(fg)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = text ?: if (active) "运行中" else "已关闭",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = fg,
        )
    }
}

/** 270° 环形增益表，末端带一个小圆点 */
@Composable
private fun GainDial(progress: Float, active: Boolean) {
    val animated by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(420),
        label = "dialProgress",
    )
    val arcT by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(300),
        label = "dialT",
    )
    val arcColor = lerp(
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
        MaterialTheme.colorScheme.primary,
        arcT,
    )
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    Box(Modifier.size(232.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 13.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val startAngle = 135f
            val sweepTotal = 270f

            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = sweepTotal,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (animated > 0.001f) {
                drawArc(
                    color = arcColor,
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                val radius = (size.minDimension - stroke) / 2f
                val angleRad = Math.toRadians((startAngle + sweepTotal * animated).toDouble())
                val cx = size.width / 2f + radius * cos(angleRad).toFloat()
                val cy = size.height / 2f + radius * sin(angleRad).toFloat()
                drawCircle(color = arcColor, radius = stroke * 0.62f, center = Offset(cx, cy))
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = stroke * 0.26f,
                    center = Offset(cx, cy),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 均衡器页
// ─────────────────────────────────────────────────────────────────────────────

/** 把 Hz 格式化成滑杆标签：31 / 125 / 1k / 16k。 */
private fun formatFreq(hz: Int): String = when {
    hz >= 1000 -> {
        val k = hz / 1000f
        if (k == k.toInt().toFloat()) "${k.toInt()}k" else "%.1fk".format(k)
    }

    else -> hz.toString()
}

@Composable
private fun TunePage(
    state: AudioState,
    onBoostChange: (Int) -> Unit,
    onEqBandChange: (Int, Int) -> Unit,
    onBassChange: (Int) -> Unit,
    onLevelerChange: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        PageHeader(
            title = "调节",
            subtitle = if (state.active) "所有改动实时生效" else "开启增强后可调",
        )
        Spacer(Modifier.height(20.dp))

        // 增益上限（从主页搬过来）
        SectionLabel("增益上限")
        Spacer(Modifier.height(10.dp))
        PaddedGlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            horizontal = 18,
            vertical = 8,
        ) {
            ValueSlider(
                label = "最大补偿",
                value = state.boostMillibel.toFloat(),
                range = Preset.FloorBoostMillibel.toFloat()..state.maxBoostMillibel.toFloat(),
                display = "+%d%%".format(Preset.perceivedPercent(state.boostMillibel)),
                enabled = state.active && !state.pausedForHeadset,
                onChange = { onBoostChange(it.toInt()) },
            )
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("均衡器")
        Spacer(Modifier.height(10.dp))
        PaddedGlassCard(
            modifier = Modifier.fillMaxWidth(),
            horizontal = 18,
            vertical = 16,
        ) {
            Text(
                text = if (state.eqBandCount > 0)
                    "${state.eqBandCount} 段 · 每段 ±6 dB"
                else "开启后读取设备真实频段",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (state.eqCenterFreqs.isEmpty()) {
                Text(
                    text = "开启增强后，这里会显示 10 段均衡频段（由 ROOT 守护进程在全局混音上建立）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.eqCenterFreqs.forEachIndexed { index, freq ->
                val label = formatFreq(freq)
                val level = state.eqLevelsDb.getOrElse(index) { 0 }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(30.dp),
                    )
                    Slider(
                        value = level.toFloat(),
                        onValueChange = { onEqBandChange(index, it.toInt()) },
                        valueRange = -600f..600f,
                        enabled = state.active && !state.pausedForHeadset,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (level == 0) "0" else "%+.1f".format(level / 100f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End,
                        color = if (level > 0) MaterialTheme.colorScheme.primary
                        else if (level < 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(42.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("辅助效果")
        Spacer(Modifier.height(10.dp))
        PaddedGlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            horizontal = 18,
            vertical = 8,
        ) {
            ValueSlider(
                label = "低音增强",
                value = state.bassBoostStrength.toFloat(),
                range = 0f..1000f,
                display = "%d%%".format(state.bassBoostStrength / 10),
                enabled = state.active && !state.pausedForHeadset,
                onChange = { onBassChange(it.toInt()) },
            )
            Spacer(Modifier.height(4.dp))
            ValueSlider(
                label = "响度均衡",
                value = state.levelerStrength.toFloat(),
                range = 0f..1000f,
                display = when {
                    !state.active -> "需开启增强"
                    !state.levelerAvailable -> "本机不支持"
                    state.levelerStrength == 0 -> "关闭"
                    else -> "%d%%".format(state.levelerStrength / 10)
                },
                enabled = state.active && !state.pausedForHeadset && state.levelerAvailable,
                onChange = { onLevelerChange(it.toInt()) },
            )
            Text(
                text = "把视频忽大忽小的音量拉平，整体响度不变",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
            )
        }

        Spacer(Modifier.height(120.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 设置页
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsPage(
    state: AudioState,
    onMonoChange: (Boolean) -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    onDiagnose: () -> String,
    root: RootState = RootState(checking = false),
    onRootRefresh: () -> Unit = {},
    onRootGainChange: (Int) -> Unit = {},
    onRootRestore: () -> Unit = {},
    onRootVendorChange: (Boolean) -> Unit = {},
) {
    var diagnoseText by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        PageHeader(title = "设置", subtitle = "声音行为与高级能力")
        Spacer(Modifier.height(20.dp))

        RootSection(
            root = root,
            onRefresh = onRootRefresh,
            onGainChange = onRootGainChange,
            onRestore = onRootRestore,
            onVendorChange = onRootVendorChange,
        )

        Spacer(Modifier.height(12.dp))
        PaddedGlassCard(
            modifier = Modifier.fillMaxWidth(),
            horizontal = 18,
            vertical = 6,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "单声道",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "系统级开关，与「无障碍 → 单声道音频」同步，由 ROOT 直接写入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.mono, onCheckedChange = onMonoChange)
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel("外观")
        Spacer(Modifier.height(10.dp))
        PaddedGlassCard(
            modifier = Modifier.fillMaxWidth(),
            horizontal = 12,
            vertical = 12,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeChip(
                    label = "跟随系统",
                    selected = themeMode == "system",
                    onClick = { onThemeModeChange("system") },
                    modifier = Modifier.weight(1f),
                )
                ThemeChip(
                    label = "浅色",
                    selected = themeMode == "light",
                    onClick = { onThemeModeChange("light") },
                    modifier = Modifier.weight(1f),
                )
                ThemeChip(
                    label = "深色",
                    selected = themeMode == "dark",
                    onClick = { onThemeModeChange("dark") },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        PaddedGlassCard(
            modifier = Modifier.fillMaxWidth(),
            horizontal = 18,
            vertical = 6,
            onClick = { diagnoseText = onDiagnose() },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "音效诊断",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "机型、ROOT 状态、守护进程日志与系统音效列表",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "查看",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        PaddedGlassCard(
            modifier = Modifier.fillMaxWidth(),
            horizontal = 18,
            vertical = 18,
        ) {
            InfoRow(
                "输出路由",
                if (state.pausedForHeadset) "${state.routeLabel}（增强已暂停）" else state.routeLabel,
            )
            Spacer(Modifier.height(12.dp))
            InfoRow(
                "当前预设",
                Preset.All.firstOrNull { it.id == state.presetId }?.label ?: "自定义",
            )
            Spacer(Modifier.height(12.dp))
            InfoRow("版本", "1.3.4")
        }

        Spacer(Modifier.height(120.dp))
    }

    val text = diagnoseText
    if (text != null) {
        AlertDialog(
            onDismissRequest = { diagnoseText = null },
            confirmButton = {
                TextButton(onClick = { diagnoseText = null }) { Text("关闭") }
            },
            title = { Text("音效诊断") },
            text = {
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            },
        )
    }
}

@Composable
private fun ThemeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ROOT 硬件增强
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RootSection(
    root: RootState,
    onRefresh: () -> Unit,
    onGainChange: (Int) -> Unit,
    onRestore: () -> Unit,
    onVendorChange: (Boolean) -> Unit,
) {
    PaddedGlassCard(
        modifier = Modifier.fillMaxWidth(),
        horizontal = 18,
        vertical = 14,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "ROOT 状态",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            val statusText: String
            val statusColor: Color
            when {
                root.checking -> {
                    statusText = "检测中"
                    statusColor = MaterialTheme.colorScheme.onSurfaceVariant
                }
                root.available -> {
                    statusText = "已授权"
                    statusColor = MaterialTheme.colorScheme.primary
                }
                else -> {
                    statusText = "未授权"
                    statusColor = MaterialTheme.colorScheme.error
                }
            }
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
            )
        }

        Spacer(Modifier.height(10.dp))

        if (!root.available) {
            Text(
                text = "需要 ROOT 权限才能工作。临时 ROOT 重启后会失效，重新注入后点「重新检测」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRefresh) { Text("重新检测") }
        } else {
            val hasMixer = root.mixerTool != null && root.targets.isNotEmpty()
            if (hasMixer) {
                var pending by remember(root.appliedDb) {
                    mutableStateOf(root.appliedDb.toFloat())
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "硬件增益",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "+${pending.roundToInt()} dB",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = pending,
                    onValueChange = { pending = it },
                    onValueChangeFinished = { onGainChange(pending.roundToInt()) },
                    valueRange = 0f..RootAudioManager.MaxGainDb.toFloat(),
                    steps = RootAudioManager.MaxGainDb - 1,
                )
                Text(
                    text = if (root.appliedDb > 0) {
                        "已提升音频芯片的输出电平\n调系统音量时这段增强会保持"
                    } else {
                        "直接提升音频芯片的输出电平，突破系统音量上限\n调系统音量时这段增强会保持"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (root.appliedDb > 0) {
                    TextButton(onClick = onRestore) { Text("恢复原始值") }
                }
            } else {
                Text(
                    text = "这台设备没有可调的硬件音量控制，增强开关仍然可用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRefresh) { Text("重新检测") }
            }

            if (root.vendorPackages.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "释放全局音效通道",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "禁用杜比 / 小米音效服务，避免它们和本应用抢占全局音效槽位（实验性，可恢复）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = root.vendorBlocked,
                        onCheckedChange = onVendorChange,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 通用小件
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PageHeader(title: String, subtitle: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = display,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Compose 预览：Android Studio 右侧 Preview 面板直接查看，改代码即时刷新，无需真机
// ─────────────────────────────────────────────────────────────────────────────

private fun previewState() = AudioState(
    active = true,
    mode = EngineMode.ROOT,
    boostMillibel = 500,
    minBoostMillibel = Preset.FloorBoostMillibel,
    effectiveBoostMillibel = 380,
    systemVolume = 6,
    maxSystemVolume = 15,
    eqLevelsDb = intArrayOf(0, 120, 0, -60, 0, 60, 0, 0, -120, 0),
    eqCenterFreqs = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000),
    bassBoostStrength = 400,
    limiterActive = true,
    mono = false,
    presetId = "default",
    routedToBluetooth = false,
    levelerStrength = 500,
    levelerAvailable = true,
)

@Preview(
    name = "① 主页 · 深色",
    showBackground = true,
    widthDp = 411,
    heightDp = 891,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PreviewHomeDark() {
    SonicForgeTheme(darkTheme = true) {
        SonicForgeApp(
            state = previewState(),
            initialPresetId = null,
            onToggle = { _, _ -> },
            onPresetSelected = {},
            onBoostChange = {},
            onEqBandChange = { _, _ -> },
            onBassChange = {},
            onLevelerChange = {},
            onMonoChange = {},
            themeMode = "system",
            onThemeModeChange = {},
            onDiagnose = { "" },
        )
    }
}

@Preview(
    name = "② 主页 · 浅色",
    showBackground = true,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun PreviewHomeLight() {
    SonicForgeTheme(darkTheme = false) {
        SonicForgeApp(
            state = previewState(),
            initialPresetId = null,
            onToggle = { _, _ -> },
            onPresetSelected = {},
            onBoostChange = {},
            onEqBandChange = { _, _ -> },
            onBassChange = {},
            onLevelerChange = {},
            onMonoChange = {},
            themeMode = "light",
            onThemeModeChange = {},
            onDiagnose = { "" },
        )
    }
}

@Preview(
    name = "③ 均衡器 · 深色",
    showBackground = true,
    widthDp = 411,
    heightDp = 891,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PreviewEqDark() {
    SonicForgeTheme(darkTheme = true) {
        Box(Modifier.fillMaxSize()) {
            AppBackground()
            TunePage(previewState(), {}, { _, _ -> }, {}, {})
        }
    }
}

@Preview(
    name = "④ 设置 · 深色",
    showBackground = true,
    widthDp = 411,
    heightDp = 891,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PreviewSettingsDark() {
    SonicForgeTheme(darkTheme = true) {
        Box(Modifier.fillMaxSize()) {
            AppBackground()
            SettingsPage(
                state = previewState(),
                onMonoChange = {},
                themeMode = "dark",
                onThemeModeChange = {},
                onDiagnose = { "" },
            )
        }
    }
}
