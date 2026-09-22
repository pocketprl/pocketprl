package dev.pocketprl.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketprl.R
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.core.chain.Network
import dev.pocketprl.data.DatedAmount
import dev.pocketprl.data.FlowBucket
import dev.pocketprl.data.WalletStats
import dev.pocketprl.ui.components.EmptyState
import dev.pocketprl.ui.components.BannerKind
import dev.pocketprl.ui.components.InfoBanner
import dev.pocketprl.ui.components.LoadingBlock
import dev.pocketprl.ui.components.ScreenScaffold
import dev.pocketprl.ui.components.SectionCard
import dev.pocketprl.ui.components.SectionTitle
import dev.pocketprl.ui.components.dayLabel
import dev.pocketprl.ui.components.formatDateTime
import dev.pocketprl.ui.theme.AppIcons
import dev.pocketprl.ui.theme.PearlTheme
import dev.pocketprl.ui.theme.TabularNumbers
import dev.pocketprl.ui.vm.SettingsViewModel
import java.text.DateFormat
import java.util.Date

/**
 * A look back over everything this wallet has ever done, rolled up from the
 * local history. Read-only and never touches keys; the only network is the
 * optional daily price series behind the "time machine" section.
 */
@Composable
fun StatsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val price by vm.price.collectAsStateWithLifecycle()
    val stats = remember(snap.revision) { vm.stats() }
    val history by vm.priceHistory.collectAsStateWithLifecycle()
    val historyLoading by vm.priceHistoryLoading.collectAsStateWithLifecycle()
    val network = vm.network
    val hide = settings.hideBalance
    val per = price.usdPerPrl
    val showFiat = settings.showFiat && network.isMainnet
    val priceStats = remember(snap.revision, history, per) { vm.priceStats() }

    LaunchedEffect(snap.revision) { vm.loadPriceHistory() }

    ScreenScaffold(title = stringResource(R.string.stats_title), onBack = onBack) {
        if (stats.isEmpty) {
            EmptyState(AppIcons.History, stringResource(R.string.stats_none_title), text = stringResource(R.string.stats_none_body))
            return@ScreenScaffold
        }
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(stringResource(R.string.stats_section_totals))
            SectionCard {
                StatRow(stringResource(R.string.stats_received_total), prl(stats.totalReceived, network, hide), fiatLine(stats.totalReceived, per, showFiat, hide))
                HorizontalDivider()
                StatRow(stringResource(R.string.stats_sent_total), prl(stats.totalSent, network, hide), fiatLine(stats.totalSent, per, showFiat, hide))
                HorizontalDivider()
                StatRow(stringResource(R.string.stats_fees_total), prl(stats.totalFees, network, hide), fiatLine(stats.totalFees, per, showFiat, hide))
                HorizontalDivider()
                StatRow(
                    stringResource(R.string.stats_net),
                    prl(stats.netFlow, network, hide, signed = true),
                    fiatLine(stats.netFlow, per, showFiat, hide, signed = true),
                    valueColor = if (stats.netFlow > 0) PearlTheme.palette.success else MaterialTheme.colorScheme.onSurface,
                )
                if (stats.totalMined > 0) {
                    HorizontalDivider()
                    StatRow(stringResource(R.string.stats_mined_total), prl(stats.totalMined, network, hide), fiatLine(stats.totalMined, per, showFiat, hide))
                }
            }

            SectionTitle(stringResource(R.string.stats_section_activity))
            SectionCard {
                StatRow(
                    stringResource(R.string.stats_tx_count),
                    Amount.group(stats.txCount.toLong()),
                    stringResource(R.string.stats_breakdown, stats.receivedCount, stats.sentCount, stats.minedCount),
                )
                HorizontalDivider()
                StatRow(stringResource(R.string.stats_first_tx), date(stats.firstTxTime))
                HorizontalDivider()
                StatRow(stringResource(R.string.stats_last_tx), date(stats.lastTxTime))
                HorizontalDivider()
                StatRow(stringResource(R.string.stats_days_active), Amount.group(stats.daysActive.toLong()))
                stats.busiestDay?.let { busy ->
                    HorizontalDivider()
                    StatRow(
                        stringResource(R.string.stats_busiest_day),
                        dayLabel(busy.dayEpochSeconds),
                        pluralStringResource(R.plurals.stats_day_tx, busy.count, busy.count),
                    )
                }
                if (stats.minedCount > 0) {
                    HorizontalDivider()
                    StatRow(stringResource(R.string.stats_blocks_mined), Amount.group(stats.minedCount.toLong()))
                }
            }

            SectionTitle(stringResource(R.string.stats_section_records))
            SectionCard {
                RecordRow(stringResource(R.string.stats_largest_received), stats.largestReceived, network, hide, per, showFiat)
                HorizontalDivider()
                RecordRow(stringResource(R.string.stats_largest_sent), stats.largestSent, network, hide, per, showFiat)
                HorizontalDivider()
                RecordRow(stringResource(R.string.stats_biggest_fee), stats.biggestFee, network, hide, per, showFiat)
                HorizontalDivider()
                StatRow(stringResource(R.string.stats_avg_fee), prl(stats.averageFee, network, hide), fiatLine(stats.averageFee, per, showFiat, hide))
            }

            SectionTitle(stringResource(R.string.stats_section_missed))
            SectionCard {
                val ps = priceStats
                when {
                    !showFiat -> InfoBanner(stringResource(R.string.stats_missed_off), BannerKind.INFO)
                    ps != null -> {
                        ps.biggestMissedGain?.let { rec ->
                            StatRow(
                                stringResource(R.string.stats_biggest_missed),
                                Amount.fiatValue(rec.missedGainFiat),
                                sub = stringResource(
                                    R.string.stats_missed_sub,
                                    Amount.pretty(rec.amount), network.ticker, Amount.usdPrice(rec.sellPrice), formatDateTime(rec.time),
                                ),
                                valueColor = PearlTheme.palette.warning,
                            )
                            HorizontalDivider()
                        }
                        ps.totalMissedGainFiat?.let { total ->
                            StatRow(
                                stringResource(R.string.stats_total_missed),
                                Amount.fiatValue(total),
                                sub = stringResource(R.string.stats_total_missed_sub),
                                valueColor = PearlTheme.palette.warning,
                            )
                            HorizontalDivider()
                        }
                        StatRow(
                            stringResource(R.string.stats_avg_sell),
                            Amount.usdPrice(ps.averageSellPrice),
                            sub = stringResource(R.string.stats_avg_sell_sub, Amount.usdPrice(ps.allTimeHigh), formatDateTime(ps.allTimeHighTime)),
                        )
                        if (per != null) {
                            HorizontalDivider()
                            StatRow(stringResource(R.string.stats_current_price), Amount.usdPrice(per))
                        }
                    }
                    historyLoading -> LoadingBlock(stringResource(R.string.stats_missed_loading))
                    history != null -> InfoBanner(stringResource(R.string.stats_missed_none), BannerKind.INFO)
                    else -> InfoBanner(stringResource(R.string.stats_missed_unavailable), BannerKind.INFO)
                }
            }

            SectionTitle(stringResource(R.string.stats_section_charts))
            SectionCard {
                ChartHeading(AppIcons.TrendingUp, stringResource(R.string.stats_chart_flows))
                Spacer(Modifier.height(12.dp))
                WeeklyFlowChart(stats.weeks, network, hide)
            }
            SectionCard {
                ChartHeading(AppIcons.Odometer, stringResource(R.string.stats_chart_balance))
                Spacer(Modifier.height(12.dp))
                BalanceChart(stats.balanceSeries, network, hide)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ChartHeading(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(30.dp).background(PearlTheme.palette.accent.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = PearlTheme.palette.accent, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RecordRow(label: String, record: DatedAmount?, network: Network, hide: Boolean, per: Double?, showFiat: Boolean) {
    if (record == null) {
        StatRow(label, stringResource(R.string.stats_empty_value))
    } else {
        val sub = listOfNotNull(fiatLine(record.amount, per, showFiat, hide), date(record.time)).joinToString("\n")
        StatRow(label, prl(record.amount, network, hide), sub)
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    sub: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.42f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(0.58f), horizontalAlignment = Alignment.End) {
            Text(value, color = valueColor, style = MaterialTheme.typography.bodyLarge.merge(TabularNumbers).copy(fontWeight = FontWeight.SemiBold), textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun prl(grain: Long, network: Network, hide: Boolean, signed: Boolean = false): String {
    if (hide) return stringResource(R.string.hidden_placeholder)
    val sign = if (signed && grain > 0) "+" else ""
    return "$sign${Amount.pretty(grain)} ${network.ticker}"
}

@Composable
private fun fiatLine(grain: Long, per: Double?, showFiat: Boolean, hide: Boolean, signed: Boolean = false): String? {
    if (!showFiat || hide || grain == 0L) return null
    val f = Amount.fiat(grain, per) ?: return null
    return stringResource(R.string.send_fiat_approx, if (signed && grain > 0) "+$f" else f)
}

@Composable
private fun date(epochSeconds: Long?): String =
    if (epochSeconds == null || epochSeconds <= 0) stringResource(R.string.stats_empty_value) else formatDateTime(epochSeconds)

@Composable
private fun shortDate(epochSeconds: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * 1000))

/** Compact y-axis level: fewer decimals as the amount grows, respecting the configured separators. */
private fun axisLabel(grain: Long): String {
    val abs = if (grain < 0) -grain else grain
    val decimals = when {
        abs >= 100L * Amount.GRAIN_PER_PRL -> 0
        abs >= 10L * Amount.GRAIN_PER_PRL -> 1
        abs >= Amount.GRAIN_PER_PRL -> 2
        abs > 0L -> 4
        else -> 0
    }
    return Amount.pretty(grain, maxDecimals = decimals)
}

private fun slotAt(x: Float, width: Float, n: Int): Int =
    if (width <= 0f || n <= 0) 0 else ((x / width) * n).toInt().coerceIn(0, n - 1)

/** Press-and-drag across a chart: reports the finger's horizontal position and the canvas width. */
private fun Modifier.chartDrag(key: Any?, onX: (Float, Float) -> Unit): Modifier = pointerInput(key) {
    val width = size.width.toFloat()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onX(down.position.x, width)
        var pressed = true
        while (pressed) {
            val event = awaitPointerEvent()
            event.changes.forEach { if (it.pressed) onX(it.position.x, width) }
            pressed = event.changes.any { it.pressed }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChartLegend(vararg entries: Pair<Color, String>) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        entries.forEach { LegendDot(it.first, it.second) }
    }
}

/** A y-axis label column: top value, middle, bottom, aligned with the three grid lines. */
@Composable
private fun AxisLabels(top: Long, mid: Long, bottom: Long, height: androidx.compose.ui.unit.Dp) {
    Column(
        modifier = Modifier.width(58.dp).height(height),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        Text(axisLabel(top), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(axisLabel(mid), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(axisLabel(bottom), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Weekly diverging bars: money in rises above the centre line, money out sinks
 * below it. Press and drag to drop a crosshair on a week and read its numbers.
 */
@Composable
private fun WeeklyFlowChart(weeks: List<FlowBucket>, network: Network, hide: Boolean) {
    val inColor = PearlTheme.palette.success
    val outColor = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val crosshair = MaterialTheme.colorScheme.primary
    var selected by remember(weeks) { mutableStateOf<Int?>(null) }
    val max = remember(weeks) { weeks.maxOfOrNull { maxOf(it.inflow, it.outflow) }?.coerceAtLeast(1L) ?: 1L }
    val chartHeight = 150.dp
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            AxisLabels(top = max, mid = 0, bottom = max, height = chartHeight)
            Spacer(Modifier.width(8.dp))
            Canvas(modifier = Modifier.weight(1f).height(chartHeight).chartDrag(weeks) { x, w -> selected = slotAt(x, w, weeks.size) }) {
                val mid = size.height / 2f
                val slot = size.width / weeks.size.coerceAtLeast(1)
                val barW = (slot * 0.34f).coerceAtMost(16.dp.toPx())
                val half = mid - 3.dp.toPx()
                drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                drawLine(grid, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())
                drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                weeks.forEachIndexed { i, w ->
                    val cx = slot * i + slot / 2f
                    val inH = (w.inflow.toFloat() / max) * half
                    val outH = (w.outflow.toFloat() / max) * half
                    if (inH > 0f) drawRoundRect(inColor, topLeft = Offset(cx - barW - 1f, mid - inH), size = Size(barW, inH), cornerRadius = CornerRadius(barW / 2f))
                    if (outH > 0f) drawRoundRect(outColor, topLeft = Offset(cx + 1f, mid), size = Size(barW, outH), cornerRadius = CornerRadius(barW / 2f))
                }
                selected?.takeIf { it in weeks.indices }?.let { i ->
                    val cx = slot * i + slot / 2f
                    drawLine(crosshair, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.5.dp.toPx())
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(66.dp))
            Box(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    weeks.forEachIndexed { i, w ->
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (i % 2 == 0) Text(shortDate(w.start), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        selected?.takeIf { it in weeks.indices }?.let { i ->
            val w = weeks[i]
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.stats_week_readout, shortDate(w.start), prl(w.inflow, network, hide), prl(w.outflow, network, hide)),
                style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(8.dp))
        ChartLegend(inColor to stringResource(R.string.stats_legend_in), outColor to stringResource(R.string.stats_legend_out))
    }
}

/** Cumulative balance over every transaction, from zero to the current total, with draggable levels. */
@Composable
private fun BalanceChart(series: List<DatedAmount>, network: Network, hide: Boolean) {
    val accent = PearlTheme.palette.accent
    val grid = MaterialTheme.colorScheme.outlineVariant
    val crosshair = MaterialTheme.colorScheme.primary
    if (series.size < 2) {
        Text(stringResource(R.string.stats_empty_value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var selected by remember(series) { mutableStateOf<Int?>(null) }
    val min = series.minOf { it.amount }
    val max = series.maxOf { it.amount }
    val mid = min + (max - min) / 2
    val range = (max - min).coerceAtLeast(1L)
    val chartHeight = 140.dp
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            AxisLabels(top = max, mid = mid, bottom = min, height = chartHeight)
            Spacer(Modifier.width(8.dp))
            Canvas(modifier = Modifier.weight(1f).height(chartHeight).chartDrag(series) { x, w -> selected = slotAt(x, w, series.size) }) {
                val w = size.width
                val h = size.height
                drawLine(grid, Offset(0f, 0f), Offset(w, 0f), strokeWidth = 1.dp.toPx())
                drawLine(grid, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1.dp.toPx())
                drawLine(grid, Offset(0f, h), Offset(w, h), strokeWidth = 1.dp.toPx())
                val line = Path()
                val fill = Path()
                series.forEachIndexed { i, p ->
                    val x = w * i / (series.size - 1)
                    val y = h - ((p.amount - min).toFloat() / range) * h
                    if (i == 0) {
                        line.moveTo(x, y)
                        fill.moveTo(x, h)
                        fill.lineTo(x, y)
                    } else {
                        line.lineTo(x, y)
                        fill.lineTo(x, y)
                    }
                }
                fill.lineTo(w, h)
                fill.close()
                drawPath(fill, color = accent.copy(alpha = 0.12f))
                drawPath(line, color = accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                selected?.takeIf { it in series.indices }?.let { i ->
                    val x = w * i / (series.size - 1)
                    val y = h - ((series[i].amount - min).toFloat() / range) * h
                    drawLine(crosshair, Offset(x, 0f), Offset(x, h), strokeWidth = 1.5.dp.toPx())
                    drawCircle(accent, radius = 4.dp.toPx(), center = Offset(x, y))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(66.dp))
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(shortDate(series.first().time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shortDate(series.last().time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        selected?.takeIf { it in series.indices }?.let { i ->
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.stats_balance_readout, shortDate(series[i].time), prl(series[i].amount, network, hide)),
                style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
