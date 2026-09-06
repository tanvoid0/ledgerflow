package io.tanvoid0.codecraft.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.tanvoid0.codecraft.CurriculumStore
import io.tanvoid0.codecraft.Lesson
import io.tanvoid0.codecraft.LessonState
import io.tanvoid0.codecraft.Stage
import io.tanvoid0.codecraft.Step
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.awt.geom.QuadCurve2D
import java.awt.geom.RoundRectangle2D
import java.util.Locale
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants

// The board's own two colours, so the dock and the browser page agree about
// what "done" and "where you are" look like in either theme.
private val DONE = JBColor(0x0F6B58, 0x4FC0A5)
private val HERE = JBColor(0x6B3C63, 0xC08FB6)

/** "half a day" is 0.5, "2 weeks" is ten working days. Anything else is 0. */
internal fun estimateDays(text: String?): Double {
    val m = Regex("(half a|[0-9.]+)\\s*(day|week)").find(text.orEmpty()) ?: return 0.0
    val n = if (m.groupValues[1] == "half a") 0.5 else m.groupValues[1].toDoubleOrNull() ?: return 0.0
    return if (m.groupValues[2] == "week") n * 5 else n
}

/**
 * What the system is made of at each step.
 *
 * A step lists its services and infra as the system *so far*: sometimes the
 * whole set, sometimes only what is new (`+ redis`, `all`, `grafana / tempo`).
 * Nothing is ever retired, so a step is the step before it plus what it names
 * — which is what makes a step's dependencies derivable at all, since the file
 * has no dependency field.
 */
