package com.leon.bugreport;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;

import static com.leon.bugreport.BugReportSettings.createCustomPlayerHead;
import static com.leon.bugreport.BugReportSettings.getSettingsGUI;

public class BugReportManager {

    static Map<UUID, List<String>> bugReports;
    private static BugReportDatabase database;
    static Plugin plugin;

    public static FileConfiguration config;
    public static File configFile;

    public static String language;
    public static BugReportLanguage lang;

    private final LinkDiscord discord;
    private final List<Category> reportCategories;

    static String pluginTitle;
    static ChatColor pluginColor;

    public BugReportManager(Plugin plugin) {
        BugReportManager.plugin = plugin;
        bugReports = new HashMap<>();
        database = new BugReportDatabase();

        loadBugReports();
        loadConfig();

        String webhookURL = config.getString("webhookURL", "");
        pluginTitle = Objects.requireNonNull(config.getString("pluginTitle", "[Bug Report]"));
		pluginColor = BugReportCommand.stringColorToColorCode(Objects.requireNonNull(Objects.requireNonNull(config.getString ("pluginColor", "Yellow")).toUpperCase()));

        discord = new LinkDiscord(webhookURL);
        reportCategories = loadReportCategories();
    }

    public static boolean checkCategoryConfig() {
        if (config.contains("reportCategories")) {
            List<Map<?, ?>> categoryList = config.getMapList("reportCategories");
            for (Map<?, ?> categoryMap : categoryList) {
                Object[] keys = categoryMap.keySet().toArray();
                Object[] values = categoryMap.values().toArray();

                for (int i = 0; i < keys.length; i++) {
                    if (values[i] == null) {
                        if (BugReportLanguage.getText (language, "missingValueMessage") != null) {
                            plugin.getLogger().warning(BugReportLanguage.getText (language, "missingValueMessage").replace("%key%", keys[i].toString()));
                        } else {
                            plugin.getLogger().warning("Error: Missing " + keys[i] + " in reportCategories in config.");
                        }
                        return false;
                    }
                }
            }
        } else {
            if (BugReportLanguage.getText (language, "missingReportCategoryMessage") != null) {
                plugin.getLogger().warning(BugReportLanguage.getText (language, "missingReportCategoryMessage"));
            } else {
                plugin.getLogger().warning("Error: Missing reportCategories in config.");
            }
            return false;
        }
        return true;
    }

    public static void loadConfig() {
        configFile = new File(plugin.getDataFolder(), "config.yml");

        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(configFile);

        language = config.getString("language", "en");
        lang = new BugReportLanguage(plugin, "languages.yml");
    }

    private List<Category> loadReportCategories() {
        if (checkCategoryConfig()) {
            List<Category> categories = new ArrayList<>();

            List<Map<?, ?>> categoryList = config.getMapList("reportCategories");

            for (Map<?, ?> categoryMap : categoryList) {
                String name = categoryMap.get("name").toString();
                int id = Integer.parseInt(categoryMap.get("id").toString());

                String description = categoryMap.get("description").toString();
                String itemString = categoryMap.get("item").toString();
                String color = categoryMap.get("color").toString().toUpperCase();

                Material itemMaterial = Material.matchMaterial(itemString);
                if (itemMaterial == null) {
                    continue;
                }

                ItemStack itemStack = new ItemStack(itemMaterial);
                ItemMeta itemMeta = itemStack.getItemMeta();
                itemMeta.setDisplayName(ChatColor.YELLOW + name);
                itemMeta.setLore(Collections.singletonList(ChatColor.GRAY + description));
                itemStack.setItemMeta(itemMeta);
                categories.add(new Category(id, name, color, itemStack));
            }

            return categories;
        } else {
            if (BugReportLanguage.getText (language, "wentWrongLoadingCategoriesMessage") != null) {
                plugin.getLogger().warning(BugReportLanguage.getText (language, "wentWrongLoadingCategoriesMessage"));
            } else {
                plugin.getLogger().warning("Error: Something went wrong while loading the report categories.");
            }
            return null;
        }
    }

