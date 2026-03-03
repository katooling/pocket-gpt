from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.devctl import config_models
from tools.devctl.subprocess_utils import DevctlError
from tools.devctl.subprocess_utils import REPO_ROOT


class ConfigModelsTest(unittest.TestCase):
    def test_load_devctl_configs_success(self) -> None:
        configs = config_models.load_devctl_configs(REPO_ROOT)
        self.assertEqual("./gradlew", configs.lanes.lanes.test.gradle_binary)
        self.assertEqual("scripts/android/ensure_device.sh", configs.device.preflight.ensure_device_command[1])

    def test_load_devctl_configs_invalid_yaml(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            config_dir = root / "config" / "devctl"
            config_dir.mkdir(parents=True, exist_ok=True)
            (config_dir / "lanes.yaml").write_text("lanes: [", encoding="utf-8")
            (config_dir / "device.yaml").write_text("{}", encoding="utf-8")
            (config_dir / "stage2.yaml").write_text("{}", encoding="utf-8")

            with self.assertRaises(DevctlError) as raised:
                config_models.load_devctl_configs(root)
            self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_load_devctl_configs_schema_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            config_dir = root / "config" / "devctl"
            config_dir.mkdir(parents=True, exist_ok=True)
            (config_dir / "lanes.yaml").write_text("lanes: {}", encoding="utf-8")
            (config_dir / "device.yaml").write_text("{}", encoding="utf-8")
            (config_dir / "stage2.yaml").write_text("{}", encoding="utf-8")

            with self.assertRaises(DevctlError) as raised:
                config_models.load_devctl_configs(root)
            self.assertEqual("CONFIG_ERROR", raised.exception.code)


if __name__ == "__main__":
    unittest.main()
