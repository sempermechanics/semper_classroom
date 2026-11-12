# Engine validation record

One row per dataset per engine pin: what was compared against a reference, the
bound, the result, and where the evidence is. Every engine bump adds rows here in
the bump PR ([TESTING.md](TESTING.md), Bumping the engine pin). A row says what a
run showed on that date; it does not carry forward to a later pin.

The comparisons themselves are engine tests in the submodule
(`engine/tests/dice/`, `engine/tests/integration/`); the engine repo's CI runs the
DICe ones on every commit. The DIC Challenge sweep and the real-data checks (PMMA,
steel) run only by hand: the sweep needs `SEMPER_RUN_SEEDBENCH=1` and
`SEMPER_DICE_REPO` set to a `dicengine/dice` checkout
(`engine/tests/integration/test_seeding_bench.cpp:120-128`), and the real-data
scripts live in material_testing ([FORK_SYNC.md](../ops/FORK_SYNC.md)).

## v0.6.1 (`f072da0`), pinned by #470

| Date | Dataset | Metric and bound | Result | Evidence |
|---|---|---|---|---|
| 2026-10-22 | DICe `dic_challenge_12` (open-hole tension, CFRP), step 01 | u, v RMS against `DICe_solution_01.txt` below 0.005 px, max below 0.02 px, at least 95 % of points compared (`engine/tests/dice/test_field_agreement.cpp:54-56`) | Pass | Engine CI run 39769086078 on `f072da0` (push to `main`): Host tests, Sanitized (asan-ubsan) and Sanitized (tsan) succeeded |
| 2026-10-22 | DICe `dic_challenge_12`, steps 03, 06, 11 | RMS against `DICe_solution_{03,06,11}.txt` below 0.05 px, at least 90 % compared (`engine/tests/dice/test_field_agreement.cpp:162-163`) | Pass | Same run |
| 2026-11-12 | DICe `dic_challenge_12`, steps 01, 03, 06, 11, by hand | As the two rows above | Pass: 230 / 230 compared at every step; u, v RMS 0.0006 / 0.0006 px (step 01, max 0.0033), 0.0011 / 0.0010 (03), 0.0014 / 0.0017 (06), 0.0024 / 0.0029 (11, max 0.0215) | `dic_tests DiceFieldAgreement` built from the pinned submodule in WSL Ubuntu 22.04 (GCC, Release, `-DSEMPER_OPENCL=OFF`) |
| 2026-11-12 | The app's 2D DIC example `dice_oht_series` (all 12 images of `dic_challenge_12`), steps 01–11, by hand | Medians of u, v, eyy over the accepted points (corr ≤ 0.15) against DICe's medians over its 230 points; no bound, recorded for the example's "What to expect" | Subset 17, step 5, 21 px window (what Try it proposes on a Galaxy S22+, speckle 5 px), ROI (40, 40, 320, 950), fixed reference: about 11 300 points a frame, all accepted; median v within 0.06 px of DICe at every step (−0.62 vs −0.63 px at 01, −8.69 vs −8.64 at 11), u within 0.03 px, eyy 6.68 vs 6.78 × 10⁻³ at 11. The S22+'s own Try it run (arm64 debug build, 3.5 s) gave the same frame-11 medians to the digit (v −8.69, u −0.72, eyy +6.68 × 10⁻³). Per-step table in `app/src/main/assets/examples/dice_oht_series/README.md` | The engine's C API (`semper_run`, `libsemper_c` of the same WSL build), a throwaway driver not in the repo |
| — | DIC Challenge sample 5 (`test_seeding_bench.cpp`) | as in the test | **Not recorded at this pin** | Owed: someone with `SEMPER_DICE_REPO` runs it at `f072da0` and adds the row |
| — | Real data: PMMA 3-point bend, `steel_24` | as in material_testing's scripts | **Not recorded at this pin** | `engine/CHANGELOG.md` 0.6.1 cites PMMA numbers without a date or run id; owed as above |

The DICe agreement bounds are inter-code agreement, not correctness: the
experiment has no analytic truth (`engine/tests/dice/test_field_agreement.cpp:8-11`).
