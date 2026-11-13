# Screenshot recapture checklist

Every screenshot in this folder was retaken on **2026-11-13** from a debug build
of `main` at `9d7e8cc8`, on the `Pixel_5` AVD (API 34, 1080 × 2340, light theme,
`SEMPER_DEV_AUTH_BYPASS` on, so local-only with the cloud off). Phone screens are
scaled to **460 × 997** (the full screen, no crop); the manual shows them at
`width="300"`, the lab section at `260`, the README at `220`. The lab report
strips are the PDF pages rendered at 900 px high, 16 px apart, each with a
1 px #DDD border.

The runs are the app's own examples (Home **+** → **No photos yet? Try an
example**), so anyone can repeat them:

- **Steel strip in tension** (tensile), its ROI 1960 × 297 untouched: E 149.6 GPa
  (frames 1–26, R² 0.9955), Rp0.2 305.7 MPa, peak 435.50 MPa (frame 38),
  40 of 40 on the curve, out to 337 mε.
- **PMMA beam in 3-point bending**, beam-height taps 571 px (0.0543 mm/px): E
  from the graph 2.00 GPa, average 2.10 GPa.
- **Carbon-fibre plate with a hole** (2D DIC), 11 frames.

The picker and warning shots used images pushed to `/sdcard/Pictures/SemperDocs`
and media-scanned: example frames (steel, PMMA, CFRP), a short PMMA video for the
badge, and two synthetic speckle patterns. One is coarse (it measures 15.8 px)
for the over-resolved warning. The other measures 7.9 px; with the subset slider
dragged to 15 px, it raises the span warning.

| File | State captured | Used in |
|---|---|---|
| `home.png` | Four analyses under **Today**: CFRP (11 frames · 93.3%), the CFRP sweep (9 of 9 solved), PMMA (33 frames · 92.8%), steel (40 frames · 96.1%), each *Not backed up* | Manual §1 |
| `test-type.png` | **Which test?** with 2D DIC first, then Tensile and Bending, and the **Try an example** link | Manual §12, README |
| `new-analysis-source.png` | **New analysis** sheet, Images tab, example frames with a video badge | Manual §4 |
| `media-picker-permission-empty.png` | Images tab before media permission: **Allow access** | Manual §4 |
| `media-picker-files-saf.png` | **Files** tab handing off to the system browser (Downloads) | Manual §4 |
| `speckle-warning.png` | Step 1, coarse speckle reference: "Speckle is about 15.8 px, above the 9 px guide…" | Manual §4 |
| `step1-frames.png` | Step 1, CFRP reference and three deformed frames with order badges | Manual §4 |
| `frame-order-menu.png` | The sort menu over the steel example's 40 frames | Manual §4 |
| `step2-parameters.png` | Steel step 2: ROI 1960 × 297 · 23,128 points, Speckle 4.8 px, subset 19, step 5, strain window 5 (21 px VSG), **Compute · 34 s** | Manual §4 |
| `speckle-span-warning.png` | Step 2 on the 7.9 px pattern, subset dragged to 15: "…at least 25 px here." | Manual §4 |
| `running.png` | Run sheet at *Frame 27 of 40* · 65.9%, convergence bins, **Cancel** | Manual §4 |
| `roi-editor.png` | Draw + Crop on the CFRP plate, one rectangle, HUD "ROI: 310 × 838 px \| Pos: (44, 98)" | Manual §6, README |
| `step3-sweep.png` | Sweep settings: Subset 15–35, Strain window 3–11 (11–121 px VSG), step subset ÷ 3, line cut X, **Run 9 · 2 s** | Manual §7 |
| `result-lattice.png` | 9 combinations · 9 solved · 0 skipped, the focused node ringed, All / Node, Exx | Manual §7 |
| `settings-used.png` | ⓘ sheet on a sweep combination, scrolled to Settings used and the centre line cut | Manual §8 |
| `result-viewer.png` | CFRP frame 11 of 11, Exx, colour bar 1.09 / −3.18 mε, a probed point, chrome shown | Manual §8, README |
| `share-sheet.png` | Share on the steel result: This field … Lab report … Everything | README |
| `home-selection.png` | Two analyses selected, "2 selected", **Select all**, delete | Manual §10 |
| `delete-dialog.png` | Delete with no cloud copy: "Delete steel_00? This can't be undone." | Manual §10 |
| `settings.png` | Settings, every section collapsed, About / Sign out | Manual §10 |
| `step1-tensile.png` | Steel example: 40 frames, `steel_loads.csv` (kN), 12.5 mm², axis X | Manual §12, lab workflow, README |
| `results-tensile.png` | Results with the curve, the Rp0.2 diamond, the Whole test / Elastic region toggle, the adjust icon, and E / Rp0.2 / peak as above | Manual §12, lab workflow, README |
| `step1-bending.png` | PMMA example: 33 frames, `pmma_loads.csv` (N), 75 / 12 / 31 mm, beam height 571 px | Manual §12, lab workflow, README |
| `beam-taps.png` | Beam height editor zoomed: both marks, the probe circle, "31 mm over 571 px · 0.0543 mm/px" | Manual §12, lab workflow, README |
| `results-bending.png` | Load–deflection graph, E 2.00 GPa (slope 690.15 kg/mm, R² 0.9993), average 2.10 GPa | Manual §12, lab workflow, README |
| `lab-report-tensile.png` | All four PDF pages: Frame column, no Extension column, full-width table, mean-Exx Calculation line, yield strength in Results | Manual §12, lab workflow, README |
| `lab-report-bending.png` | All three PDF pages, full-width table | Manual §12, lab workflow, README |

`semper-mark.png` is the logo. `video-extract.png` was deleted on 2026-11-13: nothing
linked to it.

## Still needs a signed-in account with a cloud backup

The emulator's dev bypass has the cloud off, so these need a real account on a
phone (the S22 runs a signed-in debug build):

- `home.png` with mixed cloud icons (**Backed up** / **Upload pending** / **Only in cloud**).
- The delete dialog on a backed-up row (**From this phone** / **From the cloud** / **Everywhere**).
- `settings.png` with **Analyses data management** expanded on a row with Download / Restore / Delete, and the transfer banner.
- The guest sign-in screen with **Continue as guest** (ADR-036) has no screenshot yet; it needs `SEMPER_DEV_AUTH_BYPASS=false`.
