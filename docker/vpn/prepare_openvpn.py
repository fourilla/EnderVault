from __future__ import annotations

import ipaddress
import os
import re
import socket
import sys
from pathlib import Path, PurePosixPath


REMOTE_PATTERN = re.compile(
    r"^(?P<prefix>\s*remote\s+)(?P<quote>['\"]?)(?P<host>[^\s'\"]+)(?P=quote)(?P<suffix>.*)$",
    re.IGNORECASE,
)
FILE_PATTERN = re.compile(
    r"^(?P<prefix>\s*(?:ca|cert|key|pkcs12|tls-auth|tls-crypt|tls-crypt-v2|crl-verify|extra-certs|askpass)\s+)"
    r"(?P<quote>['\"]?)(?P<path>[^\s'\"]+)(?P=quote)(?P<suffix>.*)$",
    re.IGNORECASE,
)


def fail(message: str) -> None:
    print(f"OpenVPN profile preparation failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def resolve_host(host: str, suffix: str) -> str:
    try:
        return str(ipaddress.ip_address(host.strip("[]")))
    except ValueError:
        pass

    family = socket.AF_UNSPEC
    lowered_suffix = suffix.lower()
    if "udp4" in lowered_suffix or "tcp4" in lowered_suffix:
        family = socket.AF_INET
    elif "udp6" in lowered_suffix or "tcp6" in lowered_suffix:
        family = socket.AF_INET6

    try:
        answers = socket.getaddrinfo(host, None, family, socket.SOCK_STREAM)
    except socket.gaierror as error:
        fail(f"cannot resolve a remote endpoint ({error})")

    addresses = []
    for answer in answers:
        address = answer[4][0]
        if address not in addresses:
            addresses.append(address)

    if not addresses:
        fail("a remote endpoint resolved without an address")

    if family == socket.AF_UNSPEC:
        addresses.sort(key=lambda value: ipaddress.ip_address(value).version)
    return addresses[0]


def container_file_path(raw_path: str, input_dir: Path) -> str:
    candidate = Path(raw_path)
    if candidate.is_absolute():
        fail(f"external file paths must be relative to the profile directory: {raw_path}")

    resolved_input = input_dir.resolve()
    resolved_file = (resolved_input / candidate).resolve()
    try:
        relative = resolved_file.relative_to(resolved_input)
    except ValueError:
        fail(f"profile file reference escapes the profile directory: {raw_path}")

    if not resolved_file.is_file():
        fail(f"referenced profile file does not exist: {raw_path}")
    if resolved_file.is_symlink():
        fail(f"symbolic profile file references are not supported: {raw_path}")

    return str(PurePosixPath("/gluetun/input") / PurePosixPath(relative.as_posix()))


def prepare_profile(input_path: Path, output_path: Path) -> tuple[int, int]:
    try:
        source = input_path.read_text(encoding="utf-8-sig")
    except UnicodeDecodeError:
        fail("the OpenVPN profile must be UTF-8 text")
    except OSError as error:
        fail(f"cannot read the OpenVPN profile ({error})")

    remote_count = 0
    resolved_count = 0
    rewritten_file_count = 0
    output_lines = []

    for line in source.splitlines(keepends=True):
        stripped = line.lstrip()
        if not stripped or stripped.startswith(("#", ";")):
            output_lines.append(line)
            continue

        remote_match = REMOTE_PATTERN.match(line.rstrip("\r\n"))
        if remote_match:
            remote_count += 1
            host = remote_match.group("host")
            resolved_host = resolve_host(host, remote_match.group("suffix"))
            if resolved_host != host.strip("[]"):
                resolved_count += 1
            newline = "\n" if line.endswith(("\n", "\r")) else ""
            output_lines.append(
                f"{remote_match.group('prefix')}{resolved_host}{remote_match.group('suffix')}{newline}"
            )
            continue

        file_match = FILE_PATTERN.match(line.rstrip("\r\n"))
        if file_match:
            raw_path = file_match.group("path")
            if raw_path.lower() == "[inline]":
                output_lines.append(line)
                continue
            container_path = container_file_path(raw_path, input_path.parent)
            newline = "\n" if line.endswith(("\n", "\r")) else ""
            output_lines.append(
                f"{file_match.group('prefix')}\"{container_path}\"{file_match.group('suffix')}{newline}"
            )
            rewritten_file_count += 1
            continue

        output_lines.append(line)

    if remote_count == 0:
        fail("the OpenVPN profile does not contain a remote directive")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = output_path.with_suffix(output_path.suffix + ".tmp")
    temporary_path.write_text("".join(output_lines), encoding="utf-8", newline="\n")
    os.replace(temporary_path, output_path)
    return resolved_count, rewritten_file_count


def main() -> None:
    input_dir = Path(os.environ.get("ENDERVAULT_VPN_INPUT_DIR", "/input"))
    output_dir = Path(os.environ.get("ENDERVAULT_VPN_OUTPUT_DIR", "/runtime/generated"))
    config_name = os.environ.get("ENDERVAULT_VPN_CONFIG_NAME", "custom.ovpn")

    if Path(config_name).name != config_name or not config_name.lower().endswith((".ovpn", ".conf")):
        fail("ENDERVAULT_VPN_CONFIG_NAME must be a plain .ovpn or .conf file name")

    input_path = input_dir / config_name
    if not input_path.is_file():
        fail(f"profile not found: {input_path}")
    if input_path.is_symlink():
        fail("symbolic OpenVPN profiles are not supported")

    output_path = output_dir / config_name
    resolved_count, rewritten_file_count = prepare_profile(input_path, output_path)
    print(
        "OpenVPN profile prepared successfully "
        f"(resolved endpoints: {resolved_count}, normalized file references: {rewritten_file_count})."
    )


if __name__ == "__main__":
    main()
