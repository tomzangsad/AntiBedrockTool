package com.kaizer.antibt;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Core detection engine shared across all platforms.
 * Tracks player sessions and detects bedrocktool-like behavior.
 */
public class DetectionEngine {

    public enum Action {
        KICK, LOG_ONLY, BAN_IP
    }

    public record DetectionResult(Action action, String reason, String playerName, UUID playerUuid, String ip) {}

    private final AntiBTConfig config;
    private final Logger logger;
    private final Consumer<DetectionResult> resultHandler;
    private final FloodgateHelper floodgateHelper;

    // Active sessions by UUID
    private final ConcurrentHashMap<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();

    // IP -> list of join timestamps (for reconnect rate limiting)
    private final ConcurrentHashMap<String, Deque<Long>> connectionHistory = new ConcurrentHashMap<>();

    // IP -> suspicious disconnect count
    private final ConcurrentHashMap<String, Integer> suspiciousCount = new ConcurrentHashMap<>();

    // IP -> ban expiry time
    private final ConcurrentHashMap<String, Long> ipBans = new ConcurrentHashMap<>();

    // IP -> cooldown expiry time (short block after first suspicious disconnect)
    private final ConcurrentHashMap<String, Long> flaggedIPs = new ConcurrentHashMap<>();

    // IP -> headless block (detected headless client, permanently blocked until restart or config reload)
    private final ConcurrentHashMap<String, Long> headlessBlocked = new ConcurrentHashMap<>();

    // Persistent ban file path
    private final Path banFile;

    public DetectionEngine(AntiBTConfig config, Logger logger, Consumer<DetectionResult> resultHandler) {
        this.config = config;
        this.logger = logger;
        this.resultHandler = resultHandler;
        this.floodgateHelper = new FloodgateHelper(logger);
        this.banFile = config.getDataFolder().resolve("banned-ips.txt");
        loadPersistentBans();
    }

    public FloodgateHelper getFloodgateHelper() {
        return floodgateHelper;
    }

    // ==================== Persistent IP Ban Storage ====================

