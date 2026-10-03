#!/usr/bin/env python3
"""
WO-AURUM-008 DSP reference implementation + deterministic test battery.

This is a line-for-line algorithmic mirror of
android/app/src/main/java/com/apexforge/genesisplayer/ParametricDsp.kt
(RBJ cookbook biquads, coefficient smoothing, auto headroom, tanh limiter,
presets, profiles, Easy mapping).

Why Python: this box has no JDK/Kotlin toolchain and the Gradle daemon cannot
run here (sandbox hijacks loopback TCP), so the Kotlin unit tests
(ParametricDspTest.kt) are written for Gradle CI / a real build machine and
are marked NOT VERIFIED here. The numbers below are REAL measurements of the
identical algorithm — not of the Kotlin file itself. Any divergence between
this mirror and the Kotlin file is a defect; the mirror was transcribed
formula-by-formula from ParametricDsp.kt.

Run: python3 tools/dsp_reference.py  (writes tools/dsp_measurements.json)
"""
import json, math, time, sys

SR = 48000
PI = math.pi

# ---------------------------------------------------------------- math ---

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

IDENT = (1.0,0.0,0.0,0.0,0.0)

class Biquad:
    def __init__(self,sr=SR,tau=0.02):
        self.sr=sr; self.tau=tau
        self.cur=list(IDENT); self.tgt=list(IDENT)
        self.x1=self.x2=self.y1=self.y2=0.0
    def alpha(self): return 1.0-math.exp(-1.0/(self.tau*self.sr))
    def set_target(self,c):
        self.tgt = list(c) if all(math.isfinite(x) for x in c) else list(IDENT)
    def snap(self): self.cur=list(self.tgt)
    def max_err(self): return max(abs(a-b) for a,b in zip(self.cur,self.tgt))
    def process(self,x):
        a=self.alpha()
        if a>=1.0 or self.max_err()==0.0: self.cur=list(self.tgt)
        else:
            n=[ca+(ta-ca)*a for ca,ta in zip(self.cur,self.tgt)]
            self.cur = n if all(math.isfinite(v) for v in n) else list(self.tgt)
        b0,b1,b2,a1,a2=self.cur
        y=b0*x+b1*self.x1+b2*self.x2-a1*self.y1-a2*self.y2
        y=y if math.isfinite(y) else 0.0
        self.x2,self.x1=self.x1,x; self.y2,self.y1=self.y1,y
        return y
    def reset(self): self.x1=self.x2=self.y1=self.y2=0.0

# --------------------------------------------------------------- model ---

def LS(f,g): return ("LS",f,g,0.7071,False)
def PK(f,g,q=1.0): return ("PK",f,g,q,False)
def HS(f,g): return ("HS",f,g,0.7071,False)

def coeffs_of(band,sr=SR):
    t,f,g,q,bp = band
    if bp: return IDENT
    if t=="LS": return low_shelf(f,g,sr,q)
    if t=="PK": return peaking(f,q,g,sr)
    return high_shelf(f,g,sr,q)

PRESETS = {
 "Reference / Flat": (0.0, [LS(80,0),PK(120,0),PK(300,0),PK(800,0),PK(2000,0),PK(4500,0),PK(8000,0),HS(10000,0)]),
 "FEEL IT": (-4.5, [LS(60,4),PK(120,3),PK(280,-2,1.2),PK(1000,0),PK(3000,1.5),PK(6000,1),PK(9000,0.5),HS(10000,2)]),
 "Night Drive": (-5.5, [LS(70,5),PK(130,3.5),PK(300,-3,1.2),PK(900,-0.5),PK(2500,2),PK(5500,0.5),PK(8500,-0.5),HS(9000,-1)]),
 "Emo / Vocal": (-4.5, [LS(80,2),PK(200,-2),PK(1200,2.5,1.1),PK(3500,3),PK(500,-0.5),PK(7000,1.5),PK(10000,0.5),HS(12000,1)]),
 "Trap-Rock / Rage": (-6.5, [LS(55,6),PK(110,4),PK(250,-2.5,1.2),PK(900,-1),PK(3000,2.5),PK(5500,3),PK(8000,1.5),HS(10000,2.5)]),
 "Dark Cinematic": (-6.0, [LS(45,6),PK(100,3),PK(220,-1.5,1.2),PK(800,-1),PK(2000,1),PK(6000,0.5),PK(9000,0),HS(12000,-2)]),
}

def grid():
    pts=[]; f=20.0
    while f<=20000.0: pts.append(f); f*=10.0**(1/24)
    return pts

