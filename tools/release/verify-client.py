#!/usr/bin/env python3
"""Hash-diff a client release against the reference client (the publish-verify rule). Runs on the host.

    python3 -I verify-client.py --base-manifest layers/base/<v>/manifest.json
        (--client-dir DIR | --client-manifest layers/client/<v>/manifest.json)
        --reference DIR [--allow FILE] [--cache FILE] [--previous manifest.json]

The effective set is the base layer's manifest overlaid by the client layer (by path and sha256), exactly what the
launcher installs. Every file of the reference client must be in it with the same sha256, except the patterns in the
allow file (`missing <glob>`, `different <glob>`, `extra <glob>`; # comments). Extra files are listed and must be
allowed too. With --previous (the top-level manifest.json players have now), every file of that release's
effective set must still be in the new one (`removed <glob>` allows a deliberate removal), which catches an overlay
dropped by mistake even where the reference client has no such file (the 1.2.0 Arcane-maps crash); changed files are
listed. Exit 0 = PASS (zero unexpected missing, different or extra). Reference hashes are cached by
(size, mtime) in --cache so a rerun only hashes what changed. Read-only apart from the cache file.
"""
import argparse, fnmatch, hashlib, json, os, sys


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def walk(root):
    for d, dirs, files in os.walk(root):
        dirs.sort()
        for name in sorted(files):
            full = os.path.join(d, name)
            if os.path.islink(full):
                continue
            yield os.path.relpath(full, root).replace(os.sep, '/'), full


def tree_hashes(root, cache_path=None):
    cache = {}
    if cache_path and os.path.exists(cache_path):
        with open(cache_path) as f:
            cache = json.load(f)
        if cache.get('root') != root:
            cache = {}
    entries = cache.get('files', {})
    out, fresh, hashed = {}, {}, 0
    for rel, full in walk(root):
        st = os.stat(full)
        key = [st.st_size, int(st.st_mtime)]
        old = entries.get(rel)
        if old and old[:2] == key:
            digest = old[2]
        else:
            digest = sha256(full)
            hashed += 1
        fresh[rel] = key + [digest]
        out[rel] = digest
    if cache_path:
        tmp = cache_path + '.tmp'
        with open(tmp, 'w') as f:
            json.dump({'root': root, 'files': fresh}, f)
        os.replace(tmp, cache_path)
    return out, hashed


def manifest_files(path):
    with open(path) as f:
        m = json.load(f)
    return {e['path']: e['sha256'] for e in m['files']}


def release_files(top):
    with open(top) as f:
        m = json.load(f)
    out = {}
    for layer in m['layers']:
        out.update(manifest_files(os.path.join(os.path.dirname(top), layer['manifestUrl'])))
    return m['version'], out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--base-manifest', required=True)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument('--client-dir')
    g.add_argument('--client-manifest')
    ap.add_argument('--reference', required=True)
    ap.add_argument('--allow')
    ap.add_argument('--cache')
    ap.add_argument('--previous')
    ap.add_argument('--show', type=int, default=40)
    a = ap.parse_args()

    allow = {'missing': [], 'different': [], 'extra': [], 'removed': []}
    if a.allow:
        with open(a.allow) as f:
            for line in f:
                line = line.split('#', 1)[0].strip()
                if line:
                    kind, pat = line.split(None, 1)
                    allow[kind].append(pat)

    effective = manifest_files(a.base_manifest)
    if a.client_dir:
        client, _ = tree_hashes(a.client_dir)
    else:
        client = manifest_files(a.client_manifest)
    overlay_same = sum(1 for p, h in client.items() if effective.get(p) == h)
    effective.update(client)
    ref, hashed = tree_hashes(a.reference, a.cache)

    fold = lambda s: {p.lower(): p for p in s}
    eff_l = fold(effective)
    missing, different, extra = [], [], []
    for p, h in ref.items():
        q = eff_l.get(p.lower())
        if q is None:
            missing.append(p)
        elif effective[q] != h:
            different.append(p)
    ref_l = fold(ref)
    extra = [p for p in effective if p.lower() not in ref_l]

    def split(kind, items):
        ok = [p for p in items if any(fnmatch.fnmatchcase(p, pat) for pat in allow[kind])]
        return sorted(set(items) - set(ok)), sorted(ok)

    print(f'reference {a.reference}: {len(ref)} files ({hashed} hashed now, rest from cache)')
    print(f'effective set: base {len(manifest_files(a.base_manifest))} + client {len(client)} '
          f'({overlay_same} client files identical to base) = {len(effective)} files')
    bad = 0
    checks = [('missing', missing), ('different', different), ('extra', extra)]
    if a.previous:
        pver, prev = release_files(a.previous)
        removed = [p for p in prev if p.lower() not in eff_l]
        changed = sorted(p for p in prev if p.lower() in eff_l and effective[eff_l[p.lower()]] != prev[p])
        prev_l = fold(prev)
        added = sorted(p for p in effective if p.lower() not in prev_l)
        print(f'vs previous release {pver}: {len(changed)} changed, {len(added)} added, {len(removed)} removed')
        for p in changed[:a.show]:
            print(f'  changed: {p}')
        for p in added[:a.show]:
            print(f'  added: {p}')
        checks.append(('removed', removed))
    for kind, items in checks:
        unexpected, allowed = split(kind, items)
        print(f'{kind}: {len(items)} ({len(allowed)} allowed, {len(unexpected)} NOT allowed)')
        for p in allowed:
            print(f'  allowed {kind}: {p}')
        for p in unexpected[:a.show]:
            print(f'  NOT ALLOWED {kind}: {p}')
        if len(unexpected) > a.show:
            print(f'  ... {len(unexpected) - a.show} more')
        bad += len(unexpected)
    print('HASH-DIFF PASS' if bad == 0 else f'HASH-DIFF FAIL: {bad} unexpected differences')
    return 0 if bad == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
