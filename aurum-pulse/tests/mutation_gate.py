"""Prove targeted safety regressions are caught; local temp copies, no network."""
from pathlib import Path
import json
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MUTATIONS = [
    ('bypass-source-receipts', 'taste-intelligence.mjs',
     'function hasReceipt(t, kind, asOf = END_OF_TIME) {',
     'function hasReceipt(t, kind, asOf = END_OF_TIME) { return true;'),
    ('disable-owner-block', 'taste-intelligence.mjs',
     'if (p.blocks.has(identity(t)) || p.blocks.has(artistKey(t.artist)))', 'if (false)'),
    ('replay-feedback', 'taste-intelligence.mjs',
     'if (seen.has(key)) continue;', '// Mutant: allow duplicate feedback.'),
    ('truncate-old-rejects', 'engine.mjs', '.slice(-limit)', '.slice(-3000)'),
    ('publish-incomplete-gold', 'taste-intelligence.mjs',
     "if (!item) return blocked('MANIFEST_INCOMPLETE_OR_BLOCKED');", 'if (!item) continue;'),
]

def main():
    outcomes = []
    for name, filename, before, after in MUTATIONS:
        with tempfile.TemporaryDirectory(prefix='aurum-mutation-') as tmp:
            root = Path(tmp)
            shutil.copytree(ROOT / 'web', root / 'web')
            shutil.copytree(ROOT / 'tests', root / 'tests', ignore=shutil.ignore_patterns('__pycache__'))
            path = root / 'web' / filename
            source = path.read_text()
            if source.count(before) != 1:
                raise RuntimeError(f'{name}: mutation anchor changed; update the test deliberately')
            path.write_text(source.replace(before, after, 1))
            subprocess.run(['node', '--check', str(path)], check=True, capture_output=True, timeout=10)
            run = subprocess.run(['node', '--test', str(root / 'tests' / 'curation-hardening.test.mjs')],
                                 capture_output=True, text=True, timeout=30)
            killed = run.returncode == 1 and 'ERR_ASSERTION' in run.stdout
            outcomes.append({'mutation': name, 'detected': killed,
                             'failing_tests': [s for s in run.stdout.splitlines() if s.startswith('not ok ')]})
    print(json.dumps({'tested': len(outcomes), 'detected': sum(o['detected'] for o in outcomes),
                      'scope': 'Targeted mutation checks, not an independent security audit',
                      'outcomes': outcomes}, indent=2))
    return 0 if all(o['detected'] for o in outcomes) else 1

if __name__ == '__main__':
    raise SystemExit(main())
