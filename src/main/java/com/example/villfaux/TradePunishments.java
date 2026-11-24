package com.example.villfaux;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public final class TradePunishments implements Listener {

    private static final int SLOWNESS_TICKS = 20 * 60 * 10;
    private static final long SLOWNESS_MILLIS = 10 * 60 * 1000L;

    private final VillFauxPlugin plugin;
    private final NamespacedKey protectedSlownessKey;

    public TradePunishments(VillFauxPlugin plugin) {
        this.plugin = plugin;
        this.protectedSlownessKey = new NamespacedKey(
                plugin,
                "protected_slowness_until"
        );
    }

    public void launchArrows(Location origin, Player target) {
        if (origin.getWorld() == null
                || !target.isOnline()
                || target.isDead()
                || !origin.getWorld().equals(target.getWorld())) {
            return;
        }

        launchOne(
                origin.clone().add(0.0, 0.12, 0.0),
                target,
                false
        );

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (target.isOnline()
                    && !target.isDead()
                    && origin.getWorld().equals(target.getWorld())) {
                launchOne(
                        origin.clone().add(0.0, -0.12, 0.0),
                        target,
                        true
                );
            }
        }, 6L);
    }

    private void launchOne(
            Location origin,
            Player target,
            boolean slowness
    ) {
        ItemDisplay visual = origin.getWorld().spawn(
                origin,
                ItemDisplay.class,
                display -> {
                    display.setItemStack(
                            new ItemStack(Material.TIPPED_ARROW)
                    );
                    display.setItemDisplayTransform(
                            ItemDisplay.ItemDisplayTransform.FIXED
                    );
                    display.setBillboard(Display.Billboard.FIXED);
                    display.setGravity(false);
                    display.setPersistent(false);
                    display.setTeleportDuration(1);
                }
        );

        new BukkitRunnable() {

            private final Location position = origin.clone();
            private int age;

            @Override
            public void run() {
                if (!visual.isValid()
                        || !target.isOnline()
                        || target.isDead()
                        || !position.getWorld().equals(target.getWorld())
                        || ++age > 100) {
                    finish();
                    return;
                }

                Location destination = target.getLocation()
                        .add(0.0, 1.0, 0.0);

                Vector offset = destination.toVector()
                        .subtract(position.toVector());

                double distance = offset.length();

                if (distance <= 0.6) {
                    hit(target, slowness);
                    finish();
                    return;
                }

                double speed = 0.8;

                Vector step = offset.normalize()
                        .multiply(Math.min(speed, distance));

                position.add(step);
                position.setDirection(step);

                // Блоки не останавливают этот снаряд.
                visual.teleport(position);
            }

            private void finish() {
                visual.remove();
                cancel();
            }

        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void hit(Player target, boolean slowness) {
        if (!target.isOnline() || target.isDead()) {
            return;
        }

        if (slowness) {
            long until = System.currentTimeMillis()
                    + SLOWNESS_MILLIS;

            target.getPersistentDataContainer().set(
                    protectedSlownessKey,
                    PersistentDataType.LONG,
                    until
            );

            target.addPotionEffect(
                    new PotionEffect(
                            PotionEffectType.SLOWNESS,
                            SLOWNESS_TICKS,
                            0,
                            false,
                            true,
                            true
                    ),
                    true
            );
        } else {
            target.addPotionEffect(
                    new PotionEffect(
                            PotionEffectType.INSTANT_DAMAGE,
                            1,
                            0
                    )
            );
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onEffectChange(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (event.getCause() != EntityPotionEffectEvent.Cause.MILK) {
            return;
        }

        PotionEffect oldEffect = event.getOldEffect();

        if (oldEffect == null
                || !oldEffect.getType().equals(
                        PotionEffectType.SLOWNESS
                )) {
            return;
        }

        Long until = player.getPersistentDataContainer().get(
                protectedSlownessKey,
                PersistentDataType.LONG
        );

        if (until == null) {
            return;
        }

        if (System.currentTimeMillis() >= until) {
            player.getPersistentDataContainer().remove(
                    protectedSlownessKey
            );
            return;
        }

        event.setCancelled(true);
    }
}