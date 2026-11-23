"""
The continents as small islands for Loka Helper's Important Waypoints, drawn from Loka's own data:
each territory's outline from Loka's map (the Dynmap markers the Loka Map module reads too) and its
biome from Loka's API, so Kalros comes out icy, Garama sandy and Balak with its volcano.

    python3 islands.py <workdir> <outdir>

Downloads into <workdir>, writes <outdir>/<continent>.png (with a .png.mcmeta so the game smooths
them when it scales them) and prints each picture's size for LokaHelperScreen's island table.
Aladra, the spawn island, is on neither, so it is drawn here as a ring of hexes round its portals.
Needs numpy and Pillow.
"""
import json, math, os, random, sys, urllib.request

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

WORLDS = {  # continent id: (map instance, world key)
    "kalros": ("kalros", "north"),
    "ascalon": ("ascalon", "west"),
    "garama": ("garama", "south"),
    "rivina": ("conquest", "lilboi"),
    "balak": ("conquest", "bigboi"),
}

BIOME = {
    # Kalros
    "moor": (128, 124, 88), "megataiga": (62, 98, 66), "taiga": (78, 118, 80), "roofedforest": (44, 80, 44),
    "icemountains": (232, 240, 248), "icetaiga": (170, 200, 192), "icewastes": (210, 226, 240),
    "cherrygrove": (234, 162, 196),
    # Ascalon
    "sunsetisles": (236, 168, 112), "forest": (82, 150, 72), "mountains": (142, 148, 152),
    "ancientforest": (58, 110, 60), "claycliffs": (190, 122, 92), "mushroomisle": (160, 120, 160),
    "birchforest": (142, 186, 102), "mangrove": (80, 122, 72), "plains": (130, 190, 92),
    # Garama
    "savannah": (184, 172, 94), "ancientjungle": (40, 110, 52), "jungle": (58, 142, 62), "mesa": (200, 112, 72),
    "palegarden": (176, 184, 176), "desert": (230, 210, 142), "swamp": (86, 112, 76),
    # Rivina
    "lbisle1": (110, 182, 100), "lbisle2": (96, 170, 122), "lbisle3": (122, 192, 92), "lbisle4": (90, 162, 112),
    "lbisle5": (132, 186, 98),
    # Balak
    "balak_volcano": (74, 56, 52), "balak_ashlands": (112, 102, 98), "balak_boneyard": (214, 206, 182),
    "balak_northfields": (112, 172, 82), "balak_beach": (226, 210, 152), "balak_westcoast": (92, 152, 92),
}
DEFAULT = (120, 164, 92)
SAND = (226, 210, 156)
SHALLOW = (110, 200, 232)
LONGEST = 360          # the picture's longest side, in pixels
SS = 2                 # drawn at twice the size, then halved, for smooth edges


def fetch(url, path):
    if not os.path.exists(path):
        request = urllib.request.Request(url, headers={"User-Agent": "BetterLoka island renderer"})
        with urllib.request.urlopen(request, timeout=60) as response, open(path, "wb") as out:
            out.write(response.read())
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def continent_shapes(work, cid):
    instance, world = WORLDS[cid]
    markers = fetch(f"https://map.lokamc.com/{instance}/tiles/_markers_/marker_{world}.json", f"{work}/{world}.json")
    api = fetch(f"https://api.lokamc.com/territories/search/findByWorld?world={world}", f"{work}/api-{world}.json")
    api = api["_embedded"]["territories"] if isinstance(api, dict) else api
    biome = {t["num"]: t.get("areaName") for t in api}
    shapes = []
    for area in markers["sets"]["Territories"]["areas"].values():
        shapes.append((list(zip(area["x"], area["z"])), biome.get(area.get("label"))))
    return shapes


def hex_points(cx, cz, r):
    return [(cx + r * math.cos(math.radians(60 * k + 30)), cz + r * math.sin(math.radians(60 * k + 30))) for k in range(6)]


def aladra_shapes():
    """Spawn: a portal hex in the middle and a ring of plains, forest and sand round it."""
    r = 100.0
    w = math.sqrt(3) * r
    shapes = [(hex_points(0, 0, r), "aladra_spawn")]
    ring = ["plains", "forest", "birchforest", "plains", "sunsetisles", "forest"]
    for k, kind in enumerate(ring):
        a = math.radians(60 * k)
        shapes.append((hex_points(w * math.cos(a), w * math.sin(a), r), kind))
    return shapes


def mask_of(img):
    return np.array(img.split()[-1], dtype=np.float32) / 255.0


def grow(mask_img, pixels):
    out = mask_img
    while pixels > 0:
        step = min(pixels, 6)
        out = out.filter(ImageFilter.MaxFilter(2 * step + 1))
        pixels -= step
    return out


