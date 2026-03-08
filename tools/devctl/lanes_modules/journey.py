from __future__ import annotations

from typing import Sequence

from tools.devctl import lanes as _lanes


def run_journey(raw_args: Sequence[str], context: _lanes.RuntimeContext) -> None:
    _lanes._lane_journey_impl(raw_args, context)


def run_fast_smoke(raw_args: Sequence[str], context: _lanes.RuntimeContext) -> None:
    _lanes._lane_fast_smoke_impl(raw_args, context)


def run_valid_output(raw_args: Sequence[str], context: _lanes.RuntimeContext) -> None:
    _lanes._lane_valid_output_impl(raw_args, context)


def run_strict_journey(raw_args: Sequence[str], context: _lanes.RuntimeContext) -> None:
    _lanes._lane_strict_journey_impl(raw_args, context)
