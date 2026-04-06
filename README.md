# AntiBedrockTool

A multi-platform Minecraft plugin that **completely blocks bedrocktool resource pack downloaders** using a whitelist-based verification system.

## How It Works

**bedrocktool** connects → disconnects in ~1 second → never gets verified → never gets resource packs.  
**Real players** connect → play for 30 seconds → get verified → reconnect → receive resource packs normally.

```
New Bedrock player connects
        │
        ▼
[Geyser Extension] Checks verified-players.txt
        │
   Not verified ──────────────────────── Verified
        │                                    │
  Strip ALL packs                     Send packs normally ✅
        │
   Player joins server
        │
[Velocity/BungeeCord/Spigot] Starts 30-second timer
        │
   30 seconds pass?
    No ──► bedrocktool disconnects → never verified ❌
    Yes ──► Write to verified-players.txt → kick to reconnect
        │
   Player reconnects → Verified → Gets packs ✅
```

---

## Installation

### Required (always)
| File | Location |
|------|----------|
| `antibedrocktool-geyser.jar` | `plugins/Geyser-Velocity/extensions/` or `plugins/Geyser-BungeeCord/extensions/` |

### Choose one backend (based on your setup)
| Proxy/Server | File | Location |
|-------------|------|----------|
| **Velocity** | `antibedrocktool-velocity.jar` | `plugins/` |
| **BungeeCord** | `antibedrocktool-bungeecord.jar` | `plugins/` |
| **Spigot/Paper** | `antibedrocktool-spigot.jar` | `plugins/` |

> ⚠️ **The Geyser extension is required.** Without it, bedrocktool can download packs freely.

---

## Commands

All commands require the `antibt.admin` permission.

| Command | Description |
|---------|-------------|
| `/antibt status` | Show verified players, bans, and current settings |
| `/antibt verify <player>` | Manually verify a player (skip the 30-second wait) |
| `/antibt reset` | Clear all verified players (everyone must re-verify) |
| `/antibt unban` | Clear all IP bans |
| `/antibt reload` | Reload config and verified player list |

---

## Verification System

### verified-players.txt format
```
# Lines starting with # are comments
playername
ip:1.2.3.4
```

- **Username entry**: Player is verified by their Bedrock username (works even if IP changes)
- **IP entry**: Player is verified by IP address (bonus check)

The Velocity/BungeeCord/Spigot plugin **writes** this file.  
The Geyser extension **reads** this file (reloads every 2 seconds).

### Shared file locations (Geyser reads all of these)
```
plugins/antibedrocktool/verified-players.txt
plugins/AntiBedrockTool/verified-players.txt
plugins/Geyser-Velocity/extensions/antibedrocktool/verified-players.txt
```

---

## Player Experience

### First-time player (unverified)
1. Connect → enters server **without resource packs** (sees default textures)
2. Play normally for **30 seconds**
3. Automatically kicked with: `§aYou have been verified! Please reconnect to load resource packs.`
4. Reconnect → **resource packs load normally** ✅
5. Future connections: packs load immediately, no wait

### Returning verified player
- Packs load immediately on connect ✅

---

## Configuration

Default config is auto-generated on first run at `plugins/AntiBedrockTool/config.yml`.

```yaml
kick-message: "§cSuspicious connection detected."
headless-kick-message: "§cConnection rejected."
block-headless-clients: true
first-offense-block: true
first-offense-block-minutes: 30
persist-bans: true
```

> **Note**: `block-headless-clients` and `first-offense-block` only apply to **Java players** in the current version. Bedrock player protection is handled entirely by the Geyser extension.

---

## Building from Source

**Requirements**: Java 17+, Gradle

```bash
git clone https://github.com/your-username/AntiBedrockTool.git
cd AntiBedrockTool/Plugin
./gradlew clean build
```

Output JARs will be in each module's `build/libs/` directory.

---

## Architecture

```
Plugin/
├── common/       # Shared: DetectionEngine, AntiBTConfig, FloodgateHelper
├── velocity/     # Velocity proxy plugin
├── bungeecord/   # BungeeCord proxy plugin
├── spigot/       # Spigot/Paper backend plugin
└── geyser/       # Geyser extension (REQUIRED)
```

### Responsibility split
| Component | Handles | Does |
|-----------|---------|------|
| **Geyser Extension** | All Bedrock players | Strips packs from unverified players |
| **Velocity/BungeeCord/Spigot** | Bedrock players | 30s verification timer, writes verified-players.txt |
| **Velocity/BungeeCord/Spigot** | Java players | DetectionEngine (suspicious connection detection) |

---

## License

MIT