def render(shapes, cid):
    xs = [x for pts, _ in shapes for x, _ in pts]
    zs = [z for pts, _ in shapes for _, z in pts]
    x0, x1, z0, z1 = min(xs), max(xs), min(zs), max(zs)
    scale = (LONGEST * SS * 0.80) / max(x1 - x0, z1 - z0)
    margin = int(LONGEST * SS * 0.10)
    W = int((x1 - x0) * scale) + 2 * margin
    H = int((z1 - z0) * scale) + 2 * margin

    def px(pts):
        return [(margin + (x - x0) * scale, margin + (z - z0) * scale) for x, z in pts]

    land = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(land)
    for pts, biome in shapes:
        colour = (190, 188, 178) if biome == "aladra_spawn" else BIOME.get(biome, DEFAULT)
        draw.polygon(px(pts), fill=colour + (255,))
    # Seal the hairline seams between neighbouring hexes.
    alpha = land.split()[-1].filter(ImageFilter.MaxFilter(3)).filter(ImageFilter.MinFilter(3))
    rgb = land.convert("RGB").filter(ImageFilter.MaxFilter(3))
    land = Image.merge("RGBA", (*rgb.split(), alpha))

    # Light from the top left, and a little grain, so the flat colours read as ground.
    a = np.array(land, dtype=np.float32)
    yy, xx = np.mgrid[0:H, 0:W]
    light = 1.10 - 0.20 * ((xx / W) * 0.4 + (yy / H) * 0.6)
    rng = np.random.default_rng(len(cid) * 7919)
    grain = 1.0 + rng.normal(0, 0.035, (H, W))
    a[..., :3] = np.clip(a[..., :3] * (light * grain)[..., None], 0, 255)
    land = Image.fromarray(a.astype(np.uint8), "RGBA")

    # Territory borders, in a darker shade of each one's own colour, as Loka's map draws them.
    borders = ImageDraw.Draw(land)
    for pts, biome in shapes:
        colour = (190, 188, 178) if biome == "aladra_spawn" else BIOME.get(biome, DEFAULT)
        dark = tuple(int(c * 0.62) for c in colour) + (210,)
        p = px(pts)
        borders.line(p + [p[0]], fill=dark, width=SS, joint="curve")
    land.putalpha(alpha)

    # Details: Balak's volcano smoulders, Aladra has its portal.
    detail = ImageDraw.Draw(land)
    for pts, biome in shapes:
        p = px(pts)
        cx = sum(x for x, _ in p) / len(p)
        cy = sum(y for _, y in p) / len(p)
        if biome == "balak_volcano":
            r = 9 * SS
            detail.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(52, 38, 36, 255))
            r = 5 * SS
            detail.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(255, 120, 40, 255))
            r = 2 * SS
            detail.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(255, 220, 120, 255))
        if biome == "aladra_spawn":
            w, h = 14 * SS, 20 * SS
            detail.rectangle((cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), fill=(36, 24, 52, 255))
            detail.rectangle((cx - w / 2 + 3 * SS, cy - h / 2 + 3 * SS, cx + w / 2 - 3 * SS, cy + h / 2 - 3 * SS),
                             fill=(168, 84, 236, 255))

    # Beach round the land, shallow water round the beach.
    beach_mask = grow(alpha, 4 * SS).filter(ImageFilter.GaussianBlur(SS * 0.6))
    shallow_mask = grow(alpha, 14 * SS).filter(ImageFilter.GaussianBlur(9 * SS))
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    shallow = Image.new("RGBA", (W, H), SHALLOW + (0,))
    shallow.putalpha(shallow_mask.point(lambda v: int(v * 0.55)))
    out = Image.alpha_composite(out, shallow)
    beach = Image.new("RGBA", (W, H), SAND + (0,))
    beach.putalpha(beach_mask)
    out = Image.alpha_composite(out, beach)
    # A thin dark line where the beach meets the water, so the coast holds at small sizes.
    edge = np.clip(np.array(grow(alpha, 5 * SS), np.float32) - np.array(grow(alpha, 4 * SS), np.float32), 0, 255)
    rim = Image.new("RGBA", (W, H), (40, 70, 80, 0))
    rim.putalpha(Image.fromarray((edge * 0.55).astype(np.uint8)))
    out = Image.alpha_composite(out, rim)
    out = Image.alpha_composite(out, land)
    return out.resize((W // SS, H // SS), Image.LANCZOS)


def main():
    work, outdir = sys.argv[1], sys.argv[2]
    os.makedirs(work, exist_ok=True)
    os.makedirs(outdir, exist_ok=True)
    sizes = {}
    for cid in ["aladra"] + list(WORLDS):
        shapes = aladra_shapes() if cid == "aladra" else continent_shapes(work, cid)
        image = render(shapes, cid)
        image.save(f"{outdir}/{cid}.png", optimize=True)
        with open(f"{outdir}/{cid}.png.mcmeta", "w") as f:
            f.write('{\n  "texture": {\n    "blur": true,\n    "clamp": true\n  }\n}\n')
        sizes[cid] = image.size
        print(cid, image.size)
    print(json.dumps(sizes))


if __name__ == "__main__":
    main()
