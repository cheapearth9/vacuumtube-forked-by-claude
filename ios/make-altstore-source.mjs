//prints the AltStore / SideStore source json for the latest ios build
//usage: node make-altstore-source.mjs <version> <build number> <owner/repo> <ipa path>

import fs from 'node:fs'

const [ version, build, repo, ipa ] = process.argv.slice(2)
const base = `https://github.com/${repo}/releases/download/ios-latest`
const size = fs.statSync(ipa).size
const date = new Date().toISOString()
const icon = `https://raw.githubusercontent.com/${repo}/main/ios/VacuumTube/Assets.xcassets/AppIcon.appiconset/icon-1024.png`
const description = 'An unofficial iOS port of VacuumTube: the regular YouTube TV interface with an ad blocker and VacuumTube\'s settings menu. Plug in an HDMI adapter or use AirPlay and YouTube appears on that screen by itself; the phone becomes the remote. External keyboards work too.'

console.log(JSON.stringify({
    name: 'VacuumTube',
    identifier: 'io.github.cheapearth9.vacuumtube.source',
    subtitle: 'YouTube TV without ads, on your TV screen',
    description,
    iconURL: icon,
    website: `https://github.com/${repo}`,
    tintColor: '#FF0033',
    apps: [
        {
            name: 'VacuumTube',
            bundleIdentifier: 'io.github.cheapearth9.vacuumtube',
            developerName: 'shy1132 (iOS port by cheapearth9)',
            subtitle: 'YouTube TV, no ads',
            localizedDescription: description,
            iconURL: icon,
            tintColor: '#FF0033',
            screenshotURLs: [],
            // newer sources list versions, older AltStore builds read the flat fields below
            versions: [
                { version: `${version}.${build}`, buildVersion: String(build), date, size, downloadURL: `${base}/VacuumTube-iOS.ipa`, minOSVersion: '15.0', localizedDescription: `Build ${build}` }
            ],
            version: `${version}.${build}`,
            versionDate: date,
            versionDescription: `Build ${build}`,
            downloadURL: `${base}/VacuumTube-iOS.ipa`,
            size
        }
    ],
    news: []
}, null, 2))
