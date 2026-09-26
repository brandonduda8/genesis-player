#!/usr/bin/env python3
"""Summarise JUnit XML results and fail loudly if anything failed or nothing ran.

Usage: junit_summary.py <results-dir> [label]

Besides printing to the log, it emits one GitHub Actions ``::notice`` annotation
that lists every test as PASS/FAIL (plus ``::error`` annotations for up to 10
failures). Annotations are readable through the checks API, so reviewers and
agents can see per-test results without downloading logs.
"""
import glob
import sys
import xml.etree.ElementTree as ET


def esc(s: str) -> str:
    # GitHub workflow-command escaping for annotation messages.
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


root_dir = sys.argv[1] if len(sys.argv) > 1 else "."
label = sys.argv[2] if len(sys.argv) > 2 else "JUnit results"
total = fail = err = skip = 0
lines, failures = [], []
for f in sorted(glob.glob(f"{root_dir}/**/*.xml", recursive=True)):
    r = ET.parse(f).getroot()
    suites = [r] if r.tag == "testsuite" else list(r.iter("testsuite"))
    for s in suites:
        t, fl, e, sk = (int(s.get(k, 0)) for k in ("tests", "failures", "errors", "skipped"))
        total += t; fail += fl; err += e; skip += sk
        print(f"{s.get('name', f)}: tests={t} failures={fl} errors={e} skipped={sk}")
        for case in s.iter("testcase"):
            name = f"{case.get('classname', '').split('.')[-1]}.{case.get('name')}"
            bad = list(case.findall("failure")) + list(case.findall("error"))
            if bad:
                msg = (bad[0].get("message") or (bad[0].text or "")).strip().splitlines()
                first = msg[0][:200] if msg else ""
                lines.append(f"FAIL {name} :: {first}")
                failures.append((name, first))
                print(f"  FAIL {name}: {first}")
            elif case.find("skipped") is not None:
                lines.append(f"SKIP {name}")
            else:
                lines.append(f"PASS {name}")

summary = f"TOTAL tests={total} failures={fail} errors={err} skipped={skip}"
print(summary)
print(f"::notice title={label}::{esc(summary + chr(10) + chr(10).join(lines))}")
for name, first in failures[:10]:
    print(f"::error title=FAIL {name}::{esc(first)}")
if total == 0:
    print("NO TESTS RAN — treating as failure")
    sys.exit(1)
sys.exit(1 if (fail or err) else 0)
