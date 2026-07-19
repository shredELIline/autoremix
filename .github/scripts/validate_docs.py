#!/usr/bin/env python3
"""Validate the static documentation tree without network access."""

from __future__ import annotations

import argparse
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit


REQUIRED_SCREENSHOTS = (
    "analysis-cache-dark.png",
    "library-dark.png",
    "now-playing-dark.png",
    "now-playing-light.png",
    "queue-dark.png",
    "settings-dark.png",
    "transition-dark.png",
    "transition-in-progress-dark.png",
)


class LinkParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.links: list[tuple[str, int]] = []

    def handle_starttag(
        self, tag: str, attrs: list[tuple[str, str | None]]
    ) -> None:
        for name, value in attrs:
            if name in {"href", "src"} and value:
                self.links.append((value, self.getpos()[0]))


def local_target(document: Path, value: str, root: Path) -> Path | None:
    parsed = urlsplit(value)
    if parsed.scheme or parsed.netloc or not parsed.path:
        return None
    path = Path(unquote(parsed.path))
    if path.is_absolute():
        return root / str(path).lstrip("/\\")
    return document.parent / path


def validate(root: Path) -> list[str]:
    errors: list[str] = []
    if not root.is_dir():
        return [f"documentation directory is missing: {root}"]
    if not (root / "index.html").is_file():
        errors.append("docs/index.html is missing")
    for required in ("assets/og.png", "assets/favicon.svg"):
        if not (root / required).is_file():
            errors.append(f"required showcase asset is missing: {required}")
    for screenshot in REQUIRED_SCREENSHOTS:
        if not (root / "assets" / "screenshots" / screenshot).is_file():
            errors.append(f"required screenshot is missing: {screenshot}")

    files = [path for path in root.rglob("*") if path.is_file()]
    for path in files:
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            if path.suffix.lower() in {".html", ".css", ".js", ".md", ".json"}:
                errors.append(f"{path}: text file is not valid UTF-8")
            continue

        if "\x00" in text:
            errors.append(f"{path}: contains a NUL byte")
        if path.suffix.lower() != ".html":
            continue

        lowered = text.lower()
        for forbidden in ("file://", "localhost:", "127.0.0.1:"):
            if forbidden in lowered:
                errors.append(f"{path}: contains forbidden published URL {forbidden}")

        parser = LinkParser()
        try:
            parser.feed(text)
            parser.close()
        except Exception as exc:  # HTMLParser errors are rare but actionable.
            errors.append(f"{path}: HTML parse failed: {exc}")
            continue

        for value, line in parser.links:
            target = local_target(path, value, root)
            if target is None:
                continue
            target = target.resolve()
            try:
                target.relative_to(root)
            except ValueError:
                errors.append(f"{path}:{line}: local target escapes docs root: {value}")
                continue
            if target.is_dir():
                target = target / "index.html"
            if not target.exists():
                errors.append(f"{path}:{line}: missing local target {value}")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", default="docs", type=Path)
    args = parser.parse_args()

    errors = validate(args.root.resolve())
    if errors:
        print("Static documentation validation failed:")
        for error in errors:
            print(f"- {error}")
        return 1

    print("Static documentation validation passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
