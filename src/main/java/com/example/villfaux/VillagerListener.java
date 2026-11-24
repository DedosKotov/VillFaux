package com.example.villfaux;

import io.papermc.paper.event.player.PlayerTradeEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.StructureType;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Vindicator;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class VillagerListener implements Listener {

    private final VillFauxPlugin plugin;
    private final TradePunishments tradePunishments;

    private final Set<UUID> eggClicks = new HashSet<>();

    public VillagerListener(VillFauxPlugin plugin) {
        this.plugin = plugin;
        this.tradePunishments = new TradePunishments(plugin);

        Bukkit.getPluginManager().registerEvents(
                tradePunishments,
                plugin
        );
    }

    // ---------- Естественное появление ----------

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onVillagerSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }

        String reason = event.getSpawnReason().name();

        boolean allowed = reason.equals("NATURAL")
                || reason.equals("CHUNK_GEN")
                || reason.equals("BREEDING")
                || reason.equals("BRED");

        if (!allowed || !plugin.canSpawn()) {
            return;
        }

        double chance = Math.max(
                0.0,
                Math.min(
                        1.0,
                        plugin.getConfig().getDouble(
                                "spawn-chance",
                                0.0005
                        )
                )
        );

        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!villager.isValid()
                    || villager.isDead()
                    || !plugin.canSpawn()) {
                return;
            }

            if (!isNearGeneratedVillage(villager.getLocation())) {
                return;
            }

            if (plugin.registerSpecial(villager)) {
                prepareAndAnnounce(villager);
            }
        });
    }

    // Этот метод также вызывается командой /villfaux spawn.
    public void prepareAndAnnounce(Villager villager) {
        configureVillager(villager);
        announceSpawn(villager);
    }

    // ---------- Объявление только в чате ----------

    @SuppressWarnings("deprecation")
    private void announceSpawn(Villager villager) {
        Location location = villager.getLocation();
        World world = location.getWorld();

        if (world == null) {
            return;
        }

        Location village = null;

        if (world.getEnvironment() == World.Environment.NORMAL) {
            village = world.locateNearestStructure(
                    location,
                    StructureType.VILLAGE,
                    4,
                    false
            );
        }

        String coordinates;

        if (village != null) {
            coordinates = "Деревня: X: " + village.getBlockX()
                    + "  Z: " + village.getBlockZ();
        } else {
            coordinates = "Житель: X: " + location.getBlockX()
                    + "  Y: " + location.getBlockY()
                    + "  Z: " + location.getBlockZ();
        }

        Component announcement = Component.text(
                "Особый житель появился",
                NamedTextColor.LIGHT_PURPLE
        ).append(
                Component.text(
                        " — " + coordinates,
                        NamedTextColor.RED
                )
        );

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(announcement);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean isNearGeneratedVillage(Location location) {
        World world = location.getWorld();

        if (world == null
                || world.getEnvironment() != World.Environment.NORMAL) {
            return false;
        }

        Location village = world.locateNearestStructure(
                location,
                StructureType.VILLAGE,
                4,
                false
        );

        if (village == null) {
            return false;
        }

        double radius = Math.max(
                16.0,
                plugin.getConfig().getDouble(
                        "village-radius",
                        96.0
                )
        );

        double dx = village.getX() - location.getX();
        double dz = village.getZ() - location.getZ();

        return dx * dx + dz * dz <= radius * radius;
    }

    // ---------- Настройка VillFaux ----------

    private void configureVillager(Villager villager) {
        villager.customName(
                Component.text("VillFaux", NamedTextColor.RED)
        );

        villager.setCustomNameVisible(true);
        villager.setAdult();
        villager.setAgeLock(true);
        villager.setPersistent(true);
        villager.setRemoveWhenFarAway(false);
        villager.setCanPickupItems(false);
        villager.setProfession(Villager.Profession.LIBRARIAN);
        villager.setVillagerLevel(5);

        setTradeRecipe(villager);
    }

    private void setTradeRecipe(Villager villager) {
        MerchantRecipe recipe = new MerchantRecipe(
                new ItemStack(Material.GOLDEN_APPLE, 1),
                Integer.MAX_VALUE
        );

        recipe.addIngredient(
                new ItemStack(Material.DIRT, 1)
        );

        recipe.setExperienceReward(false);
        recipe.setVillagerExperience(0);
        recipe.setPriceMultiplier(0.0F);
        recipe.setDemand(0);
        recipe.setSpecialPrice(0);

        villager.setRecipes(List.of(recipe));
    }

    // Обновляет рецепт у уже существующих VillFaux.
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onSpecialTradeOpen(InventoryOpenEvent event) {
        if (!(event.getInventory() instanceof MerchantInventory inventory)) {
            return;
        }

        if (!(inventory.getMerchant() instanceof Villager villager)
                || !plugin.isSpecial(villager)) {
            return;
        }

        setTradeRecipe(villager);
    }

    // ---------- Администраторское яйцо ----------

    private boolean isSpecialEgg(ItemStack item) {
        if (item == null
                || item.getType() != Material.VILLAGER_SPAWN_EGG
                || !item.hasItemMeta()) {
            return false;
        }

        return item.getItemMeta()
                .getPersistentDataContainer()
                .has(
                        plugin.getEggKey(),
                        PersistentDataType.BYTE
                );
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEggUse(PlayerInteractEvent event) {
        if (!isSpecialEgg(event.getItem())) {
            return;
        }

        Action action = event.getAction();

        if (action != Action.RIGHT_CLICK_BLOCK
                && action != Action.RIGHT_CLICK_AIR) {
            return;
        }

        event.setCancelled(true);

        Player player = event.getPlayer();

        if (!player.isOp()) {
            player.sendMessage(
                    "Это яйцо доступно только администраторам."
            );
            return;
        }

        if (action != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null
                || event.getHand() == null) {
            return;
        }

        if (!eggClicks.add(player.getUniqueId())) {
            return;
        }

        Bukkit.getScheduler().runTask(
                plugin,
                () -> eggClicks.remove(player.getUniqueId())
        );

        Location location = event.getClickedBlock()
                .getRelative(event.getBlockFace())
                .getLocation()
                .add(0.5, 0.0, 0.5);

        if (!location.getBlock().isPassable()
                || !location.clone()
                        .add(0.0, 1.0, 0.0)
                        .getBlock()
                        .isPassable()) {
            player.sendMessage(
                    "Здесь недостаточно места для VillFaux."
            );
            return;
        }

        World world = location.getWorld();

        if (world == null) {
            return;
        }

        Villager villager = world.spawn(
                location,
                Villager.class
        );

        if (!villager.isValid() || villager.isDead()) {
            return;
        }

        // Жители из яйца не занимают слот естественного VillFaux.
        plugin.markEggVillager(villager);
        prepareAndAnnounce(villager);

        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemStack item = player.getInventory()
                    .getItem(event.getHand());

            item.setAmount(item.getAmount() - 1);

            player.getInventory().setItem(
                    event.getHand(),
                    item
            );
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEggUseOnEntity(PlayerInteractEntityEvent event) {
        ItemStack item = event.getPlayer()
                .getInventory()
                .getItem(event.getHand());

        if (!isSpecialEgg(item)) {
            return;
        }

        event.setCancelled(true);

        if (!event.getPlayer().isOp()) {
            event.getPlayer().sendMessage(
                    "Это яйцо доступно только администраторам."
            );
            return;
        }

        event.getPlayer().sendMessage(
                "Используй яйцо по блоку, а не по существу."
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        eggClicks.remove(event.getPlayer().getUniqueId());
    }

    // ---------- Последствия каждой успешной сделки ----------

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onTrade(PlayerTradeEvent event) {
        if (!(event.getMerchant() instanceof Villager villager)
                || !plugin.isSpecial(villager)) {
            return;
        }

        Player buyer = event.getPlayer();

        Location arrowOrigin = villager.getEyeLocation().clone();
        Location explosionLocation = villager.getLocation().clone();

        double explosionChance = percentage(
                "trade-explosion-chance",
                5.0
        );

        double pumpkinChance = percentage(
                "pumpkin-chance",
                5.0
        );

        double arrowsChance = percentage(
                "arrows-chance",
                10.0
        );

        double roll = ThreadLocalRandom.current()
                .nextDouble(100.0);

        // Один результат на сделку.
        // Видимость покупателя и закрытие торговли не проверяются.
        if (roll < explosionChance) {
            float power = getExplosionPower(
                    "trade-explosion-power",
                    7.0
            );

            Bukkit.getScheduler().runTask(plugin, () -> {
                createExplosion(explosionLocation, power);
            });

        } else if (roll < explosionChance + pumpkinChance) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (buyer.isOnline() && !buyer.isDead()) {
                    giveCursedPumpkin(buyer);
                }
            });

        } else if (roll < explosionChance
                + pumpkinChance
                + arrowsChance) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (buyer.isOnline() && !buyer.isDead()) {
                    tradePunishments.launchArrows(
                            arrowOrigin,
                            buyer
                    );
                }
            });
        }
    }

    private double percentage(String key, double fallback) {
        double value = plugin.getConfig().getDouble(
                key,
                fallback
        );

        if (!Double.isFinite(value)) {
            return fallback;
        }

        return Math.max(
                0.0,
                Math.min(100.0, value)
        );
    }

    private float getExplosionPower(String key, double fallback) {
        double value = plugin.getConfig().getDouble(
                key,
                fallback
        );

        if (!Double.isFinite(value)) {
            value = fallback;
        }

        return (float) Math.max(0.0, value);
    }

    private void createExplosion(Location location, float power) {
        World world = location.getWorld();

        if (world == null) {
            return;
        }

        world.createExplosion(
                location,
                power,
                false,
                true
        );
    }

    private void giveCursedPumpkin(Player player) {
        ItemStack oldHelmet = player.getInventory().getHelmet();

        ItemStack pumpkin = new ItemStack(
                Material.CARVED_PUMPKIN
        );

        ItemMeta meta = pumpkin.getItemMeta();

        meta.displayName(
                Component.text(
                        "Проклятие VillFaux",
                        NamedTextColor.DARK_RED
                )
        );

        meta.addEnchant(
                Enchantment.BINDING_CURSE,
                1,
                true
        );

        pumpkin.setItemMeta(meta);
        player.getInventory().setHelmet(pumpkin);

        if (oldHelmet != null && !oldHelmet.getType().isAir()) {
            for (ItemStack leftover
                    : player.getInventory()
                            .addItem(oldHelmet)
                            .values()) {
                player.getWorld().dropItemNaturally(
                        player.getLocation(),
                        leftover
                );
            }
        }

        player.sendMessage(
                "VillFaux надел на тебя проклятую тыкву!"
        );
    }

    // ---------- Нападение и превращение ----------

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Villager villager)
                || !plugin.isSpecial(villager)) {
            return;
        }

        if (event.getFinalDamage() <= 0.0) {
            return;
        }

        LivingEntity attacker = findAttacker(
                event.getDamager()
        );

        if (attacker == null) {
            return;
        }

        event.setCancelled(true);
        closeTrading(villager);

        Location location = villager.getLocation();
        World world = location.getWorld();

        if (world == null) {
            return;
        }

        Vindicator vindicator = world.spawn(
                location,
                Vindicator.class
        );

        if (!vindicator.isValid()) {
            return;
        }

        vindicator.customName(
                Component.text(
                        "VillFaux",
                        NamedTextColor.DARK_RED
                )
        );

        vindicator.setCustomNameVisible(true);
        vindicator.setPersistent(true);
        vindicator.setRemoveWhenFarAway(false);

        if (vindicator.getEquipment() != null) {
            vindicator.getEquipment().setItemInMainHand(
                    new ItemStack(Material.IRON_AXE)
            );
        }

        if (!plugin.replaceSpecial(villager, vindicator)) {
            vindicator.remove();
            return;
        }

        // Удаление при превращении не запускает onDeath.
        villager.remove();
        vindicator.setTarget(attacker);

        for (int i = 0; i < 2; i++) {
            Ravager ravager = world.spawn(
                    location,
                    Ravager.class
            );

            if (ravager.isValid()) {
                ravager.setTarget(attacker);
            }
        }
    }

    private LivingEntity findAttacker(Entity damager) {
        if (damager instanceof Projectile projectile) {
            if (projectile.getShooter()
                    instanceof LivingEntity shooter) {
                return shooter;
            }

            return null;
        }

        return damager instanceof LivingEntity living
                ? living
                : null;
    }

    private void closeTrading(Villager villager) {
        for (var viewer
                : List.copyOf(
                        villager.getInventory().getViewers()
                )) {
            viewer.closeInventory();
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onTransform(EntityTransformEvent event) {
        if (plugin.isSpecial(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    // ---------- Обязательный взрыв после смерти ----------

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();

        if (!plugin.isSpecial(entity)) {
            return;
        }

        plugin.recordDeath(entity);

        if (entity instanceof Villager villager) {
            closeTrading(villager);
        }

        Location location = entity.getLocation().clone();

        float power = getExplosionPower(
                "explosion-power",
                7.0
        );

        // Без случайной проверки: взрыв после смерти всегда.
        Bukkit.getScheduler().runTask(plugin, () -> {
            createExplosion(location, power);
        });
    }
}