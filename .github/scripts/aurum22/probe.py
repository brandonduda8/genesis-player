"""Read Aurum's MediaSession from `dumpsys media_session`: prints STATE=<n> POS=<ms> TITLE=<...>.

PlaybackState: 2 = paused, 3 = playing. Copied from gate.yml's probe, plus the current title."""
import re, subprocess
out = subprocess.run(['adb', 'shell', 'dumpsys', 'media_session'], capture_output=True, text=True, timeout=30).stdout
idx = out.find('com.apexforge.genesisplayer')
block = out[idx:idx + 6000] if idx >= 0 else ''
m = re.search(r'state=(\d+)[^}]*?position=(\d+)', block)
state, pos = (m.group(1), m.group(2)) if m else ('', '')
t = re.search(r'metadata:.*?description=([^,\n]*)', block, re.S)
print(f"STATE={state} POS={pos} TITLE={(t.group(1).strip() if t else '')}")