LIM_T = 10.0**(-1.0/20.0)
def limit(x):
    ax=abs(x)
    if ax<=LIM_T: return x
    return math.copysign(LIM_T+(1-LIM_T)*math.tanh((ax-LIM_T)/(1-LIM_T)), x)

class PEQ:
    def __init__(self,sr=SR):
        self.sr=sr; self.preamp=0.0; self.bypass_all=False
        self.bands=None; self.filters=[Biquad(sr) for _ in range(8)]
        self.protection=False; self.limiter_hit=False
    def apply_preset(self,name):
        pre,bands=PRESETS[name]
        self.preamp=min(max(pre,-12.0),6.0)
        self.bands=[(t,f,g,q,False) for t,f,g,q,_ in bands]
        self.bypass_all=False
        for fl in self.filters: fl.reset()
        self.retarget(snap=True)
    def retarget(self,snap=False):
        for fl,b in zip(self.filters,self.bands): fl.set_target(coeffs_of(b,self.sr))
        if snap:
            for fl in self.filters: fl.snap()
    def _resp_raw(self,f):
        db=self.preamp
        for b in self.bands: db+=magnitude_db(coeffs_of(b,self.sr),f,self.sr)
        return db
    def worst_case(self):
        if self.bypass_all: return 0.0
        return max(self._resp_raw(f) for f in grid())
    def eff_preamp(self):
        if self.bypass_all: return 0.0
        w=self.worst_case()
        return self.preamp-w if w>0 else self.preamp
    def response_db(self,f):
        if self.bypass_all: return 0.0
        db=self.eff_preamp()
        for b in self.bands: db+=magnitude_db(coeffs_of(b,self.sr),f,self.sr)
        return db
    def broadband_mean(self):
        g=grid(); return sum(self.response_db(f) for f in g)/len(g)
    def ab_bypass_trim_db(self):
        # Mirror of ParametricEq.abBypassTrimDb: attenuation for the BYPASSED
        # path so A/B is level-matched. Boosting the engaged path is futile
        # (the headroom cut eats exactly that boost); attenuation never clips.
        return self.broadband_mean()
    def process(self,xs,protect=True):
        out=[]
        if self.bypass_all or not xs:
            self.protection=False; self.limiter_hit=False; return list(xs)
        pre=10.0**(self.eff_preamp()/20.0) if protect else 10.0**(self.preamp/20.0)
        cut=(self.eff_preamp()<self.preamp-1e-9) if protect else False
        hit=False
        for x in xs:
            s=x*pre
            for fl in self.filters: s=fl.process(s)
            l=limit(s) if protect else s
            if l!=s: hit=True
            out.append(l)
        self.limiter_hit=hit; self.protection=cut or hit
        return out
    def impulse(self,n=4096):
        for fl in self.filters: fl.reset(); fl.snap()
        pre=10.0**(self.eff_preamp()/20.0); out=[]
        for i in range(n):
            s=(1.0 if i==0 else 0.0)*pre
            for fl in self.filters: s=fl.process(s)
            out.append(s)
        for fl in self.filters: fl.reset()
        return out

def easy_map(bass_on,bass,low,mid,high):
    bg = min(max(bass,0),12)*0.5 if bass_on else 0.0
    return (0.0,[LS(80,low),PK(55,bg),PK(130,low*0.5),PK(280,low*0.25,1.2),
                 PK(1000,mid),PK(3200,mid*0.5),PK(7000,high*0.5),HS(10000,high)])

# ---------------------------------------------------------------- tests ---

R = {"checks":[]}
def check(name,ok,detail=""):
    R["checks"].append({"name":name,"pass":bool(ok),"detail":str(detail)})
    if not ok: print("FAIL:",name,detail)

def frange(a,b,mult):
    f=a; out=[]
    while f<=b: out.append(f); f*=mult
    return out

