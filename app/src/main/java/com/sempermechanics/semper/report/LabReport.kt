package com.sempermechanics.semper.report

import com.sempermechanics.semper.data.mechanical.CurveCorrection
import com.sempermechanics.semper.report.LabReportFormat.frameCell
import com.sempermechanics.semper.report.LabReportFormat.gpa
import com.sempermechanics.semper.report.LabReportFormat.num
import com.sempermechanics.semper.report.LabReportFormat.sci
import java.util.Locale

/**
 * The student lab report: the journal write-up a first-semester lab asks for,
 * section by section in the order the handwritten reports use, filled with
 * this session's numbers. Fixed wording is in [LabReportText]; values the app
 * cannot know (a final diameter measured after fracture, a gauge length in
 * mm) keep their place as blank lines for the student to fill in by hand.
 *
 * Pure — a [Document] is an ordered list of [Block]s that [LabReportPdf]
 * draws and the unit tests read. Graph blocks carry their series; the caller
 * renders them (the same off-screen plot the full report uses) and hands the
 * bitmaps to the PDF.
 */
object LabReport {

    data class Document(val experiment: String, val title: String, val blocks: List<Block>) {
        /** Section headings in order — what the layout tests compare against the originals. */
        val headings: List<String> get() = blocks.filterIsInstance<Block.Heading>().map { it.text }

        val graphs: List<Block.Graph> get() = blocks.filterIsInstance<Block.Graph>()
    }

    sealed class Block {
        /** A section heading, e.g. "Aim". */
        data class Heading(val text: String) : Block()

        data class Paragraph(val text: String) : Block()

        /** Numbered steps, e.g. a procedure (a), (b), … */
        data class Steps(val items: List<String>) : Block()

        /** "Label = value"; a null value is a blank line to fill in by hand. */
        data class Field(val label: String, val value: String?) : Block()

        /** The reference photo, where the original has its setup sketch. */
        data class Figure(val caption: String) : Block()

        /** The observation table; [weights] share the line's width between the columns. */
        data class Table(
            val headers: List<String>,
            val rows: List<List<String>>,
            val weights: List<Float>,
        ) : Block()

        /** A worked calculation, one line per step. */
        data class Calculation(val lines: List<String>) : Block()

        data class Graph(
            val id: String,
            val title: String,
            val xLabel: String,
            val yLabel: String,
            val series: List<Series>,
            /** Boxed on the plot, e.g. "E = 194.0 GPa". */
            val annotation: String?,
            /** Points called out with a marker and a legend entry, e.g. the yield point. */
            val marks: List<Mark> = emptyList(),
        ) : Block()

        /** Ruled blank lines, e.g. for the student's own conclusion. */
        data class RuledLines(val count: Int) : Block()
    }

    data class Series(val points: List<Pair<Float, Float>>, val isFit: Boolean)

    /** A point called out on a [Block.Graph], with its legend [label]. */
    data class Mark(val x: Float, val y: Float, val label: String)

    const val GRAPH_ELASTIC = "elastic"
    const val GRAPH_FULL = "full"

    /**
     * The document for a session's curve, or null when there is nothing to
     * report: an empty curve, or bending without the load point tapped (no
     * deflection, so none of the lab's numbers).
     */
    fun of(curve: StressStrain.Curve, modulus: ElasticModulus.Fit?): Document? {
        if (curve.isEmpty) return null
        return when (val model = curve.model) {
            is StressStrain.Model.Axial -> tensile(curve, model, modulus)
            is StressStrain.Model.Flexural ->
                BeamDeflection.summarize(curve)?.let { LabReportBending.document(model, it) }
        }
    }

    // ── tensile: Experiment "Measurement of tensile strains and modulus of elasticity" ──

