package com.kaizer.antibt.geyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.event.connection.ConnectionRequestEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.pack.ResourcePack;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geyser Extension — WHITELIST-BASED resource pack protection.
 *
 * bedrocktool spoofs ALL client data perfectly, so fingerprinting is useless.
 * Instead: STRIP ALL PACKS from every unverified player.
 *
 * Flow:
 * 1. New player connects → packs STRIPPED (not verified yet)
 * 2. Player joins server, plays for 30+ seconds → Velocity plugin writes
 *    their username to verified-players.txt
 * 3. Player is kicked with "Please reconnect for resource packs"
 * 4. Player reconnects → verified → packs sent normally
 *
 * bedrocktool: connects → packs stripped → disconnects in ~1s → NEVER verified
 * → NEVER gets packs, not even once.
 */
public class AntiBTGeyserExtension implements Extension {

    // Verified players (loaded from file, shared with Velocity plugin)
    private final Set<String> verifiedPlayers = ConcurrentHashMap.newKeySet();
    // Verified IPs
    private final Set<String> verifiedIPs = ConcurrentHashMap.newKeySet();

    // IP -> ban expiry
    private final ConcurrentHashMap<String, Long> bannedIPs = new ConcurrentHashMap<>();

    // Recent connection IP tracking
    private final ConcurrentHashMap<String, Long> recentConnectionIPs = new ConcurrentHashMap<>();

    // File paths
    private final List<Path> verifiedFilePaths = new ArrayList<>();
    private final List<Path> banFilePaths = new ArrayList<>();

    private volatile long lastVerifiedReloadTime = 0;
    private volatile long lastBanReloadTime = 0;
    private static final long RELOAD_INTERVAL_MS = 2000;

    private String reconnectMessage = "§aResource packs ready! Please reconnect.";

    // ==================== Lifecycle ====================

    @Subscribe
    public void onGeyserPostInit(GeyserPostInitializeEvent event) {
        discoverFilePaths();
        loadVerifiedPlayers();
        loadBannedIPs();

        this.logger().info("[AntiBedrockTool-Geyser] Extension enabled! (Whitelist-based pack protection)");
        this.logger().info("[AntiBedrockTool-Geyser] Verified players: " + verifiedPlayers.size()
                + " | Verified IPs: " + verifiedIPs.size()
                + " | Banned IPs: " + bannedIPs.size());
    }

    // ==================== Event Handlers ====================

    /**
     * LAYER 1: Connection level — block banned IPs before anything.
     */
    @Subscribe
    public void onConnectionRequest(ConnectionRequestEvent event) {
        long now = System.currentTimeMillis();
        String ip = event.inetSocketAddress().getAddress().getHostAddress();

        // Reload files periodically
        if (now - lastBanReloadTime > RELOAD_INTERVAL_MS) {
            loadBannedIPs();
        }
        if (now - lastVerifiedReloadTime > RELOAD_INTERVAL_MS) {
            loadVerifiedPlayers();
        }

        // Check ban list
        Long banExpiry = bannedIPs.get(ip);
        if (banExpiry != null) {
            if (now < banExpiry) {
                this.logger().warning("[AntiBedrockTool-Geyser] BLOCKED (banned): " + ip);
                event.setCancelled(true);
                return;
            } else {
                bannedIPs.remove(ip);
            }
        }

        // Track IP for pack stripping correlation
        recentConnectionIPs.put(ip, now);
    }

    /**
     * LAYER 2: BEFORE resource packs are sent.
     * If player is NOT verified → strip ALL packs.
     * bedrocktool will NEVER be verified because it disconnects in ~1 second.
     */
    @Subscribe
    public void onSessionLoadResourcePacks(SessionLoadResourcePacksEvent event) {
        GeyserConnection conn = event.connection();
        String username = conn.bedrockUsername();

        // Check if this player is verified (by username OR by IP)
        if (username != null && verifiedPlayers.contains(username.toLowerCase())) {
            this.logger().info("[AntiBedrockTool-Geyser] VERIFIED player: " + username + " → sending packs normally");
            return;
        }

        // Check by IP
        String ip = findMostRecentIP();
        if (ip != null && verifiedIPs.contains(ip)) {
            this.logger().info("[AntiBedrockTool-Geyser] VERIFIED IP: " + ip + " (" + username + ") → sending packs normally");
            return;
        }

        // NOT VERIFIED → strip ALL packs
        List<ResourcePack> packs = event.resourcePacks();
        int packCount = packs.size();

        if (packCount > 0) {
            List<UUID> packUUIDs = new ArrayList<>();
            for (ResourcePack pack : packs) {
                packUUIDs.add(pack.manifest().header().uuid());
            }
            for (UUID uuid : packUUIDs) {
                event.unregister(uuid);
            }
        }

        this.logger().warning("[AntiBedrockTool-Geyser] STRIPPED " + packCount
                + " packs from UNVERIFIED player: " + username
                + " (IP: " + (ip != null ? ip : "unknown") + ")");
    }

