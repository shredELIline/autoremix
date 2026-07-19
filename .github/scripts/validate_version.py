#!/usr/bin/env python3
"""Keep release, Android, iOS, and changelog versions aligned."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


SEMVER = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")


def properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator:
            raise ValueError(f"{path}: invalid property: {raw_line}")
        values[key.strip()] = value.strip()
    return values


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", help="Expected release tag, for example v2.1.0")
    args = parser.parse_args()

    errors: list[str] = []
    try:
        version = properties(Path("version.properties"))
    except (OSError, ValueError) as error:
        print(f"Version validation failed:\n- {error}")
        return 1

    name = version.get("VERSION_NAME", "")
    code = version.get("VERSION_CODE", "")
    if not SEMVER.fullmatch(name):
        errors.append(f"VERSION_NAME is not MAJOR.MINOR.PATCH: {name!r}")
    if not code.isdecimal() or int(code) < 1:
        errors.append(f"VERSION_CODE is not a positive integer: {code!r}")

    android = Path("platform-android/build.gradle.kts").read_text(encoding="utf-8")
    if "versionName = appVersionName" not in android:
        errors.append("Android versionName is not sourced from version.properties")
    if "versionCode = appVersionCode" not in android:
        errors.append("Android versionCode is not sourced from version.properties")

    xcode = Path("platform-ios/AutoRemix.xcodeproj/project.pbxproj").read_text(
        encoding="utf-8"
    )
    marketing_versions = set(re.findall(r"MARKETING_VERSION = ([^;]+);", xcode))
    build_versions = set(re.findall(r"CURRENT_PROJECT_VERSION = ([^;]+);", xcode))
    if marketing_versions != {name}:
        errors.append(f"iOS MARKETING_VERSION values differ: {marketing_versions}")
    if build_versions != {code}:
        errors.append(f"iOS CURRENT_PROJECT_VERSION values differ: {build_versions}")

    changelog = Path("CHANGELOG.md").read_text(encoding="utf-8")
    if not re.search(rf"^## {re.escape(name)}(?:\s|$)", changelog, re.MULTILINE):
        errors.append(f"CHANGELOG.md has no {name} section")

    if args.tag and args.tag != f"v{name}":
        errors.append(f"tag {args.tag!r} must equal v{name}")

    try:
        history = json.loads(Path("release-history.json").read_text(encoding="utf-8"))
        releases = history["releases"]
        if not isinstance(releases, list) or not releases:
            raise ValueError("releases must be a non-empty list")

        previous_name = (-1, -1, -1)
        previous_code = 0
        for release in releases:
            release_name = release["version_name"]
            release_code = release["version_code"]
            release_tag = release["tag"]
            if not isinstance(release_name, str) or not SEMVER.fullmatch(release_name):
                raise ValueError(f"invalid history version: {release_name!r}")
            if not isinstance(release_code, int) or release_code < 1:
                raise ValueError(f"invalid history version code: {release_code!r}")
            if release_tag != f"v{release_name}":
                raise ValueError(f"invalid history tag: {release_tag!r}")

            release_tuple = tuple(int(part) for part in release_name.split("."))
            if release_tuple <= previous_name:
                raise ValueError("history versions are not strictly increasing")
            if release_code <= previous_code:
                raise ValueError("history version codes are not strictly increasing")
            previous_name = release_tuple
            previous_code = release_code

        if releases[-1]["version_name"] != name or str(
            releases[-1]["version_code"]
        ) != code:
            errors.append("current version is not the last release-history entry")
    except (OSError, json.JSONDecodeError, KeyError, TypeError, ValueError) as error:
        errors.append(f"release-history.json: {error}")

    if errors:
        print("Version validation failed:")
        for error in errors:
            print(f"- {error}")
        return 1

    print(f"Version validation passed: {name} ({code}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
