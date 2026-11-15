#!/usr/bin/env python3
"""Builds one AltStore / SideStore source from every repo of the owner that has an "ios-latest" release with an .ipa in it.
Name, bundle id, version and minimum iOS come from the IPA's Info.plist. altsource/apps.json only holds the source's own
details plus optional per-repo overrides (icon, subtitle, description, tint, hidden repos). No edit is needed for a new repo.
usage: python3 altsource/make.py > altsource.json    (GITHUB_TOKEN optional: raises the api rate limit)"""

import io, json, os, plistlib, re, sys, urllib.request, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
config = json.load(open(os.path.join(HERE, 'apps.json')))
owner = config['owner']
overrides = config.get('overrides', {})
TAG = 'ios-latest'


def get(url, binary=False):
    request = urllib.request.Request(url, headers={ 'User-Agent': 'altsource', 'Accept': 'application/vnd.github+json' })
    if os.environ.get('GITHUB_TOKEN') and 'api.github.com' in url:
        request.add_header('Authorization', 'Bearer ' + os.environ['GITHUB_TOKEN'])
    with urllib.request.urlopen(request, timeout=120) as response:
        data = response.read()
    return data if binary else json.loads(data)


def info_plist(ipa_bytes):
    with zipfile.ZipFile(io.BytesIO(ipa_bytes)) as z:
        name = next(n for n in z.namelist() if re.fullmatch(r'Payload/[^/]+\.app/Info\.plist', n))
        return plistlib.loads(z.read(name))


repos, page = [], 1
while True:
    chunk = get(f'https://api.github.com/users/{owner}/repos?per_page=100&page={page}')
    repos += chunk
    if len(chunk) < 100: break
    page += 1

apps = []
for repo in sorted(repos, key=lambda r: r['name'].lower()):
    full = repo['full_name']
    override = overrides.get(full, {})
    if repo.get('fork') or repo.get('archived') or override.get('hidden'): continue

    try:
        release = get(f'https://api.github.com/repos/{full}/releases/tags/{TAG}')
    except Exception:
        continue # no ios-latest release: not an iOS repo

    asset = next((a for a in release['assets'] if a['name'].lower().endswith('.ipa')), None)
    if not asset: continue

    try:
        plist = info_plist(get(asset['browser_download_url'], binary=True))
    except Exception as error:
        print(f'skipping {full}: cannot read the ipa ({error})', file=sys.stderr)
        continue

    build = (re.search(r'Build\s+(\d+)', release.get('body') or '', re.I) or [None, None])[1] or str(plist.get('CFBundleVersion', '1'))
    version = f"{plist.get('CFBundleShortVersionString', '1.0')}.{build}"
    name = override.get('name') or plist.get('CFBundleDisplayName') or plist.get('CFBundleName') or repo['name']
    description = override.get('description') or repo.get('description') or name
    icon = override.get('iconURL') or f'https://github.com/{owner}.png'
    tint = override.get('tintColor') or config.get('tintColor', '#0A84FF')
    note = f'Build {build}'

    apps.append({
        'name': name,
        'bundleIdentifier': plist['CFBundleIdentifier'],
        'developerName': override.get('developerName') or owner,
        'subtitle': override.get('subtitle') or (repo.get('description') or '')[:60],
        'localizedDescription': description,
        'iconURL': icon,
        'tintColor': tint,
        'screenshotURLs': [],
        'versions': [ { 'version': version, 'buildVersion': build, 'date': asset['updated_at'], 'size': asset['size'],
                        'downloadURL': asset['browser_download_url'], 'minOSVersion': plist.get('MinimumOSVersion', '15.0'), 'localizedDescription': note } ],
        'version': version, 'versionDate': asset['updated_at'], 'versionDescription': note,
        'downloadURL': asset['browser_download_url'], 'size': asset['size']
    })

source = { k: config[k] for k in ('name', 'identifier', 'subtitle', 'description', 'iconURL', 'website', 'tintColor') }
source.update({ 'apps': apps, 'news': [] })
print(json.dumps(source, indent=2))
