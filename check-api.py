"""Reject platform classes/methods/fields introduced after API 10 in compiled app code."""
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

javap, database, directory = sys.argv[1:]
classes = {c.get('name'): c for c in ET.parse(database).getroot()}

def lookup(name, member, kind, visited=None):
    visited = set() if visited is None else visited
    if name in visited or name not in classes:
        return None
    visited.add(name)
    cls = classes[name]
    for entry in cls.findall(kind):
        if entry.get('name') == member:
            return max(int(cls.get('since', 1)), int(entry.get('since', cls.get('since', 1))))
    for parent in cls.findall('extends') + cls.findall('implements'):
        result = lookup(parent.get('name'), member, kind, visited)
        if result is not None:
            return result
    return None

checked = 0
for path in Path(directory).rglob('*.class'):
    p = subprocess.run([javap, '-verbose', str(path)], capture_output=True, text=True, check=True)
    for kind, owner, member, signature in re.findall(
        r'=\s+(Methodref|InterfaceMethodref|Fieldref)\s+[^\n]+?//\s+([\w/$]+)\.([^:]+):([^\s]+)', p.stdout):
        if owner not in classes:
            continue
        cls = classes[owner]
        assert int(cls.get('since', 1)) <= 10, 'New platform class: ' + owner
        name = member.replace('"', '') + (signature if kind != 'Fieldref' else '')
        since = lookup(owner, name, 'field' if kind == 'Fieldref' else 'method')
        if since is not None:
            assert since <= 10, 'New platform member: ' + owner + '.' + name + ' (API ' + str(since) + ')'
            checked += 1
print('PASS: API 10 platform member audit (' + str(checked) + ' references)')
