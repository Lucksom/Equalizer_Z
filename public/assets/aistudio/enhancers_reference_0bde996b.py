"""Reference for the four enhancers (deep bass, surround, beats, vocal).
Defines the curves, the mixer with the 6 dB budget, the MBC blend, and checks the
fast PCHIP renderer used by the optimised engine against the v4 renderer.
Run: python3 -I enhancers_reference.py
"""
import math, itertools, random

CENTRES = [20.0 * 2 ** (i / 3.0) for i in range(31)]
LOG = [math.log2(f) for f in CENTRES]

def bump(x, hz, db, oct_):          # gaussian in log2(f)
    return db * math.exp(-0.5 * ((x - math.log2(hz)) / oct_) ** 2)

def low_shelf(x, hz, db, w):         # applies below hz; db/2 at hz
    return db / (1.0 + math.exp((x - math.log2(hz)) / w))

# (kind, hz, db, width) ; kind B = bump, L = low shelf
SPEC = {
 "deep_bass": {
   "full":  [("B",55,4.5,0.55),("B",140,1.5,0.40),("B",300,-1.0,0.50),("L",24,-4.0,0.25)],
   "small": [("B",110,3.5,0.45),("B",170,1.5,0.40),("B",300,-1.0,0.50)],
 },
 "surround": {
   "full":  [("B",10000,1.0,0.60)],
   "small": [("B",10000,1.0,0.60)],
 },
 "beats": {
   "full":  [("B",70,2.5,0.45),("B",200,1.5,0.35),("B",320,-1.5,0.50),("B",4200,2.5,0.50),("B",9000,1.0,0.50)],
   "small": [("B",120,2.5,0.45),("B",200,1.5,0.35),("B",320,-1.5,0.50),("B",4200,2.5,0.50),("B",9000,1.0,0.50)],
 },
 "vocal": {
   "full":  [("B",300,-1.5,0.50),("B",1000,1.0,0.50),("B",2800,3.0,0.45),("B",5000,1.0,0.40),("B",7000,-1.5,0.30),("B",11000,1.0,0.50)],
   "small": [("B",300,-1.5,0.50),("B",1000,1.0,0.50),("B",2800,3.0,0.45),("B",5000,1.0,0.40),("B",7000,-1.5,0.30),("B",11000,1.0,0.50)],
 },
}
ORDER = ["deep_bass", "surround", "beats", "vocal"]

def curve(name, variant):
    out = []
    for x in LOG:
        v = 0.0
        for k, hz, db, w in SPEC[name][variant]:
            v += bump(x, hz, db, w) if k == "B" else low_shelf(x, hz, db, w)
        out.append(v)
    return out

CURVES = {(n, v): curve(n, v) for n in ORDER for v in ("full", "small")}
BUDGET = 6.0

def mix(amounts, small):
    v = "small" if small else "full"
    out = [0.0] * 31
    for a, n in zip(amounts, ORDER):
        if a:
            c = CURVES[(n, v)]
            for i in range(31): out[i] += a * c[i]
    peak = max(out)
    scale = BUDGET / peak if peak > BUDGET else 1.0
    return [o * scale for o in out], scale

# ---- MBC blend ----
BEATS_ATT = [50, 30, 25, 12, 4, 5]
BEATS_REL = [250, 150, 120, 80, 50, 50]
BEATS_RAT = [3.0, 2.8, 2.2, 2.5, 2.8, 2.0]
BASE = dict(att=[40,15,12,6,1.5,2], rel=[250,160,130,90,45,50], rat=[4.0,3.5,2.5,3.0,3.5,2.5], thr=[-6,-6,-6,-7,-9,-8])

def geo(a, b, t): return math.exp(math.log(a) * (1 - t) + math.log(b) * t)

def blend_mbc(base, amounts):
    a_bass, a_sur, a_beat, a_voc = amounts
    att = [geo(base["att"][i], BEATS_ATT[i], a_beat) for i in range(6)]
    rel = [geo(base["rel"][i], BEATS_REL[i], a_beat) for i in range(6)]
    rat = [base["rat"][i] * (1 - a_beat) + BEATS_RAT[i] * a_beat for i in range(6)]
    thr = list(base["thr"])
    rel[0] += 70 * a_bass; rel[1] += 40 * a_bass
    thr[4] -= 2.0 * a_voc; rat[4] += 1.0 * a_voc
    return dict(att=att, rel=rel, rat=rat, thr=thr)

# ---- renderer: v4 PCHIP vs fast (precomputed basis) ----
SL = [31,62,125,250,500,1000,2000,4000,8000,16000]
XS = [math.log2(f) for f in SL]

def pchip_v4(ys, x):
    n = len(XS)
    if x <= XS[0]: return ys[0]
    if x >= XS[-1]: return ys[-1]
    h = [XS[i+1]-XS[i] for i in range(n-1)]
    d = [(ys[i+1]-ys[i])/h[i] for i in range(n-1)]
    m = [0.0]*n
    for i in range(1, n-1):
        if d[i-1]*d[i] <= 0: m[i] = 0.0
        else:
            w1 = 2*h[i]+h[i-1]; w2 = h[i]+2*h[i-1]
            m[i] = (w1+w2)/(w1/d[i-1]+w2/d[i])
    k = next(i for i in range(n-1) if x <= XS[i+1])
    t = (x-XS[k])/h[k]; t2=t*t; t3=t2*t
    return (2*t3-3*t2+1)*ys[k]+(t3-2*t2+t)*h[k]*m[k]+(-2*t3+3*t2)*ys[k+1]+(t3-t2)*h[k]*m[k+1]

