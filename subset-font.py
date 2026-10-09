"""Regenerate static CJK subsets for the camera's old font renderer.

python -m pip install fonttools
python subset-font.py
Uses pinned upstream fonts and a locally bundled subset helper adapted from Recipe Lab.
"""
import importlib.util
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('recipe_font_subset', root / 'tools/font-subset-common.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
output = root / 'assets/fonts'
output.mkdir(parents=True, exist_ok=True)
english = ''.join(e.text or '' for e in ET.parse(root / 'res/values/strings.xml').getroot())
for locale, font in zip(['zh-rCN', 'zh-rTW'], module.FONTS):
    table, name, path, digest, unused, family = font
    text = ''.join(e.text or '' for e in ET.parse(root / ('res/values-' + locale + '/strings.xml')).getroot())
    chars = set(text + english + ''.join(chr(n) for n in range(32, 127)))
    source = module.source(root / 'out/font-source', name, path, digest)
    target = output / ('GrainLabCJKsc.ttf' if locale == 'zh-rCN' else 'GrainLabCJKtc.ttf')
    module.build(source, target, family.replace('Recipe Lab', 'Grain Lab'), chars)
    from fontTools.ttLib import TTFont
    supported = set(TTFont(target).getBestCmap())
    missing = {ord(c) for c in chars if not c.isspace()} - supported
    if missing:
        raise RuntimeError('Missing glyphs: ' + str(missing))
# The upstream OFL notice is checked in under assets/fonts/OFL.txt.
if not (output / 'OFL.txt').is_file():
    raise RuntimeError('Missing bundled font license: assets/fonts/OFL.txt')
print('PASS: both Chinese translations have all required font glyphs')
