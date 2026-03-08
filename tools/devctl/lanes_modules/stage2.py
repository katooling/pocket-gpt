from __future__ import annotations

from typing import Sequence

from tools.devctl import lanes as _lanes


def run_stage2(raw_args: Sequence[str], context: _lanes.RuntimeContext) -> None:
    _lanes._lane_stage2_impl(raw_args, context)


def run_nightly_hardware(raw_args: Sequence[str], context: _lanes.RuntimeContext) -> None:
    _lanes._lane_nightly_hardware_impl(raw_args, context)
