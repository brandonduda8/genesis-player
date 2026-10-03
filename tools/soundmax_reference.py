#!/usr/bin/env python3
"""
SoundMax cross-check — NOT the deliverable tests.

This is a line-for-line algorithmic mirror of
  android/app/src/main/java/com/apexforge/genesisplayer/SoundMax.kt
(PULVERIZE profile, excursion guard, hard/soft limiter, loudness comp)
plus the ParametricEq chain pieces it builds on (RBJ biquads, auto
headroom, tanh limiter from ParametricDsp.kt).

Why Python: this box has no JDK/Kotlin toolchain and the Gradle daemon
cannot run here (sandbox hijacks loopback TCP), so SoundMaxTest.kt is
written for Gradle CI / a real build machine and is marked NOT VERIFIED
here. The numbers below are REAL measurements of the identical algorithm
— not of the Kotlin file itself. Any divergence between this mirror and
the Kotlin file is a defect; the mirror was transcribed formula-by-formula
from SoundMax.kt.

Run: python3 tools/soundmax_reference.py  (writes tools/soundmax_measurements.json)
"""
import json, math, sys

SR = 48000
PI = math.pi

# ------------------------------------------------------------- math ---
def clamp_freq(f, sr=SR): return min(max(f, 1.0), sr * 0.49)
def clamp_q(q): return min(max(q, 0.1), 18.0)
def clamp_gain(g): return min(max(g, -15.0), 15.0)

def _norm(b0,b1,b2,a0,a1,a2):
    if not math.isfinite(a0) or a0 == 0.0: return (1.0,0.0,0.0,0.0,0.0)
    c = (b0/a0,b1/a0,b2/a0,a1/a0,a2/a0)
    return c if all(math.isfinite(x) for x in c) else (1.0,0.0,0.0,0.0,0.0)

def peaking(f,q,g,sr=SR):
    f,q,g = clamp_freq(f,sr), clamp_q(q), clamp_gain(g)
    A = 10.0**(g/40.0); w0 = 2*PI*f/sr
    alpha = math.sin(w0)/(2*q); cw = math.cos(w0)
    return _norm(1+alpha*A, -2*cw, 1-alpha*A, 1+alpha/A, -2*cw, 1-alpha/A)

def low_shelf(f,g,sr=SR,S=0.7071):
    f,g = clamp_freq(f,sr), clamp_gain(g)
    A = 10.0**(g/40.0); w0 = 2*PI*f/sr
    alpha = math.sin(w0)/2*math.sqrt((A+1/A)*(1/clamp_q(S)-1)+2); cw = math.cos(w0)
    sqA = math.sqrt(A)
    return _norm(
        A*((A+1)-(A-1)*cw+2*sqA*alpha), 2*A*((A-1)-(A+1)*cw),
        A*((A+1)-(A-1)*cw-2*sqA*alpha),
        (A+1)+(A-1)*cw+2*sqA*alpha, -2*((A-1)+(A+1)*cw),
        (A+1)+(A-1)*cw-2*sqA*alpha)

def high_shelf(f,g,sr=SR,S=0.7071):
    f,g = clamp_freq(f,sr), clamp_gain(g)
    A = 10.0**(g/40.0); w0 = 2*PI*f/sr
    alpha = math.sin(w0)/2*math.sqrt((A+1/A)*(1/clamp_q(S)-1)+2); cw = math.cos(w0)
    sqA = math.sqrt(A)
    return _norm(
        A*((A+1)+(A-1)*cw+2*sqA*alpha), -2*A*((A-1)+(A+1)*cw),
        A*((A+1)+(A-1)*cw-2*sqA*alpha),
        (A+1)-(A-1)*cw+2*sqA*alpha, 2*((A-1)-(A+1)*cw),
        (A+1)-(A-1)*cw-2*sqA*alpha)

def magnitude_db(c,f,sr=SR):
    w = 2*PI*clamp_freq(f,sr)/sr; cw,sw = math.cos(w),math.sin(w)
    b0,b1,b2,a1,a2 = c
    nr = b0+b1*cw+b2*math.cos(2*w); ni = -(b1*sw+b2*math.sin(2*w))
    dr = 1+a1*cw+a2*math.cos(2*w);  di = -(a1*sw+a2*math.sin(2*w))
    mag = math.hypot(nr,ni)/math.hypot(dr,di)
    return 20*math.log10(max(mag,1e-12))

def grid():
    pts=[]; f=20.0
    while f<=20000.0: pts.append(f); f*=10.0**(1/24)
    return pts
