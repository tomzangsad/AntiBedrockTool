package com.kaizer.antibt.bungee;

import com.kaizer.antibt.AntiBTConfig;
import com.kaizer.antibt.DetectionEngine;
import com.kaizer.antibt.FloodgateHelper;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class AntiBTBungee extends Plugin implements Listener {

    private AntiBTConfig config;
    private DetectionEngine engine;

    // Pack verification
    private final ConcurrentHashMap<UUID, Long> bedrockJoinTimes = new ConcurrentHashMap<>();
    private final Set<String> verifiedUsernames = ConcurrentHashMap.newKeySet();
    private static final long VERIFY_DELAY_MS = 30_000;

    @Override
    public void onEnable() {
        config = new AntiBTConfig(getDataFolder().toPath());
        config.load();

        Logger logger = getLogger();
        engine = new DetectionEngine(config, logger, result -> {
            ProxiedPlayer player = getProxy().getPlayer(result.playerUuid());
            if (player != null) {
                player.disconnect(new TextComponent(config.getKickMessage()));
            }
        });

        getProxy().getPluginManager().registerListener(this, this);
        loadVerifiedPlayers();

        // Tick every second for Java player detection
        getProxy().getScheduler().schedule(this, () -> {
            List<DetectionEngine.DetectionResult> results = engine.tick();
            for (DetectionEngine.DetectionResult result : results) {
                ProxiedPlayer player = getProxy().getPlayer(result.playerUuid());
                if (player != null) {
                    player.disconnect(new TextComponent(config.getKickMessage()));
                    engine.removeSession(result.playerUuid());
                }
            }
        }, 1, 1, TimeUnit.SECONDS);

        // Verification tick every 5 seconds for Bedrock players
        getProxy().getScheduler().schedule(this, this::verificationTick, 5, 5, TimeUnit.SECONDS);

        // Register command
        getProxy().getPluginManager().registerCommand(this, new Command("antibt", "antibt.admin") {
            @Override
            public void execute(CommandSender sender, String[] args) {
                if (args.length == 0) {
                    sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/antibt reload - Reload config"));
                    sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/antibt status - Show status"));
                    sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/antibt unban - Clear all IP bans"));
                    sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/antibt reset - Clear verified players"));
                    sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/antibt verify <player> - Manually verify"));
                    return;
                }
                switch (args[0].toLowerCase()) {
                    case "reload" -> {
                        config.load();
                        loadVerifiedPlayers();
                        sender.sendMessage(new TextComponent(ChatColor.GREEN + "AntiBedrockTool config reloaded."));
                    }
                    case "status" -> {
                        sender.sendMessage(new TextComponent(ChatColor.AQUA + "AntiBedrockTool v2.0.0"));
                        FloodgateHelper fh = engine.getFloodgateHelper();
                        long bedrockCount = getProxy().getPlayers().stream()
                            .filter(p -> fh.isBedrockPlayer(p.getUniqueId())).count();
                        sender.sendMessage(new TextComponent(ChatColor.GRAY + "Bedrock players online: " + bedrockCount));
                        sender.sendMessage(new TextComponent(ChatColor.GRAY + "Active sessions: " + engine.getActiveSessionCount()));
                        sender.sendMessage(new TextComponent(ChatColor.RED + "Banned IPs: " + engine.getBannedIpCount()));
                        sender.sendMessage(new TextComponent(ChatColor.GOLD + "Flagged IPs: " + engine.getFlaggedIpCount()));
                        sender.sendMessage(new TextComponent(ChatColor.GREEN + "Verified players: " + verifiedUsernames.size()));
                        sender.sendMessage(new TextComponent(ChatColor.GRAY + "Headless block: " + (config.isBlockHeadlessClients() ? "ON" : "OFF")));
                        sender.sendMessage(new TextComponent(ChatColor.GRAY + "First-offense block: " + (config.isFirstOffenseBlock() ? "ON" : "OFF")));
                    }
                    case "unban" -> {
                        engine.clearAllBans();
                        for (Path p : List.of(
                                getDataFolder().toPath().resolve("banned-ips.txt"),
                                getDataFolder().toPath().getParent().resolve("antibedrocktool/banned-ips.txt"))) {
                            try {
                                if (Files.exists(p)) Files.writeString(p, "# Cleared\n");
                            } catch (IOException ignored) {}
                        }
                        sender.sendMessage(new TextComponent(ChatColor.GREEN + "Cleared all IP bans."));
                    }
                    case "reset" -> {
                        verifiedUsernames.clear();
                        try {
                            Files.writeString(getVerifiedFilePath(), "# Cleared\n");
                        } catch (IOException ignored) {}
                        sender.sendMessage(new TextComponent(ChatColor.GREEN + "Cleared all verified players."));
                    }
                    case "verify" -> {
                        if (args.length < 2) {
                            sender.sendMessage(new TextComponent(ChatColor.RED + "Usage: /antibt verify <player>"));
                            return;
                        }
                        String targetName = args[1];
                        String targetIp = "0.0.0.0";
                        ProxiedPlayer tp = getProxy().getPlayer(targetName);
                        if (tp != null) targetIp = tp.getAddress().getAddress().getHostAddress();
                        writeVerifiedPlayer(targetName, targetIp);
                        verifiedUsernames.add(targetName.toLowerCase());
                        sender.sendMessage(new TextComponent(ChatColor.GREEN + "Verified: " + targetName));
                    }
                    default -> sender.sendMessage(new TextComponent(ChatColor.RED + "Unknown subcommand."));
                }
            }
        });

        logger.info("AntiBedrockTool v2.0.0 enabled - BungeeCord");
        logger.info("[AntiBedrockTool] Headless client blocking: " + (config.isBlockHeadlessClients() ? "ON" : "OFF"));
        logger.info("[AntiBedrockTool] First-offense IP block: " + (config.isFirstOffenseBlock() ? "ON (" + config.getFirstOffenseBlockMinutes() + "min)" : "OFF"));
        logger.info("[AntiBedrockTool] Persistent bans: " + (config.isPersistBans() ? "ON" : "OFF"));
    }

    @Override
    public void onDisable() {
        config.save();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(LoginEvent event) {
        String ip = event.getConnection().getAddress().getAddress().getHostAddress();
        UUID uuid = event.getConnection().getUniqueId();
        String name = event.getConnection().getName();
        FloodgateHelper fh = engine.getFloodgateHelper();
        boolean isBedrock = fh.isBedrockPlayer(uuid);

        // Bedrock: skip DetectionEngine, only track for verification
        if (isBedrock) {
            if (!verifiedUsernames.contains(name.toLowerCase())) {
                bedrockJoinTimes.put(uuid, System.currentTimeMillis());
                getLogger().info("[AntiBedrockTool] Bedrock player " + name + " joined - will verify in " + (VERIFY_DELAY_MS / 1000) + "s");
            }
            return;
        }

        // Java only: DetectionEngine
        if (!engine.onJoin(uuid, name, ip, false)) {
            event.setCancelled(true);
            event.setCancelReason(new TextComponent(config.getKickMessage()));
        }
    }

    @EventHandler
    public void onServerConnect(ServerConnectedEvent event) {
        ProxiedPlayer player = event.getPlayer();
        if (!engine.getFloodgateHelper().isBedrockPlayer(player.getUniqueId())) {
            engine.onSpawn(player.getUniqueId());
        }
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        ProxiedPlayer player = event.getPlayer();
        bedrockJoinTimes.remove(player.getUniqueId());
        if (!engine.getFloodgateHelper().isBedrockPlayer(player.getUniqueId())) {
            engine.onQuit(player.getUniqueId());
        }
    }

    // ==================== Pack Verification ====================

    private void verificationTick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : bedrockJoinTimes.entrySet()) {
            UUID uuid = entry.getKey();
            if (now - entry.getValue() >= VERIFY_DELAY_MS) {
                ProxiedPlayer player = getProxy().getPlayer(uuid);
                if (player != null) {
                    String username = player.getName();
                    String ip = player.getAddress().getAddress().getHostAddress();
                    writeVerifiedPlayer(username, ip);
                    verifiedUsernames.add(username.toLowerCase());
                    bedrockJoinTimes.remove(uuid);
                    getLogger().info("[AntiBedrockTool] VERIFIED: " + username + " (IP: " + ip + ")");
                    player.disconnect(new TextComponent(
                            "§aYou have been verified!\n§ePlease reconnect to load resource packs."));
                } else {
                    bedrockJoinTimes.remove(uuid);
                }
            }
        }
    }

    private Path getVerifiedFilePath() {
        return getDataFolder().toPath().resolve("verified-players.txt");
    }

    private void loadVerifiedPlayers() {
        Path file = getVerifiedFilePath();
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("ip:")) continue;
                verifiedUsernames.add(line.toLowerCase());
            }
            getLogger().info("[AntiBedrockTool] Loaded " + verifiedUsernames.size() + " verified players");
        } catch (IOException e) {
            getLogger().warning("[AntiBedrockTool] Failed to load verified players: " + e.getMessage());
        }
    }

    private void writeVerifiedPlayer(String username, String ip) {
        Path file = getVerifiedFilePath();
        try {
            Files.createDirectories(file.getParent());
            Set<String> existing = new HashSet<>();
            if (Files.exists(file)) existing.addAll(Files.readAllLines(file));
            StringBuilder sb = new StringBuilder();
            if (!existing.contains(username.toLowerCase())) sb.append(username.toLowerCase()).append(System.lineSeparator());
            String ipEntry = "ip:" + ip;
            if (!existing.contains(ipEntry)) sb.append(ipEntry).append(System.lineSeparator());
            if (sb.length() > 0) Files.writeString(file, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            getLogger().warning("[AntiBedrockTool] Failed to write verified player: " + e.getMessage());
        }
    }
}
