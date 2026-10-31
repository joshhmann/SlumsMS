#!/usr/bin/env python3
"""Pre-publish checks for publish-client, run on the release host (python3 -I - <command> ... < publish_checks.py).

  news FILE
      The news file passes the same rules the launcher publisher enforces for manifest news
      (launcher repo src/updater.mjs validateNews): a JSON array of at most 10 entries, each with a nonempty
      plain-text title of at most 120 characters, an optional date YYYY-MM-DD and an optional body of at most
      1000 characters (newlines allowed). Run before anything is published, so a bad news file cannot leave a
      published layer behind a failed release step.

  same-layer SOURCE PUBLISHED_VERSION_DIR LAYER VERSION
      Exit 0 when PUBLISHED_VERSION_DIR/manifest.json is byte for byte the layer manifest that
      `node tools/publish.mjs --layer LAYER --source SOURCE --version VERSION` would write (same SHA-256), and
      every file it lists is there with its size; exit 3 when it differs; exit 2 on a usage or read error.
      Lets a rerun reuse a layer that an earlier, failed run already published from the same staging tree.

Mirrors tools/publish.mjs collectFiles/writeLayer (name order by UTF-16 code units, JSON.stringify(m, null, 2) + "\\n",
encodeURIComponent-style file URLs). Any doubt (a mismatch in this mirror) makes the layers compare different, which
aborts the rerun as before: it never skips a layer that is not identical.
"""
import hashlib
import json
import os
import re
import stat
import sys
from urllib.parse import quote

NEWS_MAX_ENTRIES = 10
NEWS_TITLE_MAX = 120
NEWS_BODY_MAX = 1000
# String.prototype.trim(): ECMAScript WhiteSpace + LineTerminator (not Python's str.isspace set).
_JS_TRIM = '\t\n\x0b\x0c\r              ' \
           '    　﻿'
_DATE = re.compile(r'[0-9]{4}-[0-9]{2}-[0-9]{2}')


def _js_len(s):
    return len(s.encode('utf-16-le')) // 2


def _news_text(value, label, limit, multiline):
    unsafe = re.compile('[\x00-\x09\x0b-\x1f\x7f]' if multiline else '[\x00-\x1f\x7f]')
    if not isinstance(value, str) or not value.strip(_JS_TRIM) or _js_len(value) > limit or unsafe.search(value):
        raise ValueError(f'{label} must be nonempty plain text of at most {limit} characters.')


def check_news(value):
    """Raise ValueError with the publisher's message when VALUE would be refused as manifest news."""
    if not isinstance(value, list) or len(value) > NEWS_MAX_ENTRIES:
        raise ValueError(f'Manifest news must be an array of at most {NEWS_MAX_ENTRIES} entries.')
    for index, entry in enumerate(value, 1):
        if not isinstance(entry, (dict, list)):
            raise ValueError(f'Invalid manifest news entry {index}.')
        entry = entry if isinstance(entry, dict) else {}
        _news_text(entry.get('title'), f'News {index} title', NEWS_TITLE_MAX, False)
        if 'date' in entry and (not isinstance(entry['date'], str) or not _DATE.fullmatch(entry['date'])):
            raise ValueError(f'News {index} date must be YYYY-MM-DD.')
        if 'body' in entry:
            _news_text(entry['body'], f'News {index} body', NEWS_BODY_MAX, True)


def _js_sort_key(name):
    return name.encode('utf-16-be')


def collect_files(directory, prefix=''):
    """[(relative path, size)] in tools/publish.mjs collectFiles order; symlinks and special files are refused."""
    files = []
    for name in sorted(os.listdir(directory), key=_js_sort_key):
        full = os.path.join(directory, name)
        relative = f'{prefix}/{name}' if prefix else name
        st = os.lstat(full)
        if stat.S_ISLNK(st.st_mode):
            raise ValueError(f'Source symlinks are forbidden: {relative}')
        if stat.S_ISDIR(st.st_mode):
            files.extend(collect_files(full, relative))
        elif stat.S_ISREG(st.st_mode):
            files.append((relative, st.st_size))
        else:
            raise ValueError(f'Source contains a non-regular file: {relative}')
    return files


def _sha256_file(filename):
    h = hashlib.sha256()
    with open(filename, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def layer_manifest_body(source, layer, version):
    """The bytes publish.mjs --layer writes as manifest.json for SOURCE."""
    files = []
    for relative, size in collect_files(source):
        files.append({'path': relative, 'size': size,
                      'sha256': _sha256_file(os.path.join(source, *relative.split('/'))),
                      'url': 'files/' + '/'.join(quote(part, safe='-_.~') for part in relative.split('/'))})
    manifest = {'schemaVersion': 2, 'layer': layer, 'version': version, 'files': files}
    return (json.dumps(manifest, indent=2, ensure_ascii=False) + '\n').encode('utf-8')


def same_layer(source, published, layer, version):
    """(identical?, would-be sha256, published sha256 or None)."""
    want = layer_manifest_body(source, layer, version)
    want_sha = hashlib.sha256(want).hexdigest()
    mpath = os.path.join(published, 'manifest.json')
    if not os.path.isfile(mpath) or os.path.islink(mpath):
        return False, want_sha, None
    with open(mpath, 'rb') as f:
        have = f.read()
    have_sha = hashlib.sha256(have).hexdigest()
    if have != want:
        return False, want_sha, have_sha
    for entry in json.loads(have)['files']:
        target = os.path.join(published, 'files', *entry['path'].split('/'))
        if not os.path.isfile(target) or os.path.islink(target) or os.path.getsize(target) != entry['size']:
            return False, want_sha, have_sha
    return True, want_sha, have_sha


def _no_constant(name):
    raise ValueError(f'{name} is not JSON')   # Python accepts NaN/Infinity; JSON.parse does not


def main(argv):
    if len(argv) == 2 and argv[0] == 'news':
        try:
            with open(argv[1], encoding='utf-8') as f:
                check_news(json.load(f, parse_constant=_no_constant))
        except (OSError, ValueError) as error:   # json.JSONDecodeError is a ValueError
            print(f'news file {argv[1]}: {error}', file=sys.stderr)
            return 1
        print(f'news file {argv[1]}: OK for the publisher')
        return 0
    if len(argv) == 5 and argv[0] == 'same-layer':
        source, published, layer, version = argv[1:]
        try:
            same, want_sha, have_sha = same_layer(source, published, layer, version)
        except (OSError, ValueError) as error:
            print(f'same-layer: {error}', file=sys.stderr)
            return 2
        print(f'would publish layer manifest SHA-256 {want_sha}; published {have_sha or "(no manifest.json)"}')
        return 0 if same else 3
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
