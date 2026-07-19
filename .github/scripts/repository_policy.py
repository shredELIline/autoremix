#!/usr/bin/env python3
"""Run high-confidence secret and repository license/model policy checks."""

from __future__ import annotations

import re
import subprocess
from pathlib import Path


REQUIRED_POLICY_FILES = {
    "LICENSE": ("Apache License", "Version 2.0"),
    "MODEL_LICENSES.md": ("Model licenses",),
    "THIRD_PARTY_MODELS.md": ("Third-party model",),
    "THIRD_PARTY_NOTICES.md": ("Third-party notices",),
    "SECURITY.md": ("Security policy",),
}

SECRET_PATTERNS = {
    "private key": re.compile(
        rb"-----BEGIN (?:RSA |DSA |EC |OPENSSH |PGP )?PRIVATE KEY-----"
    ),
    "GitHub token": re.compile(
        rb"(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{82,255})"
    ),
    "AWS access key": re.compile(rb"(?:AKIA|ASIA)[A-Z0-9]{16}"),
    "Google API key": re.compile(rb"AIza[0-9A-Za-z_-]{35}"),
    "Slack token": re.compile(rb"xox[baprs]-[0-9A-Za-z-]{20,}"),
    "Stripe live key": re.compile(rb"sk_live_[0-9A-Za-z]{16,}"),
    "npm token": re.compile(rb"npm_[A-Za-z0-9]{36}"),
}

SENSITIVE_SUFFIXES = {
    ".jks",
    ".keystore",
    ".mobileprovision",
    ".p12",
    ".pfx",
    ".provisionprofile",
}

MODEL_SUFFIXES = {
    ".coreml",
    ".gguf",
    ".mlmodel",
    ".onnx",
    ".pt",
    ".pth",
    ".safetensors",
    ".tflite",
}


def tracked_files() -> list[Path]:
    output = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"]
    )
    return [Path(item.decode("utf-8")) for item in output.split(b"\0") if item]


def check_policy_files(errors: list[str]) -> None:
    for name, markers in REQUIRED_POLICY_FILES.items():
        path = Path(name)
        if not path.is_file():
            errors.append(f"missing policy file: {name}")
            continue
        text = path.read_text(encoding="utf-8")
        for marker in markers:
            if marker.casefold() not in text.casefold():
                errors.append(f"{name}: missing expected marker {marker!r}")


def check_secrets(files: list[Path], errors: list[str]) -> None:
    for path in files:
        if path.suffix.lower() in SENSITIVE_SUFFIXES:
            errors.append(f"tracked signing material is forbidden: {path}")
        if not path.is_file() or path.stat().st_size > 10 * 1024 * 1024:
            continue
        data = path.read_bytes()
        for label, pattern in SECRET_PATTERNS.items():
            if pattern.search(data):
                errors.append(f"possible {label} in tracked file: {path}")


def check_models(files: list[Path], errors: list[str]) -> None:
    model_files = [path for path in files if path.suffix.lower() in MODEL_SUFFIXES]
    if not model_files:
        return

    licenses = Path("MODEL_LICENSES.md").read_text(encoding="utf-8")
    notices = Path("THIRD_PARTY_MODELS.md").read_text(encoding="utf-8")
    for path in model_files:
        normalized = path.as_posix()
        if normalized not in licenses or normalized not in notices:
            errors.append(f"model weight lacks license inventory entries: {normalized}")
        checksum = Path(f"{path}.sha256")
        if checksum not in files:
            errors.append(f"model weight lacks tracked checksum: {checksum.as_posix()}")


def main() -> int:
    errors: list[str] = []
    files = tracked_files()
    check_policy_files(errors)
    check_secrets(files, errors)
    check_models(files, errors)

    if errors:
        print("Repository policy validation failed:")
        for error in errors:
            print(f"- {error}")
        return 1

    print("Secret, license-document, and model policy checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
