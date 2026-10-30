"""Reference implementation of the AutoEq -> correction31 conversion (section 16 of the v5 doc).

Purpose: produce golden test vectors that the Kotlin port must reproduce (tolerance 0.05 dB).
Pure standard library. Run with: python3 -I autoeq_ref.py
"""
import math
import re

CENTRES = [20.0 * 2 ** (i / 3.0) for i in range(31)]

LINE = re.compile(
    r"^\s*Filter\s*\d*\s*:\s*(ON|OFF)\s+(PK|LSC|HSC)\s+Fc\s+([-+0-9.eE]+)\s*Hz\s+Gain\s+([-+0-9.eE]+)\s*dB\s+Q\s+([-+0-9.eE]+)",
    re.IGNORECASE,
)
PREAMP = re.compile(r"^\s*Preamp\s*:\s*([-+0-9.eE]+)\s*dB", re.IGNORECASE)


def parse_peq(text):
    preamp = 0.0
    filters = []
    for line in text.splitlines():
        m = PREAMP.match(line)
        if m:
            preamp = float(m.group(1))
            continue
        m = LINE.match(line)
        if not m:
            continue
        on = m.group(1).upper() == "ON"
        typ = m.group(2).upper()
        fc, gain, q = float(m.group(3)), float(m.group(4)), float(m.group(5))
        if on and 10 <= fc <= 24000 and 0.1 <= q <= 20 and abs(gain) <= 30:
            filters.append((typ, fc, gain, q))
    return preamp, filters


def biquad(typ, fc, gain_db, q, fs):
    A = 10 ** (gain_db / 40.0)
    w0 = 2 * math.pi * fc / fs
    cw, sw = math.cos(w0), math.sin(w0)
    alpha = sw / (2 * q)
    if typ == "PK":
        b = (1 + alpha * A, -2 * cw, 1 - alpha * A)
        a = (1 + alpha / A, -2 * cw, 1 - alpha / A)
    elif typ == "LSC":
        s = 2 * math.sqrt(A) * alpha
        b = (A * ((A + 1) - (A - 1) * cw + s), 2 * A * ((A - 1) - (A + 1) * cw), A * ((A + 1) - (A - 1) * cw - s))
        a = ((A + 1) + (A - 1) * cw + s, -2 * ((A - 1) + (A + 1) * cw), (A + 1) + (A - 1) * cw - s)
    else:  # HSC
        s = 2 * math.sqrt(A) * alpha
        b = (A * ((A + 1) + (A - 1) * cw + s), -2 * A * ((A - 1) + (A + 1) * cw), A * ((A + 1) + (A - 1) * cw - s))
        a = ((A + 1) - (A - 1) * cw + s, 2 * ((A - 1) - (A + 1) * cw), (A + 1) - (A - 1) * cw - s)
    return b, a


def mag_db(b, a, f, fs):
    w = 2 * math.pi * f / fs
    z1 = complex(math.cos(-w), math.sin(-w))
    z2 = z1 * z1
    num = b[0] + b[1] * z1 + b[2] * z2
    den = a[0] + a[1] * z1 + a[2] * z2
    return 20 * math.log10(abs(num / den))


def taper(f):
    if f <= 10000:
        return 1.0
    if f >= 14000:
        return 0.0
    return 1.0 - math.log2(f / 10000.0) / math.log2(1.4)


def raw_response(filters, fs):
    return [sum(mag_db(*biquad(t, fc, g, q, fs)[:2], f, fs) if False else mag_db(*biquad(t, fc, g, q, fs), f, fs)
                for t, fc, g, q in filters) for f in CENTRES]


def to_correction31(filters, strength=0.8, fs=44100.0, cap_lo=6.0, cap_hi=3.0, cut=-12.0):
    raw = raw_response(filters, fs)
    out = []
    for f, r in zip(CENTRES, raw):
        v = r * taper(f)
        v = max(cut, min(cap_hi if f > 6000 else cap_lo, v))
        out.append(v * strength)
    return out


# ---------- GraphicEQ ("GraphicEQ: 20 -0.5; 21 -0.5; ...") ----------
def parse_graphic_eq(text):
    m = re.search(r"GraphicEQ\s*:\s*(.+)", text)
    pts = []
    for part in m.group(1).split(";"):
        part = part.strip()
        if part:
            f, g = part.split()
            pts.append((float(f), float(g)))
    return pts