internal fun systemPerStep(steps: List<Step>): Map<String, List<String>> {
    val out = LinkedHashMap<String, List<String>>()
    var carried = emptyList<String>()
    steps.forEach { st ->
        val named = (st.services.orEmpty() + st.infra.orEmpty())
            .filterNot { it.trim() == "all" }
            .flatMap { it.trim().removePrefix("+").split("/") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        carried = (carried + named).distinct()
        out[st.id.orEmpty()] = carried
    }
    return out
}

/** Every part of the system against the step it arrives in. */
internal fun arrivals(steps: List<Step>): Map<String, String> {
    val system = systemPerStep(steps)
    val first = LinkedHashMap<String, String>()
    steps.forEach { st ->
        val id = st.id.orEmpty()
        system[id].orEmpty().forEach { first.putIfAbsent(it, id) }
    }
    return first
}

internal class RoadNode(
    val step: Step,
    val bounds: Rectangle,
    val done: Int,
    val total: Int,
    val arriving: List<String>,
    val system: List<String>,
    val lessonsDone: Int,
    val lessonsTotal: Int,
) {
    val complete: Boolean get() = total > 0 && done == total
    val started: Boolean get() = done > 0
}

internal class RoadLane(
    val stage: Stage,
    val bounds: Rectangle,
    val done: Int,
    val total: Int,
    val days: Double,
)

/** One row of the matrix: a part of the system against the steps that run on it. */
internal class TrackRow(val name: String, val arrivesAt: String, val y: Int)

/**
 * The system laid out against the steps, one row per service or piece of
 * infrastructure. Column `i` is step `i`, so a row reads as: nothing, nothing,
 * arrives, and in use from there to the end.
 */
internal class RoadMatrix(
    val x: Int,
    val top: Int,
    val labelW: Int,
    val cellW: Int,
    val rowH: Int,
    val rows: List<TrackRow>,
) {
    fun cellX(i: Int) = x + labelW + i * cellW
    fun rowAt(py: Int): TrackRow? = rows.firstOrNull { py >= it.y && py < it.y + rowH }
}

internal class RoadMap(
    val lanes: List<RoadLane>,
    val nodes: List<RoadNode>,
    /** step id -> the steps that put something this step still runs on into the system */
    val dependsOn: Map<String, List<String>>,
    val height: Int,
    /** Null when the dock is too narrow for a cell to be worth painting. */
    val matrix: RoadMatrix? = null,
) {
    fun node(id: String?): RoadNode? = nodes.firstOrNull { it.step.id == id }
}

/** Every size the layout needs, so it can be laid out and asserted without a UI. */
internal class RoadMetrics(
    val pad: Int,
    val gutter: Int,
    val laneGap: Int,
    val nodeGap: Int,
    val headerH: Int,
    val laneHeadH: Int,
    val rowH: Int,
    val smallH: Int,
    val nodePad: Int,
    val barH: Int,
    val minLane: Int,
)

/**
 * Stages become lanes, steps become nodes on the lane's spine. Lanes are laid
 * out in as many columns as the width allows and in reading order across them,
 * so the same graph works in a 300px dock and in a floated window.
 */
internal fun layoutRoadmap(
    stages: List<Stage>,
    m: RoadMetrics,
    width: Int,
    doneOf: (Step) -> Int,
    lessonsOf: (Step) -> Pair<Int, Int>,
): RoadMap {
    val steps = stages.flatMap { it.steps.orEmpty() }
    val system = systemPerStep(steps)
    val first = arrivals(steps)

    val usable = width - m.pad * 2
    val cols = ((usable + m.laneGap) / (m.minLane + m.laneGap)).coerceIn(1, maxOf(1, stages.size))
    val laneW = (usable - m.laneGap * (cols - 1)) / cols
    val bottom = IntArray(cols) { m.headerH }

    val lanes = ArrayList<RoadLane>()
    val nodes = ArrayList<RoadNode>()
    stages.forEachIndexed { i, stage ->
        val col = i % cols
        val x = m.pad + col * (laneW + m.laneGap)
        val laneTop = bottom[col]
        var y = laneTop + m.laneHeadH
        stage.steps.orEmpty().forEach { st ->
            val id = st.id.orEmpty()
            val arriving = system[id].orEmpty().filter { first[it] == id }
            val (lessonsDone, lessonsTotal) = lessonsOf(st)
            val subline = arriving.isNotEmpty() || lessonsTotal > 0
            val h = m.nodePad * 2 + m.rowH + 4 + m.barH + if (subline) m.smallH + 2 else 0
            nodes += RoadNode(
                st, Rectangle(x + m.gutter, y, laneW - m.gutter, h),
                doneOf(st), st.taskList.size, arriving, system[id].orEmpty(), lessonsDone, lessonsTotal
            )
            y += h + m.nodeGap
        }
        val own = stage.steps.orEmpty()
        lanes += RoadLane(
            stage, Rectangle(x, laneTop, laneW, y - laneTop),
            own.sumOf { doneOf(it) }, own.sumOf { it.taskList.size }, own.sumOf { estimateDays(it.estimate) }
        )
        bottom[col] = y + m.laneGap
    }

    val deps = steps.associate { st ->
        val id = st.id.orEmpty()
        id to system[id].orEmpty().mapNotNull { first[it] }.filter { it != id }.distinct()
    }

    // The matrix under the lanes: one row per part of the system, one column
    // per step. It is dropped rather than squeezed when a column would be too
    // thin to read as a cell.
    var y = (bottom.maxOrNull() ?: m.headerH) + m.laneGap
    val labelW = minOf(m.minLane / 2, usable * 2 / 5)
    val cellW = if (steps.isEmpty()) 0 else (usable - labelW) / steps.size
    val rowH = m.smallH + 3
    val matrix = if (cellW < m.smallH / 2 || steps.isEmpty()) null else {
        val top = y + m.laneHeadH + rowH
        val rows = first.entries.mapIndexed { i, e -> TrackRow(e.key, e.value, top + i * rowH) }
        y = top + rows.size * rowH
        RoadMatrix(m.pad, top - rowH, labelW, cellW, rowH, rows)
    }
    return RoadMap(lanes, nodes, deps, y + m.pad, matrix)
}

/**
 * The whole course at once: the burn-up of tasks over the seventeen steps, a
 * lane per stage, a node per step, and — for the step you are on — a line back
 * to every step that put something into the system this one still runs on.
 * Click a node to work on that step.
 *
 * Painted rather than assembled: seventeen cards with progress bars, a chart
 * and the arcs between them are a picture, not a form, and a picture costs one
 * paint pass instead of a few hundred components in a dock that has to stay
 * responsive while you tick tasks.
 */
class MapView(private val onPick: (Step) -> Unit) : Wrapper() {

    private val canvas = Canvas()
    private val scroll = JBScrollPane(WidthTracking(canvas)).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }

    /**
     * Lesson state is read through a supplier rather than copied in: it changes
     * whenever the learner uses a shortcut, and the map should not need
     * rebuilding to notice.
     */
    fun show(store: CurriculumStore?, lessons: List<Lesson>, states: () -> Map<String, LessonState>) {
        canvas.store = store
        canvas.lessons = lessons.groupBy { it.step }
        canvas.states = states
        setContent(
            if (store == null) emptyState(AllIcons.General.Information, "No curriculum loaded.")
            else scroll
        )
        refresh(canvas.selected)
    }

    /** Numbers or the selected step changed. Cheap - one layout pass and a repaint. */
    fun refresh(selected: Step? = canvas.selected) {
        canvas.selected = selected
        canvas.relayout()
        canvas.repaint()
    }

    internal fun map(): RoadMap? = canvas.map

    private inner class Canvas : JComponent() {

        var store: CurriculumStore? = null
        var lessons: Map<String, List<Lesson>> = emptyMap()
        var states: () -> Map<String, LessonState> = { emptyMap() }
        var selected: Step? = null
        var map: RoadMap? = null
            private set

        private var hover: RoadNode? = null
        private var hoverTrack: String? = null

        init {
            isOpaque = true
            background = UIUtil.getPanelBackground()
            font = JBUI.Fonts.label()
            toolTipText = ""            // without this the tooltip manager never asks
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    nodeAt(e)?.let { onPick(it.step) }
                }

                override fun mouseExited(e: MouseEvent) {
                    hover = null
                    hoverTrack = null
                    repaint()
                }
            })
            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val n = nodeAt(e)
                    val track = trackAt(e)?.name
                    if (n !== hover || track != hoverTrack) {
                        hover = n
                        hoverTrack = track
                        cursor = Cursor.getPredefinedCursor(
                            if (n != null) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR
                        )
                        repaint()
                    }
                }
            })
        }

        // ---- layout ------------------------------------------------------

        private fun metrics(): RoadMetrics {
            val fm = getFontMetrics(font)
            val small = getFontMetrics(smallFont())
            return RoadMetrics(
                pad = JBUI.scale(10),
                gutter = JBUI.scale(16),
                laneGap = JBUI.scale(12),
                nodeGap = JBUI.scale(6),
                headerH = fm.height * 2 + JBUI.scale(66),
                laneHeadH = fm.height + JBUI.scale(12),
                rowH = fm.height,
                smallH = small.height,
                nodePad = JBUI.scale(6),
                barH = JBUI.scale(4),
                minLane = JBUI.scale(260),
            )
        }

        fun relayout() {
            val s = store
            val w = if (width > 0) width else JBUI.scale(320)
            map = if (s == null) null else layoutRoadmap(
                s.curriculum?.stages.orEmpty(), metrics(), w, { s.doneCount(it) }, { lessonCount(it) }
            )
            revalidate()
        }

        /** Lessons settled (used or skipped) against lessons armed for that step. */
        private fun lessonCount(step: Step): Pair<Int, Int> {
            val armed = lessons[step.id].orEmpty()
            if (armed.isEmpty()) return 0 to 0
            val st = states()
            return armed.count { st[it.id] != null && st[it.id] != LessonState.PENDING } to armed.size
        }

        override fun getPreferredSize(): Dimension =
            Dimension(JBUI.scale(220), map?.height ?: JBUI.scale(120))

        override fun setBounds(x: Int, y: Int, w: Int, h: Int) {
            val resized = w != width
            super.setBounds(x, y, w, h)
            if (resized) relayout()
        }

        private fun nodeAt(e: MouseEvent): RoadNode? = map?.nodes?.firstOrNull { it.bounds.contains(e.point) }

        private fun trackAt(e: MouseEvent): TrackRow? {
            val m = map ?: return null
            val mx = m.matrix ?: return null
            if (e.x < mx.x || e.x > mx.cellX(m.nodes.size)) return null
            return mx.rowAt(e.y)
        }

        override fun getToolTipText(e: MouseEvent): String? {
            trackAt(e)?.let { row ->
                val on = map?.nodes.orEmpty().count { it.system.contains(row.name) }
                return "<html><b>${row.name}</b><br>arrives in step ${row.arrivesAt}, " +
                    "and every one of the $on steps from there runs on it</html>"
            }
            val n = nodeAt(e) ?: return null
            return buildString {
                append("<html><b>${n.step.id.orEmpty()}  ${n.step.title.orEmpty()}</b><br>")
                append("${n.done}/${n.total} tasks")
                n.step.estimate?.let { append(" &middot; $it") }
                if (n.lessonsTotal > 0) append(" &middot; ${n.lessonsDone}/${n.lessonsTotal} lessons")
                append("<br>runs on ${n.system.joinToString(", ").ifEmpty { "nothing yet" }}</html>")
            }
        }

        // ---- painting ----------------------------------------------------

        private fun smallFont(): Font = JBUI.Fonts.smallFont()

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.color = background
                g2.fillRect(0, 0, width, height)
                val s = store ?: return
                val m = map ?: return
                GraphicsUtil.setupAAPainting(g2)
                header(g2, s, m)
                m.lanes.forEach { lane(g2, it) }
                edges(g2, m)
                m.nodes.forEach { node(g2, it) }
                dim(g2, m)
                matrix(g2, m)
            } finally {
                g2.dispose()
            }
        }

        private fun header(g2: Graphics2D, s: CurriculumStore, m: RoadMap) {
            val (done, total) = s.totals()
            val steps = s.steps
            val complete = steps.count { s.isComplete(it) }
            val left = steps.filterNot { s.isComplete(it) }.sumOf { estimateDays(it.estimate) }
            val pad = JBUI.scale(10)
            val fm = getFontMetrics(font)

            g2.font = font.deriveFont(Font.BOLD)
            g2.color = UIUtil.getLabelForeground()
            val pct = if (total > 0) done * 100 / total else 0
            g2.drawString("$pct%   $done/$total tasks", pad, pad + fm.ascent)

            g2.font = smallFont()
            g2.color = UIUtil.getContextHelpForeground()
            val lessons = m.nodes.sumOf { it.lessonsTotal }
            val lessonsDone = m.nodes.sumOf { it.lessonsDone }
            val line = buildString {
                append("$complete/${steps.size} steps  ·  ${days(left)} of work left")
                if (lessons > 0) append("  ·  $lessonsDone/$lessons lessons")
            }
            g2.drawString(line, pad, pad + fm.height + getFontMetrics(smallFont()).ascent + JBUI.scale(2))

            val top = pad + fm.height + getFontMetrics(smallFont()).height + JBUI.scale(8)
            burnup(g2, s, Rectangle(pad, top, width - pad * 2, JBUI.scale(44)))
        }

        /**
         * Cumulative tasks: the dashed line is what the course asks of you, the
         * filled curve is what you have ticked, and the gap between them at the
         * right is the work left.
         */
        private fun burnup(g2: Graphics2D, s: CurriculumStore, r: Rectangle) {
            val steps = s.steps
            if (steps.isEmpty() || r.width <= 0) return
            val total = steps.sumOf { it.taskList.size }.coerceAtLeast(1)
            val x = { i: Int -> r.x + r.width.toFloat() * i / steps.size }
            val y = { v: Int -> r.y + r.height - r.height.toFloat() * v / total }

            val plan = Path2D.Float().apply { moveTo(x(0), y(0)) }
            val ticked = Path2D.Float().apply { moveTo(x(0), y(0)) }
            var cp = 0
            var cd = 0
            steps.forEachIndexed { i, st ->
                cp += st.taskList.size
                cd += s.doneCount(st)
                plan.lineTo(x(i + 1), y(cp))
                ticked.lineTo(x(i + 1), y(cd))
            }

            g2.color = JBColor.border()
            g2.stroke = BasicStroke(1f)
            g2.drawLine(r.x, r.y + r.height, r.x + r.width, r.y + r.height)

            val area = Path2D.Float(ticked).apply {
                lineTo(x(steps.size), y(0))
                closePath()
            }
            g2.color = ColorUtil.withAlpha(DONE, 0.18)
            g2.fill(area)

            g2.color = UIUtil.getContextHelpForeground()
            g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(3f, 3f), 0f)
            g2.draw(plan)

            g2.color = DONE
            g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
            g2.draw(ticked)

            val at = steps.indexOfFirst { !s.isComplete(it) }
            if (at >= 0) {
                g2.color = HERE
                g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(2f, 2f), 0f)
                val cx = (x(at) + x(at + 1)) / 2
                g2.drawLine(cx.toInt(), r.y, cx.toInt(), r.y + r.height)
            }
            g2.stroke = BasicStroke(1f)
        }

        private fun lane(g2: Graphics2D, lane: RoadLane) {
            val fm = getFontMetrics(font)
            val b = lane.bounds
            val baseline = b.y + fm.ascent

            g2.font = font.deriveFont(Font.BOLD)
            g2.color = DONE
            val letter = lane.stage.letter.orEmpty()
            g2.drawString(letter, b.x, baseline)
            val nameX = b.x + fm.stringWidth(letter) + JBUI.scale(8)

            g2.font = smallFont()
            val counts = "${lane.done}/${lane.total}"
            val countW = getFontMetrics(smallFont()).stringWidth(counts)
            g2.color = UIUtil.getContextHelpForeground()
            g2.drawString(counts, b.x + b.width - countW, baseline)

            g2.font = font.deriveFont(Font.BOLD)
            g2.color = UIUtil.getLabelForeground()
            g2.drawString(
                fit(fm, lane.stage.name.orEmpty(), b.width - (nameX - b.x) - countW - JBUI.scale(8)),
                nameX, baseline
            )

            val ruleY = b.y + fm.height + JBUI.scale(4)
            g2.color = JBColor.border()
            g2.drawLine(b.x, ruleY, b.x + b.width, ruleY)
            // the spine every node in this lane hangs off
            g2.drawLine(b.x + JBUI.scale(5), ruleY + JBUI.scale(8), b.x + JBUI.scale(5), b.y + b.height - JBUI.scale(10))
        }

        private fun node(g2: Graphics2D, n: RoadNode) {
            val b = n.bounds
            val fm = getFontMetrics(font)
            val smallFm = getFontMetrics(smallFont())
            val isSelected = n.step === selected
            val isDep = selected != null && map?.dependsOn?.get(selected?.id).orEmpty().contains(n.step.id)

            g2.color = UIUtil.getListBackground()
            g2.fill(RoundRectangle2D.Float(
                b.x.toFloat(), b.y.toFloat(), b.width.toFloat(), b.height.toFloat(),
                JBUI.scale(7).toFloat(), JBUI.scale(7).toFloat()
            ))
            g2.color = when {
                isSelected -> HERE
                isDep -> ColorUtil.withAlpha(HERE, 0.55)
                n === hover -> UIUtil.getLabelForeground()
                else -> JBColor.border()
            }
            g2.stroke = BasicStroke(if (isSelected) JBUI.scale(2).toFloat() else 1f)
            g2.draw(RoundRectangle2D.Float(
                b.x.toFloat(), b.y.toFloat(), b.width.toFloat(), b.height.toFloat(),
                JBUI.scale(7).toFloat(), JBUI.scale(7).toFloat()
            ))
            g2.stroke = BasicStroke(1f)

            // the dot on the lane's spine: filled when the step is finished,
            // ringed while it is under way, hollow before you get there
            val d = JBUI.scale(9)
            val cx = b.x - JBUI.scale(11)
            val cy = b.y + JBUI.scale(12)
            g2.color = if (n.complete || n.started) DONE else JBColor.border()
            if (n.complete) g2.fillOval(cx, cy, d, d) else {
                g2.color = background
                g2.fillOval(cx, cy, d, d)
                g2.color = if (n.started) DONE else JBColor.border()
                g2.drawOval(cx, cy, d, d)
                if (n.started) g2.fillOval(cx + JBUI.scale(3), cy + JBUI.scale(3), d - JBUI.scale(6), d - JBUI.scale(6))
            }

            val left = b.x + JBUI.scale(9)
            val right = b.x + b.width - JBUI.scale(9)
            val baseline = b.y + JBUI.scale(6) + fm.ascent

            g2.font = smallFont()
            g2.color = UIUtil.getContextHelpForeground()
            val id = n.step.id.orEmpty()
            g2.drawString(id, left, baseline)
            val idW = smallFm.stringWidth(id) + JBUI.scale(7)

            val counts = "${n.done}/${n.total}"
            val countW = smallFm.stringWidth(counts)
            g2.color = if (n.complete) DONE else UIUtil.getContextHelpForeground()
            g2.drawString(counts, right - countW, baseline)

            g2.font = font.deriveFont(Font.BOLD)
            g2.color = UIUtil.getLabelForeground()
            g2.drawString(
                fit(fm, n.step.title.orEmpty(), right - countW - JBUI.scale(8) - (left + idW)),
                left + idW, baseline
            )

            val barY = b.y + JBUI.scale(6) + fm.height + 4
            val barW = right - left
            g2.color = JBColor.border()
            g2.fillRect(left, barY, barW, JBUI.scale(4))
            if (n.done > 0 && n.total > 0) {
                g2.color = DONE
                g2.fillRect(left, barY, barW * n.done / n.total, JBUI.scale(4))
            }

            if (n.arriving.isNotEmpty() || n.lessonsTotal > 0) {
                val bits = ArrayList<String>()
                if (n.arriving.isNotEmpty()) bits += "+ " + n.arriving.joinToString(", ")
                if (n.lessonsTotal > 0) bits += "${n.lessonsDone}/${n.lessonsTotal} lessons"
                g2.font = smallFont()
                g2.color = if (n.arriving.isNotEmpty()) DONE else UIUtil.getContextHelpForeground()
                g2.drawString(
                    fit(smallFm, bits.joinToString("  ·  "), barW),
                    left, barY + JBUI.scale(4) + 2 + smallFm.ascent
                )
            }
        }

        /**
         * Every part of the system against the steps that run on it: the row is
         * the thing, the solid cell is where it arrives, and it never leaves
         * again. Hovering a row is the other half of the link the graph draws
         * one step at a time.
         */
        private fun matrix(g2: Graphics2D, m: RoadMap) {
            val mx = m.matrix ?: return
            val fm = getFontMetrics(font)
            val small = getFontMetrics(smallFont())
            val cell = maxOf(mx.cellW - JBUI.scale(2), JBUI.scale(2))

            g2.font = font.deriveFont(Font.BOLD)
            g2.color = UIUtil.getLabelForeground()
            g2.drawString("What each step runs on", mx.x, mx.top - fm.height - JBUI.scale(10) + fm.ascent)
            g2.color = JBColor.border()
            g2.drawLine(mx.x, mx.top - JBUI.scale(4), mx.x + mx.labelW + mx.cellW * m.nodes.size, mx.top - JBUI.scale(4))

            // Step numbers over the columns, but only where one fits.
            g2.font = smallFont()
            if (mx.cellW >= small.stringWidth("00") + JBUI.scale(3)) {
                m.nodes.forEachIndexed { i, n ->
                    val id = n.step.id.orEmpty()
                    g2.color = if (n.step === selected) HERE else UIUtil.getContextHelpForeground()
                    g2.drawString(id, mx.cellX(i) + (cell - small.stringWidth(id)) / 2, mx.top + small.ascent)
                }
            }

            mx.rows.forEach { row ->
                val lit = row.name == hoverTrack
                g2.color = if (lit) HERE else UIUtil.getContextHelpForeground()
                g2.drawString(fit(small, row.name, mx.labelW - JBUI.scale(6)), mx.x, row.y + small.ascent)
                m.nodes.forEachIndexed { i, n ->
                    if (!n.system.contains(row.name)) {
                        g2.color = ColorUtil.withAlpha(JBColor.border(), 0.4)
                    } else {
                        val base = if (n.complete) DONE else HERE
                        g2.color = if (row.arrivesAt == n.step.id) base else ColorUtil.withAlpha(base, if (lit) 0.5 else 0.25)
                    }
                    g2.fillRect(mx.cellX(i), row.y + 1, cell, mx.rowH - JBUI.scale(3))
                }
            }
        }

        /** Hovering a matrix row leaves only the steps that run on it lit. */
        private fun dim(g2: Graphics2D, m: RoadMap) {
            val track = hoverTrack ?: return
            g2.color = ColorUtil.withAlpha(background, 0.7)
            m.nodes.filterNot { it.system.contains(track) }.forEach {
                g2.fillRect(it.bounds.x, it.bounds.y, it.bounds.width, it.bounds.height)
            }
        }

        /**
         * The links the data file never states: the selected step drawn back to
         * every step that introduced something it is still running on.
         */
        private fun edges(g2: Graphics2D, m: RoadMap) {
            val to = m.node(selected?.id) ?: return
            val deps = m.dependsOn[selected?.id].orEmpty().mapNotNull { m.node(it) }
            if (deps.isEmpty()) return
            g2.color = ColorUtil.withAlpha(HERE, 0.45)
            g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
            val ty = (to.bounds.y + JBUI.scale(16)).toFloat()
            deps.forEach { from ->
                val fy = (from.bounds.y + JBUI.scale(16)).toFloat()
                val bend = minOf(from.bounds.x, to.bounds.x) - JBUI.scale(13).toFloat()
                g2.draw(
                    QuadCurve2D.Float(
                        from.bounds.x.toFloat(), fy,
                        bend - JBUI.scale(6), (fy + ty) / 2,
                        to.bounds.x.toFloat(), ty
                    )
                )
            }
            g2.stroke = BasicStroke(1f)
        }

        private fun fit(fm: FontMetrics, text: String, width: Int): String {
            if (width <= 0) return ""
            if (fm.stringWidth(text) <= width) return text
            val ell = "…"
            var end = text.length
            while (end > 0 && fm.stringWidth(text.substring(0, end) + ell) > width) end--
            return text.substring(0, end) + ell
        }

        private fun days(d: Double): String =
            if (d % 1.0 == 0.0) "${d.toInt()}d" else String.format(Locale.ROOT, "%.1fd", d)
    }
}
