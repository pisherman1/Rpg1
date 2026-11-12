"""Generates the tileable dungeon textures (albedo + normal maps) in assets/textures.

Run: python3 tools/make_textures.py   (needs numpy and Pillow)
"""
import numpy as np
from PIL import Image
import os

N = 256
OUT = os.path.join(os.path.dirname(__file__), "..", "assets", "textures")
rng = np.random.default_rng(7)


def noise(scale, octaves=4, n=N):
    """Tileable fractal noise in 0..1 built from filtered white noise (FFT, so it wraps)."""
    total = np.zeros((n, n))
    amp, s = 1.0, scale
    fy = np.fft.fftfreq(n)[:, None]
    fx = np.fft.fftfreq(n)[None, :]
    f = np.sqrt(fx * fx + fy * fy)
    for _ in range(octaves):
        white = rng.standard_normal((n, n))
        filt = np.exp(-(f * s) ** 2 * 40)
        layer = np.real(np.fft.ifft2(np.fft.fft2(white) * filt))
        layer = (layer - layer.min()) / (layer.max() - layer.min())
        total += layer * amp
        amp *= 0.5
        s *= 0.5
    return (total - total.min()) / (total.max() - total.min())


def normal_map(height, strength):
    dx = (np.roll(height, -1, 1) - np.roll(height, 1, 1)) * strength
    dy = (np.roll(height, -1, 0) - np.roll(height, 1, 0)) * strength
    nz = np.ones_like(height)
    l = np.sqrt(dx * dx + dy * dy + nz * nz)
    n = np.stack([-dx / l, dy / l, nz / l], -1)  # OpenGL-style (Godot) green up
    return ((n * 0.5 + 0.5) * 255).astype(np.uint8)


def save(name, rgb, height, strength):
    Image.fromarray(np.clip(rgb * 255, 0, 255).astype(np.uint8)).save(os.path.join(OUT, name + "_albedo.png"))
    Image.fromarray(normal_map(height, strength)).save(os.path.join(OUT, name + "_normal.png"))


def tint(gray, low, high):
    low, high = np.array(low), np.array(high)
    return low + (high - low) * gray[..., None]


def flagstones():
    """Irregular stone slabs separated by grout."""
    h = np.zeros((N, N))
    ids = np.zeros((N, N), int)
    rows = [0, 70, 128, 200, 256]
    k = 0
    for r in range(4):
        y0, y1 = rows[r], rows[r + 1]
        x = int(rng.integers(0, 60))
        cuts = [x]
        while cuts[-1] < x + N:
            cuts.append(cuts[-1] + int(rng.integers(70, 120)))
        for c in range(len(cuts) - 1):
            for xx in range(cuts[c], cuts[c + 1]):
                ids[y0:y1, xx % N] = k
            k += 1
    # Distance to slab edge -> bevel
    edge = np.zeros((N, N), bool)
    edge |= ids != np.roll(ids, 1, 0)
    edge |= ids != np.roll(ids, 1, 1)
    dist = np.full((N, N), 12.0)
    d = np.where(edge, 0.0, 12.0)
    for _ in range(12):
        d = np.minimum(d, np.minimum.reduce([np.roll(d, 1, 0) + 1, np.roll(d, -1, 0) + 1, np.roll(d, 1, 1) + 1, np.roll(d, -1, 1) + 1]))
    dist = d
    bevel = np.clip(dist / 6.0, 0, 1)
    slab_tone = rng.uniform(0.75, 1.1, k + 1)[ids]
    n1, n2 = noise(8), noise(30)
    h = bevel * (0.7 + 0.3 * n1) + 0.15 * n2
    grout = dist < 1.5
    gray = (0.55 + 0.35 * n1 - 0.15 * n2) * slab_tone
    gray = np.where(grout, 0.18 + 0.1 * n2, gray * (0.75 + 0.25 * bevel))
    rgb = tint(np.clip(gray, 0, 1), (0.10, 0.09, 0.10), (0.62, 0.57, 0.52))
    # Moss and dirt in the cracks
    moss = (noise(20) > 0.62) & (dist < 4)
    rgb[moss] = rgb[moss] * 0.5 + np.array([0.12, 0.2, 0.08])
    save("floor", rgb, h, 5.0)


