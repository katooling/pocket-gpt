from __future__ import annotations

from typing import Sequence

from tools.devctl import lanes as _lanes


def run_android_instrumented(
    raw_args: Sequence[str],
    context: _lanes.RuntimeContext,
    *,
    strict: bool = True,
) -> None:
    _lanes._lane_android_instrumented_impl(raw_args, context, strict=strict)


def run_device(raw_args: Sequence[str], context: _lanes.RuntimeContext) -> None:
    _lanes._lane_device_impl(raw_args, context)
