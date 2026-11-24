package com.example.villfaux;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.StructureType;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class VillFauxPlugin extends JavaPlugin implements Listener {

    private NamespacedKey specialKey;
    private NamespacedKey eggKey;

    private File stateFile;
    private YamlConfiguration state;

    private UUID activeId;
    private long nextSpawnAt;

    private VillagerListener villagerListener;
    private boolean villageSearchRunning;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        specialKey = new NamespacedKey(this, "villfaux");
        eggKey = new NamespacedKey(this, "villfaux_egg");

        stateFile = new File(getDataFolder(), "state.yml");
        state = YamlConfiguration.loadConfiguration(stateFile);

        String savedId = state.getString("active-uuid");

        if (savedId != null && !savedId.isBlank()) {
            try {
                activeId = UUID.fromString(savedId);
            } catch (IllegalArgumentException exception) {
                getLogger().severe(
                        "Некорректный UUID в state.yml. "
                                + "Плагин отключён, чтобы не создать "
                                + "второго естественного VillFaux."
                );

                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }

        nextSpawnAt = state.getLong("next-spawn-at", 0L);

        Objects.requireNonNull(getCommand("villfaux"))
                .setExecutor(this);

        villagerListener = new VillagerListener(this);

        getServer().getPluginManager()
                .registerEvents(this, this);

        getServer().getPluginManager()
                .registerEvents(villagerListener, this);

        getLogger().info("VillFaux включён.");
    }

    @Override
    public void onDisable() {
        if (state != null && stateFile != null) {
            saveState();
        }
    }

    public NamespacedKey getSpecialKey() {
        return specialKey;
    }

    public NamespacedKey getEggKey() {
        return eggKey;
    }

    public boolean isSpecial(Entity entity) {
        return entity.getPersistentDataContainer()
                .has(specialKey, PersistentDataType.BYTE);
    }

    public boolean canSpawn() {
        return activeId == null
                && System.currentTimeMillis() >= nextSpawnAt;
    }

    public UUID getActiveId() {
        return activeId;
    }

    public void markEggVillager(Entity entity) {
        entity.getPersistentDataContainer()
                .set(specialKey, PersistentDataType.BYTE, (byte) 1);
    }

    public boolean registerSpecial(Entity entity) {
        return registerSpecial(entity, false);
    }

    public boolean registerSpecial(
            Entity entity,
            boolean ignoreCooldown
    ) {
        if (activeId != null) {
            return false;
        }

        if (!ignoreCooldown
                && System.currentTimeMillis() < nextSpawnAt) {
            return false;
        }

        entity.getPersistentDataContainer()
                .set(specialKey, PersistentDataType.BYTE, (byte) 1);

        activeId = entity.getUniqueId();
        saveState();

        return true;
    }

    public boolean replaceSpecial(
            Entity oldEntity,
            Entity replacement
    ) {
        if (!isSpecial(oldEntity)) {
            return false;
        }

        replacement.getPersistentDataContainer()
                .set(specialKey, PersistentDataType.BYTE, (byte) 1);

        if (activeId != null
                && activeId.equals(oldEntity.getUniqueId())) {
            activeId = replacement.getUniqueId();
            saveState();
        }

        return true;
    }

    public void recordDeath(Entity entity) {
        if (activeId == null
                || !activeId.equals(entity.getUniqueId())) {
            return;
        }

        activeId = null;

        long hours = Math.max(
                0L,
                getConfig().getLong("respawn-hours", 12L)
        );

        nextSpawnAt = System.currentTimeMillis()
                + hours * 60L * 60L * 1000L;

        saveState();
    }

    private void saveState() {
        state.set(
                "active-uuid",
                activeId == null ? null : activeId.toString()
        );

        state.set("next-spawn-at", nextSpawnAt);

        try {
            state.save(stateFile);
        } catch (IOException exception) {
            getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Не удалось сохранить состояние VillFaux.",
                    exception
            );
        }
    }

    // ---------- Команда /villfaux ----------

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!command.getName().equalsIgnoreCase("villfaux")) {
            return false;
        }

        if (!sender.isOp()) {
            sender.sendMessage(
                    "Команды VillFaux доступны только администраторам."
            );
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Эту команду нужно вводить в игре.");
            return true;
        }

        if (args.length != 1
                || !args[0].equalsIgnoreCase("spawn")) {
            player.sendMessage(
                    Component.text(
                            "Использование: /villfaux spawn",
                            NamedTextColor.YELLOW
                    )
            );
            return true;
        }

        startVillageSearch(player);
        return true;
    }

    // ---------- Команда /give egg VillFaux ----------

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onCommandPreprocess(
            PlayerCommandPreprocessEvent event
    ) {
        String[] parts = event.getMessage()
                .trim()
                .split("\\s+");

        boolean requestedEgg = parts.length == 3
                && parts[0].equalsIgnoreCase("/give")
                && parts[1].equalsIgnoreCase("egg")
                && parts[2].equalsIgnoreCase("VillFaux");

        if (!requestedEgg) {
            return;
        }

        event.setCancelled(true);
        giveEgg(event.getPlayer());
    }

    private void giveEgg(Player player) {
        if (!player.isOp()) {
            player.sendMessage(
                    Component.text(
                            "Эта команда доступна только администраторам.",
                            NamedTextColor.RED
                    )
            );
            return;
        }

        ItemStack egg = new ItemStack(Material.VILLAGER_SPAWN_EGG);
        ItemMeta meta = egg.getItemMeta();

        meta.displayName(
                Component.text(
                        "Яйцо призыва VillFaux",
                        NamedTextColor.LIGHT_PURPLE
                )
        );

        meta.getPersistentDataContainer()
                .set(eggKey, PersistentDataType.BYTE, (byte) 1);

        egg.setItemMeta(meta);

        for (ItemStack leftover
                : player.getInventory().addItem(egg).values()) {
            player.getWorld().dropItemNaturally(
                    player.getLocation(),
                    leftover
            );
        }

        player.sendMessage(
                Component.text(
                        "Ты получил яйцо VillFaux.",
                        NamedTextColor.GREEN
                )
        );
    }

    // ---------- Поиск случайной деревни ----------

    private void startVillageSearch(Player player) {
        if (activeId != null) {
            player.sendMessage(
                    "Естественный VillFaux уже существует. "
                            + "Второго создать нельзя."
            );
            return;
        }

        if (villageSearchRunning) {
            player.sendMessage(
                    "Поиск деревни уже выполняется. Подожди."
            );
            return;
        }

        World world = player.getWorld();

        if (world.getEnvironment() != World.Environment.NORMAL) {
            player.sendMessage(
                    "Для этой команды нужно находиться в обычном мире."
            );
            return;
        }

        Location center = player.getLocation().clone();

        villageSearchRunning = true;

        player.sendMessage(
                "Ищу деревню около случайных точек "
                        + "в пределах 5000 блоков по X и Z. "
                        + "Поиск может занять время."
        );

        new BukkitRunnable() {
            private int attempts;

            @Override
            public void run() {
                if (!player.isOnline() || !player.isOp()) {
                    finish();
                    return;
                }

                if (activeId != null) {
                    player.sendMessage(
                            "Поиск остановлен: "
                                    + "естественный VillFaux уже появился."
                    );
                    finish();
                    return;
                }

                if (attempts >= 12) {
                    player.sendMessage(
                            "Подходящая деревня не найдена. "
                                    + "Переместись в другую область "
                                    + "и повтори команду."
                    );
                    finish();
                    return;
                }

                attempts++;

                try {
                    Location village = findVillage(world, center);

                    if (village == null) {
                        return;
                    }

                    Location spawnLocation =
                            findSafeSurface(world, village);

                    if (spawnLocation == null) {
                        return;
                    }

                    Villager villager = world.spawn(
                            spawnLocation,
                            Villager.class
                    );

                    if (!villager.isValid() || villager.isDead()) {
                        return;
                    }

                    if (!registerSpecial(villager, true)) {
                        villager.remove();

                        player.sendMessage(
                                "Не удалось зарегистрировать VillFaux: "
                                        + "возможно, он уже существует."
                        );

                        finish();
                        return;
                    }

                    villagerListener.prepareAndAnnounce(villager);

                    player.sendMessage(
                            "VillFaux создан без проверки таймера "
                                    + "и шанса появления. Координаты жителя: "
                                    + "X: " + spawnLocation.getBlockX()
                                    + " Y: " + spawnLocation.getBlockY()
                                    + " Z: " + spawnLocation.getBlockZ()
                    );

                    finish();
                } catch (Exception exception) {
                    getLogger().log(
                            java.util.logging.Level.SEVERE,
                            "Ошибка при создании VillFaux в деревне.",
                            exception
                    );

                    player.sendMessage(
                            "Произошла ошибка. Посмотри консоль сервера."
                    );

                    finish();
                }
            }

            private void finish() {
                villageSearchRunning = false;
                cancel();
            }
        }.runTaskTimer(this, 1L, 20L);
    }

    @SuppressWarnings("deprecation")
    private Location findVillage(World world, Location center) {
        int x = center.getBlockX()
                + ThreadLocalRandom.current().nextInt(-5000, 5001);

        int z = center.getBlockZ()
                + ThreadLocalRandom.current().nextInt(-5000, 5001);

        Location searchPoint = new Location(world, x, 0, z);

        if (!world.getWorldBorder().isInside(searchPoint)) {
            return null;
        }

        Location village = world.locateNearestStructure(
                searchPoint,
                StructureType.VILLAGE,
                8,
                false
        );

        if (village == null
                || !world.getWorldBorder().isInside(village)) {
            return null;
        }

        if (Math.abs(village.getX() - center.getX()) > 5000
                || Math.abs(village.getZ() - center.getZ()) > 5000) {
            return null;
        }

        return village;
    }

    private Location findSafeSurface(
            World world,
            Location village
    ) {
        for (int attempt = 0; attempt < 40; attempt++) {
            int x = village.getBlockX()
                    + ThreadLocalRandom.current().nextInt(-24, 25);

            int z = village.getBlockZ()
                    + ThreadLocalRandom.current().nextInt(-24, 25);

            int y = world.getHighestBlockYAt(x, z);

            if (y + 2 >= world.getMaxHeight()) {
                continue;
            }

            Material ground = world.getBlockAt(x, y, z).getType();

            if (!ground.isSolid()
                    || ground.name().endsWith("_LEAVES")
                    || ground == Material.CACTUS
                    || ground == Material.MAGMA_BLOCK
                    || ground == Material.CAMPFIRE
                    || ground == Material.SOUL_CAMPFIRE) {
                continue;
            }

            if (!world.getBlockAt(x, y + 1, z).isEmpty()
                    || !world.getBlockAt(x, y + 2, z).isEmpty()) {
                continue;
            }

            Location result = new Location(
                    world,
                    x + 0.5,
                    y + 1.0,
                    z + 0.5
            );

            if (world.getWorldBorder().isInside(result)) {
                return result;
            }
        }

        return null;
    }
}