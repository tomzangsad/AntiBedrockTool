package com.kaizer.antibt.velocity;

import com.google.inject.Inject;
import com.kaizer.antibt.AntiBTConfig;
import com.kaizer.antibt.DetectionEngine;
import com.kaizer.antibt.FloodgateHelper;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Plugin(
    id = "antibedrocktool",
    name = "AntiBedrockTool",
    version = "2.0.0",
    authors = {"Kaizer"},
    description = "Detects and blocks bedrocktool resource pack downloaders"
)
public class AntiBTVelocity {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private AntiBTConfig config;
    private DetectionEngine engine;

    // Pack verification: Bedrock player UUID -> join timestamp
    private final ConcurrentHashMap<UUID, Long> bedrockJoinTimes = new ConcurrentHashMap<>();
    // Already verified players (to avoid re-kicking)
    private final Set<String> verifiedUsernames = ConcurrentHashMap.newKeySet();
    private static final long VERIFY_DELAY_MS = 30_000; // 30 seconds

    @Inject
    public AntiBTVelocity(ProxyServer proxy, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = Logger.getLogger("AntiBedrockTool");
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        config = new AntiBTConfig(dataDirectory);
        config.load();

        engine = new DetectionEngine(config, logger, result -> {
            proxy.getPlayer(result.playerUuid()).ifPresent(player ->
                player.disconnect(Component.text(config.getKickMessage()))
            );
        });

        // Tick every second
        proxy.getScheduler().buildTask(this, () -> {
            List<DetectionEngine.DetectionResult> results = engine.tick();
            for (DetectionEngine.DetectionResult result : results) {
                proxy.getPlayer(result.playerUuid()).ifPresent(player -> {
                    player.disconnect(Component.text(config.getKickMessage()));
                    engine.removeSession(result.playerUuid());
                });
            }
        }).repeat(1, TimeUnit.SECONDS).schedule();

        // Load existing verified players
        loadVerifiedPlayers();

        // Verification tick: check Bedrock players every 5 seconds
        proxy.getScheduler().buildTask(this, this::verificationTick)
                .repeat(5, TimeUnit.SECONDS).schedule();

        // Register command
        proxy.getCommandManager().register("antibt", new AntiBTCommand());

        logger.info("AntiBedrockTool v2.0.0 enabled - Velocity");
        logger.info("[AntiBedrockTool] Headless client blocking: " + (config.isBlockHeadlessClients() ? "ON" : "OFF"));
        logger.info("[AntiBedrockTool] First-offense IP block: " + (config.isFirstOffenseBlock() ? "ON (" + config.getFirstOffenseBlockMinutes() + "min)" : "OFF"));
        logger.info("[AntiBedrockTool] Persistent bans: " + (config.isPersistBans() ? "ON" : "OFF"));
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        config.save();
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        String ip = player.getRemoteAddress().getAddress().getHostAddress();
        FloodgateHelper fh = engine.getFloodgateHelper();
        boolean isBedrock = fh.isBedrockPlayer(player.getUniqueId());

        // Bedrock players: skip DetectionEngine entirely.
        // Pack protection is handled by Geyser extension (whitelist-based pack stripping).
        // DetectionEngine caused false positives (short sessions flagged as suspicious).
        if (isBedrock) {
            if (!verifiedUsernames.contains(player.getUsername().toLowerCase())) {
                bedrockJoinTimes.put(player.getUniqueId(), System.currentTimeMillis());
                logger.info("[AntiBedrockTool] Bedrock player " + player.getUsername()
                        + " joined - will verify in " + (VERIFY_DELAY_MS / 1000) + "s");
            }
            return;
        }

        // Java players only: run DetectionEngine
        if (!engine.onJoin(player.getUniqueId(), player.getUsername(), ip, false)) {
            event.setResult(LoginEvent.ComponentResult.denied(Component.text(config.getKickMessage())));
        }
    }