def bricks():
    """Rough stone bricks in running bond."""
    bh, bw = 32, 64
    y, x = np.mgrid[0:N, 0:N]
    row = y // bh
    xo = (x + (row % 2) * bw // 2) % N
    col = xo // bw
    ids = row * 10 + col
    fy = (y % bh) / bh
    fx = (xo % bw) / bw
    edge = np.minimum.reduce([fy, 1 - fy, fx * 2, (1 - fx) * 2])
    bevel = np.clip(edge * 8, 0, 1)
    tone = rng.uniform(0.7, 1.15, ids.max() + 1)[ids]
    n1, n2 = noise(10), noise(40)
    h = bevel * (0.75 + 0.25 * n1) + 0.2 * n2
    mortar = edge < 0.06
    gray = (0.5 + 0.3 * n1 - 0.2 * n2) * tone * (0.7 + 0.3 * bevel)
    gray = np.where(mortar, 0.15 + 0.08 * n1, gray)
    rgb = tint(np.clip(gray, 0, 1), (0.09, 0.08, 0.10), (0.58, 0.52, 0.50))
    save("wall", rgb, h, 6.0)


def planks():
    """Dark wooden planks with grain (chests, props)."""
    y, x = np.mgrid[0:N, 0:N]
    plank = x // 64
    grain = noise(3)
    streak = 0.5 + 0.5 * np.sin((x / N) * np.pi * 2 * 22 + grain * 5 + np.sin(y / N * np.pi * 4) * 1.5 + plank * 3)
    gap = (x % 64) < 3
    gray = 0.45 + 0.35 * streak * (0.6 + 0.4 * noise(20))
    gray = np.where(gap, 0.1, gray)
    rgb = tint(np.clip(gray, 0, 1), (0.12, 0.06, 0.03), (0.62, 0.40, 0.22))
    h = np.where(gap, 0.0, 0.6 + 0.2 * streak)
    save("wood", rgb, h, 3.0)


def roughstone():
    """Plain rough rock for wall tops and stairs."""
    n1, n2 = noise(12), noise(50)
    gray = 0.35 + 0.4 * n1 + 0.15 * n2
    rgb = tint(np.clip(gray, 0, 1), (0.08, 0.07, 0.09), (0.5, 0.46, 0.44))
    save("rock", rgb, n1 * 0.7 + n2 * 0.3, 4.0)


def square_flags():
    """Square, slightly uneven floor flags with dark joints (4 x 4 per texture)."""
    cell = N // 4
    y, x = np.mgrid[0:N, 0:N]
    ids = (y // cell) * 4 + (x // cell)
    fy = (y % cell) / cell
    fx = (x % cell) / cell
    edge = np.minimum.reduce([fy, 1 - fy, fx, 1 - fx])
    bevel = np.clip(edge * 10, 0, 1)
    tone = rng.uniform(0.75, 1.1, 16)[ids]
    tilt = rng.uniform(-0.12, 0.12, (16, 2))
    slope = tilt[ids, 0] * (fx - 0.5) + tilt[ids, 1] * (fy - 0.5)
    n1, n2, n3 = noise(6), noise(24), noise(60)
    h = bevel * (0.75 + 0.15 * n1) + 0.12 * n2 + slope
    joint = edge < 0.035
    gray = (0.5 + 0.28 * n1 - 0.12 * n2) * tone * (0.7 + 0.3 * bevel)
    gray = np.where(joint, 0.12 + 0.06 * n3, gray)
    rgb = tint(np.clip(gray, 0, 1), (0.07, 0.075, 0.085), (0.5, 0.52, 0.55))
    cracks = (np.abs(noise(14) - 0.5) < 0.006) & ~joint & (n2 > 0.55)
    rgb[cracks] *= 0.7
    moss = (noise(16) > 0.7) & (edge < 0.06)
    rgb[moss] = rgb[moss] * 0.6 + np.array([0.05, 0.09, 0.04])
    save("floor", rgb, h, 5.0)


os.makedirs(OUT, exist_ok=True)
flagstones()
square_flags()
bricks()
planks()
roughstone()
print("textures written to", os.path.abspath(OUT))
