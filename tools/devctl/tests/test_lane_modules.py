from __future__ import annotations

import unittest

from tools.devctl import lanes
from tools.devctl.lanes_modules import android, journey, maestro, screenshot_pack, shared, stage2


class LaneModulesDispatchTest(unittest.TestCase):
    def test_shared_module_delegates_to_test_impl(self) -> None:
        calls: list[tuple[tuple[str, ...], object]] = []
        original = lanes._lane_test_impl
        try:
            lanes._lane_test_impl = lambda raw_args, context: calls.append((tuple(raw_args), context))  # type: ignore[assignment]
            token = object()
            shared.run_test(["quick"], token)  # type: ignore[arg-type]
        finally:
            lanes._lane_test_impl = original  # type: ignore[assignment]
        self.assertEqual([(("quick",), token)], calls)

    def test_android_module_delegates_to_android_impl(self) -> None:
        calls: list[tuple[tuple[str, ...], object, bool]] = []
        original = lanes._lane_android_instrumented_impl
        try:
            lanes._lane_android_instrumented_impl = (  # type: ignore[assignment]
                lambda raw_args, context, strict=True: calls.append((tuple(raw_args), context, strict))
            )
            token = object()
            android.run_android_instrumented(["--flag"], token, strict=False)  # type: ignore[arg-type]
        finally:
            lanes._lane_android_instrumented_impl = original  # type: ignore[assignment]
        self.assertEqual([("--flag",)], [args for args, _, _ in calls])
        self.assertFalse(calls[0][2])

    def test_maestro_module_delegates_to_maestro_impl(self) -> None:
        calls: list[tuple[tuple[str, ...], object, bool]] = []
        original = lanes._lane_maestro_impl
        try:
            lanes._lane_maestro_impl = (  # type: ignore[assignment]
                lambda raw_args, context, strict=True: calls.append((tuple(raw_args), context, strict))
            )
            token = object()
            maestro.run_maestro([], token, strict=True)  # type: ignore[arg-type]
        finally:
            lanes._lane_maestro_impl = original  # type: ignore[assignment]
        self.assertEqual([tuple()], [args for args, _, _ in calls])
        self.assertTrue(calls[0][2])

    def test_screenshot_pack_module_delegates_to_impl(self) -> None:
        calls: list[tuple[tuple[str, ...], object]] = []
        original = lanes._lane_screenshot_pack_impl
        try:
            lanes._lane_screenshot_pack_impl = lambda raw_args, context: calls.append((tuple(raw_args), context))  # type: ignore[assignment]
            token = object()
            screenshot_pack.run_screenshot_pack(["--update-reference"], token)  # type: ignore[arg-type]
        finally:
            lanes._lane_screenshot_pack_impl = original  # type: ignore[assignment]
        self.assertEqual([("--update-reference",)], [args for args, _ in calls])

    def test_journey_module_delegates_to_fast_smoke_impl(self) -> None:
        calls: list[tuple[tuple[str, ...], object]] = []
        original = lanes._lane_fast_smoke_impl
        try:
            lanes._lane_fast_smoke_impl = lambda raw_args, context: calls.append((tuple(raw_args), context))  # type: ignore[assignment]
            token = object()
            journey.run_fast_smoke(["--steps", "instrumentation"], token)  # type: ignore[arg-type]
        finally:
            lanes._lane_fast_smoke_impl = original  # type: ignore[assignment]
        self.assertEqual([(("--steps", "instrumentation"), token)], calls)

    def test_stage2_module_delegates_to_stage2_impl(self) -> None:
        calls: list[tuple[tuple[str, ...], object]] = []
        original = lanes._lane_stage2_impl
        try:
            lanes._lane_stage2_impl = lambda raw_args, context: calls.append((tuple(raw_args), context))  # type: ignore[assignment]
            token = object()
            stage2.run_stage2(["--profile", "quick"], token)  # type: ignore[arg-type]
        finally:
            lanes._lane_stage2_impl = original  # type: ignore[assignment]
        self.assertEqual([(("--profile", "quick"), token)], calls)


if __name__ == "__main__":
    unittest.main()