    private void loadPersistentBans() {
        if (!config.isPersistBans()) return;
        if (!Files.exists(banFile)) return;
        try {
            long now = System.currentTimeMillis();
            List<String> lines = Files.readAllLines(banFile);
            int loaded = 0;
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    long expiry = Long.parseLong(parts[1]);
                    if (expiry > now) {
                        ipBans.put(parts[0], expiry);
                        loaded++;
                    }
                }
            }
            if (loaded > 0) {
                logger.info("[AntiBedrockTool] Loaded " + loaded + " persistent IP bans");
            }
        } catch (Exception e) {
            logger.warning("[AntiBedrockTool] Failed to load persistent bans: " + e.getMessage());
        }
    }

    private void savePersistentBans() {
        if (!config.isPersistBans()) return;
        try {
            Files.createDirectories(banFile.getParent());
            long now = System.currentTimeMillis();
            List<String> lines = new ArrayList<>();
            lines.add("# AntiBedrockTool IP Bans - format: ip=expiryTimestamp");
            for (Map.Entry<String, Long> entry : ipBans.entrySet()) {
                if (entry.getValue() > now) {
                    lines.add(entry.getKey() + "=" + entry.getValue());
                }
            }
            Files.write(banFile, lines);
        } catch (Exception e) {
            logger.warning("[AntiBedrockTool] Failed to save persistent bans: " + e.getMessage());
        }
    }

    // ==================== Headless Client Check ====================

    /**
     * Check if a Bedrock player is a headless client (bedrocktool without real client).
     * Should be called at login time BEFORE allowing the connection through.
     * Returns a non-null reason string if blocked, or null if allowed.
     */
    public String checkHeadlessClient(UUID uuid, String name, String ip) {
        if (!config.isBlockHeadlessClients()) return null;
        String cleanIp = stripPort(ip);
        if (config.getWhitelistedIPs().contains(cleanIp)) return null;

        int score = floodgateHelper.getSuspicionScore(uuid);
        if (score >= config.getHeadlessSuspicionThreshold()) {
            String fingerprint = floodgateHelper.getClientFingerprint(uuid);
            logger.warning("[AntiBedrockTool] BLOCKED headless client: " + name
                    + " | IP: " + cleanIp + " | Score: " + score + " | " + fingerprint);

            // Immediately ban this IP
            long banUntil = System.currentTimeMillis() + config.getIpBanDurationMinutes() * 60_000L;
            ipBans.put(cleanIp, banUntil);
            headlessBlocked.put(cleanIp, banUntil);
            savePersistentBans();

            return "Headless client detected (score=" + score + ")";
        }
        return null;
    }

    /**
     * Called when a player connects. Returns false if the IP is banned.
     */
    public boolean onJoin(UUID uuid, String name, String ip, boolean isBedrock) {
        String cleanIp = stripPort(ip);

        // Check whitelist
        if (config.getWhitelistedIPs().contains(cleanIp)) {
            return true;
        }

        // Check headless block list
        if (isBedrock) {
            Long headlessExpiry = headlessBlocked.get(cleanIp);
            if (headlessExpiry != null) {
                if (System.currentTimeMillis() < headlessExpiry) {
                    if (config.isLogSuspicious()) {
                        logger.warning("[AntiBedrockTool] Blocked headless-flagged IP: " + cleanIp + " (" + name + ")");
                    }
                    return false;
                } else {
                    headlessBlocked.remove(cleanIp);
                }
            }
        }

        // Check flagged IP cooldown (blocks Bedrock players that disconnected without spawning recently)
        if (isBedrock) {
            Long flagExpiry = flaggedIPs.get(cleanIp);
            if (flagExpiry != null) {
                if (System.currentTimeMillis() < flagExpiry) {
                    if (config.isLogSuspicious()) {
                        logger.warning("[AntiBedrockTool] Blocked flagged IP (cooldown): " + cleanIp + " (" + name + ")");
                    }
                    return false;
                } else {
                    flaggedIPs.remove(cleanIp);
                }
            }
        }

        // Check IP bans
        Long banExpiry = ipBans.get(cleanIp);
        if (banExpiry != null) {
            if (System.currentTimeMillis() < banExpiry) {
                if (config.isLogSuspicious()) {
                    logger.warning("[AntiBedrockTool] Blocked banned IP: " + cleanIp + " (" + name + ")");
                }
                return false;
            } else {
                ipBans.remove(cleanIp);
                savePersistentBans();
            }
        }

        // Check reconnect rate
        if (isBedrock && isReconnectFlooding(cleanIp)) {
            logDetection("Reconnect flooding", name, cleanIp);
            // Immediately ban IP for reconnect flooding
            banIp(cleanIp, name, uuid, "Reconnect flooding");
            return false;
        }

        // Record connection
        recordConnection(cleanIp);

        // Create session
        PlayerSession session = new PlayerSession(uuid, name, cleanIp, isBedrock);
        sessions.put(uuid, session);

        return true;
    }

    /**
     * Called when a player fully spawns into the world.
     */
    public void onSpawn(UUID uuid) {
        PlayerSession session = sessions.get(uuid);
        if (session != null) {
            session.setSpawned(true);
        }
    }

    /**
     * Called when a player moves.
     */
    public void onMove(UUID uuid) {
        PlayerSession session = sessions.get(uuid);
        if (session != null) {
            session.setMoved(true);
        }
    }

    /**
     * Called when a player disconnects.
     */
    public void onQuit(UUID uuid) {
        PlayerSession session = sessions.remove(uuid);
        if (session == null || !session.isBedrockPlayer()) return;

        session.setDisconnectTime(System.currentTimeMillis());
        long duration = session.getSessionDuration();

        // Pattern 1: Connected for very short time, never spawned (packs mode)
        // This is the PRIMARY bedrocktool detection pattern.
        if (!session.hasSpawned() && duration < config.getNoSpawnKickSeconds() * 1000L) {
            flagSuspicious(session, "Disconnected without spawning (duration: " + (duration / 1000) + "s)");
        }

        // Pattern 2: Spawned but never moved, short session
        if (session.hasSpawned() && !session.hasMoved() && duration < config.getNoMoveKickSeconds() * 2000L) {
            flagSuspicious(session, "Spawned but never moved (duration: " + (duration / 1000) + "s)");
        }
    }

    /**
     * Periodic check - call every second from platform scheduler.
     * Returns list of UUIDs to kick.
     */
    public List<DetectionResult> tick() {
        List<DetectionResult> results = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (PlayerSession session : sessions.values()) {
            if (!session.isBedrockPlayer()) continue;

            long elapsed = now - session.getJoinTime();

            // Check: Bedrock player joined but hasn't spawned after threshold
            if (!session.hasSpawned() && elapsed > config.getNoSpawnKickSeconds() * 1000L) {
                DetectionResult result = new DetectionResult(
                    Action.KICK,
                    "No spawn after " + config.getNoSpawnKickSeconds() + "s (possible pack downloader)",
                    session.getName(), session.getUuid(), session.getIp()
                );
                results.add(result);
                logDetection(result.reason(), session.getName(), session.getIp());
                // Also flag the IP immediately
                flagSuspicious(session, result.reason());
            }

            // Check: Bedrock player spawned but hasn't moved after threshold
            if (session.hasSpawned() && !session.hasMoved() && elapsed > config.getNoMoveKickSeconds() * 1000L) {
                DetectionResult result = new DetectionResult(
                    Action.KICK,
                    "No movement after " + config.getNoMoveKickSeconds() + "s",
                    session.getName(), session.getUuid(), session.getIp()
                );
                results.add(result);
                logDetection(result.reason(), session.getName(), session.getIp());
            }
        }

        // Cleanup expired IP bans
        boolean bansChanged = ipBans.entrySet().removeIf(e -> now > e.getValue());
        if (bansChanged) savePersistentBans();

        // Cleanup expired flagged IPs
        flaggedIPs.entrySet().removeIf(e -> now > e.getValue());

        // Cleanup expired headless blocks
        headlessBlocked.entrySet().removeIf(e -> now > e.getValue());

        return results;
    }

    private void flagSuspicious(PlayerSession session, String reason) {
        String ip = session.getIp();
        int count = suspiciousCount.merge(ip, 1, Integer::sum);
        logDetection(reason, session.getName(), ip);

        if (config.isFirstOffenseBlock()) {
            // AGGRESSIVE: First offense = immediate long block
            long blockUntil = System.currentTimeMillis() + config.getFirstOffenseBlockMinutes() * 60_000L;
            flaggedIPs.put(ip, blockUntil);
            logger.warning("[AntiBedrockTool] IP blocked (first offense): " + ip
                    + " for " + config.getFirstOffenseBlockMinutes() + " minutes");
        } else {
            // Legacy: short cooldown
            long flagUntil = System.currentTimeMillis() + config.getFlagCooldownMinutes() * 60_000L;
            flaggedIPs.put(ip, flagUntil);
        }

        if (count >= config.getIpBanThreshold()) {
            banIp(ip, session.getName(), session.getUuid(), reason);
        }
    }

    private void banIp(String ip, String name, UUID uuid, String reason) {
        long banUntil = System.currentTimeMillis() + config.getIpBanDurationMinutes() * 60_000L;
        ipBans.put(ip, banUntil);
        suspiciousCount.remove(ip);
        savePersistentBans();
        logger.warning("[AntiBedrockTool] IP BANNED: " + ip + " for " + config.getIpBanDurationMinutes()
                + " minutes | Reason: " + reason);
        resultHandler.accept(new DetectionResult(Action.BAN_IP, reason, name, uuid, ip));
    }

    private boolean isReconnectFlooding(String ip) {
        Deque<Long> history = connectionHistory.get(ip);
        if (history == null) return false;

        long cutoff = System.currentTimeMillis() - config.getReconnectWindowSeconds() * 1000L;
        synchronized (history) {
            while (!history.isEmpty() && history.peekFirst() < cutoff) {
                history.pollFirst();
            }
            return history.size() >= config.getMaxReconnectsInWindow();
        }
    }

    private void recordConnection(String ip) {
        connectionHistory.computeIfAbsent(ip, k -> new ConcurrentLinkedDeque<>()).add(System.currentTimeMillis());
    }

    private void logDetection(String reason, String name, String ip) {
        if (config.isLogSuspicious()) {
            logger.warning("[AntiBedrockTool] " + reason + " | Player: " + name + " | IP: " + ip);
        }
    }

    public void removeSession(UUID uuid) {
        sessions.remove(uuid);
    }

    public boolean isIpBanned(String ip) {
        Long expiry = ipBans.get(stripPort(ip));
        return expiry != null && System.currentTimeMillis() < expiry;
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }

    public int getBannedIpCount() {
        return ipBans.size();
    }

    public int getFlaggedIpCount() {
        return flaggedIPs.size();
    }

    public void clearAllBans() {
        ipBans.clear();
        flaggedIPs.clear();
        headlessBlocked.clear();
        suspiciousCount.clear();
        connectionHistory.clear();
    }

    private static String stripPort(String address) {
        if (address == null) return "";
        // Handle /ip:port format
        if (address.startsWith("/")) address = address.substring(1);
        int colon = address.lastIndexOf(':');
        if (colon > 0) address = address.substring(0, colon);
        return address;
    }
}
