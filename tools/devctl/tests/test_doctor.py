from __future__ import annotations

import json
import unittest

from tools.devctl import doctor


class _Result:
    def __init__(self, returncode: int, stdout: str = "", stderr: str = ""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


class DoctorTest(unittest.TestCase):
    def test_doctor_json_output_shape(self) -> None:
        report = doctor.DoctorReport(checks=[])
        payload = json.loads(report.to_json())
        self.assertIn("ok", payload)
        self.assertIn("checks", payload)
        self.assertIn("failed_required", payload)

    def test_doctor_marks_missing_sdk(self) -> None:
        original_which = doctor.shutil.which
        original_run = doctor.run_subprocess
        try:
            doctor.shutil.which = lambda name: "/usr/bin/adb" if name == "adb" else None

            def fake_run(command, **_kwargs):
                if command[:3] == ["adb", "devices", "-l"]:
                    return _Result(0, "List of devices attached\nDEVICE_SERIAL_REDACTED device\n", "")
                return _Result(0, "", "")

            doctor.run_subprocess = fake_run
            report = doctor.run_doctor(env={})
            sdk = [check for check in report.checks if check.name == "android_sdk"][0]
            self.assertFalse(sdk.ok)
        finally:
            doctor.shutil.which = original_which
            doctor.run_subprocess = original_run

    def test_doctor_detects_no_device(self) -> None:
        original_which = doctor.shutil.which
        original_run = doctor.run_subprocess
        try:
            doctor.shutil.which = lambda name: "/usr/bin/adb" if name == "adb" else None

            def fake_run(command, **_kwargs):
                if command[:3] == ["adb", "devices", "-l"]:
                    return _Result(0, "List of devices attached\n\n", "")
                return _Result(0, "", "")

            doctor.run_subprocess = fake_run
            report = doctor.run_doctor(env={"ANDROID_HOME": "/tmp"})
            device = [check for check in report.checks if check.name == "adb_device"][0]
            self.assertFalse(device.ok)
        finally:
            doctor.shutil.which = original_which
            doctor.run_subprocess = original_run

    def test_doctor_detects_missing_maestro(self) -> None:
        original_which = doctor.shutil.which
        original_run = doctor.run_subprocess
        try:
            def fake_which(name: str):
                if name == "adb":
                    return "/usr/bin/adb"
                return None

            doctor.shutil.which = fake_which

            def fake_run(command, **_kwargs):
                if command[:3] == ["adb", "devices", "-l"]:
                    return _Result(0, "List of devices attached\nDEVICE_SERIAL_REDACTED device\n", "")
                if command[:2] == ["./gradlew", "--no-daemon"]:
                    return _Result(1, "", "install failed")
                return _Result(0, "", "")

            doctor.run_subprocess = fake_run
            report = doctor.run_doctor(env={"ANDROID_HOME": "/tmp"})
            maestro = [check for check in report.checks if check.name == "maestro_cli"][0]
            self.assertFalse(maestro.ok)
        finally:
            doctor.shutil.which = original_which
            doctor.run_subprocess = original_run


if __name__ == "__main__":
    unittest.main()
