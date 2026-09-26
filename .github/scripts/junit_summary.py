#!/usr/bin/env python3
"""Summarise JUnit XML results and fail loudly if anything failed or nothing ran.

Usage: junit_summary.py <results-dir>
"""
import glob
import sys
import xml.etree.ElementTree as ET

root_dir = sys.argv[1] if len(sys.argv) > 1 else "."
total = fail = err = skip = 0
for f in sorted(glob.glob(f"{root_dir}/**/*.xml", recursive=True)):
    r = ET.parse(f).getroot()
    t, fl, e, s = (int(r.get(k, 0)) for k in ("tests", "failures", "errors", "skipped"))
    total += t; fail += fl; err += e; skip += s
    print(f"{r.get('name', f)}: tests={t} failures={fl} errors={e} skipped={s}")
    for case in r.iter("testcase"):
        for bad in list(case.findall("failure")) + list(case.findall("error")):
            print(f"  FAIL {case.get('classname')}.{case.get('name')}: {bad.get('message', '')[:300]}")
print(f"TOTAL tests={total} failures={fail} errors={err} skipped={skip}")
if total == 0:
    print("NO TESTS RAN — treating as failure")
    sys.exit(1)
sys.exit(1 if (fail or err) else 0)
