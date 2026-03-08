from __future__ import annotations

from typing import Sequence

from tools.devctl import lanes as _lanes


def run_maestro(
    raw_args: Sequence[str],
    context: _lanes.RuntimeContext,
    *,
    strict: bool = True,
) -> None:
    _lanes._lane_maestro_impl(raw_args, context, strict=strict)
