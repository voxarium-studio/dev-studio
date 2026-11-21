package studio.voxarium.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.flow.conflate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.jewel.ui.component.Text

private enum class TokenKind(val field: String) {
    Uncached("uncached"), Cached("cached"), Unclassified("unclassified"),
}

private enum class Metric { Tokens, Cost, Duration }

private fun JsonObject.amount(key: String): Double = string(key)?.toDoubleOrNull()
    ?.takeIf { it.isFinite() && it >= 0 } ?: 0.0

private fun JsonObject.day(): LocalDate? = string("day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

private fun Messages.number(value: Double, digits: Int = 0): String =
    NumberFormat.getNumberInstance(Locale.forLanguageTag(language)).apply { maximumFractionDigits = digits }.format(value)

private fun Messages.metric(value: Double, metric: Metric): String = when (metric) {
    Metric.Tokens -> when {
        value >= 999_500_000 -> "${number(value / 1_000_000_000, 1)}B"
        value >= 999_500 -> "${number(value / 1_000_000, 1)}M"
        value >= 999.95 -> "${number(value / 1_000, 1)}K"
        else -> number(value, if (value < 1) 3 else 1)
    }
    Metric.Cost -> if (value == 0.0) "$0" else "$" + NumberFormat.getNumberInstance(Locale.forLanguageTag(language)).apply {
        val digits = if (value < 0.01) 6 else 2
        maximumFractionDigits = digits
        minimumFractionDigits = if (value < 0.01) 3 else 2
    }.format(value)
    Metric.Duration -> {
        val seconds = value / 1_000
        when {
            seconds < 60 -> this["stats.durationChart.seconds"].replace("{value}", number(seconds, 2))
            seconds < 3_600 -> this["stats.durationChart.minutes"].replace("{value}", number(seconds / 60, 1))
            else -> this["stats.durationChart.hours"].replace("{value}", number(seconds / 3_600, 1))
        }
    }
}

@Composable
internal fun StatisticsPage(tab: ProjectTab, modifier: Modifier = Modifier) {
    val api = tab.api ?: return
    val m = localMessages.current
    val colors = localColors.current
    var teamId by remember(api) { mutableStateOf<String?>(null) }
    var teams by remember(api) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var stats by remember(api, teamId) { mutableStateOf<JsonObject?>(null) }
    var problem by remember(api, teamId) { mutableStateOf<Problem?>(null) }
    var busy by remember(api, teamId) { mutableStateOf(true) }
    var retry by remember(api) { mutableIntStateOf(0) }
    var teamMenu by remember { mutableStateOf(false) }
    val team = teams.find { it.string("id") == teamId } ?: teams.firstOrNull()

    LaunchedEffect(api, teamId) {
        // §10.4: read statistics for every task, including archived work, through the project's scope.
        snapshotFlow { tab.pageRefresh to retry }.conflate().collect {
            problem = null
            busy = true
            try {
                val fresh = api.get("/teams").objects()
                val active = fresh.find { it.string("id") == teamId } ?: fresh.firstOrNull()
                val selected = active?.string("id")
                if (selected != stats?.string("team_id")) stats = null
                teams = fresh
                stats = selected?.let { api.get("/teams/${segment(it)}/stats").jsonObject }
            } catch (error: Exception) {
                problem = error.problem()
            } finally {
                busy = false
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        LibraryHeader(m["statistics"], m["stats.subtitle"]) {
            if (busy) {
                StudioLoadingIcon(Modifier.size(StudioTokens.iconSize))
                Muted(m["loading"])
            }
            team?.let { current ->
                val name = current.string("name") ?: current.string("id").orEmpty()
                if (teams.size == 1) LibraryCaption(name, Modifier.widthIn(max = 205.dp))
                else Box(Modifier.widthIn(max = 205.dp)) {
                    WorkAction(name, "chevron-down") { teamMenu = true }
                    StudioMenu(teamMenu, { teamMenu = false }, teams.map { entry ->
                        StudioMenuItem(entry.string("name") ?: entry.string("id").orEmpty(), checked = entry == current) {
                            teamId = entry.string("id")
                        }
                    }, initialItem = teams.indexOf(current))
                }
            }
            WorkAction(m[if (problem == null) "refresh" else "retry"], "refresh-cw", enabled = !busy) { retry++ }
        }
        problem?.let { WorkProblemBanner(it) }
        val current = stats
        when {
            current != null -> key(current.string("team_id") ?: team?.string("id")) { StatisticsContent(current) }
            busy -> Centered { Muted(m["loading"]) }
            problem != null -> WorkEmpty(m["stats.unavailable"], icon = "chart-line")
            else -> WorkEmpty(m["stats.noTeam"], m["stats.noTeamHint"], "chart-line")
        }
    }
}

@Composable
private fun StatisticsContent(stats: JsonObject) {
    val m = localMessages.current
    val scroll = rememberScrollState()
    val today = LocalDate.now(ZoneOffset.UTC)
    val totals = stats.obj("totals") ?: JsonObject(emptyMap())
    val kinds = TokenKind.entries.filter { it != TokenKind.Unclassified || totals.amount("spent_unclassified_tokens") > 0 }
    var tokenKind by remember { mutableStateOf(TokenKind.Uncached) }
    val activeKind = tokenKind.takeIf { it in kinds } ?: TokenKind.Uncached
    Box(Modifier.fillMaxSize().studioArrival()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(StudioTokens.panePad),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.space4)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 960.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space4)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
                            StatisticsMetric(stats, totals, today, activeKind, kinds) { tokenKind = it }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
                            StatisticsMetric(stats, totals, today, null)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
                        StatisticsMetric(stats, totals, today, activeKind, kinds) { tokenKind = it }
                        Rule()
                        StatisticsMetric(stats, totals, today, null)
                    }
                }
            }
            StatisticsTrend(m["stats.durationChart.title"], "durationChart", m["stats.durationChart.hint"],
                points(stats, "average_task_duration_series", "average_duration_ms", "tasks"), Metric.Duration, today)
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatisticsMetric(
    stats: JsonObject,
    totals: JsonObject,
    today: LocalDate,
    tokenKind: TokenKind?,
    tokenKinds: List<TokenKind> = emptyList(),
    chooseTokenKind: (TokenKind) -> Unit = {},
) {
    val m = localMessages.current
    val metric = if (tokenKind == null) Metric.Cost else Metric.Tokens
    val suffix = tokenKind?.let { "${it.field}_tokens" } ?: "usd"
    Heading(m[if (tokenKind == null) "stats.metrics.usd" else "stats.metrics.tokens"], size = StudioTokens.titleSize.value.toInt())
    if (tokenKind != null) {
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            for (kind in tokenKinds) {
                StudioSelectionRow(kind == tokenKind, { chooseTokenKind(kind) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.spaceHair)) {
                        LibraryCaption(m["stats.token.${kind.field}Short"])
                        Text(m.metric(totals.amount("spent_${kind.field}_tokens"), metric), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        LibraryCaption(m["stats.token.${tokenKind.field}Hint"], maxLines = 3)
    }

    val finished = m.number(totals.amount("done"))
    val archived = totals.amount("archived")
    val cancelled = totals.amount("cancelled_$suffix")
    val completedNote = m[if (archived > 0) "stats.finishedArchived" else "stats.finished"]
        .replace("{count}", finished).replace("{archived}", m.number(archived))
    val cancelledNote = if (cancelled > 0) m["stats.tiles.cancelledNote"].replace("{cost}", m.metric(cancelled, metric))
        else m["stats.tiles.nothingCancelled"]
    Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
        StatisticTile(m["stats.tiles.spent"], m.metric(totals.amount("spent_$suffix"), metric), m["stats.tiles.spentNote"], Modifier.weight(1f))
        StatisticTile(m["stats.tiles.finished"], m.metric(totals.amount("done_$suffix"), metric), completedNote, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space3)) {
        StatisticTile(m["stats.tiles.inFlight"], m.metric(totals.amount("in_flight_$suffix"), metric), m["stats.tiles.inFlightNote"], Modifier.weight(1f))
        StatisticTile(m["stats.tiles.tasks"], m.number(totals.amount("tasks")), cancelledNote, Modifier.weight(1f))
    }

    StatisticsActivity(stats.objects("activity"), suffix, metric, today)
    val trend = if (tokenKind == null) "chart" else "tokensChart"
    val average = if (tokenKind == null) "cost" else "tokens"
    StatisticsTrend(m["stats.$trend.title"], trend, m["stats.$trend.hint"],
        points(stats, if (tokenKind == null) "series" else "token_series", "cumulative_$suffix", "runs", "spent_$suffix"),
        metric, today, cumulative = true)
    StatisticsTrend(m["stats.averageChart.${average}Title"], "averageChart", m["stats.averageChart.${average}Hint"],
        points(stats, "average_task_series", "average_$suffix", "tasks"), metric, today)

    val spendField = "spent_$suffix"
    val members = stats.objects("by_member").map { row ->
        val ratio = m["stats.byMember.doneRatio"].replace("{done}", m.number(row.amount("done")))
            .replace("{tasks}", m.number(row.amount("tasks")))
        SpendRow(row.string("name").orEmpty(), row.amount(spendField), ratio, row)
    }
    val models = stats.objects(if (tokenKind == null) "by_model" else "by_model_tokens").map { row ->
        SpendRow(row.string("model") ?: m["stats.byModel.unknown"], row.amount(spendField))
    }
    val hint = if (tokenKind == null) "hint" else "tokensHint"
    StatisticsBreakdown(m["stats.byMember.title"], m["stats.byMember.$hint"], m["stats.byMember.noTasks"], members, metric)
    StatisticsBreakdown(m["stats.byModel.title"], m["stats.byModel.$hint"], m["stats.byModel.empty"], models, metric)
}

@Composable
private fun StatisticTile(label: String, value: String, note: String, modifier: Modifier) {
    Box(modifier) {
        LibraryCard(gap = StudioTokens.space1) {
            LibraryCaption(label, maxLines = 2)
            Text(value, fontSize = StudioTokens.metricValueSize, fontWeight = FontWeight.SemiBold)
            LibraryCaption(note, maxLines = 3)
        }
    }
}

private data class SpendRow(val label: String, val amount: Double, val note: String? = null, val member: JsonObject? = null)

@Composable
private fun StatisticsBreakdown(title: String, hint: String, empty: String, rows: List<SpendRow>, metric: Metric) {
    val m = localMessages.current
    val colors = localColors.current
    val locale = Locale.forLanguageTag(m.language)
    val sorted = rows.sortedWith(compareByDescending<SpendRow> { it.amount }.thenBy { it.label.lowercase(locale) })
    val maximum = sorted.firstOrNull()?.amount ?: 0.0
    LibraryCard {
        LibrarySectionTitle(title)
        LibraryCaption(hint, maxLines = 3)
        if (sorted.isEmpty()) LibraryCaption(empty)
        for (row in sorted) {
            Column(verticalArrangement = Arrangement.spacedBy(StudioTokens.space1)) {
                Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                    row.member?.let { MemberAvatar(it) }
                    Text(row.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.metric(row.amount, metric), fontWeight = FontWeight.Medium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                    val shape = RoundedCornerShape(StudioTokens.radiusSm)
                    Box(Modifier.weight(1f).height(6.dp).background(colors.inset, shape)) {
                        if (maximum > 0) Box(Modifier.fillMaxWidth((row.amount / maximum).toFloat())
                            .fillMaxHeight().background(colors.accent, shape))
                    }
                    row.note?.let { LibraryCaption(it) }
                }
            }
        }
    }
}

private data class StatisticsPoint(val day: LocalDate, val value: Double, val count: Double, val spent: Double?)

private fun points(stats: JsonObject, series: String, value: String, count: String, spent: String? = null): List<StatisticsPoint> =
    stats.objects(series).mapNotNull { row ->
        row.day()?.let { StatisticsPoint(it, row.amount(value), row.amount(count), spent?.let(row::amount)) }
    }.sortedBy { it.day }

@Composable
private fun StatisticsTrend(title: String, key: String, hint: String, data: List<StatisticsPoint>, metric: Metric,
                            today: LocalDate, cumulative: Boolean = false) {
    LibraryCard {
        LibrarySectionTitle(title)
        LibraryCaption(hint, maxLines = 5)
        if (data.isEmpty()) LibraryCaption(localMessages.current["stats.$key.empty"], maxLines = 3)
        else StatisticsLine(data, title, metric, today, cumulative)
    }
}

@Composable
private fun Modifier.statisticsChartFocus(): Modifier =
    studioFocusRing(RoundedCornerShape(StudioTokens.radiusSm), background = localColors.current.cardFill).focusable()

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun StatisticsLine(data: List<StatisticsPoint>, title: String, metric: Metric, today: LocalDate, cumulative: Boolean) {
    val m = localMessages.current
    val colors = localColors.current
    val locale = Locale.forLanguageTag(m.language)
    val dayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val start = minOf(today.minusDays(6), data.first().day).toEpochDay()
    val end = maxOf(today, data.last().day).toEpochDay()
    val maximum = data.maxOf { it.value }.takeIf { it > 0 } ?: 1.0
    val plotFractions = remember(data, start, end) { data.map { ((it.day.toEpochDay() - start).toDouble() / (end - start)).toFloat() } }
    var selected by remember(data) { mutableIntStateOf(data.lastIndex) }
    val point = data[selected]
    val detail = listOfNotNull(dayFormat.format(point.day), m.metric(point.value, metric),
        point.spent?.let { "${m["stats.table.spent"]} ${m.metric(it, metric)}" },
        "${m[if (cumulative) "stats.table.runs" else "stats.tiles.tasks"]} ${m.number(point.count)}").joinToString(" · ")

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
        Column(Modifier.height(128.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
            LibraryCaption(m.metric(maximum, metric))
            LibraryCaption(m.metric(maximum / 2, metric))
            LibraryCaption(m.metric(0.0, metric))
        }
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(128.dp)
                .semantics { contentDescription = "$title. $detail" }
                .onPointerEvent(PointerEventType.Move) { event ->
                    val target = event.changes.firstOrNull()?.position?.x ?: return@onPointerEvent
                    val fraction = target / size.width.coerceAtLeast(1)
                    selected = plotFractions.indices.minBy { abs(plotFractions[it] - fraction) }
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.DirectionLeft -> { selected = (selected - 1).coerceAtLeast(0); true }
                        Key.DirectionRight -> { selected = (selected + 1).coerceAtMost(data.lastIndex); true }
                        else -> false
                    }
                }.statisticsChartFocus()) {
                val top = StudioTokens.space1.toPx()
                val bottom = size.height - top
                for (fraction in listOf(0f, .5f, 1f)) {
                    val y = top + (bottom - top) * fraction
                    drawLine(colors.subtleBorder, Offset(0f, y), Offset(size.width, y), StudioTokens.borderWidth.toPx())
                }
                val coords = data.mapIndexed { index, point ->
                    Offset(plotFractions[index] * size.width, bottom - (point.value / maximum).toFloat() * (bottom - top))
                }
                val last = if (cumulative) Offset(size.width, coords.last().y) else coords.last()
                val line = Path().apply {
                    moveTo(coords.first().x, coords.first().y)
                    for (coord in coords.drop(1)) lineTo(coord.x, coord.y)
                    lineTo(last.x, last.y)
                }
                val fill = Path().apply {
                    moveTo(coords.first().x, coords.first().y)
                    for (coord in coords.drop(1)) lineTo(coord.x, coord.y)
                    lineTo(last.x, last.y)
                    lineTo(last.x, bottom)
                    lineTo(coords.first().x, bottom)
                    close()
                }
                drawPath(fill, colors.accent.copy(alpha = StudioTokens.statusTintOpacity))
                drawPath(line, colors.accent, style = Stroke(StudioTokens.focusWidth.toPx()))
                drawCircle(colors.accent, 3.dp.toPx(), coords.last())
                val cursor = coords[selected]
                drawLine(colors.border, Offset(cursor.x, top), Offset(cursor.x, bottom), StudioTokens.borderWidth.toPx())
                drawCircle(colors.accent, 4.dp.toPx(), cursor)
                drawCircle(colors.surface, 4.dp.toPx(), cursor, style = Stroke(StudioTokens.borderWidth.toPx()))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LibraryCaption(dayFormat.format(LocalDate.ofEpochDay(start)))
                LibraryCaption(dayFormat.format(LocalDate.ofEpochDay(end)))
            }
        }
    }
    LibraryCaption(detail, maxLines = 2)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun StatisticsActivity(activity: List<JsonObject>, suffix: String, metric: Metric, today: LocalDate) {
    val m = localMessages.current
    val colors = localColors.current
    val locale = Locale.forLanguageTag(m.language)
    val weekdayFormat = TextStyle.SHORT
    val days = remember(activity) { activity.mapNotNull { entry -> entry.day()?.let { it to entry } }.toMap() }
    val max = (activity.maxOfOrNull { it.amount("activity") } ?: 0.0).coerceAtLeast(1.0)
    val shades = listOf(colors.inset, lerp(colors.inset, colors.accent, .32f), lerp(colors.inset, colors.accent, .55f),
        lerp(colors.inset, colors.accent, .78f), colors.accent)
    // These are heat-map coordinates, shared by the painted cells, hover hit-test and weekday axis.
    val monthHeaderHeight = 17.dp
    val monthStep = 14.dp
    val cell = 11.dp
    val cellCorner = StudioTokens.radiusSm / 2
    LibraryCard {
        LibrarySectionTitle(m["stats.activity.title"])
        LibraryCaption(m["stats.activity.hint"], maxLines = 4)
        Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space2)) {
            Box(Modifier.height(monthHeaderHeight + monthStep * 7)) {
                listOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.FRIDAY).forEach { weekday ->
                    LibraryCaption(weekday.getDisplayName(weekdayFormat, locale),
                        Modifier.offset(y = monthHeaderHeight + monthStep * weekday.ordinal))
                }
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val weeks = floor(maxWidth / monthStep).toInt().coerceIn(1, 53)
                val lastLabelPosition = (maxWidth - StudioTokens.space6).coerceAtLeast(0.dp)
                val start = today.minusDays(today.dayOfWeek.value - 1L).minusWeeks(weeks - 1L)
                val last = (today.toEpochDay() - start.toEpochDay()).toInt()
                var selected by remember(start, today) { mutableIntStateOf(last) }
                val day = start.plusDays(selected.toLong())
                val entry = days[day]
                val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
                val detail = listOfNotNull(dateFormat.format(day), if (entry == null) m["stats.activity.noActivity"] else null,
                    entry?.let { "${m["stats.table.runs"]} ${m.number(it.amount("runs"))}" },
                    entry?.let { "${m["stats.tiles.finished"]} ${m.number(it.amount("finished"))}" },
                    entry?.let { m.metric(it.amount("spent_$suffix"), metric) }).joinToString(" · ")
                Column {
                    val months = (0 until weeks).map { start.plusWeeks(it.toLong()).month }
                    val monthColumns = months.indices.filter { it == 0 || months[it] != months[it - 1] }
                    val labels = if (monthColumns.getOrNull(1)?.let { it < 3 } == true) monthColumns.drop(1) else monthColumns
                    Box(Modifier.fillMaxWidth().height(monthHeaderHeight)) {
                        for (column in labels) LibraryCaption(months[column].getDisplayName(TextStyle.SHORT, locale),
                            Modifier.offset(x = (monthStep * column).coerceAtMost(lastLabelPosition)))
                    }
                    Canvas(Modifier.fillMaxWidth().height(monthStep * 7)
                        .semantics { contentDescription = "${m["stats.activity.title"]}. $detail" }
                        .onPointerEvent(PointerEventType.Move) { event ->
                            val position = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                            val step = monthStep.toPx()
                            val row = (position.y / step).toInt()
                            val column = (position.x / step).toInt()
                            val index = column * 7 + row
                            if (row in 0..6 && column in 0 until weeks && index in 0..last) selected = index
                        }.onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            val offset = when (event.key) {
                                Key.DirectionLeft -> -7
                                Key.DirectionRight -> 7
                                Key.DirectionUp -> -1
                                Key.DirectionDown -> 1
                                else -> return@onKeyEvent false
                            }
                            if ((offset != -1 || selected % 7 > 0) && (offset != 1 || selected % 7 < 6)
                                && selected + offset in 0..last) selected += offset
                            true
                        }.statisticsChartFocus()) {
                        val step = monthStep.toPx()
                        val edge = cell.toPx()
                        val corner = CornerRadius(cellCorner.toPx())
                        for (index in 0..last) {
                            val count = days[start.plusDays(index.toLong())]?.amount("activity") ?: 0.0
                            val level = ceil(4 * count / max).toInt().coerceIn(0, 4)
                            val location = Offset((index / 7) * step, (index % 7) * step)
                            drawRoundRect(shades[level], location, Size(edge, edge), corner)
                            if (index == selected) drawRoundRect(colors.text, location, Size(edge, edge), corner,
                                style = Stroke(StudioTokens.borderWidth.toPx()))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(StudioTokens.space1), verticalAlignment = Alignment.CenterVertically) {
                        LibraryCaption(m["stats.activity.less"])
                        for (shade in shades) Box(Modifier.size(cell).background(shade, RoundedCornerShape(cellCorner)))
                        LibraryCaption(m["stats.activity.more"])
                    }
                    LibraryCaption(detail, maxLines = 2)
                }
            }
        }
    }
}
