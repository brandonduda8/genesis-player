"""Local-only end-to-end browser and PCM playback smoke; never touches real music."""
from pathlib import Path
from playwright.sync_api import sync_playwright
import contextlib, subprocess, os, sys, time, wave, math, struct, urllib.request
root=Path(__file__).resolve().parents[1]
port='18787'
tone=root/'tests'/'test-tone.wav'
with wave.open(str(tone),'wb') as w:
 w.setnchannels(1);w.setsampwidth(2);w.setframerate(22050)
 for i in range(22050*2):
  a=int(4500*math.sin(2*math.pi*440*i/22050));w.writeframesraw(struct.pack('<h',a))
proc=subprocess.Popen([sys.executable,str(root/'server'/'app.py')],env={**os.environ,'AURUM_PORT':port},stdout=subprocess.PIPE,stderr=subprocess.PIPE)
try:
 for n in range(40):
  try:
   with urllib.request.urlopen('http://127.0.0.1:'+port+'/api/health',timeout=1) as h: assert h.status==200
   break
  except Exception: time.sleep(.15)
 else:raise RuntimeError('Could not reach loopback test server')
 with sync_playwright() as p:
  browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox','--disable-dev-shm-usage','--autoplay-policy=no-user-gesture-required'])
  page=browser.new_page(viewport={'width':390,'height':844})
  errors=[]
  page.on('pageerror',lambda x: errors.append(str(x)))
  page.goto('http://127.0.0.1:'+port+'/',wait_until='domcontentloaded',timeout=20000)
  page.wait_for_timeout(300)
  assert page.locator('#welcome').is_visible(), 'Welcome should open'
  page.locator('#next-step').click()
  page.locator('.suggestion-chip').filter(has_text='Yung Gravy').click()
  page.locator('.suggestion-chip').filter(has_text='Mac Miller').click()
  page.locator('#next-step').click();page.locator('#next-step').click();page.locator('#next-step').click()
  assert not page.locator('#welcome').is_visible(), 'Welcome should close'
  page.locator('#file-picker').set_input_files(str(tone))
  page.locator('.row-btn').first.click()
  page.wait_for_timeout(600)
  print('NOW',page.locator('#now-title').inner_text(),'TIME',page.locator('#elapsed').inner_text(),'ERRORS',errors)
  assert 'test-tone' in page.locator('#now-title').inner_text()
  assert not errors
  page.screenshot(path=str(root/'aurum-pulse-mobile-playing.png'),full_page=True)
  browser.close()
finally:
 proc.terminate()
 try:proc.wait(timeout=3)
 except subprocess.TimeoutExpired:proc.kill();proc.wait()
 tone.unlink(missing_ok=True)
