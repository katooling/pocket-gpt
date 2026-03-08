from __future__ import annotations

from typing import Sequence

from tools.devctl import lanes as _lanes


def run_test(raw_args: Sequence[str], context: _lanes.RuntimeContext) -> None:
    _lanes._lane_test_impl(raw_args, context)
