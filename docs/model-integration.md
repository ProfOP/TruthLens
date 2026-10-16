# Model integration

| Component | Artifact/configuration | Semantics |
|---|---|---|
| WELFake | `models/Welfake/results/truthlens_distilbert`; max length 256; exported threshold 0.96 | 0 REAL, 1 FAKE; credibility is P(REAL)×100; truncation is reported. Deployment override `TRUTHLENS_TEXT_CONFIDENCE_THRESHOLD=0.70` is applied at request time and stored separately from exported config. |
| FaceForensics++ | `models/FFC++/truthlens_faceforensics_model`; temperature from saved config; binary threshold 0.42; exported margin 0.135 | AUTHENTIC/MANIPULATED on FF++ facial frames. Deployment override `TRUTHLENS_IMAGE_UNCERTAIN_MARGIN=0.05`. A 0.58 binary threshold was not applied. Confidence is the threshold-selected binary class probability. |
| LIAR | `models/Liar/truthlens_liar_models` | Six experimental labels; similarity is relatedness, not proof |
| FEVER | Iteration 2 index/metadata and `experiment_report_v2.json` `best_parameters` | SUPPORTED/REFUTED/INSUFFICIENT from local evidence; Iteration 2 is selected. Iteration 5 `final_parameters.json` is not used. The deployed `verify()` reproduces the notebook pipeline: NLI premise = page title + evidence text, hypothesis = claim; entailment/contradiction/neutral are read by **name** via `id2label`; the verdict scans the retrieved candidate pool (top-10) and requires the score to clear `min_nli` **and** `min_margin` over the other classes (`predict_verdict_v2`). The selected passage and named per-candidate scores are persisted (`nli_labels`, `selected`). Coverage is the local repository, not live web search. Missing evidence is INSUFFICIENT, not false. Scores are not merged into a fabricated truth probability. |
| SHAP | Live `/v1/explanations/shap` using installed `shap==0.46.0` | Token contributions for text actually processed by WELFake. Bounded `max_evals`. Saved example CSVs are not reused. Status is stored separately (`generated` / `unavailable`). |

Saved reports (dataset/split specific, not live service guarantees, not SRS target attainment):

- WELFake: approximately 99.20% dataset test accuracy (saved report also lists 99.293%).
- FaceForensics++: 76.21% overall binary accuracy, Macro-F1 0.6104, authentic recall 41.43% at the evaluated operating point. Selective accuracy 83.23% applies only to about 69.57% coverage.
- LIAR: 23.30% six-class validation accuracy.
- FEVER Iteration 2: approximately 44.44% accuracy and 0.4239 Macro-F1 on a 360-claim development comparison.

Old metrics do not automatically apply to deployment overrides. The SRS image accuracy target is not claimed as met. Poor predictions remain a model-quality issue; thresholds were not retuned as a substitute.
