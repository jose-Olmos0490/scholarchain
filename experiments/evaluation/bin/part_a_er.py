"""Item 1 tables and plot from the raw output of the Phase 5 evaluator (gold-eval-results.csv and
summary.json). Bootstrap intervals are computed here because the Phase 5 tooling reports Wilson
intervals only. Counting rules are the tooling's own: only ACCEPTED is a predicted match, and
UNSURE is excluded."""

import json
import os
import random

from part_a_common import read_csv, svg_intervals, write_csv

SEED = 20261125
RESAMPLES = 1000
GOLD_TARGET = 200
PATHS = ("EXACT_ID", "DETERMINISTIC_BIBLIOGRAPHIC", "PROBABILISTIC", "LLM_ADJUDICATED")
OUTCOMES = ("ACCEPTED", "REJECTED", "UNRESOLVED", "CONFLICT")
METRICS = ("precision", "recall", "f1", "specificity", "coverage", "unresolved_rate", "conflict_rate")


def counts(rows):
    c = {(g, o): 0 for g in ("SAME", "DIFFERENT") for o in OUTCOMES}
    for label, outcome in rows:
        c[(label, outcome)] += 1
    same = sum(c[("SAME", o)] for o in OUTCOMES)
    diff = sum(c[("DIFFERENT", o)] for o in OUTCOMES)
    tp, fp = c[("SAME", "ACCEPTED")], c[("DIFFERENT", "ACCEPTED")]
    return c, same, diff, tp, fp, same - tp, diff - fp


def ratio(num, den):
    return num / den if den else None


def metrics(rows):
    c, same, diff, tp, fp, fn, tn = counts(rows)
    n = same + diff
    precision, recall = ratio(tp, tp + fp), ratio(tp, tp + fn)
    f1 = None
    if precision is not None and recall is not None and precision + recall > 0:
        f1 = 2 * precision * recall / (precision + recall)
    return {
        "precision": precision, "recall": recall, "f1": f1, "specificity": ratio(tn, tn + fp),
        "coverage": ratio(sum(c[(g, o)] for g in ("SAME", "DIFFERENT") for o in ("ACCEPTED", "REJECTED")), n),
        "unresolved_rate": ratio(c[("SAME", "UNRESOLVED")] + c[("DIFFERENT", "UNRESOLVED")], n),
        "conflict_rate": ratio(c[("SAME", "CONFLICT")] + c[("DIFFERENT", "CONFLICT")], n),
    }


def percentile(values, q):
    """Linear interpolation between order statistics."""
    values = sorted(values)
    position = q * (len(values) - 1)
    low = int(position)
    high = min(low + 1, len(values) - 1)
    return values[low] + (values[high] - values[low]) * (position - low)


def bootstrap(rows, group):
    rng = random.Random("%d:%s" % (SEED, group))
    draws = {m: [] for m in METRICS}
    for _ in range(RESAMPLES):
        sample = [rows[rng.randrange(len(rows))] for _ in range(len(rows))]
        for name, value in metrics(sample).items():
            if value is not None:
                draws[name].append(value)
    return draws


def fmt(value):
    return "n/a" if value is None else "%.4f" % value


def build(raw, out):
    results = read_csv(os.path.join(raw, "er", "gold-eval-results.csv"))
    with open(os.path.join(raw, "er", "summary.json")) as handle:
        summary = json.load(handle)
    results.sort(key=lambda r: r["pair_id"])
    included = [r for r in results if r["included"] == "true"]
    groups = [("overall", included)]
    for kind in ("researcher", "work"):
        groups.append((kind, [r for r in included if r["kind"] == kind]))
    for kind in ("researcher", "work"):
        for path in PATHS:
            groups.append(("%s/%s" % (kind, path),
                           [r for r in included if r["kind"] == kind and r["match_path"] == path]))
    for path in PATHS:
        groups.append(("all/%s" % path, [r for r in included if r["match_path"] == path]))

    confusion_rows, metric_rows, ci_rows, plot = [], [], [], []
    for name, members in groups:
        rows = [(r["human_label"], r["decision"]) for r in members]
        c, same, diff, tp, fp, fn, tn = counts(rows)
        for label in ("SAME", "DIFFERENT"):
            confusion_rows.append([name, label] + [c[(label, o)] for o in OUTCOMES])
        point = metrics(rows)
        metric_rows.append([name, len(rows), same, diff, tp, fp, fn, tn] + [fmt(point[m]) for m in METRICS])
        draws = bootstrap(rows, name) if rows else {m: [] for m in METRICS}
        for metric in METRICS:
            values = draws[metric]
            low = percentile(values, 0.025) if values else None
            high = percentile(values, 0.975) if values else None
            ci_rows.append([name, metric, len(rows), fmt(point[metric]), fmt(low), fmt(high), len(values), RESAMPLES,
                            SEED, "degenerate" if values and low == high else ""])
            if name in ("overall", "researcher", "work") and metric in ("precision", "recall", "f1", "specificity"):
                plot.append(("%s %s" % (name, metric), point[metric], low, high))

    # Cross-check the recomputed counts against the Phase 5 tool's own summary.
    check_rows = []
    for kind in ("researcher", "work"):
        tool = summary["metrics"]["byKind"][kind]
        mine = counts([(r["human_label"], r["decision"]) for r in included if r["kind"] == kind])
        for key, value in zip(("tp", "fp", "fn", "tn"), mine[3:]):
            check_rows.append([kind, key, tool[key], value, "yes" if tool[key] == value else "NO"])

    gold = summary["goldSet"]
    write_csv(out, "er/gold-set-size.csv", ("measure", "value"), [
        ["gold pairs", gold["pairs"]], ["target pairs", GOLD_TARGET], ["shortfall", GOLD_TARGET - gold["pairs"]],
        ["SAME", gold["labels"]["SAME"]], ["DIFFERENT", gold["labels"]["DIFFERENT"]],
        ["UNSURE", gold["labels"]["UNSURE"]], ["gold set sha256", gold["sha256"]]])
    write_csv(out, "er/confusion-matrix.csv", ("group", "human_label") + OUTCOMES, confusion_rows)
    write_csv(out, "er/metrics.csv", ("group", "pairs", "same", "different", "tp", "fp", "fn", "tn") + METRICS,
              metric_rows)
    write_csv(out, "er/bootstrap-ci.csv", ("group", "metric", "pairs", "point", "ci95_low", "ci95_high",
                                           "defined_resamples", "resamples", "seed", "note"), ci_rows)
    write_csv(out, "er/tool-crosscheck.csv", ("kind", "count", "tool", "recomputed", "match"), check_rows)
    write_csv(out, "er/errors.csv", ("pair_id", "kind", "human_label", "outcome_class", "match_path", "decision",
                                     "score", "reason_codes"),
              [[r[k] for k in ("pair_id", "kind", "human_label", "outcome_class", "match_path", "decision", "score",
                               "reason_codes")] for r in results if r["outcome_class"] in ("FP", "FN")])
    svg_intervals(out, "er/metrics-ci.svg",
                  "Entity resolution: point estimate and bootstrap 95%% interval (%d pairs)" % gold["pairs"], plot)
