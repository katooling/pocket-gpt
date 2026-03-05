from __future__ import annotations

import importlib.util
import shutil
import tempfile
import unittest
from pathlib import Path

from tools.devctl.subprocess_utils import REPO_ROOT


def _load_validator_module():
    module_path = REPO_ROOT / "scripts/benchmarks/validate_stage2_runtime_evidence.py"
    spec = importlib.util.spec_from_file_location("stage2_runtime_validator", module_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Failed to load validator module from {module_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Stage2RuntimeEvidenceValidatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.validator = _load_validator_module()
        cls.fixtures_root = REPO_ROOT / "tools/devctl/tests/fixtures/stage2_runtime_evidence"

    def _copy_fixture(self, name: str) -> Path:
        fixture_src = self.fixtures_root / name
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            run_dir = tmp_path / "run"
            shutil.copytree(fixture_src, run_dir)
            # Keep temporary directory alive for caller by copying to a second temp root.
            stable_tmp = Path(tempfile.mkdtemp())
            stable_run_dir = stable_tmp / "run"
            shutil.copytree(run_dir, stable_run_dir)
            return stable_run_dir

    def test_pass_fixture_is_valid(self) -> None:
        run_dir = self._copy_fixture("pass")
        try:
            self.validator.validate_run_dir(run_dir)
        finally:
            shutil.rmtree(run_dir.parent, ignore_errors=True)

    def test_fail_fixture_rejects_non_native_backend(self) -> None:
        run_dir = self._copy_fixture("fail_backend")
        try:
            with self.assertRaises(self.validator.ValidationError):
                self.validator.validate_run_dir(run_dir)
        finally:
            shutil.rmtree(run_dir.parent, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
