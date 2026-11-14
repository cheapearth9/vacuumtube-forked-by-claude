//builds one AltStore / SideStore source that lists every iOS app in apps.json, using each repo's latest "ios-latest" release
//usage: node altsource/make.mjs > altsource.json   (GITHUB_TOKEN is optional, it only raises the api rate limit)

import fs from 'node:fs'

const config = JSON.parse(fs.readFileSync(new URL('./apps.json', import.meta.url), 'utf8'))
const headers = { 'User-Agent': 'altsource', Accept: 'application/vnd.github+json' }
if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`

const apps = []

for (const app of config.apps) {
    const res = await fetch(`https://api.github.com/repos/${app.repo}/releases/tags/${app.tag || 'ios-latest'}`, { headers })
    if (!res.ok) { console.error(`skipping ${app.name}: ${res.status}`); continue }

    const release = await res.json()
    const asset = release.assets.find((a) => a.name === app.ipa)
    if (!asset) { console.error(`skipping ${app.name}: no ${app.ipa} in the release`); continue }

    const build = /Build\s+(\d+)/i.exec(release.body || '')?.[1]
    const stamp = asset.updated_at.replace(/\D/g, '').slice(0, 12)
    const version = `${app.version}.${build || stamp}`
    const url = asset.browser_download_url

    apps.push({
        name: app.name,
        bundleIdentifier: app.bundleIdentifier,
        developerName: app.developerName,
        subtitle: app.subtitle,
        localizedDescription: app.description,
        iconURL: app.iconURL,
        tintColor: app.tintColor,
        screenshotURLs: [],
        versions: [ { version, buildVersion: build || stamp, date: asset.updated_at, size: asset.size, downloadURL: url, minOSVersion: app.minOS || '15.0', localizedDescription: build ? `Build ${build}` : 'Latest build' } ],
        version,
        versionDate: asset.updated_at,
        versionDescription: build ? `Build ${build}` : 'Latest build',
        downloadURL: url,
        size: asset.size
    })
}

console.log(JSON.stringify({
    name: config.name,
    identifier: config.identifier,
    subtitle: config.subtitle,
    description: config.description,
    iconURL: config.iconURL,
    website: config.website,
    tintColor: config.tintColor,
    apps,
    news: []
}, null, 2))
