"""Offline browser layout smoke using inline local source (no HTTP navigation).
This does NOT prove the production server or third-party audio source.
"""
from pathlib import Path
import base64
from playwright.sync_api import sync_playwright
ROOT=Path(__file__).resolve().parents[1]; web=ROOT/'web'
html=(web/'index.html').read_text()
svg=base64.b64encode((web/'assets'/'aurum-pulse.svg').read_bytes()).decode()
html=html.replace('src="/assets/aurum-pulse.svg"',f'src="data:image/svg+xml;base64,{svg}"')
html=html.replace('href="/assets/aurum-pulse.svg"',f'href="data:image/svg+xml;base64,{svg}"')
html=html.replace('<link rel="stylesheet" href="/styles.css">','<style>'+(web/'styles.css').read_text()+'</style>')
html=html.replace('<script type="module" src="/app.mjs"></script>','')
engine=(web/'engine.mjs').read_text().replace('export const ','const ').replace('export function ','function ')
app=(web/'app.mjs').read_text().split('\n',1)[1]
js=engine+'\n'+app
errors=[]
with sync_playwright() as P:
 browser=P.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
 for width,height,name in [(1365,930,'desktop'),(390,844,'mobile')]:
  context=browser.new_context(viewport={'width':width,'height':height},device_scale_factor=1)
  page=context.new_page();page.on('pageerror',lambda e:errors.append(str(e)))
  page.set_content(html,wait_until='domcontentloaded')
  page.add_script_tag(content=js,type='module')
  page.wait_for_timeout(1200)
  print(name, 'welcome:',page.locator('#welcome').get_attribute('class'),'h1:',page.locator('.hero h1').inner_text(),'errors:',errors[-2:])
  page.screenshot(path=str(ROOT/f'aurum-pulse-{name}.png'),full_page=True)
  if name=='desktop':
   assert page.locator('#welcome').is_visible()
   page.locator('#next-step').click();page.locator('#next-step').click()
   page.locator('#listener-name').fill('Listener');page.locator('#next-step').click()
   assert not page.locator('#welcome').is_visible()
   page.locator('[data-view="sound"]').click();page.wait_for_timeout(150)
   page.locator('#bass').fill('4');assert page.locator('#bass-value').inner_text()=='+4 dB'
   page.screenshot(path=str(ROOT/'aurum-pulse-sound-lab.png'),full_page=True)
  context.close()
 browser.close()
print('PAGE_ERRORS:',errors)
if errors:raise RuntimeError('Browser script produced exceptions')