GRID = grid()

# --------------------------------------------------------- presets ---
def LS(f,g): return ("LS",f,g,0.7071)
def PK(f,g,q=1.0): return ("PK",f,g,q)
def HS(f,g): return ("HS",f,g,0.7071)

def coeffs_of(band,sr=SR):
    t,f,g,q = band
    if t=="LS": return low_shelf(f,g,sr,q)
    if t=="PK": return peaking(f,q,g,sr)
    return high_shelf(f,g,sr,q)

PULVERIZE_BANDS = [LS(60,7.0), PK(120,4.5,1.0), PK(280,-3.0,1.2), PK(900,-1.0,1.0),
                   PK(3500,3.0,1.0), PK(7000,2.0,1.0), PK(12000,2.0,0.9), HS(14000,1.0)]
PULVERIZE_PREAMP = -7.5

FLAT_BANDS = [LS(80,0),PK(120,0),PK(300,0),PK(800,0),PK(2000,0),PK(4500,0),PK(8000,0),HS(10000,0)]

# the six WO-AURUM-008 presets (for the stress fixture)
PRESETS = {
 "Reference / Flat": (0.0, FLAT_BANDS),
 "FEEL IT": (-4.5, [LS(60,4),PK(120,3),PK(280,-2,1.2),PK(1000,0),PK(3000,1.5),PK(6000,1),PK(9000,0.5),HS(10000,2)]),
 "Night Drive": (-5.5, [LS(70,5),PK(130,3.5),PK(300,-3,1.2),PK(900,-0.5),PK(2500,2),PK(5500,0.5),PK(8500,-0.5),HS(9000,-1)]),
 "Emo / Vocal": (-4.5, [LS(80,2),PK(200,-2),PK(1200,2.5,1.1),PK(3500,3),PK(500,-0.5),PK(7000,1.5),PK(10000,0.5),HS(12000,1)]),
 "Trap-Rock / Rage": (-6.5, [LS(55,6),PK(110,4),PK(250,-2.5,1.2),PK(900,-1),PK(3000,2.5),PK(5500,3),PK(8000,1.5),HS(10000,2.5)]),
 "Dark Cinematic": (-6.0, [LS(45,6),PK(100,3),PK(220,-1.5,1.2),PK(800,-1),PK(2000,1),PK(6000,0.5),PK(9000,0),HS(12000,-2)]),
 "Pulverize / Rage-Max": (PULVERIZE_PREAMP, PULVERIZE_BANDS),
}

def filter_worst(bands):
    cs=[coeffs_of(b) for b in bands]
    return max(sum(magnitude_db(c,f) for c in cs) for f in GRID)

def response_db(bands, preamp, f):
    cs=[coeffs_of(b) for b in bands]
    worst = preamp + max(sum(magnitude_db(c,g) for c in cs) for g in GRID)
    eff = preamp - worst if worst > 0 else preamp
    return eff + sum(magnitude_db(c,f) for c in cs)

# ------------------------------------------------------------ guard ---
def excursion_guard(bands, preamp):
    out=[]; changed=False
    for t,f,g,q in bands:
        ng=g
        if t=="LS" and f < 100.0 and g > 2.0: ng=2.0
        if t=="PK" and 90.0 <= f <= 160.0 and g > 3.0: ng=3.0
        if ng!=g: changed=True
        out.append((t,f,ng,q))
    if not changed:
        return bands, preamp
    worst = filter_worst(out)
    pre = math.floor((-worst-0.5)*2)/2
    pre = min(max(pre,-12.0),6.0)
    return out, pre

# ---------------------------------------------------------- limiter ---
CEIL = 10.0**(-1.0/20.0)
def soft_tanh(x, t=CEIL):
    ax=abs(x)
    return x if ax<=t else math.copysign(t+(1-t)*math.tanh((ax-t)/(1-t)), x)

class HardKnee:
    def __init__(self, sr=SR, release_ms=50.0, ceil=CEIL):
        self.rel = math.exp(-1.0/((release_ms/1000.0)*sr)); self.c=ceil; self.g=1.0
    def reset(self): self.g=1.0
    def sample(self, x):
        ax=abs(x)
        tgt = self.c/max(ax,1e-12) if ax>self.c else 1.0
        self.g = tgt if tgt < self.g else self.g+(1-self.rel)*(tgt-self.g)
        return x*self.g

