#!/usr/bin/env python3
"""Compile the sky's AGSL with Skia's SkSL compiler and render one frame on the CPU.

AGSL is Skia's SkSL, so skia-python's RuntimeEffect compiles it the way Android's
RuntimeShader does, without a device. See docs/ui/sky.md.

Usage (in a virtualenv with skia-python and numpy):
  scripts/check-starfield-agsl.py [--theme tokyo-night|catppuccin-latte] [--clock S]
                                  [--out sky.png] [--compare reference.png]

--compare takes the JVM reference render (app/build/sky/sky-<theme>-agsl-reference.png,
written by SkyCanvasPlanTest with its clock in sky-<theme>.clock) and prints the
per-channel difference. Exits non-zero when the shader does not compile.
"""
import argparse
import pathlib
import re
import struct
import sys

import skia

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = ROOT / "app/src/main/kotlin/dev/avillagran/ot3android/ui/sky/StarfieldShader.kt"

# background, foreground, accent (tokens.md) and the light-sky flag.
THEMES = {
    "tokyo-night": ((0x1A, 0x1B, 0x26), (0xA9, 0xB1, 0xD6), (0x7A, 0xA2, 0xF7), False),
    "catppuccin-latte": ((0xEF, 0xF1, 0xF5), (0x4C, 0x4F, 0x69), (0x1E, 0x66, 0xF5), True),
}


def rgba(c):
    return [c[0] / 255, c[1] / 255, c[2] / 255, 1.0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--theme", default="tokyo-night", choices=THEMES)
    ap.add_argument("--clock", type=float, default=20.0)
    ap.add_argument("--out", default="sky-agsl.png")
    ap.add_argument("--compare")
    args = ap.parse_args()

    agsl = re.search(r'STARFIELD_AGSL: String = """(.*?)"""', SOURCE.read_text(), re.S).group(1)
    effect = skia.RuntimeEffect.MakeForShader(agsl)
    if effect is None:
        sys.exit("AGSL did not compile")
    names = [u.name for u in effect.uniforms()]
    print("compiled; uniforms:", ", ".join(names))

    bg, fg, accent, light = THEMES[args.theme]
    base = [bg[i] / 255 * 0.94 + fg[i] / 255 * 0.06 for i in range(3)] if light else [v / 255 / 2.4 for v in bg]
    # The same frame as SkyCanvasPlanTest: 411 x 126 dp at density 2, drift 640, energy 1.
    values = {
        "pixelDensity": [2.0], "opacity": [1.0], "drift": [640.0], "clock": [args.clock],
        "meteors": [0.85], "brightness": [0.9 if light else 1.25], "nebula": [0.55], "scroll": [0.0],
        "fade": [1 - 62 * 0.85 / 126], "size": [411.0, 126.0], "origin": [0.0, 0.0],
        "star": rgba(fg), "accent": rgba(accent), "base": base + [1.0],
        "meteor": rgba(fg) if light else [1.0, 1.0, 1.0, 1.0], "ink": [1.7 if light else 1.0],
    }
    data = b"".join(struct.pack("%df" % len(values[n]), *values[n]) for n in names)
    shader = effect.makeShader(skia.Data.MakeWithCopy(data))
    surface = skia.Surface(822, 252)
    canvas = surface.getCanvas()
    canvas.clear(skia.Color(*bg))
    paint = skia.Paint()
    paint.setShader(shader)
    canvas.drawPaint(paint)
    surface.makeImageSnapshot().save(args.out, skia.kPNG)
    print("wrote", args.out)

    if args.compare:
        import numpy as np

        def load(path):
            image = skia.Image.open(path).convert(alphaType=skia.kUnpremul_AlphaType, colorType=skia.kRGBA_8888_ColorType)
            return np.array(image)[..., :3].astype(int)

        diff = np.abs(load(args.out) - load(args.compare))
        print("mean abs diff %.4f / 255, max %d, pixels over 8: %d" % (diff.mean(), diff.max(), (diff.max(axis=2) > 8).sum()))


if __name__ == "__main__":
    main()