H = [XS[i+1]-XS[i] for i in range(9)]
SEG, BAS = [], []
for x in LOG:
    if x <= XS[0]: SEG.append(-1); BAS.append(None)
    elif x >= XS[-1]: SEG.append(-2); BAS.append(None)
    else:
        k = next(i for i in range(9) if x <= XS[i+1]); t=(x-XS[k])/H[k]; t2=t*t; t3=t2*t
        SEG.append(k); BAS.append((2*t3-3*t2+1, t3-2*t2+t, -2*t3+3*t2, t3-t2))

def pchip_fast(ys):
    d = [(ys[i+1]-ys[i])/H[i] for i in range(9)]
    m = [0.0]*10
    for i in range(1, 9):
        if d[i-1]*d[i] > 0:
            w1 = 2*H[i]+H[i-1]; w2 = H[i]+2*H[i-1]
            m[i] = (w1+w2)/(w1/d[i-1]+w2/d[i])
    out = []
    for i in range(31):
        k = SEG[i]
        if k == -1: out.append(ys[0])
        elif k == -2: out.append(ys[9])
        else:
            a, b, c, e = BAS[i]
            out.append(a*ys[k]+b*H[k]*m[k]+c*ys[k+1]+e*H[k]*m[k+1])
    return out

if __name__ == "__main__":
    print("== curves at 100% (dB) ==")
    print("  i   Hz     bass_full bass_small surround beats_full beats_small vocal")
    for i, f in enumerate(CENTRES):
        print(f"{i:3d} {f:7.1f} {CURVES[('deep_bass','full')][i]:8.2f} {CURVES[('deep_bass','small')][i]:9.2f} "
              f"{CURVES[('surround','full')][i]:8.2f} {CURVES[('beats','full')][i]:9.2f} {CURVES[('beats','small')][i]:10.2f} {CURVES[('vocal','full')][i]:6.2f}")
    print("\n== peaks (dB) per enhancer ==")
    for n in ORDER:
        print(n, "full %.2f  small %.2f" % (max(CURVES[(n,'full')]), max(CURVES[(n,'small')])))
    print("\n== stacking: peak before budget / scale applied ==")
    worst = 0
    for combo in itertools.product([0, 1], repeat=4):
        for small in (False, True):
            out, s = mix(list(map(float, combo)), small)
            v = "small" if small else "full"
            raw = max(sum(a*CURVES[(n,v)][i] for a,n in zip(combo,ORDER)) for i in range(31))
            if combo in [(1,0,0,0),(1,0,1,0),(1,1,1,1),(0,0,1,1)]:
                print(combo, v, "raw peak %.2f -> scale %.3f -> peak %.2f" % (raw, s, max(out)))
            worst = max(worst, max(out))
    print("max peak after budget over all on/off combos: %.2f" % worst)
    # continuity of the budget: scale is continuous in amount
    prev = None; maxjump = 0
    for k in range(0, 101):
        out, s = mix([k/100, 0, k/100, 0], False)
        if prev: maxjump = max(maxjump, max(abs(a-b) for a, b in zip(out, prev)))
        prev = out
    print("largest per 0.01 amount step (bass+beats sweep): %.3f dB" % maxjump)

    print("\n== golden: bass 100% full, selected centres ==")
    for i in (0, 3, 6, 9, 12, 20, 24, 28):
        print(i, round(CURVES[("deep_bass","full")][i], 3))
    out, s = mix([1.0, 0.5, 0.0, 0.0], False)
    print("GOLDEN_MIX bass1.0+surround0.5 full:", [round(x, 3) for x in out])
    out, s = mix([0.0, 0.0, 1.0, 1.0], True)
    print("GOLDEN_MIX beats1.0+vocal1.0 small:", [round(x, 3) for x in out], "scale", round(s, 4))

    print("\n== MBC blend ==")
    for am in ([0,0,0,0],[1,0,0,0],[0,0,1,0],[0,0,0,1],[0.5,0,0.5,0.5]):
        b = blend_mbc(BASE, am)
        print(am, "att", [round(x,2) for x in b["att"]], "rel", [round(x,1) for x in b["rel"]], "rat", [round(x,2) for x in b["rat"]], "thr", b["thr"])

    print("\n== fast PCHIP vs v4 PCHIP ==")
    random.seed(7); worst = 0
    for _ in range(2000):
        ys = [random.uniform(-12, 12) for _ in range(10)]
        f = pchip_fast(ys)
        ref = [pchip_v4(ys, x) for x in LOG]
        worst = max(worst, max(abs(a-b) for a, b in zip(f, ref)))
    print("max |fast - v4| over 2000 random slider sets: %.2e dB" % worst)
