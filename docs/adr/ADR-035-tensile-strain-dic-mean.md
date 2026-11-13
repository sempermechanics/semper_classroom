# ADR-035: Tensile strain is the DIC field's mean along the load axis

**Status:** Accepted, built
**Date:** 2026-11-11
**Deciders:** app owner
**Supersedes:** [ADR-012](ADR-012-tensile-strain-virtual-extensometer.md) (tensile strain as the virtual extensometer's ΔL / L₀)

## Context

The classroom app exists to show DIC. Its tensile curve plotted the virtual
extensometer's ΔL / L₀ (ADR-012): the far end band's mean displacement minus
the near band's, over the distance between the bands.

A student asked how to match a point on the graph to that frame's DIC result.
Under ADR-012 they could not. A frame's Details (ⓘ) sheet gives the field's
max, min and mean; the graph's strain was none of them. It came from the
displacement of two end bands, which no screen shows.

The lab report's table made a second problem. Its **Extension (px)** column
sits beside the blank **Gauge length of specimen (mm)** line, and the row-1
calculation read "Strain = ΔL / L₀". A reader divides the extension in pixels
by the gauge length in mm. The report had already been reworded once to say
"px over px" (`49be3954`).

The owner's view: the extensometer is an extra calculation the app does not
need. The strain a student sees on a frame should be the strain on the graph,
and any difference from an extensometer is DIC's own.

## Decision

The tensile strain (`StressStrain.Model.Axial`) is the **mean of the load-axis
strain component (Exx or Eyy) over the accepted points** of the analysed
region, in millistrain:

- `Axial.strainMilli(data)` is `DicResult.fieldStats(…)`'s mean, then the
  session's `CurveCorrection`. `Model.strainMilli` no longer takes a gauge.
  This is the number the Details sheet shows as "mean" for Exx or Eyy on that
  frame, so a point on the graph is that frame's own field statistic. An
  **Adjust curve** correction, when one is set, is applied on top.
- A tensile frame is off the curve only when it has no load or no accepted
  point. A frame whose end band has left the picture stays on the curve.
- The wording follows: `strainName` is "mean Exx" / "mean Eyy"; the DIC PDF's
  curve page reads "Strain: mean Exx over accepted points"; the lab report
  says "Strain measured by = 2D DIC, mean Exx over the analysed region" and
  "Strain = mean Exx over the analysed region, read by DIC for row 1 = …".
- The virtual extensometer is **removed** (`report/Extensometer.kt`,
  `Curve.gauge`, `Point.extensionPx`), and with it the lab report's
  **Extension (px)** column, its "DIC gauge length L₀" line and the
  Calculation's extension line. The table is S.No, Frame, Load (kN),
  Stress (MPa), Strain. A first build of this decision kept the column as a
  second reading; the owner dropped it the same day.
- Bending is unchanged: the outer-fibre strain from the gradient through the
  tapped thickness (ADR-012's 2026-10-19 amendment).

## Options considered

### A: Keep ΔL / L₀ (ADR-012)

**Pros:** engineering strain between two fixed lengths, as ISO 6892-1 and
ASTM E8 define elongation, whatever tensor the engine computes.
**Cons:** not a number any frame shows; the Extension column invites px over
mm; the curve stops when an end band leaves the picture, which on the steel
example is before the peak.

### B: The region mean of Exx / Eyy, with no extensometer (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low: one `fieldStats` call per frame; the gauge is no longer passed to the model |
| Cost | One pass over the points per frame |
| Risk | After necking the value depends on the region drawn; at large strain it may not be engineering strain (below) |

### C: Plot both, or a toggle

**Cons:** two strains and two E values for one test; the student has to know
which one to hand in. Rejected in ADR-012 and again here.

### D: The region mean, with the extensometer kept for the report's Extension column

**Pros:** the handwritten report's table has an Extension column, and
ΔL / L₀ is a check against the mean.
**Cons:** a second number beside the strain that is close to it but not it,
in pixels, next to a gauge length in mm: the confusion this record starts
from. Built first, then dropped by the owner.

## Trade-offs

ADR-012 warned of three things. They are accepted now, not solved.

- **After necking the mean depends on the region.** Once strain is not even,
  the mean depends on how much of the region the neck covers, so two students
  who draw different ROIs on one run get different elongation after the peak.
  It is not comparable with an extensometer's. The elastic part, where E is
  fitted, is not affected.
- **The strain tensor is not stated.** The engine contract
  (`docs/engine/ENGINE_APP_CONTRACT.md` §A.4) fixes the `.dat` layout but does
  not say whether Exx / Eyy is small strain or Green–Lagrange. At a 20 %
  elongation Green–Lagrange reads about 10 % above engineering strain
  (E = e + e²/2). So at large strain the mean may not be engineering strain.
- **The curve no longer stops when an end leaves the picture.** Late frames
  average only the points still in view. On the steel example frame 40 keeps
  16,785 of frame 1's 22,055 accepted points, and its mean reads 12 % above
  the dataset's gauge points.

Also:

- **Noise.** The mean averages every accepted point, not two bands of a tenth
  each, so it is the quieter of the two. On the steel example the first three
  frames fit with R² 0.913 (0.897 under ΔL / L₀).
- **A point matches the frame only before a correction.** With **Adjust
  curve** set, the graph shows scale × mean + bias.

## Consequences

- The curve is not stored; the viewer, share, PDF and CSV rebuild it from the
  `.dat` files. Existing tensile sessions show the new strain, E and Rp0.2 the
  next time they are opened or exported. No migration.
- The CSV format does not change. Its point rows carry the field (per-point
  Exx / Eyy), load and stress, never the curve's strain. The values in the
  `# mechanical_results` trailer change (E, its frames and R², Rp0.2, and the
  peak's `on_curve`).
- A reported peak is off the curve only when its frame has no accepted point.
- `.dat` and GIF oracles are untouched: this is downstream of the solve.
- The steel example (Zenodo 18311953, Galaxy S22+, engine 0.6.1, 2026-11-11;
  [REAL_WORLD_VALIDATION.md](../app/REAL_WORLD_VALIDATION.md)):

  | | ΔL / L₀ (before) | Mean Exx (now) |
  |---|---:|---:|
  | Photos on the curve | 35 of 40 | 40 of 40 |
  | E, frames 1–26 | 148.9 GPa, R² 0.9952 | 149.6 GPa, R² 0.9955 |
  | Rp0.2 | 305.6 MPa at 4.03 mε | 305.7 MPa at 4.02 mε, between frames 29 and 30 |
  | Peak stress, 435.50 MPa at frame 38 | off the curve | on the curve |
  | The curve runs to | 66.9 mε (frame 35) | 337 mε (frame 40) |

  Over frames 13–28 the mean reads 7.1 % above the dataset's gauge points
  (ΔL / L₀: 8.0 %), and E is 5.0 % below their 157.5 GPa (was 5.5 %).
  `RealSteelModulusTest` holds the new curve.
- The screenshots `docs/images/results-tensile.png` and
  `lab-report-tensile.png` were retaken on 2026-11-13 (TD-279, closed).

## Action items

- [x] `Axial` strain from `fieldStats`; `Model.strainMilli` without the gauge;
      report wording (`LabReport`, `LabReportText`); `Extensometer` and the
      Extension column removed; tests (`StressStrainTest`, `LabReportTest`,
      `AnalysisCsvPreambleTest`, `RealSteelModulusTest`).
- [x] Re-run the steel example under the mean and replace the tables
      (2026-11-11, Galaxy S22+).
- [x] Retake the two tensile screenshots (TD-279, done 2026-11-13).
- [ ] ADR-012's option C (a gauge length in mm) stays deferred until tensile
      has a mm scale.
