#!/usr/bin/env python3
"""Make OptiFine CIT rules that match on enchantments work in vanilla Java Edition 26.3.

    python3 cit_enchantments_to_26.3.py <pack .zip or folder> <26.3 client.jar> <output .zip>

OptiFine's CIT (optifine/cit/*.properties) is not read by vanilla. Since 1.21.5 the game's own item
model definitions (assets/minecraft/items/<item>.json) can pick a model by component predicate, so a
rule like "diamond_sword with knockback 2" becomes a minecraft:condition on minecraft:enchantments.

Only rules whose sole condition is the item and its enchantments are converted: rules that match on
a custom name, lore or NBT have no vanilla equivalent and stay in optifine/cit for mods that read it.
The rest of the pack is copied unchanged, minus files 26.3 does not read.
"""
import io
import json
import os
import sys
import zipfile

# OptiFine and pre-1.13 names that differ from the 26.3 enchantment ids
ENCHANTMENT_IDS = {'curse_of_binding': 'binding_curse', 'curse_of_vanishing': 'vanishing_curse',
                   'sweeping': 'sweeping_edge'}
# Properties that narrow a rule in ways an enchantment predicate cannot express
UNSUPPORTED = ('nbt.', 'damage', 'components.', 'hand', 'enchantments=')
# 1.13 renamed these vanilla textures; packs converted with an older tool keep the old name
RENAMED = {'assets/minecraft/textures/misc/enchanted_glint_entity.png':
           'assets/minecraft/textures/misc/enchanted_glint_armor.png'}


def read_pack(path):
    files = {}
    if os.path.isdir(path):
        for root, _, names in os.walk(path):
            for n in names:
                full = os.path.join(root, n)
                with open(full, 'rb') as f:
                    files[os.path.relpath(full, path).replace(os.sep, '/')] = f.read()
    else:
        with zipfile.ZipFile(path) as z:
            files = {n: z.read(n) for n in z.namelist() if not n.endswith('/')}
    roots = [n[:-len('pack.mcmeta')] for n in files if n.endswith('pack.mcmeta')]
    if not roots:
        sys.exit('no pack.mcmeta in ' + path)
    root = min(roots, key=len)
    return {n[len(root):]: d for n, d in files.items() if n.startswith(root)}


def parse_properties(text):
    props = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line[0] in '#!' or '=' not in line:
            continue
        k, v = line.split('=', 1)
        props[k.strip()] = v.strip()
    return props


def strip_ns(name):
    return name.split(':', 1)[1] if ':' in name else name


def rule_texture(props, folder, files, item):
    """CIT names its texture with texture=, tile= or texture.<vanilla name>=, relative to the rule."""
    candidates = [v for k, v in props.items() if k == 'texture' or k == 'tile' or k.startswith('texture.')]
    for value in candidates:
        value = value[:-4] if value.endswith('.png') else value
        for rel in (folder + '/' + value.lstrip('./') + '.png', 'assets/minecraft/' + value + '.png'):
            if rel in files:
                return rel
    if candidates:
        return None
    rel = folder + '/' + item + '.png'
    return rel if rel in files else None


def parse_levels(text):
    levels = []
    for part in text.replace(',', ' ').split():
        if '-' in part:
            lo, hi = part.split('-', 1)
            levels.append((int(lo) if lo else None, int(hi) if hi else None))
        else:
            levels.append((int(part), int(part)))
    return levels


def collect_rules(files):
    rules, skipped = [], []
    for rel in sorted(files):
        if not (rel.startswith('assets/minecraft/optifine/cit/') and rel.endswith('.properties')):
            continue
        props = parse_properties(files[rel].decode('utf-8', 'replace'))
        if props.get('type', 'item') != 'item' or 'enchantmentIDs' not in props:
            continue
        if any(k.startswith(UNSUPPORTED) for k in props):
            skipped.append(rel)
            continue
        items = (props.get('items') or props.get('matchItems') or '').split()
        enchants = [ENCHANTMENT_IDS.get(strip_ns(e), strip_ns(e)) for e in props['enchantmentIDs'].split()]
        levels = parse_levels(props['enchantmentLevels']) if 'enchantmentLevels' in props else [(None, None)]
        folder = rel.rsplit('/', 1)[0]
        for item in items:
            item = strip_ns(item)
            texture = rule_texture(props, folder, files, item)
            if texture is None:
                skipped.append(rel)
                continue
            rules.append({'file': rel, 'item': item, 'enchants': enchants, 'levels': levels,
                          'texture': texture, 'weight': int(props.get('weight', 0))})
    return rules, skipped


