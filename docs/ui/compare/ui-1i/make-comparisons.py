"""Compose the evidence PNGs without changing the source screenshots (Pillow)."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

here = Path(__file__).resolve().parent
font_path = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
font = ImageFont.truetype(font_path, 18)
small = ImageFont.truetype(font_path, 13)
reference = Image.open(here.parents[1] / 'reference/ot3-sidebar-and-thread.png').crop((40, 60, 510, 1210))
sources = [
    ('ot3 reference: sidebar crop', reference),
    ('9df6d39 + rich fixture: Latte', Image.open(here / 'baseline-rich-home.png')),
    ('UI1i: same fixture / Latte', Image.open(here / 'after-rich-home.png')),
    ('UI1i: extra Tokyo Night view', Image.open(here / 'after-rich-dark-home.png')),
]
width, gap, top = 360, 16, 70
images = [im.resize((width, round(im.height * width / im.width))) for _, im in sources]
out = Image.new('RGB', (gap + (width + gap) * 4, top + max(im.height for im in images) + 50), '#f6f6f6')
draw = ImageDraw.Draw(out)
for i, ((label, _), im) in enumerate(zip(sources, images)):
    x = gap + i * (width + gap)
    draw.text((x, 12), label, font=font, fill='#222222')
    out.paste(im, (x, top))
draw.text((gap, out.height - 35), 'Different desktop theme/content. Compare composition and roles; source wins over old reference captures.', font=small, fill='#333333')
out.save(here / 'comparison-home.png')
out = Image.new('RGB', (932, 1070), '#f6f6f6')
draw = ImageDraw.Draw(out)
for i, (name, label) in enumerate([('before-chat.png', 'UI1h baseline / Latte'), ('after-chat.png', 'UI1i chrome / same content')]):
    x = 8 + i * 466
    draw.text((x + 4, 12), label, font=font, fill='#222222')
    out.paste(Image.open(here / name).resize((450, 1000)), (x, 50))
out.save(here / 'comparison-chat.png')
