#!/usr/bin/env python3
"""Print failures from JUnit-style XML results.

Gradle's output for connected (emulator) tests buries the actual assertion
under a wall of framework stack trace, which makes a CI failure hard to act on
from the log alone. This prints just the failing test and its message.

Usage: tools/print-test-failures.py <dir-or-file> [...]
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def files(paths):
    for path in paths:
        if os.path.isdir(path):
            yield from glob.glob(os.path.join(path, "**", "*.xml"), recursive=True)
        elif os.path.isfile(path):
            yield path


def main(argv):
    found = 0
    for path in sorted(files(argv)):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            problems = list(case.findall("failure")) + list(case.findall("error"))
            for problem in problems:
                found += 1
                name = f'{case.get("classname")}.{case.get("name")}'
                detail = (problem.text or problem.get("message") or "").strip()
                print(f"FAILED  {name}")
                print(detail[:2000])
                print("-" * 70)
    print(f"{found} failing test(s)" if found else "No failures recorded in the XML results.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or ["."]))