    private fun tensile(
        curve: StressStrain.Curve,
        model: StressStrain.Model.Axial,
        modulus: ElasticModulus.Fit?,
    ): Document {
        val t = LabReportText.Tensile
        val first = curve.points.first()
        val results = TensileResults.of(curve, modulus)
        return Document(
            experiment = LabReportText.EXPERIMENT,
            title = t.TITLE,
            blocks = buildList {
                add(Block.Heading(LabReportText.AIM))
                add(Block.Paragraph(t.AIM))
                add(Block.Heading(t.MATERIALS_HEADING))
                add(Block.Paragraph(t.MATERIALS))
                add(Block.Heading(LabReportText.THEORY))
                t.THEORY.forEach { add(Block.Paragraph(it)) }
                add(Block.Figure(t.FIGURE))
                add(Block.Heading(LabReportText.OBSERVATIONS))
                add(Block.Field(t.TOTAL_LENGTH, null))
                add(Block.Field(t.GAUGE_LENGTH, null))
                add(Block.Field(t.DIAMETER, null))
                add(Block.Field(t.AREA, num(model.areaMm2, 2)))
                add(Block.Field(t.STRAIN_BY, t.strainBy(model.strainName)))
                model.correction.takeUnless { it.isNone }?.let { add(Block.Field(t.CORRECTION, t.correction(it))) }
                add(Block.Field(t.FINAL_DIAMETER, null))
                add(Block.Field(t.FINAL_GAUGE_LENGTH, null))
                add(tensileTable(curve))
                add(Block.Heading(LabReportText.CALCULATION))
                add(
                    Block.Calculation(
                        listOf(
                            t.stressLine(
                                num(first.loadN / NEWTONS_PER_KN, LOAD_DECIMALS),
                                num(model.areaMm2, 2),
                                num(model.correction.removeStress(first.stressMPa), 2),
                            ),
                            t.strainLine(
                                model.strainName,
                                sci(model.correction.removeStrain(first.strainMilli) / MILLI),
                            ),
                        ) + correctedLine(model.correction, first),
                    ),
                )
                addAll(tensileGraphs(curve, results))
                add(Block.Heading(LabReportText.RESULTS))
                add(Block.Field(t.RESULT_E, modulus?.let { modulusSummary(it) } ?: t.NO_FIT))
                modulus?.takeIf { it.modulusGPa > 0f }?.let {
                    val point = results.yieldPoint
                    add(Block.Field(t.RESULT_YIELD, point?.let { p -> "${num(p.stressMPa, 2)} MPa" } ?: t.NO_YIELD))
                }
                results.peak?.let { add(Block.Field(t.RESULT_PEAK, t.peakValue(num(it.stressMPa, 2), it))) }
                add(Block.Paragraph(LabReportText.APPROXIMATE_NOTE))
                add(Block.Heading(LabReportText.CONCLUSIONS))
                add(Block.RuledLines(CONCLUSION_LINES))
            },
        )
    }

    /** The row-1 values after the hand-entered match; none when there is no match. */
    private fun correctedLine(c: CurveCorrection, first: StressStrain.Point): List<String> =
        if (c.isNone) {
            emptyList()
        } else {
            listOf(LabReportText.Tensile.correctedLine(num(first.stressMPa, 2), sci(first.strainMilli / MILLI)))
        }

    private fun tensileTable(curve: StressStrain.Curve): Block.Table {
        val t = LabReportText.Tensile
        val rows = curve.points.mapIndexed { i, p ->
            listOf(
                "${i + 1}",
                frameCell(p.frame),
                num(p.loadN / NEWTONS_PER_KN, LOAD_DECIMALS),
                num(p.stressMPa, 2),
                sci(p.strainMilli / MILLI),
            )
        }
        return Block.Table(headers = t.TABLE_HEADERS, rows = rows, weights = t.TABLE_WEIGHTS)
    }

    private fun tensileGraphs(curve: StressStrain.Curve, results: TensileResults): List<Block> {
        val t = LabReportText.Tensile
        val modulus = results.modulus
        val fitSeries = results.fitLine?.let { Series(it, isFit = true) }
        val annotation = modulus?.let { "E = ${gpa(it.modulusGPa)} GPa" }
        val yieldMark = results.yieldPoint
            ?.let { Mark(it.strainMilli, it.stressMPa, t.yieldMark(num(it.stressMPa, 1))) }
        return buildList {
            if (modulus != null && fitSeries != null) {
                val elastic = curve.points.filter { modulus.covers(it.frame) }.map { it.strainMilli to it.stressMPa }
                add(
                    Block.Graph(
                        GRAPH_ELASTIC,
                        t.GRAPH_ELASTIC,
                        LabReportText.AXIS_STRAIN,
                        LabReportText.AXIS_STRESS,
                        listOf(Series(elastic, isFit = false), fitSeries),
                        annotation,
                    ),
                )
            }
            add(
                Block.Graph(
                    GRAPH_FULL,
                    t.GRAPH_FULL,
                    LabReportText.AXIS_STRAIN,
                    LabReportText.AXIS_STRESS,
                    listOfNotNull(Series(curve.plotPoints(), isFit = false), fitSeries),
                    annotation,
                    listOfNotNull(yieldMark),
                ),
            )
        }
    }

    /** "194.0 GPa (frames 1–12, R² 0.9989)" — the one wording for E everywhere. */
    fun modulusSummary(fit: ElasticModulus.Fit): String =
        "${gpa(fit.modulusGPa)} GPa (frames ${fit.firstFrame + 1}–${fit.lastFrame + 1}, " +
            "R² ${String.format(Locale.US, "%.4f", fit.r2)})"

    private const val NEWTONS_PER_KN = 1000f
    private const val MILLI = 1000f
    private const val CONCLUSION_LINES = 6
    private const val LOAD_DECIMALS = 3
    const val BLANK_CELL = "—"
}