    @Subscribe
    public void onServerConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        // Only track spawns for Java players in DetectionEngine
        if (!engine.getFloodgateHelper().isBedrockPlayer(player.getUniqueId())) {
            engine.onSpawn(player.getUniqueId());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        bedrockJoinTimes.remove(player.getUniqueId());
        // Only track quits for Java players in DetectionEngine
        if (!engine.getFloodgateHelper().isBedrockPlayer(player.getUniqueId())) {
            engine.onQuit(player.getUniqueId());
        }
    }

    // ==================== Pack Verification System ====================

    /**
     * Every 5 seconds, check if any Bedrock player has been online for 30+ seconds.
     * If so, write them to verified-players.txt and kick to reconnect for packs.
     */
    private void verificationTick() {
        long now = System.currentTimeMillis();

        for (Map.Entry<UUID, Long> entry : bedrockJoinTimes.entrySet()) {
            UUID uuid = entry.getKey();
            long joinTime = entry.getValue();

            if (now - joinTime >= VERIFY_DELAY_MS) {
                proxy.getPlayer(uuid).ifPresent(player -> {
                    String username = player.getUsername();
                    String ip = player.getRemoteAddress().getAddress().getHostAddress();

                    // Write to verified file
                    writeVerifiedPlayer(username, ip);
                    verifiedUsernames.add(username.toLowerCase());
                    bedrockJoinTimes.remove(uuid);

                    logger.info("[AntiBedrockTool] VERIFIED Bedrock player: " + username
                            + " (IP: " + ip + ") - kicking to reconnect for packs");

                    // Kick to reconnect — packs will be sent on next connection
                    player.disconnect(Component.text(
                            "§aYou have been verified!\n§ePlease reconnect to load resource packs."));
                });

                // If player already left, just clean up
                if (proxy.getPlayer(uuid).isEmpty()) {
                    bedrockJoinTimes.remove(uuid);
                }
            }
        }
    }

    private Path getVerifiedFilePath() {
        return dataDirectory.resolve("verified-players.txt");
    }

