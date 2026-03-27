#!/usr/bin/env python3
import csv
import json
import statistics
from pathlib import Path


def parse_float(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def median(values):
    numeric = [value for value in values if value is not None]
    if not numeric:
        return None
    return statistics.median(numeric)


def load_csv_rows(path):
    with open(path, newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        return list(reader)


def normalize_pocket_gpt_rows(rows, scenario):
    normalized = []
    for row in rows:
        row_scenario = (row.get("scenario") or "").strip().upper()
        if row_scenario and scenario and row_scenario != scenario:
            continue
        normalized.append(
            {
                "decode_tps": parse_float(row.get("decode_tps")),
                "first_token_ms": parse_float(row.get("first_token_ms")),
                "prefill_ms": parse_float(row.get("prefill_ms")),
                "model_load_ms": parse_float(row.get("model_load_ms")),
                "scenario": row_scenario or None,
                "source": "pocket-gpt",
            },
        )
    return normalized


def _candidate_value(record, keys):
    for key in keys:
        if key in record:
            return record.get(key)
    return None


def normalize_pocketpal_records(records, scenario):
    normalized = []
    for record in records:
        if not isinstance(record, dict):
            continue
        benchmark = record.get("benchmark") if isinstance(record.get("benchmark"), dict) else record
        row_scenario = str(
            _candidate_value(benchmark, ("scenario", "scenario_id", "scenarioId")) or "",
        ).strip().upper()
        if row_scenario and scenario and row_scenario != scenario:
            continue
        normalized.append(
            {
                "decode_tps": parse_float(
                    _candidate_value(
                        benchmark,
                        (
                            "decode_tps",
                            "tgAvg",
                            "token_generation_tps",
                            "tokenGenerationTps",
                            "tokensPerSecond",
                        ),
                    ),
                ),
                "first_token_ms": parse_float(
                    _candidate_value(
                        benchmark,
                        (
                            "first_token_ms",
                            "firstTokenMs",
                            "ttft_ms",
                            "timeToFirstTokenMs",
                            "time_to_first_token_ms",
                        ),
                    ),
                ),
                "prefill_ms": parse_float(
                    _candidate_value(
                        benchmark,
                        (
                            "prefill_ms",
                            "prefillMs",
                            "prompt_eval_ms",
                            "promptEvalMs",
                        ),
                    ),
                ),
                "model_load_ms": parse_float(
                    _candidate_value(
                        benchmark,
                        (
                            "model_load_ms",
                            "modelLoadMs",
                            "load_ms",
                            "loadMs",
                        ),
                    ),
                ),
                "scenario": row_scenario or None,
                "source": "pocketpal",
            },
        )
    return normalized


def load_pocketpal_rows(path, scenario):
    source = Path(path)
    suffix = source.suffix.lower()
    if suffix == ".csv":
        return normalize_pocketpal_records(load_csv_rows(path), scenario)
    if suffix != ".json":
        raise ValueError(f"Unsupported PocketPal input format: {path} (use .json or .csv)")
    with open(path, encoding="utf-8") as handle:
        payload = json.load(handle)
    if isinstance(payload, list):
        records = payload
    elif isinstance(payload, dict):
        records = [payload]
    else:
        raise ValueError("PocketPal JSON must be an object or array.")
    return normalize_pocketpal_records(records, scenario)


def build_parity_report(gpt_rows, pal_rows, min_tps_ratio, max_first_token_ratio):
    gpt_decode = median([row["decode_tps"] for row in gpt_rows])
    pal_decode = median([row["decode_tps"] for row in pal_rows])
    gpt_first = median([row["first_token_ms"] for row in gpt_rows])
    pal_first = median([row["first_token_ms"] for row in pal_rows])
    gpt_prefill = median([row["prefill_ms"] for row in gpt_rows])
    pal_prefill = median([row["prefill_ms"] for row in pal_rows])
    gpt_load = median([row["model_load_ms"] for row in gpt_rows])
    pal_load = median([row["model_load_ms"] for row in pal_rows])

    decode_ratio = None
    if gpt_decode is not None and pal_decode not in (None, 0):
        decode_ratio = gpt_decode / pal_decode

    first_token_ratio = None
    if gpt_first is not None and pal_first not in (None, 0):
        first_token_ratio = gpt_first / pal_first

    prefill_ratio = None
    if gpt_prefill is not None and pal_prefill not in (None, 0):
        prefill_ratio = gpt_prefill / pal_prefill

    model_load_ratio = None
    if gpt_load is not None and pal_load not in (None, 0):
        model_load_ratio = gpt_load / pal_load

    checks = []
    if decode_ratio is None:
        checks.append(
            {
                "name": "decode_tps_ratio",
                "status": "FAIL",
                "detail": "Missing decode_tps for one or both datasets.",
            },
        )
    else:
        checks.append(
            {
                "name": "decode_tps_ratio",
                "status": "PASS" if decode_ratio >= min_tps_ratio else "FAIL",
                "detail": f"ratio={decode_ratio:.3f}, required>={min_tps_ratio:.3f}",
            },
        )

    if first_token_ratio is None:
        checks.append(
            {
                "name": "first_token_ratio",
                "status": "SKIP",
                "detail": "PocketPal export did not include first-token latency; check skipped.",
            },
        )
    else:
        checks.append(
            {
                "name": "first_token_ratio",
                "status": "PASS" if first_token_ratio <= max_first_token_ratio else "FAIL",
                "detail": f"ratio={first_token_ratio:.3f}, required<={max_first_token_ratio:.3f}",
            },
        )

    overall = "PASS"
    for check in checks:
        if check["status"] == "FAIL":
            overall = "FAIL"
            break

    return {
        "overall": overall,
        "pocket_gpt": {
            "rows": len(gpt_rows),
            "median_decode_tps": gpt_decode,
            "median_first_token_ms": gpt_first,
            "median_prefill_ms": gpt_prefill,
            "median_model_load_ms": gpt_load,
        },
        "pocketpal": {
            "rows": len(pal_rows),
            "median_decode_tps": pal_decode,
            "median_first_token_ms": pal_first,
            "median_prefill_ms": pal_prefill,
            "median_model_load_ms": pal_load,
        },
        "ratios": {
            "decode_tps_ratio_gpt_over_pal": decode_ratio,
            "first_token_ratio_gpt_over_pal": first_token_ratio,
            "prefill_ratio_gpt_over_pal": prefill_ratio,
            "model_load_ratio_gpt_over_pal": model_load_ratio,
        },
        "checks": checks,
    }
