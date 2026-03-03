#!/usr/bin/env python3
import csv
import statistics
import sys
from collections import defaultdict


def parse_float(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def main(path):
    grouped = defaultdict(lambda: defaultdict(list))
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            key = (
                row.get("platform", "unknown"),
                row.get("device_class", "unknown"),
                row.get("runtime", "unknown"),
                row.get("model", "unknown"),
            )
            for metric in ("cold_start_s", "first_token_ms", "decode_tps", "peak_rss_mb", "battery_drop_pct_10m"):
                value = parse_float(row.get(metric))
                if value is not None and value > 0:
                    grouped[key][metric].append(value)
            crash = (row.get("crash_or_oom", "") or "").strip().lower()
            if crash in {"true", "1", "yes"}:
                grouped[key]["crash_or_oom"].append(1)

    print("platform,device_class,runtime,model,p50_first_token_ms,p50_decode_tps,p95_peak_rss_mb,mean_battery_drop_pct_10m,crash_count")
    for key, metrics in grouped.items():
        p50_first = statistics.median(metrics["first_token_ms"]) if metrics["first_token_ms"] else 0
        p50_tps = statistics.median(metrics["decode_tps"]) if metrics["decode_tps"] else 0
        peak_values = sorted(metrics["peak_rss_mb"])
        if peak_values:
            idx = int(round(0.95 * (len(peak_values) - 1)))
            p95_peak = peak_values[idx]
        else:
            p95_peak = 0
        mean_battery = statistics.mean(metrics["battery_drop_pct_10m"]) if metrics["battery_drop_pct_10m"] else 0
        crash_count = len(metrics["crash_or_oom"])
        print(
            f"{key[0]},{key[1]},{key[2]},{key[3]},"
            f"{p50_first:.1f},{p50_tps:.2f},{p95_peak:.1f},{mean_battery:.2f},{crash_count}"
        )


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: aggregate.py <csv_path>")
        sys.exit(1)
    main(sys.argv[1])
