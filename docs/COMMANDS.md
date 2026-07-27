# Commands and permissions

## Commands

| Command | Description | Permission | Usage |
|---------|-------------|------------|-------|
| `/link` | Link your Minecraft account to RootMC | `` | `/link` |
| `/waypoint` | Save an in-game waypoint (syncs to app) | `` | `/waypoint` |
| `/note` | Save a location note (syncs to app) | `` | `/note` |
| `/notes` | Info about synced notes | `` | `/notes` |
| `/waypoints` | Info about synced waypoints | `` | `/waypoints` |
| `/vault` | Claim pending vault / app purchases | `` | `/vault` |
| `/value` | Market price for item in hand and carried inventory value | `` | `/<command> [item]` |
| `/node` | Show whether the network is on HQ Node (Solar) or Cloudflare D1 | `` | `/<command>` |
| `/rootstat` | RootMC account & stats â€” link, status, shops, sync | `` | `/<command> [link/status/map/shops/sync/reload]` |

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `rootstat.reload` | Reload RootMC config | `op` |
| `rootmc.reload` | Legacy alias for rootstat.reload | `op` |
| `rootmc.waypoint` | Save in-game waypoints | `true` |
| `rootmc.note` | Save in-game notes | `true` |
| `rootmc.vault` | Claim vault purchases | `true` |
| `rootmc.map` | Open live BlueMap (/rootstat map) | `true` |
| `rootstat.link` | Start account linking (/link, /rootstat link) | `true` |
| `rootstat.status` | View link status (/rootstat status) | `true` |
| `rootstat.sync` | Force cloud sync (/rootstat sync) | `op` |
| `rootstat.map` | Open live BlueMap (/rootstat map) | `true` |
| `rootmc.node` | View HQ Node / Cloudflare connection (/node) | `true` |

