"""Plain, friendly console output. No colors that break on old terminals; no secrets, ever."""
from __future__ import annotations

import getpass
import sys

QUIET = False


def say(text: str = "") -> None:
    if not QUIET:
        print(text, flush=True)


def ok(text: str) -> None:
    say("  [ok] " + text)


def warn(text: str) -> None:
    say("  [!]  " + text)


def step(n: int, total: int, title: str) -> None:
    say("")
    say(f"=== Step {n} of {total}: {title} ===")


def remote(*presses: str) -> None:
    """Shows a sequence of remote-control presses, one per line, like a picture of the path."""
    for i, p in enumerate(presses, 1):
        say(f"   {i:>2}. {p}")


def yes(question: str, default: bool = False) -> bool:
    if not sys.stdin or not sys.stdin.isatty():
        return default
    hint = "[Y/n]" if default else "[y/N]"
    while True:
        a = input(f"{question} {hint} ").strip().lower()
        if not a:
            return default
        if a in ("y", "yes"):
            return True
        if a in ("n", "no"):
            return False


def ask(question: str, default: str = "") -> str:
    if not sys.stdin or not sys.stdin.isatty():
        return default
    a = input(f"{question}{f' [{default}]' if default else ''}: ").strip()
    return a or default


def secret(question: str) -> str:
    """Hidden input: nothing is echoed, logged or saved."""
    try:
        return getpass.getpass(f"{question} (typing is hidden; Enter to skip): ").strip()
    except (EOFError, KeyboardInterrupt):
        return ""


def choose(question: str, options: list[str]) -> int:
    for i, o in enumerate(options, 1):
        say(f"  {i}. {o}")
    while True:
        a = ask(question, "1")
        if a.isdigit() and 1 <= int(a) <= len(options):
            return int(a) - 1