    // ==================== Helper ====================

    private String findMostRecentIP() {
        long now = System.currentTimeMillis();
        String bestIp = null;
        long bestTime = 0;
        for (Map.Entry<String, Long> entry : recentConnectionIPs.entrySet()) {
            if (now - entry.getValue() < 5_000 && entry.getValue() > bestTime) {
                bestTime = entry.getValue();
                bestIp = entry.getKey();
            }
        }
        return bestIp;
    }

    // ==================== File Management ====================

    private void discoverFilePaths() {
        Path serverRoot = Path.of(".").toAbsolutePath().normalize();

        // Verified players file locations (Velocity plugin writes here)
        verifiedFilePaths.add(serverRoot.resolve("plugins/antibedrocktool/verified-players.txt"));
        verifiedFilePaths.add(serverRoot.resolve("plugins/AntiBedrockTool/verified-players.txt"));

        Path extensionData = this.dataFolder();
        if (extensionData != null) {
            verifiedFilePaths.add(extensionData.resolve("verified-players.txt"));
        }

        // Ban file locations
        banFilePaths.add(serverRoot.resolve("plugins/antibedrocktool/banned-ips.txt"));
        banFilePaths.add(serverRoot.resolve("plugins/AntiBedrockTool/banned-ips.txt"));
        if (extensionData != null) {
            banFilePaths.add(extensionData.resolve("banned-ips.txt"));
        }

        for (Path p : verifiedFilePaths) {
            this.logger().info("[AntiBedrockTool-Geyser] Verified file: " + p);
        }
        for (Path p : banFilePaths) {
            this.logger().info("[AntiBedrockTool-Geyser] Ban file: " + p);
        }
    }

    /**
     * Load verified players from file.
     * Format: one entry per line, either "username" or "ip:1.2.3.4"
     */
    private void loadVerifiedPlayers() {
        lastVerifiedReloadTime = System.currentTimeMillis();
        Set<String> newPlayers = ConcurrentHashMap.newKeySet();
        Set<String> newIPs = ConcurrentHashMap.newKeySet();

        for (Path file : verifiedFilePaths) {
            if (!Files.exists(file)) continue;
            try {
                List<String> lines = Files.readAllLines(file);
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("ip:")) {
                        newIPs.add(line.substring(3).trim());
                    } else {
                        newPlayers.add(line.toLowerCase());
                    }
                }
            } catch (IOException e) {
                this.logger().warning("[AntiBedrockTool-Geyser] Failed to read " + file + ": " + e.getMessage());
            }
        }

        verifiedPlayers.clear();
        verifiedPlayers.addAll(newPlayers);
        verifiedIPs.clear();
        verifiedIPs.addAll(newIPs);
    }

    private void loadBannedIPs() {
        lastBanReloadTime = System.currentTimeMillis();
        long now = System.currentTimeMillis();

        // Clear first so removed entries in files are actually removed from memory
        bannedIPs.clear();

        for (Path banFile : banFilePaths) {
            if (!Files.exists(banFile)) continue;
            try {
                List<String> lines = Files.readAllLines(banFile);
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        try {
                            long expiry = Long.parseLong(parts[1]);
                            if (expiry > now) {
                                bannedIPs.put(parts[0], expiry);
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } catch (IOException e) {
                this.logger().warning("[AntiBedrockTool-Geyser] Failed to read " + banFile + ": " + e.getMessage());
            }
        }

        bannedIPs.entrySet().removeIf(e -> e.getValue() < now);
    }
}