def open_top_levels(rules):
    """The highest level a pack draws also covers the levels above it (knockback 2 art for knockback 3)."""
    top = {}
    for r in rules:
        for e in r['enchants']:
            for lo, hi in r['levels']:
                if hi is not None:
                    key = (r['item'], e)
                    top[key] = max(top.get(key, 0), hi)
    for r in rules:
        r['levels'] = [(lo, None) if hi is not None and all(top.get((r['item'], e)) == hi for e in r['enchants'])
                       else (lo, hi) for lo, hi in r['levels']]


def level_value(lo, hi):
    if lo is not None and lo == hi:
        return lo
    out = {}
    if lo is not None:
        out['min'] = lo
    if hi is not None:
        out['max'] = hi
    return out


def build(files, jar):
    out = {}
    rules, skipped = collect_rules(files)
    open_top_levels(rules)
    # Same order OptiFine resolves overlapping rules in: higher weight first, then the more specific level
    rules.sort(key=lambda r: (-r['weight'], -max((lo or 0) for lo, _ in r['levels']), r['file']))

    by_item = {}
    for r in rules:
        by_item.setdefault(r['item'], []).append(r)

    for item, item_rules in by_item.items():
        vanilla_def = 'assets/minecraft/items/%s.json' % item
        if vanilla_def not in jar.namelist():
            skipped.extend(r['file'] for r in item_rules)
            continue
        base = files.get(vanilla_def) or jar.read(vanilla_def)
        model = json.loads(base)['model']
        vanilla_model = json.loads(jar.read('assets/minecraft/models/item/%s.json' % item))
        component = 'minecraft:stored_enchantments' if item == 'enchanted_book' else 'minecraft:enchantments'
        for r in reversed(item_rules):
            name = os.path.basename(r['texture'])[:-4].lower().replace(' ', '_')
            texture_id = 'item/cit/%s/%s' % (item, name)
            out['assets/minecraft/textures/%s.png' % texture_id] = files[r['texture']]
            meta = files.get(r['texture'] + '.mcmeta')
            if meta:
                out['assets/minecraft/textures/%s.png.mcmeta' % texture_id] = meta
            out['assets/minecraft/models/%s.json' % texture_id] = json_bytes(
                {'parent': vanilla_model['parent'], 'textures': {'layer0': 'minecraft:' + texture_id}})
            # Several level ranges or enchantments in one rule: any of them selects the model
            on_true = {'type': 'minecraft:model', 'model': 'minecraft:' + texture_id}
            for e in r['enchants']:
                for lo, hi in r['levels']:
                    entry = {'enchantments': 'minecraft:' + e}
                    if (lo, hi) != (None, None):
                        entry['levels'] = level_value(lo, hi)
                    model = {'type': 'minecraft:condition', 'property': 'minecraft:component',
                             'predicate': component, 'value': [entry], 'on_true': on_true, 'on_false': model}
        out[vanilla_def] = json_bytes({'model': model})
    return out, rules, skipped


def json_bytes(obj):
    return (json.dumps(obj, indent=2) + '\n').encode('utf-8')


def convert(src_path, jar_path, out_path):
    files = read_pack(src_path)
    jar = zipfile.ZipFile(jar_path)
    names = set(jar.namelist())
    added, rules, skipped = build(files, jar)

    out = {}
    dropped = []
    for rel, data in files.items():
        target = RENAMED.get(rel[:-7] if rel.endswith('.mcmeta') else rel)
        if target:
            if target not in files:
                out[target + ('.mcmeta' if rel.endswith('.mcmeta') else '')] = data
            continue
        base = rel[:-7] if rel.endswith('.mcmeta') else rel
        unread = (base.startswith('assets/minecraft/') and not base.startswith('assets/minecraft/optifine/')
                  and base not in names)
        if unread or rel == 'assets/minecraft/atlases/items.json' and data == jar.read(rel):
            dropped.append(rel)
            continue
        out[rel] = data
    out.update(added)

    with zipfile.ZipFile(out_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for rel in sorted(out):
            info = zipfile.ZipInfo(rel, date_time=(2026, 9, 15, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, out[rel])
    return rules, skipped, dropped


if __name__ == '__main__':
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    converted, not_converted, removed = convert(*sys.argv[1:])
    for r in converted:
        print('converted %s: %s %s levels %s' % (r['file'], r['item'], ','.join(r['enchants']), r['levels']))
    for rel in not_converted:
        print('kept for mods only: ' + rel)
    for rel in removed:
        print('left out (not read by 26.3): ' + rel)