# ---------------------------------------------------------- loudness ---
TABLE = [(1.00,(0.0,0.0)),(0.75,(2.0,1.0)),(0.50,(5.0,2.5)),(0.25,(9.0,4.0)),(0.00,(14.0,6.0))]
def loudness(v):
    v=min(max(v,0.0),1.0)
    for i in range(len(TABLE)-1):
        vhi,(lhi,hhi)=TABLE[i]; vlo,(llo,hlo)=TABLE[i+1]
        if v<=vhi and v>=vlo:
            t=(vhi-v)/(vhi-vlo)
            return (lhi+t*(llo-lhi), hhi+t*(hlo-hhi))
    return TABLE[-1][1]

# ------------------------------------------------------------ checks ---
results = {"checks":[]}
def check(name, ok, detail=""):
    results["checks"].append({"name":name,"ok":bool(ok),"detail":str(detail)})
    print(("PASS" if ok else "FAIL")+f": {name} {detail}")

# 1. PULVERIZE headroom + shape pins
w = filter_worst(PULVERIZE_BANDS)
check("pulverize_worst", abs(w-6.718)<0.01, f"worst={w:.3f} dB")
check("pulverize_preamp", abs(PULVERIZE_PREAMP-(-7.5))<1e-9 and w+PULVERIZE_PREAMP<0,
      f"worst+pre={w+PULVERIZE_PREAMP:.3f}")
ref = response_db(PULVERIZE_BANDS, 0.0, 1000.0)
pins = {50:5.95, 120:5.73, 300:-1.28, 4000:4.43}
for f,exp in pins.items():
    got = response_db(PULVERIZE_BANDS, 0.0, f)-ref
    check(f"shape_{f}", abs(got-exp)<0.75, f"got={got:+.2f} exp={exp:+.2f}")

# 2. guard
gb, gp = excursion_guard(PULVERIZE_BANDS, PULVERIZE_PREAMP)
check("guard_sub_cap", abs(gb[0][2]-2.0)<1e-9, f"band0={gb[0][2]}")
check("guard_punch_cap", abs(gb[1][2]-3.0)<1e-9, f"band1={gb[1][2]}")
check("guard_preamp", abs(gp-(-4.5))<1e-9, f"pre={gp}")
check("guard_mids_untouched", all(abs(gb[i][2]-PULVERIZE_BANDS[i][2])<1e-9 for i in range(2,8)))
gw = filter_worst(gb)
check("guard_headroom", gw+gp<0, f"worst+pre={gw+gp:.3f}")
# unchanged preset passes through untouched (no gratuitous preamp re-seat)
fb, fp = excursion_guard(FLAT_BANDS, 0.0)
check("guard_flat_untouched", fp==0.0 and all(b[2]==0.0 for b in fb), f"pre={fp}")

# 3. limiter transfer curves (steady state, DC steps)
def transfer(fn):
    out=[]
    if hasattr(fn,"reset"): pass
    for dc in [0.1,0.5,0.89,1.0,1.5,2.0,5.0]:
        h=HardKnee() if fn=="hard" else None
        acc=0.0; n=0
        for i in range(4800):
            y = h.sample(dc) if h else soft_tanh(dc)
            if i>=3800: acc+=abs(y); n+=1
        out.append((dc,acc/n))
    return out
soft_c = transfer("soft"); hard_c = transfer("hard")
check("soft_never_exceeds_unity", all(o<=1.0+1e-9 for _,o in soft_c),
      f"max={max(o for _,o in soft_c):.6f}")
check("soft_transparent", abs(soft_c[0][1]-0.1)<1e-9 and abs(soft_c[1][1]-0.5)<1e-9)
check("soft_asymptote", soft_c[-1][1]>0.9999, f"{soft_c[-1][1]:.6f}")
check("hard_never_exceeds_ceiling", all(o<=CEIL+1e-9 for _,o in hard_c),
      f"max={max(o for _,o in hard_c):.6f}")
check("hard_clamps", all(abs(o-CEIL)<1e-6 for _,o in hard_c[3:]),
      f"{[round(o,6) for _,o in hard_c[3:]]}")
check("hard_transparent", abs(hard_c[0][1]-0.1)<1e-9)
# zero overshoot on pathological step
h=HardKnee(); pk=0.0
for i in range(4800):
    y=h.sample(0.0 if i<100 else 2.0); pk=max(pk,abs(y))
