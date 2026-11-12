# Carbon-fibre plate with a hole (DICe test images)

Twelve images of an open-hole tension specimen of carbon-fibre composite being
pulled, from the `dic_challenge_12` regression test of the Digital Image
Correlation Engine (DICe), Sandia National Laboratories. `oht_cfrp_00.tiff` is
the reference and `oht_cfrp_01.tiff` to `oht_cfrp_11.tiff` the next eleven load
steps. The images are 400 × 1040 px, 8-bit.

**In semper Classroom:** + → *Try an example* (or Settings → Help → Example data) → this set. **Try it** opens a new 2D DIC analysis with all twelve images in; **Save to phone** copies this folder to `Download/semper examples/`.

Credit: [CREDIT.txt](CREDIT.txt). Licence: BSD-3-Clause, full text in [LICENSE.DICe](LICENSE.DICe), which has to travel with these files.

## Steps

1. New analysis → 2D DIC.
2. Reference: `oht_cfrp_00.tiff`. Deformed frames: `oht_cfrp_01.tiff` to
   `oht_cfrp_11.tiff`, in name order.
3. ROI: the plate, about x 40–360, y 40–990 px. Keep the app's defaults
   (subset 41 px, step 5 px, a 5-point strain window).
4. Compute, then scrub through the frames and look at V and Eyy.

## Expected

DICe's own solution for each frame is in `DICe_solution_01.txt` to
`DICe_solution_11.txt` (x, y, u, v, strain, at 230 points on a 35 px grid,
subset 27 px, every frame against `oht_cfrp_00.tiff`). The medians over those
points, and this app's over its own grid with the settings above (engine
0.6.1, about 11 500 points a frame):

| Frame | Median u (px), DICe | app | Median v (px), DICe | app | Median eyy (× 10⁻³), DICe | app |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | −0.10 | −0.11 | −0.63 | −0.62 | +0.48 | +0.42 |
| 2 | −0.17 | −0.19 | −1.34 | −1.37 | +1.21 | +1.10 |
| 3 | −0.28 | −0.30 | −2.23 | −2.26 | +1.83 | +1.58 |
| 4 | −0.42 | −0.42 | −2.99 | −3.01 | +2.65 | +2.51 |
| 5 | −0.48 | −0.48 | −3.87 | −3.88 | +3.30 | +3.12 |
| 6 | −0.51 | −0.51 | −4.69 | −4.70 | +3.86 | +3.69 |
| 7 | −0.55 | −0.55 | −5.58 | −5.62 | +4.59 | +4.35 |
| 8 | −0.58 | −0.57 | −6.56 | −6.60 | +5.28 | +5.27 |
| 9 | −0.64 | −0.62 | −7.56 | −7.61 | +6.11 | +5.92 |
| 10 | −0.78 | −0.76 | −8.57 | −8.63 | +6.65 | +6.51 |
| 11 | −0.75 | −0.72 | −8.64 | −8.69 | +6.78 | +6.74 |

On the last frame DICe's v runs from −12.03 to −5.01 px, the app's from −12.16
to −4.94. The medians are over different points, so they agree to a few
hundredths of a pixel, not exactly; at DICe's own 230 points the engine's u and
v differ from DICe's by 0.003 px RMS on frame 11 (`docs/engine/VALIDATION.md`
in the app's repository). Strain differs more, since each code smooths it over
its own window (DICe 360 px, the app 21 px).

The plate is pulled along y, so v grows frame by frame and changes along the
plate; the hole concentrates strain beside it. Frames 10 and 11 are nearly the
same load.
