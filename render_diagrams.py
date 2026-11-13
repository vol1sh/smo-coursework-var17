#!/usr/bin/env python3
"""Render all PlantUML diagrams to SVG via public PlantUML server (no Java required)."""
import sys
import zlib
from pathlib import Path
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET

ALPHABET = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_'
BASE = Path(__file__).resolve().parent

def plantuml_encode(text):
    data = zlib.compress(text.encode('utf-8'))[2:-4]
    result = []
    for i in range(0, len(data), 3):
        chunk = data[i:i+3].ljust(3, b'\x00')
        a, b, c = chunk
        result.extend((ALPHABET[a >> 2], ALPHABET[((a & 3) << 4) | (b >> 4)],
                       ALPHABET[((b & 15) << 2) | (c >> 6)], ALPHABET[c & 63]))
    return ''.join(result)

def render(path):
    source = path.read_text(encoding='utf-8')
    url = 'https://www.plantuml.com/plantuml/svg/' + plantuml_encode(source)
    req = Request(url, headers={'User-Agent': 'smo-coursework-var17-render/1.0'})
    with urlopen(req, timeout=45) as resp:
        svg = resp.read()
    root = ET.fromstring(svg)
    if not root.tag.endswith('svg') or b'Syntax Error' in svg or b'Error line' in svg:
        raise ValueError('PlantUML reported invalid SVG/error: ' + path.name)
    if len(svg) < 1000:
        raise ValueError('SVG unexpectedly short: ' + path.name)
    target = path.with_suffix('.svg')
    target.write_bytes(svg)
    print(f'OK: {target.relative_to(BASE)} ({len(svg)} bytes)')

if __name__ == '__main__':
    try:
        for file in sorted((BASE / 'docs').glob('*.puml')):
            render(file)
    except Exception as exc:
        print(f'ОШИБКА: {exc}', file=sys.stderr)
        sys.exit(1)
