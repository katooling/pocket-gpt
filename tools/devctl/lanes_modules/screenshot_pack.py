from __future__ import annotations

from typing import Sequence

from tools.devctl import lanes as _lanes


def run_screenshot_pack(raw_args: Sequence[str], context: _lanes.RuntimeContext) -> None:
    _lanes._lane_screenshot_pack_impl(raw_args, context)