    public List<Category> getReportCategories() {
        return reportCategories;
    }

    public static void saveConfig() {
        try {
            config.save(configFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setWebhookURL(String webhookURL) {
        config.set("webhookURL", webhookURL);
        saveConfig();
        discord.setWebhookURL(webhookURL);
    }

    public void submitBugReport(Player player, String message, Integer categoryId) {
        UUID playerId = player.getUniqueId();
        List<String> reports = bugReports.getOrDefault(playerId, new ArrayList<>());

        String playerName = player.getName();
        String playerUUID = playerId.toString();
        String worldName = player.getWorld().getName();


        String header = "Username: " + playerName + "\n" +
                "UUID: " + playerUUID + "\n" +
                "World: " + worldName + "\n" +
                "hasBeenRead: 0" + "\n" +
                "Category ID: " + categoryId + "\n" +
                "Full Message: " + message + "\n" +
                "Archived: 0" + "\n" +
                "Report ID: " + (reports.size() + 1);

        reports.add(header);
        bugReports.put(playerId, reports);

        database.addBugReport(playerName, playerId, worldName, header, message);

        if (config.getBoolean("enableBugReportNotifications", true)) {
            String defaultMessage = pluginColor + pluginTitle + " " + ChatColor.GRAY + "A new bug report has been submitted by " + ChatColor.YELLOW + playerName + ChatColor.GRAY + ".";
            String languageMessage = BugReportLanguage.getText(language, "bugReportNotificationMessage");
            String notificationMessage = (languageMessage != null)
                    ? pluginColor + pluginTitle + " " + ChatColor.GRAY + languageMessage.replace("%player%", playerName) + ChatColor.GRAY + "."
                    : defaultMessage;

            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (onlinePlayer.hasPermission("bugreport.notify")) {
                    onlinePlayer.sendMessage(notificationMessage);
                }
            }
        }

        if (config.getBoolean("enableDiscordWebhook", true)) {
            String webhookURL = config.getString("webhookURL", "");

            if (!webhookURL.isEmpty()) {
                try {
                    discord.sendBugReport(message, playerId, worldName, playerName);
                } catch (Exception e) {
                    plugin.getLogger().warning("Error sending bug report to Discord: " + e.getMessage());
                }
            } else {
                String warningMessage = BugReportLanguage.getText (language, "missingDiscordWebhookURLMessage");
                plugin.getLogger().warning(Objects.requireNonNullElse(warningMessage, "Missing webhookURL in config.yml"));
            }
        }
    }

    public static Inventory getBugReportGUI(Player player) {
        int itemsPerPage = 27;
        int navigationRow = 36;

        UUID playerId = player.getUniqueId();
        List<String> reports = bugReports.getOrDefault(playerId, new ArrayList<>());

        List<String> filteredReports = new ArrayList<>();
        for (String report : reports) {
            if (!report.contains("Archived: 1")) {
                filteredReports.add(report);
            }
        }

        int totalPages = (int) Math.ceil((double) filteredReports.size() / itemsPerPage);
        int currentPage = Math.max(1, Math.min(getCurrentPage(player), totalPages));

        Inventory gui = Bukkit.createInventory(null, 45, ChatColor.YELLOW + "Bug Report - " + BugReportLanguage.getTitleFromLanguage("pageInfo").replace("%currentPage%", String.valueOf(currentPage)).replace("%totalPages%", String.valueOf(totalPages)));

        int startIndex = (currentPage - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, filteredReports.size());

        int slotIndex = 0;
        for (int i = startIndex; i < endIndex; i++) {
            String report = filteredReports.get(i);
            String reportID = report.substring(report.indexOf("Report ID: ") + 11);
            String firstLine = report.split("\n")[0];

            ItemStack reportItem;

            if (report.contains("Archived: 1")) {
                continue;
            } else if (report.contains("hasBeenRead: 0")) {
                reportItem = new ItemStack(Material.ENCHANTED_BOOK);
            } else {
                reportItem = new ItemStack(Material.BOOK);
            }

            ItemMeta itemMeta = reportItem.getItemMeta();
            itemMeta.setDisplayName(ChatColor.YELLOW + "Bug Report #" + reportID);
            itemMeta.setLore(Collections.singletonList(ChatColor.GRAY + firstLine));

            reportItem.setItemMeta(itemMeta);

            gui.setItem(slotIndex, reportItem);
            slotIndex++;
        }

        ItemStack backButton = createButton(Material.ARROW, ChatColor.YELLOW + BugReportLanguage.getTitleFromLanguage("back"));
        ItemStack forwardButton = createButton(Material.ARROW, ChatColor.YELLOW + BugReportLanguage.getTitleFromLanguage("forward"));
        ItemStack pageIndicator = createButton(Material.PAPER, ChatColor.YELLOW + BugReportLanguage.getTitleFromLanguage("pageInfo").replace("%currentPage%", String.valueOf(currentPage)).replace("%totalPages%", String.valueOf(totalPages)));
        ItemStack settingsButton = createButton(Material.CHEST, ChatColor.YELLOW + BugReportLanguage.getTitleFromLanguage("settings"));
        ItemStack closeButton = createButton(Material.BARRIER, ChatColor.RED + BugReportLanguage.getTitleFromLanguage("close"));

        if (currentPage > 1) {
            gui.setItem(navigationRow, backButton);
        }
        if (currentPage < totalPages) {
            gui.setItem(navigationRow + 8, forwardButton);
        }

        gui.setItem(navigationRow + 2, settingsButton);
        gui.setItem(navigationRow + 4, pageIndicator);
        gui.setItem(navigationRow + 6, closeButton);

        return gui;
    }

    private static ItemStack createButton(Material material, String displayName) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(displayName);
        item.setItemMeta(meta);
        return item;
    }

    private void loadBugReports() {
        Map<UUID, List<String>> loadedReports = database.loadBugReports();
        if (loadedReports != null) {
            bugReports = loadedReports;
        }
    }

    public static class BugReportListener implements Listener {

        private final BugReportManager reportManager;
        private final Map<UUID, Boolean> closingInventoryMap;

        public BugReportListener(BugReportManager reportManager) {
            this.reportManager = reportManager;
            this.closingInventoryMap = new HashMap<>();
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onInventoryClick(InventoryClickEvent event) {
            if (!event.getView().getTitle().startsWith(ChatColor.YELLOW + "Bug Report")) {
                return;
            }

            event.setCancelled(true);

            Player player = (Player) event.getWhoClicked();
            Inventory clickedInventory = event.getClickedInventory();
            if (clickedInventory == null) {
                return;
            }

            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getType() == Material.AIR) {
                return;
            }

            ItemMeta itemMeta = clickedItem.getItemMeta();
            if (itemMeta == null || !itemMeta.hasDisplayName()) {
                return;
            }

            String displayName = itemMeta.getDisplayName();
            String customDisplayName = BugReportLanguage.getEnglishVersionFromLanguage(displayName);

            switch (customDisplayName) {
                case "Back" -> {
                    int currentPage = getCurrentPage(player);
                    if (currentPage > 1) {
                        setCurrentPage(player, currentPage - 1);
                        player.openInventory(getBugReportGUI(player));
                    }
                }
                case "Forward" -> {
                    int currentPage = getCurrentPage(player);
                    if (currentPage < reportManager.getTotalPages(player)) {
                        setCurrentPage(player, currentPage + 1);
                        player.openInventory(getBugReportGUI(player));
                    }
                }
                case "Settings" -> player.openInventory(getSettingsGUI());
                case "Close" -> {
                    closingInventoryMap.put(player.getUniqueId(), true);
                    player.closeInventory();
                }
            }

            if (displayName.startsWith(ChatColor.YELLOW + "Bug Report #")) {
                int reportID = Integer.parseInt(displayName.substring(14)) - 1;
                UUID playerId = player.getUniqueId();
                List<String> reports = bugReports.getOrDefault(playerId, new ArrayList<>());

                String report = reports.get(reportID);

                if (report.contains("hasBeenRead: 0")) {
                    report = report.replace("hasBeenRead: 0", "hasBeenRead: 1");
                    reports.set(reportID, report);
                    bugReports.put(playerId, reports);
                    database.updateBugReportHeader(playerId, reportID);
                }
                if (reportID >= 0 && reportID < reports.size()) {
                    reports.set(reportID, report);
                    openBugReportDetailsGUI(player, report, reportID);
                }
            }

            if (customDisplayName.equals("Settings")) {
                player.openInventory(getSettingsGUI());
            }

            if (customDisplayName.equals("Close")) {
                closingInventoryMap.put(player.getUniqueId(), true);
                player.closeInventory();
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onInventoryClose(@NotNull InventoryCloseEvent event) {
            if (event.getView().getTitle().startsWith(ChatColor.YELLOW + "Bug Report")) {
                Player player = (Player) event.getPlayer();
                UUID playerId = player.getUniqueId();

                if (closingInventoryMap.getOrDefault(playerId, false)) {
                    closingInventoryMap.put(playerId, false);
                    return;
                }

                closingInventoryMap.remove(playerId);
            }
        }
    }

    public static int getCurrentPage(@NotNull Player player) {
        return player.getMetadata("currentPage").get(0).asInt();
    }

    public int getTotalPages(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        List<String> reports = bugReports.getOrDefault(playerId, new ArrayList<>());
        int totalPages = (int) Math.ceil((double) reports.size() / 27);
        return totalPages;
    }

    public static void setCurrentPage(@NotNull Player player, int page) {
        player.setMetadata("currentPage", new FixedMetadataValue(plugin, page));
    }

    private static void openBugReportDetailsGUI(Player player, @NotNull String report, Integer reportId) {
        int reportIDGUI = reportId + 1;
        Inventory gui = Bukkit.createInventory(player, 36, ChatColor.YELLOW + "Bug Report Details - #" + reportIDGUI);

        String[] reportLines = report.split("\n");

        Map<String, String> reportData = new HashMap<>();

        for (String line : reportLines) {
            int colonIndex = line.indexOf(":");
            if (colonIndex >= 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                reportData.put(key, value);
            }
        }

        String username = reportData.get("Username");
        String uuid = reportData.get("UUID");
        String world = reportData.get("World");
        String fullMessage = reportData.get("Full Message");
        String category = reportData.get("Category ID");
        ItemStack emptyItem = createEmptyItem();

        ItemStack usernameItem = createInfoItem(Material.PLAYER_HEAD, ChatColor.GOLD + "Username", ChatColor.WHITE + username);
        ItemStack uuidItem = createInfoItem(Material.NAME_TAG, ChatColor.GOLD + "UUID", ChatColor.WHITE + uuid);
        ItemStack worldItem = createInfoItem(Material.GRASS_BLOCK, ChatColor.GOLD + "World", ChatColor.WHITE + world);
        ItemStack messageItem = createInfoItem(Material.PAPER, ChatColor.GOLD + "Full Message", ChatColor.WHITE + fullMessage, fullMessage.length() > 32);
        ItemStack backButton = createButton(Material.BARRIER, ChatColor.RED + BugReportLanguage.getTitleFromLanguage("back"));

        ItemStack archiveButton = createCustomPlayerHead("eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmUwZmQxMDE5OWU4ZTRmY2RhYmNhZTRmODVjODU5MTgxMjdhN2M1NTUzYWQyMzVmMDFjNTZkMThiYjk0NzBkMyJ9fX0=", ChatColor.YELLOW + BugReportLanguage.getTitleFromLanguage("archive"), 16);
//        ItemStack deleteButton = createCustomPlayerHead("eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvY2Y5YjY3YmI5Y2MxYzg4NDg2NzYwYjE3MjY1MDU0MzEyZDY1OWRmMmNjNjc1NTc1MDA0NWJkNzFjZmZiNGU2MCJ9fX0=", ChatColor.YELLOW + BugReportLanguage.getTitleFromLanguage("delete"), 17);

        for (int i = 0; i < gui.getSize(); i++) {
            gui.setItem(i, emptyItem);
        }

        gui.setItem(9, usernameItem);
        gui.setItem(11, uuidItem);
        gui.setItem(13, worldItem);
        gui.setItem(15, messageItem);

        gui.setItem(29, archiveButton);
        gui.setItem(31, backButton);
//        gui.setItem(33, deleteButton);

        if (!"null".equals(category) && !"".equals(category)) {
            List<Map<?, ?>> categoryList = config.getMapList("reportCategories");

            Optional<String> categoryNameOptional = categoryList.stream()
                    .filter(categoryMap -> Integer.parseInt(categoryMap.get("id").toString()) == Integer.parseInt(category))
                    .map(categoryMap -> categoryMap.get("name").toString())
                    .findFirst();

            if (categoryNameOptional.isPresent()) {
                String categoryName = categoryNameOptional.get();
                ItemStack categoryItem = createInfoItem(Material.CHEST, ChatColor.GOLD + "Category Name", ChatColor.WHITE + categoryName, false);
                gui.setItem(17, categoryItem);
            }
        }

        player.openInventory(gui);

        Bukkit.getPluginManager().registerEvents(new BugReportDetailsListener(gui, reportIDGUI), plugin);
    }

    private record BugReportDetailsListener(Inventory gui, Integer reportIDGUI) implements Listener {
        @EventHandler(priority = EventPriority.NORMAL)
        public void onInventoryClick(InventoryClickEvent event) {
            if (!event.getView().getTitle().startsWith(ChatColor.YELLOW + "Bug Report Details - #")) {
                return;
            }

            event.setCancelled(true);

            Player player = (Player) event.getWhoClicked();
            UUID playerId = player.getUniqueId();

            Inventory clickedInventory = event.getClickedInventory();
            if (clickedInventory == null) {
                return;
            }

            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getType() == Material.AIR) {
                return;
            }

            ItemMeta itemMeta = clickedItem.getItemMeta();
            if (itemMeta == null || !itemMeta.hasDisplayName()) {
                return;
            }

            String displayName = itemMeta.getDisplayName();

            if (displayName.equals(" ")) {
                return;
            }

            String customDisplayName = BugReportLanguage.getEnglishVersionFromLanguage(displayName);

            switch (customDisplayName) {
                case "Back" -> player.openInventory(getBugReportGUI(player));
                case "Archive" -> {
                    BugReportDatabase.updateBugReportArchive(playerId, reportIDGUI, 1);
                    player.openInventory(getBugReportGUI(player));
                }
                case "Delete" -> player.closeInventory();
                default -> {
                    return;
                }
            }

            HandlerList.unregisterAll(this);
        }
    }

    private static ItemStack createEmptyItem() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(" ");
        item.setItemMeta(meta);

        return item;
    }

    private static ItemStack createInfoItem(Material material, String name, String value, Boolean... longMessage) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);

        if (longMessage.length > 0 && longMessage[0]) {
            List<String> lore = new ArrayList<>();
            String[] words = value.split(" ");
            StringBuilder currentLine = new StringBuilder();
            for (String word : words) {
                if (currentLine.length() + word.length() > 30) {
                    lore.add(currentLine.toString());
                    currentLine = new StringBuilder();
                }

                currentLine.append(word).append(" ");
            }

            if (!currentLine.isEmpty()) {
                lore.add(currentLine.toString());
            }

            meta.setLore(lore);
        } else {
            meta.setLore(Collections.singletonList(value));
        }

        item.setItemMeta(meta);

        return item;
    }
}