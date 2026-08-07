package me.rerere.rikkahub.ui.components.message

import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.CoinsDollar
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.costguards.TokenBudgetTracker
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.formatNumber
import me.rerere.rikkahub.utils.toFixed
import java.time.Duration

/**
 * 显示消息的技术统计信息（如 token 使用量）
 */
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
    sessionTotals: TokenBudgetTracker.Totals? = null,
) {
    val settings = LocalSettings.current.displaySetting

    @Suppress("DEPRECATION") // 与项目现有 LocalClipboardManager 用法保持一致
    val clipboardManager = LocalClipboardManager.current

    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
        CompositionLocalProvider(LocalContentColor provides color) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = modifier.padding(horizontal = 4.dp),
            ) {
                val usage = message.usage
                if (settings.showTokenUsage && usage != null) {
                    // Input tokens
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Upload02,
                                contentDescription = "Input",
                                tint = color,
                                modifier = Modifier.size(12.dp),
                            )
                        },
                        content = {
                            Text(text = "${usage.promptTokens.formatNumber()} 输入")
                            // Cached tokens (count + hit-%)
                            if (usage.cachedTokens > 0 && usage.promptTokens > 0) {
                                val pct = usage.cachedTokens.toDouble() / usage.promptTokens.toDouble() * 100.0
                                Text(
                                    text = "(${message.usage?.cachedTokens?.formatNumber() ?: "0"} 命中 · ${String.format(java.util.Locale.US, "%.1f%%", pct)})",
                                )
                            }
                        },
                    )
                    // Output tokens
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Download04,
                                contentDescription = "Output",
                                modifier = Modifier.size(12.dp),
                            )
                        },
                        content = {
                            Text(text = "${usage.completionTokens.formatNumber()} 输出")
                        },
                    )
                    // Cost (USD) — shown when the provider reports it (e.g. OpenRouter usage.cost)
                    val cost = usage.cost
                    if (cost != null && cost > 0.0) {
                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.CoinsDollar,
                                    contentDescription = "Cost",
                                    tint = color,
                                    modifier = Modifier.size(12.dp),
                                )
                            },
                            content = {
                                Text(text = formatCost(cost))
                            },
                        )
                    }
                    // TPS
                    if (message.finishedAt != null) {
                        val duration =
                            Duration.between(
                                message.createdAt.toJavaLocalDateTime(),
                                message.finishedAt!!.toJavaLocalDateTime(),
                            )
                        val tps = usage.completionTokens.toFloat() / duration.toMillis() * 1000
                        val seconds = (duration.toMillis() / 1000f).toFixed(1)
                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.Zap,
                                    contentDescription = "Speed",
                                    modifier = Modifier.size(12.dp),
                                )
                            },
                            content = {
                                Text(text = "${tps.toFixed(1)} tok/s")
                            },
                        )

                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.Clock02,
                                    contentDescription = "Duration",
                                    modifier = Modifier.size(12.dp),
                                )
                            },
                            content = {
                                Text(text = "${seconds}s")
                            },
                        )
                    }
                    // 一键复制统计信息（与截图格式一致，方便直接粘贴给 AI 分析）
                    var pendingCopyText by remember { mutableStateOf("") }
                    Box(
                        modifier =
                            Modifier
                                .clickable(onClick = {
                                    val statsText =
                                        buildString {
                                            append("↑${usage.promptTokens.formatNumber()} tokens")
                                            if (usage.cachedTokens > 0 && usage.promptTokens > 0) {
                                                val pct = usage.cachedTokens.toDouble() / usage.promptTokens.toDouble() * 100.0
                                                append(" (${usage.cachedTokens.formatNumber()} cached · ${String.format(java.util.Locale.US, "%.1f%%", pct)})")
                                            }
                                            append(" ↓${usage.completionTokens.formatNumber()} tokens")
                                            val finish = message.finishedAt
                                            if (finish != null) {
                                                val duration =
                                                    Duration.between(
                                                        message.createdAt.toJavaLocalDateTime(),
                                                        finish.toJavaLocalDateTime(),
                                                    )
                                                val tps = usage.completionTokens.toFloat() / duration.toMillis() * 1000
                                                val seconds = (duration.toMillis() / 1000f).toFixed(1)
                                                append(" ⚡${tps.toFixed(1)} tok/s 🕐${seconds}s")
                                            }
                                        }
                                    pendingCopyText = statsText
                                })
                                .padding(2.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Copy01,
                            contentDescription = "复制统计",
                            tint = color,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    LaunchedEffect(pendingCopyText) {
                        if (pendingCopyText.isNotEmpty()) {
                            clipboardManager.setText(AnnotatedString(pendingCopyText))
                        }
                    }
                }
            }
            if (sessionTotals != null) {
                var sessionPendingCopy by remember { mutableStateOf("") }
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Upload02,
                                contentDescription = "Input",
                                tint = color,
                                modifier = Modifier.size(12.dp),
                            )
                        },
                        content = {
                            val cachedPct =
                                " (${sessionTotals.cachedTokens.toInt().formatNumber()} 命中缓存 · ${String.format(java.util.Locale.US, "%.1f%%", if (sessionTotals.inputTokens > 0) sessionTotals.cachedTokens.toDouble() / sessionTotals.inputTokens.toDouble() * 100.0 else 0.0)})"
                            Text(
                                text = "↑${sessionTotals.inputTokens.toInt().formatNumber()} 输入$cachedPct ↓${sessionTotals.outputTokens.toInt().formatNumber()} 输出",
                            )
                        },
                    )
                    Box(
                        modifier =
                            Modifier
                                .clickable(onClick = {
                                    val cachedPct =
                                        " (${sessionTotals.cachedTokens.toInt().formatNumber()} cached · ${String.format(java.util.Locale.US, "%.1f%%", if (sessionTotals.inputTokens > 0) sessionTotals.cachedTokens.toDouble() / sessionTotals.inputTokens.toDouble() * 100.0 else 0.0)})"
                                    sessionPendingCopy =
                                        "↑${sessionTotals.inputTokens.toInt().formatNumber()} tokens$cachedPct ↓${sessionTotals.outputTokens.toInt().formatNumber()} tokens"
                                })
                                .padding(2.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Copy01,
                            contentDescription = "复制累计统计",
                            tint = color,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    LaunchedEffect(sessionPendingCopy) {
                        if (sessionPendingCopy.isNotEmpty()) {
                            clipboardManager.setText(AnnotatedString(sessionPendingCopy))
                        }
                    }
                }
            }
        }
    }
}

// Generation cost is often a tiny fraction of a cent, so a fixed decimal count would show
// "$0.0000". Render up to 6 decimals and trim trailing zeros (e.g. "$0.0123", "$0.000045").
// A positive cost smaller than 1e-6 would round to zero at 6dp and read as "$0" (free), which
// is misleading; clamp those to a "<$0.000001" form so a real charge never displays as free.
@VisibleForTesting
internal fun formatCost(cost: Double): String {
    val rounded =
        java.math
            .BigDecimal(cost)
            .setScale(6, java.math.RoundingMode.HALF_UP)
    if (cost > 0.0 && rounded.signum() == 0) {
        return "<$0.000001"
    }
    val s = rounded.stripTrailingZeros().toPlainString()
    return "$" + s
}

@Composable
fun StatsItem(
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        icon()
        content()
    }
}
