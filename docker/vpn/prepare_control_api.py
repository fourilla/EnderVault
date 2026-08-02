from __future__ import annotations

import os
import re
import secrets
from pathlib import Path


KEY_PATTERN = re.compile(r"^[A-Za-z0-9_-]{22,128}$")
ALLOWED_ROUTES = (
    "GET /v1/publicip/ip",
    "GET /v1/vpn/status",
    "PUT /v1/vpn/status",
)


def atomic_write(path: Path, content: str, mode: int = 0o600) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_suffix(path.suffix + ".tmp")
    temporary_path.write_text(content, encoding="utf-8", newline="\n")
    temporary_path.chmod(mode)
    os.replace(temporary_path, path)
    path.chmod(mode)


def load_or_create_key(key_path: Path) -> tuple[str, bool]:
    if key_path.exists():
        if not key_path.is_file() or key_path.is_symlink():
            raise ValueError("the Control API key path must be a regular file")
        if key_path.stat().st_size > 512:
            raise ValueError("the Control API key file is unexpectedly large")
        key = key_path.read_text(encoding="utf-8").strip()
        if not KEY_PATTERN.fullmatch(key):
            raise ValueError("the existing Control API key has an invalid format")
        key_path.chmod(0o600)
        return key, False

    key = secrets.token_urlsafe(32)
    atomic_write(key_path, key + "\n")
    return key, True


def render_auth_config(api_key: str) -> str:
    routes = ", ".join(f'"{route}"' for route in ALLOWED_ROUTES)
    return (
        "[[roles]]\n"
        'name = "endervault"\n'
        f"routes = [{routes}]\n"
        'auth = "apikey"\n'
        f'apikey = "{api_key}"\n'
    )


def prepare_control_api(key_path: Path, auth_config_path: Path) -> bool:
    api_key, created = load_or_create_key(key_path)
    atomic_write(auth_config_path, render_auth_config(api_key))
    return created


def main() -> None:
    key_path = Path(
        os.environ.get(
            "ENDERVAULT_VPN_CONTROL_API_KEY_FILE",
            "/runtime/auth/control-api.key",
        )
    )
    auth_config_path = Path(
        os.environ.get(
            "ENDERVAULT_VPN_CONTROL_AUTH_CONFIG",
            "/runtime/auth/config.toml",
        )
    )

    try:
        created = prepare_control_api(key_path, auth_config_path)
    except (OSError, UnicodeError, ValueError) as error:
        raise SystemExit(f"Gluetun Control API preparation failed: {error}") from error

    action = "generated" if created else "reused"
    print(f"Gluetun Control API authentication prepared successfully (key {action}).")


if __name__ == "__main__":
    main()