    private void loadVerifiedPlayers() {
        Path file = getVerifiedFilePath();
        if (!Files.exists(file)) return;

        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!line.startsWith("ip:")) {
                    verifiedUsernames.add(line.toLowerCase());
                }
            }
            logger.info("[AntiBedrockTool] Loaded " + verifiedUsernames.size() + " verified players");
        } catch (IOException e) {
            logger.warning("[AntiBedrockTool] Failed to load verified players: " + e.getMessage());
        }
    }

    private void writeVerifiedPlayer(String username, String ip) {
        Path file = getVerifiedFilePath();
        try {
            Files.createDirectories(file.getParent());

            // Read existing entries to avoid duplicates
            Set<String> existing = new HashSet<>();
            if (Files.exists(file)) {
                existing.addAll(Files.readAllLines(file));
            }

            List<String> toAdd = new ArrayList<>();
            if (!existing.contains(username.toLowerCase())) {
                toAdd.add(username.toLowerCase());
            }
            String ipEntry = "ip:" + ip;
            if (!existing.contains(ipEntry)) {
                toAdd.add(ipEntry);
            }

            if (!toAdd.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String entry : toAdd) {
                    sb.append(entry).append(System.lineSeparator());
                }
                Files.writeString(file, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            logger.warning("[AntiBedrockTool] Failed to write verified player: " + e.getMessage());
        }
    }

    private class AntiBTCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            String[] args = invocation.arguments();

            if (args.length == 0) {
                source.sendMessage(Component.text("/antibt reload - Reload config", NamedTextColor.YELLOW));
                source.sendMessage(Component.text("/antibt status - Show status", NamedTextColor.YELLOW));
                source.sendMessage(Component.text("/antibt unban - Clear all IP bans", NamedTextColor.YELLOW));
                source.sendMessage(Component.text("/antibt reset - Clear verified players (re-test pack protection)", NamedTextColor.YELLOW));
                source.sendMessage(Component.text("/antibt verify <player> - Manually verify a player", NamedTextColor.YELLOW));
                return;
            }
            switch (args[0].toLowerCase()) {
                case "reload" -> {
                    config.load();
                    loadVerifiedPlayers();
                    source.sendMessage(Component.text("AntiBedrockTool config reloaded.", NamedTextColor.GREEN));
                }
                case "status" -> {
                    source.sendMessage(Component.text("AntiBedrockTool v2.0.0", NamedTextColor.AQUA));
                    FloodgateHelper fh = engine.getFloodgateHelper();
                    long bedrockCount = proxy.getAllPlayers().stream()
                        .filter(p -> fh.isBedrockPlayer(p.getUniqueId())).count();
                    source.sendMessage(Component.text("Bedrock players online: " + bedrockCount, NamedTextColor.GRAY));
                    source.sendMessage(Component.text("Active sessions: " + engine.getActiveSessionCount(), NamedTextColor.GRAY));
                    source.sendMessage(Component.text("Banned IPs: " + engine.getBannedIpCount(), NamedTextColor.RED));
                    source.sendMessage(Component.text("Flagged IPs: " + engine.getFlaggedIpCount(), NamedTextColor.GOLD));
                    source.sendMessage(Component.text("Verified players: " + verifiedUsernames.size(), NamedTextColor.GREEN));
                    source.sendMessage(Component.text("Headless block: " + (config.isBlockHeadlessClients() ? "ON" : "OFF"), NamedTextColor.GRAY));
                    source.sendMessage(Component.text("First-offense block: " + (config.isFirstOffenseBlock() ? "ON" : "OFF"), NamedTextColor.GRAY));
                }
                case "unban" -> {
                    // Clear all ban files
                    int cleared = 0;
                    for (Path banPath : List.of(
                            dataDirectory.resolve("banned-ips.txt"),
                            dataDirectory.getParent().resolve("antibedrocktool/banned-ips.txt"),
                            dataDirectory.getParent().resolve("AntiBedrockTool/banned-ips.txt"))) {
                        try {
                            if (Files.exists(banPath)) {
                                Files.writeString(banPath, "# Cleared by /antibt unban\n");
                                cleared++;
                            }
                        } catch (IOException ignored) {}
                    }
                    // Also clear Geyser extension ban files
                    try {
                        Path geyserBan = dataDirectory.getParent().resolve("Geyser-Velocity/extensions/antibedrocktool/banned-ips.txt");
                        if (Files.exists(geyserBan)) {
                            Files.writeString(geyserBan, "# Cleared by /antibt unban\n");
                            cleared++;
                        }
                    } catch (IOException ignored) {}
                    engine.clearAllBans();
                    source.sendMessage(Component.text("Cleared all IP bans (" + cleared + " files).", NamedTextColor.GREEN));
                }
                case "reset" -> {
                    // Clear verified players file + memory
                    verifiedUsernames.clear();
                    for (Path vPath : List.of(
                            getVerifiedFilePath(),
                            dataDirectory.getParent().resolve("Geyser-Velocity/extensions/antibedrocktool/verified-players.txt"))) {
                        try {
                            if (Files.exists(vPath)) {
                                Files.writeString(vPath, "# Cleared by /antibt reset\n");
                            }
                        } catch (IOException ignored) {}
                    }
                    source.sendMessage(Component.text("Cleared all verified players. Everyone must re-verify.", NamedTextColor.GREEN));
                }
                case "verify" -> {
                    if (args.length < 2) {
                        source.sendMessage(Component.text("Usage: /antibt verify <player>", NamedTextColor.RED));
                        return;
                    }
                    String targetName = args[1];
                    // Try to find online player for IP
                    String targetIp = null;
                    Optional<Player> targetPlayer = proxy.getPlayer(targetName);
                    if (targetPlayer.isPresent()) {
                        targetIp = targetPlayer.get().getRemoteAddress().getAddress().getHostAddress();
                    }
                    writeVerifiedPlayer(targetName, targetIp != null ? targetIp : "0.0.0.0");
                    verifiedUsernames.add(targetName.toLowerCase());
                    source.sendMessage(Component.text("Manually verified: " + targetName
                            + (targetIp != null ? " (IP: " + targetIp + ")" : " (no IP - offline)"), NamedTextColor.GREEN));
                }
                default -> source.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
            }
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("antibt.admin");
        }
    }
}
