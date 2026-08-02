from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).parent))

from prepare_openvpn import prepare_profile  # noqa: E402


class PrepareOpenVpnTest(unittest.TestCase):
    def test_resolves_remote_and_normalizes_relative_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            input_directory = root / "input"
            output_path = root / "runtime" / "generated" / "custom.ovpn"
            input_directory.mkdir()
            (input_directory / "ca.crt").write_text("certificate", encoding="utf-8")
            input_path = input_directory / "custom.ovpn"
            original = "remote vpn.example.test 1194 udp\nca ca.crt\n"
            input_path.write_text(original, encoding="utf-8")

            with patch(
                "prepare_openvpn.socket.getaddrinfo",
                return_value=[(2, 1, 6, "", ("203.0.113.7", 0))],
            ):
                resolved_count, file_count = prepare_profile(input_path, output_path)

            generated = output_path.read_text(encoding="utf-8")
            self.assertIn("remote 203.0.113.7 1194 udp", generated)
            self.assertIn('ca "/gluetun/input/ca.crt"', generated)
            self.assertEqual(1, resolved_count)
            self.assertEqual(1, file_count)
            self.assertEqual(original, input_path.read_text(encoding="utf-8"))

    def test_keeps_ip_literal_and_inline_certificate_marker(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            input_path = root / "custom.ovpn"
            output_path = root / "generated" / "custom.ovpn"
            source = "remote 198.51.100.9 443 tcp\nca [inline]\n<ca>\ndata\n</ca>\n"
            input_path.write_text(source, encoding="utf-8")

            resolved_count, file_count = prepare_profile(input_path, output_path)

            self.assertEqual(source, output_path.read_text(encoding="utf-8"))
            self.assertEqual(0, resolved_count)
            self.assertEqual(0, file_count)

    def test_rejects_reference_outside_profile_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            input_directory = root / "input"
            input_directory.mkdir()
            input_path = input_directory / "custom.ovpn"
            output_path = root / "generated" / "custom.ovpn"
            input_path.write_text(
                "remote 198.51.100.9 1194\nca ../outside.crt\n",
                encoding="utf-8",
            )

            with self.assertRaises(SystemExit):
                prepare_profile(input_path, output_path)


if __name__ == "__main__":
    unittest.main()
