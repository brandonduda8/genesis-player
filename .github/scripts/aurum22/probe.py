"""Read Aurum's MediaSession from `dumpsys media_session`: prints STATE=<n> POS=<ms> TITLE=<...>.

PlaybackState: 2 = paused, 3 = playing. API 34 prints the state either as `state=3` or `state=PLAYING(3)`;
both are accepted. With --raw, also writes the session block to $OUT/session_raw.txt for diagnosis."""
import os, re, subprocess, sys
out = subprocess.run(['adb', 'shell', 'dumpsys', 'media_session'], capture_output=True, text=True, timeout=30).stdout
# the 2.2 app's own session: package line for exactly com.apexforge.genesisplayer (not .preview)
m = re.search(r'package=com\.apexforge\.genesisplayer\b(?!\.)', out) or re.search(r'com\.apexforge\.genesisplayer(?!\.preview)', out)
block = out[m.start():m.start() + 8000] if m else ''
if '--raw' in sys.argv:
    open(os.path.join(os.environ.get('OUT', '/tmp'), 'session_raw.txt'), 'w').write(block)
ps = re.search(r'PlaybackState \{\s*state=(?:[A-Z_]+\()?(\d+)\)?,\s*position=(-?\d+)', block)
st = ps or re.search(r'state=(?:[A-Z_]+\()?(\d+)\)?', block)
po = ps and type('P', (), {'group': lambda self, i: ps.group(2)})() or re.search(r'position=(-?\d+)', block)
t = re.search(r'description=([^,\n]*)', block)
print(f"STATE={st.group(1) if st else ''} POS={po.group(1) if po else ''} TITLE={(t.group(1).strip() if t else '')}")
