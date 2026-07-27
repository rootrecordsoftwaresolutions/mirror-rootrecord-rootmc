# RootMC

RootMC â€” account linking, McMMO sync, in-game capture, Root Shops economy, app heartbeat.

| Field | Value |
|-------|-------|
| **Folder / artifact** | `rootmc` |
| **Version** | `1.7.2` |
| **Bukkit name** | `RootMC` |
| **Paper API** | `26.1` |
| **Author** | Root Record |
| **Website** | https://rootmc.net |
| **Main class** | `com.rootrecord.minecraft.rootmc.RootMcPlugin` |

## Install

1. Install **[Root-Core](https://github.com/RootRecord/root-core)** first (license/cloud spine for the suite).
2. Download `rootmc-1.7.2.jar` from [Releases](https://github.com/RootRecord/rootmc/releases) or the [plugin catalog](https://rootmc.net/plugins/).
3. Remove any older `rootmc-*.jar` from `plugins/`.
4. Drop the new jar into `plugins/` and restart (or use Root-Core suite updater when this plugin is on the public manifest).
5. Shared config and secrets live under `plugins/RootMC/` (not a per-plugin data folder unless documented otherwise).

### Dependencies

| Type | Plugins |
|------|---------|
| Hard depend | _none_ |
| Soft depend | PlaceholderAPI, Vault, ChestShop, QuickShop, Root-ChestShops, RootMC-Shops, Towny, Root-Economy, Root-Essentials |

## Configuration

Most RootMC plugins store operator YAML under `plugins/RootMC/`. After first boot, check that folder for new keys. Never commit live `cloud.yml` / database passwords to git.

## Build (monorepo)

Primary compilation is the RootMC Gradle workspace (not this standalone repo alone):

```bat
cd "D:\.1 Work Stations\RootMC\Plugin Building\Minecraft"
.\build-with-server-jdk.bat :plugins:rootmc:jar
```

This repository mirrors sources for GitHub browsing and release distribution. It depends on `rootrecord-common` inside the monorepo.

## Commands (summary)

| Command | Description |
|---------|-------------|
| `/link` | Link your Minecraft account to RootMC |
| `/waypoint` | Save an in-game waypoint (syncs to app) |
| `/note` | Save a location note (syncs to app) |
| `/notes` | Info about synced notes |
| `/waypoints` | Info about synced waypoints |
| `/vault` | Claim pending vault / app purchases |
| `/value` | Market price for item in hand and carried inventory value |
| `/node` | Show whether the network is on HQ Node (Solar) or Cloudflare D1 |
| `/rootstat` | RootMC account & stats â€” link, status, shops, sync |

Full command and permission tables: [docs/COMMANDS.md](docs/COMMANDS.md).

## Links

| Resource | URL |
|----------|-----|
| Website | https://rootmc.net |
| Plugin catalog | https://rootmc.net/plugins/ |
| This plugin page | https://rootmc.net/plugins/rootmc/ |
| Suite wiki | https://rootmc.net/wiki/plugins/ |
| Player wiki | https://rootmc.net/wiki/player/ |
| Constitution | https://rootmc.net/wiki/constitution/ |
| Economy guide | https://rootmc.net/wiki/economy/ |
| Developer keys | https://rootmc.net/developer/keys/ |
| Manifest | https://rootmc.net/plugins/manifest.json |
| Play | `play.rootmc.net` |
| Live map | https://map.rootmc.net |
| API | https://api.rootmc.net |
| Discord | https://discord.gg/rFFQYrNaqS |
| GitHub (this repo) | https://github.com/RootRecord/rootmc |
| Releases | https://github.com/RootRecord/rootmc/releases |

**Discord:** RootMC community - join for support, announcements, and governance: https://discord.gg/rFFQYrNaqS


## Documentation in this repo

- [docs/COMMANDS.md](docs/COMMANDS.md) - commands and permissions from `plugin.yml`
- [docs/LINKS.md](docs/LINKS.md) - canonical RootMC web and Discord links
- [CHANGELOG.md](CHANGELOG.md) - version history seed

## License

Copyright Root Record. All rights reserved. Source is published for transparency; no license to copy, modify, or redistribute is granted unless Root Record provides written permission.

