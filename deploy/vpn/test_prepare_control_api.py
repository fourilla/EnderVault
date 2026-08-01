from __future__ import annotations

import re
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).parent))

from prepare_control_api import ALLOWED_ROUTES, prepare_control_api  # noqa: E402


class PrepareControlApiTest(unittest.TestCase):
    def test_generates_key_and_least_privilege_auth_config(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            key_path = root / "auth" / "control-api.key"
            config_path = root / "auth" / "config.toml"

            self.assertTrue(prepare_control_api(key_path, config_path))

            key = key_path.read_text(encoding="utf-8").strip()
            config = config_path.read_text(encoding="utf-8")
            self.assertRegex(key, re.compile(r"^[A-Za-z0-9_-]{22,128}$"))
            self.assertIn(f'apikey = "{key}"', config)
            for route in ALLOWED_ROUTES:
                self.assertIn(f'"{route}"', config)
            self.assertNotIn("/v1/vpn/settings", config)

    def test_reuses_existing_key(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            key_path = root / "control-api.key"
            config_path = root / "config.toml"
            key_path.write_text("existing_control_api_key_12345\n", encoding="utf-8")

            self.assertFalse(prepare_control_api(key_path, config_path))

            self.assertIn(
                'apikey = "existing_control_api_key_12345"',
                config_path.read_text(encoding="utf-8"),
            )

    def test_rejects_invalid_existing_key(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            key_path = root / "control-api.key"
            config_path = root / "config.toml"
            key_path.write_text("bad key\n", encoding="utf-8")

            with self.assertRaises(ValueError):
                prepare_control_api(key_path, config_path)


if __name__ == "__main__":
    unittest.main()