def graphic_to_31(points, strength=0.8):
    """Log-frequency linear interpolation to the 31 centres, level-normalised so the 500 Hz-2 kHz mean is 0 dB."""
    xs = [math.log2(f) for f, _ in points]
    ys = [g for _, g in points]

    def at(f):
        x = math.log2(f)
        if x <= xs[0]:
            return ys[0]
        if x >= xs[-1]:
            return ys[-1]
        for i in range(len(xs) - 1):
            if x <= xs[i + 1]:
                t = (x - xs[i]) / (xs[i + 1] - xs[i])
                return ys[i] + (ys[i + 1] - ys[i]) * t

    cur = [at(f) for f in CENTRES]
    ref = sum(c for f, c in zip(CENTRES, cur) if 500 <= f <= 2000) / sum(1 for f in CENTRES if 500 <= f <= 2000)
    out = []
    for f, c in zip(CENTRES, cur):
        v = (c - ref) * taper(f)
        v = max(-12.0, min(3.0 if f > 6000 else 6.0, v))
        out.append(v * strength)
    return out


# ---------- INDEX.md ----------
INDEX_LINE = re.compile(r"^- \[(.+?)\]\(\./(.+?)\)(?: by (.+?))?(?: on (.+))?$")


def parse_index_line(line):
    m = INDEX_LINE.match(line.strip())
    if not m:
        return None
    name, path, by, rig = m.groups()
    return dict(name=name, path=path, source=by, rig=rig)


if __name__ == "__main__":
    # Real AutoEq-format sample (as quoted in a public forum post)
    sample = """Preamp: -4.70 dB
Filter 1: ON LSC Fc 105.0 Hz Gain -3.2 dB Q 0.70
Filter 2: ON PK Fc 174.8 Hz Gain -4.3 dB Q 0.93
Filter 3: ON PK Fc 718.6 Hz Gain 2.4 dB Q 1.85
Filter 4: ON PK Fc 3464.3 Hz Gain 4.5 dB Q 1.99
Filter 5: ON HSC Fc 10000.0 Hz Gain 3.4 dB Q 0.70
Filter 6: OFF PK Fc 0 Hz Gain 0.0 dB Q 0.00
"""
    pre, flt = parse_peq(sample)
    print("preamp", pre, "| enabled filters", len(flt))
    r44 = raw_response(flt, 44100.0)
    r48 = raw_response(flt, 48000.0)
    c44 = to_correction31(flt, 0.8, 44100.0)
    c48 = to_correction31(flt, 0.8, 48000.0)
    print(" i  centreHz  raw44.1   raw48    corr(0.8)@44.1")
    for i, f in enumerate(CENTRES):
        print(f"{i:2d} {f:9.1f} {r44[i]:8.3f} {r48[i]:8.3f} {c44[i]:8.3f}")
    print("max |44.1 vs 48k| difference over all centres (dB): %.3f" % max(abs(a - b) for a, b in zip(r44, r48)))
    print("same, centres <= 10 kHz only: %.3f" % max(abs(a - b) for a, b, f in zip(r44, r48, CENTRES) if f <= 10000))
    print("max corr %.3f -> extra input cut %.3f" % (max(c44), max(0.0, max(c44) - 3.0)))
    print("COR31_PEQ =", [round(x, 3) for x in c44])

    # GraphicEQ sample
    g = "GraphicEQ: 20 -0.5; 25 -0.5; 32 -0.9; 40 -2.1; 63 -3.6; 100 -5.0; 125 -1.9; 250 -2.5; 500 -1.0; 1000 -0.5; 2000 -0.4; 4000 2.0; 8000 -3.0; 16000 -6.0; 20000 -6.0"
    pts = parse_graphic_eq(g)
    gc = graphic_to_31(pts)
    print("COR31_GRAPHIC =", [round(x, 3) for x in gc])

    # INDEX line sample (format as documented by a third-party reader of results/INDEX.md)
    ln = "- [Sennheiser HD 650](./crinacle/GRAS%2043AG-7%20over-ear/Sennheiser%20HD%20650) by crinacle on GRAS 43AG-7"
    print(parse_index_line(ln))
    ln2 = "- [Sennheiser HD 800](./oratory1990/over-ear/Sennheiser%20HD%20800) by oratory1990"
    print(parse_index_line(ln2))