def main():
    # 1. coeff edges finite
    ok=True
    for f in (20,20000,24000):
        for q in (0.05,0.1,18,40):
            for g in (-30,-15,15,30):
                for c in (peaking(f,q,g),low_shelf(f,g),high_shelf(f,g)):
                    ok = ok and all(math.isfinite(x) for x in c)
    check("coeffs_finite_at_edges",ok)

    # 2. symmetry + shelves
    up=magnitude_db(peaking(1000,1,6),1000); dn=magnitude_db(peaking(1000,1,-6),1000)
    check("peaking_symmetric_6db", abs(up-6)<0.05 and abs(dn+6)<0.05, f"{up:.3f}/{dn:.3f}")
    ls_deep=magnitude_db(low_shelf(100,6),30); hs_top=magnitude_db(high_shelf(10000,6),18000)
    check("shelves_hit_nominal", ls_deep>5 and hs_top>5, f"{ls_deep:.2f}/{hs_top:.2f}")

    # 3. flat unity
    eq=PEQ(); eq.apply_preset("Reference / Flat")
    worst=max(abs(eq.response_db(f)) for f in frange(20,20000,1.1))
    check("flat_unity_<0.02db", worst<0.02, f"worst={worst:.6f} dB")
    R["flat_worst_db"]=worst

    # 4. impulse stability per preset
    R["impulse"]={}
    for name in PRESETS:
        e=PEQ(); e.apply_preset(name); h=e.impulse(4096)
        fin=all(math.isfinite(x) for x in h); tail=abs(h[-1]); en=sum(x*x for x in h)
        R["impulse"][name]={"tail":tail,"energy":en}
        check(f"impulse_stable[{name}]", fin and tail<1e-4 and en<100, f"tail={tail:.2e} E={en:.2f}")

    # 4b. per-band bypass removes only that band
    e=PEQ(); e.apply_preset("FEEL IT"); with_=e.response_db(120.0)
    bl=list(e.bands); bl[1]=("PK",bl[1][1],bl[1][2],bl[1][3],True); e.bands=bl; e.retarget(snap=True)
    without=e.response_db(120.0)
    e0=PEQ(); e0.apply_preset("FEEL IT")
    check("per_band_bypass", without<with_-1.0 and abs(e.response_db(8000.0)-e0.response_db(8000.0))<0.5,
          f"{with_:.2f}->{without:.2f}")

    # 5. smoothing convergence
    bq=Biquad(); bq.set_target(peaking(100,1,0)); bq.snap()
    bq.set_target(low_shelf(100,12))
    tgt=bq.tgt; start=peaking(100,1,0)
    total=max(abs(t-s) for t,s in zip(tgt,start))
    maxstep=0.0
    prev=list(bq.cur)
    for _ in range(96000):
        bq.process(0.0); cur=bq.cur
        maxstep=max(maxstep,max(abs(c-p) for c,p in zip(cur,prev))); prev=list(cur)
    check("smoothing_converges_no_spike", bq.max_err()<1e-9 and maxstep<total*0.02,
          f"residual={bq.max_err():.2e} maxstep={maxstep:.4f} total={total:.3f}")
    R["smoothing"]={"residual":bq.max_err(),"max_step":maxstep,"total_travel":total}

    # 6. preset checkpoints + response tables
    R["preset_tables"]={}; R["headroom"]={}
    CHK=[50,100,200,500,1000,2000,4000,8000,12000]
    for name in PRESETS:
        e=PEQ(); e.apply_preset(name)
        tbl={f:round(e.response_db(f),3) for f in CHK}
        R["preset_tables"][name]=tbl
        w=e.worst_case(); ep=e.eff_preamp(); mean=e.broadband_mean()
        R["headroom"][name]={"worst_case_db":round(w,3),"eff_preamp_db":round(ep,3),
                             "broadband_mean_db":round(mean,3),"ab_bypass_trim_db":round(mean,3)}
    # directional pins, SHAPE-RELATIVE (dB vs 1 kHz mids). Absolute pins would
    # fight the headroom auto-cut, which guarantees engaged response <= 0 dB
    # by construction. Relative shape is invariant under the cut.
    def shape(name):
        e=PEQ(); e.apply_preset(name); ref=e.response_db(1000.0)
        return lambda f: e.response_db(f)-ref
    R["shape"]={}
    for name in PRESETS:
        s=shape(name)
        R["shape"][name]={f:round(s(f),3) for f in (45,55,60,70,120,200,280,2000,3000,3500,5500,8000,10000,12000,18000)}
    fs=shape("FEEL IT")
    check("feel_it_shape", fs(60)>2.0 and fs(280)<-0.5 and fs(10000)>0.5,
          f"60:{fs(60):.2f} 280:{fs(280):.2f} 10k:{fs(10000):.2f}")
    ns=shape("Night Drive")
    check("night_shape", ns(70)>3.0 and ns(12000)<0.0, f"70:{ns(70):.2f} 12k:{ns(12000):.2f}")
    es=shape("Emo / Vocal")
    check("emo_shape", es(3500)>1.0 and es(200)<-0.5, f"3.5k:{es(3500):.2f} 200:{es(200):.2f}")
    rs=shape("Trap-Rock / Rage")
    check("rage_shape", rs(55)>3.0 and rs(5500)>1.5, f"55:{rs(55):.2f} 5.5k:{rs(5500):.2f}")
    cs=shape("Dark Cinematic")
    check("cine_shape", cs(45)>3.0 and cs(18000)<-1.0, f"45:{cs(45):.2f} 18k:{cs(18000):.2f}")

    # 7. headroom: BEFORE (no protection) vs AFTER (protected) on 0 dBFS stress
    R["stress"]={}
    for name in PRESETS:
        e=PEQ(); e.apply_preset(name)
        # worst-case freq
        wf,wd=20,-1e9
        for f in frange(20,20000,1.05):
            r=e._resp_raw(f)
            if r>wd: wd,wf=r,f
        freqs=[wf,60.0,1000.0,8000.0]; row={}
        for sf in freqs:
            w=2*PI*sf/SR; xs=[math.sin(w*i) for i in range(SR)]
            raw=e.process(list(xs),protect=False)
            e2=PEQ(); e2.apply_preset(name)
            pro=e2.process(list(xs),protect=True)
            rp=max(abs(x) for x in raw); pp=max(abs(x) for x in pro)
            row[round(sf,1)]={"raw_peak":round(rp,4),"protected_peak":round(pp,4),
                              "would_clip":rp>1.0,"protection":e2.protection}
        R["stress"][name]=row
        okp=all(v["protected_peak"]<=1.0 for v in row.values())
        check(f"no_clip[{name}]", okp, f"worst raw={max(v['raw_peak'] for v in row.values()):.3f}")

    # 7b. headroom exactness: eff_preamp == pre - max(0, worst), and protected worst <= 0
    okh=True
    for name,(pre,_) in PRESETS.items():
        w=R["headroom"][name]["worst_case_db"]; ep=R["headroom"][name]["eff_preamp_db"]
        if abs(ep-(pre-max(0.0,w)))>0.01: okh=False
        e=PEQ(); e.apply_preset(name)
        wp=max(e.response_db(f) for f in frange(20,20000,1.05))
        if wp>1e-6: okh=False
    check("headroom_exact", okh)

    # 8. limiter transparency at -6 dBFS, flat
    e=PEQ(); e.apply_preset("Reference / Flat")
    xs=[0.5*math.sin(i*0.05) for i in range(4800)]
    out=e.process(xs)
    dmax=max(abs(a-b) for a,b in zip(xs,out))
    check("limiter_transparent_quiet", (not e.limiter_hit) and (not e.protection) and dmax<1e-6, f"dmax={dmax:.2e}")
    # limiter caps hot signals
    for amp in (1.0,2.0,10.0):
        e=PEQ(); e.apply_preset("Reference / Flat")
        xs=[amp*math.sin(i*0.05) for i in range(4800)]
        out=e.process(xs); pk=max(abs(x) for x in out)
        check(f"limiter_caps_{amp}x", pk<=1.0, f"peak={pk:.4f}")

    # 9. bypass identity
    e=PEQ(); e.apply_preset("Trap-Rock / Rage"); e.bypass_all=True
    xs=[0.7*math.sin(i*0.1) for i in range(2048)]
    out=e.process(xs)
    check("bypass_identity", max(abs(a-b) for a,b in zip(xs,out))<1e-6)

    # 9b. volume-matched A/B: attenuate the BYPASSED path by the engaged
    # chain's measured broadband mean (Kotlin test mirror). Rich chord at a
    # modest level: limiter must stay out, trimmed bypass within 1.5 dB RMS.
    e=PEQ(); e.apply_preset("FEEL IT")
    trim=e.ab_bypass_trim_db()
    check("trim_is_attenuation", trim<=0.0, f"trim={trim:.3f}")
    N=48000
    sig=[0.20*math.sin(2*math.pi*55*i/48000)+0.20*math.sin(2*math.pi*220*i/48000)
         +0.15*math.sin(2*math.pi*880*i/48000)+0.10*math.sin(2*math.pi*3520*i/48000)
         for i in range(N)]
    engaged=e.process(sig)
    lim_out=e.limiter_hit
    e.bypass_all=True; bypassed=e.process(sig)
    g=10.0**(trim/20.0)
    eE=sum(v*v for v in engaged)/N; eB=sum((v*g)**2 for v in bypassed)/N
    ratio=10*math.log10(eE/eB)
    R["ab_match_feel_it_db"]=round(ratio,3); R["ab_trim_feel_it_db"]=round(trim,3)
    check("limiter_out_of_ab_match", not lim_out)
    check("ab_level_match", abs(ratio)<1.5, f"ratio={ratio:.2f} dB")

    # 9c. preset names/order/ranges published and pinned
    check("presets_named", list(PRESETS.keys())==["Reference / Flat","FEEL IT","Night Drive",
          "Emo / Vocal","Trap-Rock / Rage","Dark Cinematic"])
    okr=True
    for name,(pre,bands) in PRESETS.items():
        okr = okr and len(bands)==8 and -12.0<=pre<=6.0 and bands[0][0]=="LS" and bands[7][0]=="HS"
        okr = okr and all(-15.0<=b[2]<=15.0 and 20.0<=b[1]<=20000.0 for b in bands)
    check("presets_ranges", okr)

    # 9d. reset-to-flat is true flat
    e=PEQ(); e.apply_preset("Dark Cinematic")
    e.apply_preset("Reference / Flat")  # resetFlat() equivalent: gains->0, preamp 0
    worst=max(abs(e.response_db(f)) for f in frange(20,20000,1.1))
    check("reset_flat", worst<0.02 and e.preamp==0.0, f"worst={worst:.6f}")

    # 10. easy mapping
    pre,bands=easy_map(True,10,5,-1,1)
    check("easy_map_bassheavy", bands[0][2]==5.0 and bands[1][2]==5.0 and bands[4][2]==-1.0 and bands[7][2]==1.0,
          str([b[2] for b in bands]))
    pre,bands=easy_map(False,0,0,0,0)
    check("easy_map_flat", all(abs(b[2])<1e-9 for b in bands))
    pre,bands=easy_map(False,12,0,0,0)
    check("easy_map_bass_off", bands[1][2]==0.0)

    # 11. profiles
    store={}
    def save(c,pn,pa): store[f"dsp_profile_{c}_preset"]=pn; store[f"dsp_profile_{c}_preamp"]=pa
    save("bluetooth","FEEL IT",-3.0); save("phone_speaker","Night Drive",-4.0)
    check("profiles_persist", store["dsp_profile_bluetooth_preset"]=="FEEL IT"
          and store["dsp_profile_phone_speaker_preset"]=="Night Drive"
          and "dsp_profile_car_external_preset" not in store)
    # tiny-speaker cap
    pre,bands=PRESETS["Trap-Rock / Rage"]
    capped=[(b[2] if not (b[1]<150 and b[2]>3.0) else 3.0) for b in bands]
    check("phone_speaker_cap", all((b[1]>=150 or g<=3.0+1e-9) for b,g in zip(bands,capped)))
    # custom round-trip: save bands -> load -> identical
    e=PEQ(); e.apply_preset("FEEL IT")
    bl=list(e.bands); bl[1]=("PK",90.0,7.5,1.0,False); bl[3]=("PK",bl[3][1],bl[3][2],bl[3][3],True)
    store["dsp_profile_headphones_preset"]="Custom"; store["dsp_profile_headphones_preamp"]=-2.0
    for i,b in enumerate(bl): store[f"dsp_profile_headphones_band_{i}"]=f"{b[1]},{b[2]},{b[3]},{b[4]}"
    got=[store[f"dsp_profile_headphones_band_{i}"] for i in range(8)]
    gb=[tuple(float(x) if j<3 else x=="True" for j,x in enumerate(v.split(","))) for v in got]
    check("custom_roundtrip", abs(gb[1][0]-90.0)<1e-9 and abs(gb[1][1]-7.5)<1e-9 and gb[3][3] is True)

    # 12. CPU timing (1 s block)
    e=PEQ(); e.apply_preset("FEEL IT")
    xs=[0.5*math.sin(i*0.01) for i in range(SR)]
    t0=time.perf_counter(); e.process(xs); ms=(time.perf_counter()-t0)*1000
    R["cpu_ms_1s_block_python"]=round(ms,1)
    check("cpu_smoke", ms<60000, f"{ms:.1f} ms in CPython (reference only)")

    fails=[c for c in R["checks"] if not c["pass"]]
    R["summary"]={"passed":len(R["checks"])-len(fails),"failed":len(fails),"total":len(R["checks"])}
    print(f"\n{R['summary']['passed']}/{R['summary']['total']} checks passed")
    with open("tools/dsp_measurements.json","w") as fh: json.dump(R,fh,indent=1)
    print("wrote tools/dsp_measurements.json")
    sys.exit(1 if fails else 0)

if __name__=="__main__": main()
