#!/usr/bin/env python3
"""Rebuild the renamed, single-cell terminal fallback with fonttools 4.63.0.
Input: Nerd Fonts v3.4.0 patched-fonts/FiraCode/Regular/FiraCodeNerdFontMono-Regular.ttf.
No Latin glyphs; the terminal keeps its system monospace metrics.
"""
import sys
from fontTools import subset
from fontTools.ttLib import TTFont

source = sys.argv[1]
output = 'app/src/main/res/font/nerd_terminal.ttf'
options = subset.Options()
options.name_IDs = ['*']
options.name_legacy = True
options.name_languages = ['*']
font = TTFont(source)
subsetter = subset.Subsetter(options=options)
ranges = [(0xe0a0, 0xe0d7), (0xe5fa, 0xe62f), (0xe700, 0xe8ef),
          (0xea60, 0xebeb), (0xf000, 0xf2ff), (0xf300, 0xf381), (0xf400, 0xf533)]
subsetter.populate(unicodes=[cp for start, end in ranges for cp in range(start, end + 1)])
subsetter.subset(font)
names = {1: 'Ot3 Terminal Symbols', 2: 'Regular', 3: 'Ot3TerminalSymbols-Regular',
         4: 'Ot3 Terminal Symbols Regular', 6: 'Ot3TerminalSymbols-Regular'}
for record in font['name'].names:
    if record.nameID in names:
        record.string = names[record.nameID].encode(record.getEncoding())
font.save(output)
