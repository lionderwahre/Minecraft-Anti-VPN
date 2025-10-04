package com.lionderwahre.antiVpn;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class AntiVPN extends JavaPlugin implements Listener {
    private String apiKey;
    private String kickMessage;
    private List<String> whitelist;
    private boolean debugMode;
    private final Map<String, com.lionderwahre.antiVpn.AntiVPN.CacheEntry> cache = new HashMap<>();
    private final long CACHE_DURATION = 86400000L;

    public void onEnable() {
        this.loadConfigValues();
        Bukkit.getPluginManager().registerEvents(this, this);
        this.getLogger().info("AntiVPN plugin enabled!");
        if (this.apiKey == null || this.apiKey.isEmpty() || this.apiKey.equals("YOUR_API_KEY")) {
            this.getLogger().severe("ERROR: No valid API key configured! Plugin will not work.");
            this.getLogger().severe("Please register at https://proxycheck.io and enter your API key in the config.yml file.");
        }
    }

    private void loadConfigValues() {
        this.saveDefaultConfig();
        this.reloadConfig();
        FileConfiguration config = this.getConfig();
        this.apiKey = config.getString("api-key", "");
        this.kickMessage = config.getString("kick-message", "§cVPN/Proxy connections are not allowed here!");
        this.whitelist = new ArrayList<>(config.getStringList("whitelist"));
        this.debugMode = config.getBoolean("debug-mode", false);
        if (this.debugMode) {
            this.getLogger().info("Debug mode enabled");
            Logger var10000 = this.getLogger();
            boolean var10001 = !this.apiKey.isEmpty() && !this.apiKey.equals("YOUR_API_KEY");
            var10000.info("API key set: " + var10001);
            this.getLogger().info("Whitelist: " + this.whitelist);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        String ip = ((InetSocketAddress) Objects.requireNonNull(player.getAddress())).getAddress().getHostAddress();
        if (this.debugMode) {
            this.getLogger().info("Player " + name + " joined with IP: " + ip);
        }

        if (this.whitelist.contains(name)) {
            if (this.debugMode) {
                this.getLogger().info("Player " + name + " is in the whitelist - skipping VPN check");
            }
        } else if (this.apiKey != null && !this.apiKey.isEmpty() && !this.apiKey.equals("YOUR_API_KEY")) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    if (this.debugMode) {
                        this.getLogger().info("Starting VPN check for IP: " + ip);
                    }

                    boolean blocked = this.isBlockedIP(ip);
                    if (this.debugMode) {
                        this.getLogger().info("VPN check result for " + ip + ": " + (blocked ? "BLOCKED" : "ALLOWED"));
                    }

                    if (blocked) {
                        Bukkit.getScheduler().runTask(this, () -> {
                            if (player.isOnline()) {
                                player.kickPlayer(this.kickMessage);
                                this.getLogger().info("Player " + name + " was blocked due to VPN/Proxy. (" + ip + ")");
                            }
                        });
                    } else if (this.debugMode) {
                        this.getLogger().info("Player " + name + " was not blocked - no VPN/Proxy detected");
                    }
                } catch (Exception var5) {
                    this.getLogger().severe("Error checking IP " + ip + ": " + var5.getMessage());
                    if (this.debugMode) {
                        var5.printStackTrace();
                    }
                }
            });
        } else {
            this.getLogger().warning("No API key configured - cannot perform VPN check for " + name);
        }
    }

    private boolean isBlockedIP(String ip) throws Exception {
        long now = System.currentTimeMillis();
        if (this.cache.containsKey(ip)) {
            com.lionderwahre.antiVpn.AntiVPN.CacheEntry entry = this.cache.get(ip);
            if (now - entry.timestamp < 86400000L) {
                if (this.debugMode) {
                    this.getLogger().info("Cache hit for IP " + ip + ": " + (entry.isVpn ? "VPN" : "Clean"));
                }
                return entry.isVpn;
            }
            if (this.debugMode) {
                this.getLogger().info("Cache for IP " + ip + " has expired");
            }
        }

        String urlStr = "https://proxycheck.io/v2/" + ip + "?key=" + this.apiKey + "&vpn=1&asn=1";
        if (this.debugMode) {
            Logger var10000 = this.getLogger();
            String var10001 = urlStr.replace(this.apiKey, "***");
            var10000.info("API request to: " + var10001);
        }

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "AntiVPN-Plugin/1.0");
        int responseCode = conn.getResponseCode();
        if (this.debugMode) {
            this.getLogger().info("API Response Code: " + responseCode);
        }

        if (responseCode != 200) {
            throw new Exception("API returned status code: " + responseCode);
        } else {
            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();

            String line;
            while ((line = in.readLine()) != null) {
                response.append(line);
            }

            in.close();
            String resp = response.toString();
            if (this.debugMode) {
                this.getLogger().info("API Response: " + resp);
            }

            boolean blocked = false;
            String respLower = resp.toLowerCase().replaceAll("\\s+", "");
            if (respLower.contains("\"proxy\":\"yes\"") || respLower.contains("\"vpn\":\"yes\"") ||
                respLower.contains("\"type\":\"vpn\"") || respLower.contains("\"type\":\"proxy\"") ||
                respLower.contains("\"type\":\"socks") || respLower.contains("\"type\":\"http") ||
                respLower.contains("\"type\":\"https")) {
                blocked = true;
            }

            if (resp.toLowerCase().contains("\"status\":\"error\"")) {
                this.getLogger().warning("API error for IP " + ip + ": " + resp);
            }

            if (this.debugMode) {
                this.getLogger().info("Final decision for IP " + ip + ": " + (blocked ? "BLOCKED" : "ALLOWED"));
            }

            this.cache.put(ip, new com.lionderwahre.antiVpn.AntiVPN.CacheEntry(blocked, now));
            return blocked;
        }
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("antivpn.admin")) {
                sender.sendMessage("§cNo permission!");
                return true;
            } else {
                this.loadConfigValues();
                sender.sendMessage("§aAntiVPN config reloaded!");
                this.getLogger().info(sender.getName() + " reloaded the AntiVPN config.");
                return true;
            }
        } else if (args.length == 1 && args[0].equalsIgnoreCase("debug")) {
            if (!sender.hasPermission("antivpn.admin")) {
                sender.sendMessage("§cNo permission!");
                return true;
            } else {
                this.debugMode = !this.debugMode;
                this.getConfig().set("debug-mode", this.debugMode);
                this.saveConfig();
                sender.sendMessage("§aDebug mode " + (this.debugMode ? "enabled" : "disabled"));
                return true;
            }
        } else {
            String name;
            if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
                if (!sender.hasPermission("antivpn.admin")) {
                    sender.sendMessage("§cNo permission!");
                    return true;
                } else {
                    name = args[1];
                    Player target = Bukkit.getPlayer(name);
                    if (target == null) {
                        sender.sendMessage("§cPlayer not found!");
                        return true;
                    } else {
                        String ip = ((InetSocketAddress) Objects.requireNonNull(target.getAddress())).getAddress().getHostAddress();
                        sender.sendMessage("§eChecking IP of " + name + " (" + ip + ")...");
                        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                            try {
                                boolean blocked = this.isBlockedIP(ip);
                                Bukkit.getScheduler().runTask(this, () -> {
                                    sender.sendMessage("§aResult for " + name + ": " + (blocked ? "§cVPN/Proxy detected" : "§aNo VPN/Proxy"));
                                });
                            } catch (Exception var5) {
                                Bukkit.getScheduler().runTask(this, () -> {
                                    sender.sendMessage("§cError checking: " + var5.getMessage());
                                });
                            }
                        });
                        return true;
                    }
                }
            } else if (args.length >= 1 && args[0].equalsIgnoreCase("whitelist")) {
                if (!sender.hasPermission("antivpn.admin")) {
                    sender.sendMessage("§cNo permission!");
                    return true;
                } else if (args.length == 1) {
                    sender.sendMessage("§eUsage: /antivpn whitelist <add|remove|list> [Name]");
                    return true;
                } else if (args[1].equalsIgnoreCase("add") && args.length == 3) {
                    name = args[2];
                    if (!this.whitelist.contains(name)) {
                        this.whitelist.add(name);
                        this.getConfig().set("whitelist", this.whitelist);
                        this.saveConfig();
                        sender.sendMessage("§a" + name + " was added to the whitelist.");
                        this.getLogger().info("Whitelist: " + name + " added by " + sender.getName());
                    } else {
                        sender.sendMessage("§e" + name + " is already on the whitelist.");
                    }
                    return true;
                } else if (args[1].equalsIgnoreCase("remove") && args.length == 3) {
                    name = args[2];
                    if (this.whitelist.remove(name)) {
                        this.getConfig().set("whitelist", this.whitelist);
                        this.saveConfig();
                        sender.sendMessage("§c" + name + " was removed from the whitelist.");
                        this.getLogger().info("Whitelist: " + name + " removed by " + sender.getName());
                    } else {
                        sender.sendMessage("§e" + name + " is not on the whitelist.");
                    }
                    return true;
                } else if (args[1].equalsIgnoreCase("list")) {
                    if (this.whitelist.isEmpty()) {
                        sender.sendMessage("§7The whitelist is empty.");
                    } else {
                        sender.sendMessage("§aWhitelist: §f" + String.join(", ", this.whitelist));
                    }
                    return true;
                } else {
                    sender.sendMessage("§eUsage: /antivpn whitelist <add|remove|list> [Name]");
                    return true;
                }
            } else {
                sender.sendMessage("§eUsage:");
                sender.sendMessage("§e/antivpn reload - Reload config");
                sender.sendMessage("§e/antivpn debug - Toggle debug mode");
                sender.sendMessage("§e/antivpn check <player> - Check a player’s IP");
                sender.sendMessage("§e/antivpn whitelist <add|remove|list> - Manage whitelist");
                return true;
            }
        }
    }

    private static class CacheEntry {
        boolean isVpn;
        long timestamp;

        CacheEntry(boolean isVpn, long timestamp) {
            this.isVpn = isVpn;
            this.timestamp = timestamp;
        }
    }
}