check("hard_zero_overshoot", pk<=CEIL*(1+1e-9), f"peak={pk:.6f} ceil={CEIL:.6f}")
# release recovery
h=HardKnee()
for _ in range(1000): h.sample(2.0)
for _ in range(24000): y=h.sample(0.1)
check("hard_release_recovers", abs(abs(y)-0.1)<0.005, f"y={y:.4f}")
# soft knee analysis pins
check("tanh_threshold", abs(CEIL-0.891251)<1e-6, f"{CEIL:.6f}")
e=1e-4; slope=(soft_tanh(CEIL+e)-soft_tanh(CEIL-e))/(2*e)
check("tanh_slope_at_knee", abs(slope-1.0)<0.01, f"{slope:.4f}")
check("tanh_0dbfs_in", abs(20*math.log10(soft_tanh(1.0))-(-0.228))<0.01,
      f"{20*math.log10(soft_tanh(1.0)):+.3f} dBFS")

# 4. loudness table
check("loud_full", loudness(1.0)==(0.0,0.0))
check("loud_quiet", loudness(0.0)==(14.0,6.0))
check("loud_half", loudness(0.5)==(5.0,2.5))
m=loudness(0.625)
check("loud_interp", abs(m[0]-3.5)<1e-9 and abs(m[1]-1.75)<1e-9, f"{m}")
check("loud_clamp", loudness(-0.5)==loudness(0.0) and loudness(1.5)==loudness(1.0))
mono=all(loudness(a)[0]<=loudness(b)[0]+1e-12 and loudness(a)[1]<=loudness(b)[1]+1e-12
         for a,b in zip([1.0,0.75,0.5,0.25,0.0],[0.75,0.5,0.25,0.0,-0.1]))
check("loud_monotonic", mono)

# 5. flat unity
fw = max(abs(response_db(FLAT_BANDS,0.0,f)) for f in GRID)
check("flat_unity_0.01db", fw<0.01, f"worst={fw:.2e} dB")

# 6. 0 dBFS stress through the FULL chain (biquads + preamp cut + tanh limiter)
class Biquad:
    def __init__(s,sr=SR):
        s.c=list((1,0,0,0,0)); s.x1=s.x2=s.y1=s.y2=0.0
    def snap(s,c): s.c=list(c)
    def process(s,x):
        b0,b1,b2,a1,a2=s.c
        y=b0*x+b1*s.x1+b2*s.x2-a1*s.y1-a2*s.y2
        y=y if math.isfinite(y) else 0.0
        s.x2,s.x1=s.x1,x; s.y2,s.y1=s.y1,y
        return y
    def reset(s): s.x1=s.x2=s.y1=s.y2=0.0

def process_block(bands, preamp, xs):
    fs=[Biquad() for _ in bands]
    for f,b in zip(fs,bands): f.snap(coeffs_of(b)); f.reset()
    worst = preamp + max(sum(magnitude_db(coeffs_of(b),g) for b in bands) for g in GRID)
    eff = preamp-worst if worst>0 else preamp
    pre = 10.0**(eff/20.0)
    out=[]
    for x in xs:
        s=x*pre
        for f in fs: s=f.process(s)
        out.append(soft_tanh(s))
    return out

for name,(pre,bands) in PRESETS.items():
    worstf = max(GRID, key=lambda g: response_db(bands,0.0,g))
    ok=True; detail=""
    for sf in [worstf,60.0,1000.0,8000.0]:
        w=2*PI*sf/SR
        xs=[math.sin(w*i) for i in range(SR)]
        pk=max(abs(v) for v in process_block(bands,pre,xs))
        if pk>1.0: ok=False; detail=f"@{sf:.0f}Hz peak={pk:.4f}"
    check(f"stress_{name}", ok, detail or f"worstf={worstf:.0f}Hz")
    # guarded variant
    gb2,gp2=excursion_guard(bands,pre)
    worstf2 = max(GRID, key=lambda g: response_db(gb2,0.0,g))
    w=2*PI*worstf2/SR
    pk=max(abs(v) for v in process_block(gb2,gp2,[math.sin(w*i) for i in range(SR)]))
    check(f"stress_guarded_{name}", pk<=1.0, f"peak={pk:.4f}")

results["summary"]={"total":len(results["checks"]),
                   "passed":sum(1 for c in results["checks"] if c["ok"]),
                   "kotlin_tests":"NOT VERIFIED on this box (no JDK/kotlinc; Gradle daemon blocked)"}
with open("tools/soundmax_measurements.json","w") as f:
    json.dump(results,f,indent=2)
print(f"\n{results['summary']['passed']}/{results['summary']['total']} cross-checks passed")
print("wrote tools/soundmax_measurements.json")
sys.exit(0 if results['summary']['passed']==results['summary']['total'] else 1)
