package com.authmebia.dialog.shared;

import com.authmebia.cfg.Cfg;
import com.authmebia.dialog.Dialoglib;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class InventoryAuthInput implements Listener {

    private static final int[] DIGIT_SLOTS = {
            11, 12, 13,
            20, 21, 22,
            29, 30, 31
    };
    private static final int ZERO_SLOT = 39;
    private static final int DELETE_SLOT = 41;
    private static final int CONFIRM_SLOT = 42;
    private static final int PROGRESS_SLOT = 4;
    private static final int LOGOUT_SLOT = 49;

    private static final java.util.Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    public InventoryAuthInput() {}

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        handleClick(event);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        handleClose(event);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        SESSIONS.remove(event.getPlayer().getUniqueId());
    }
    public static void clearSession(UUID uuid) {
        SESSIONS.remove(uuid);
    }
    public static void open(Player player, Cfg cfg, String statusLine, Consumer<String> onConfirm, Runnable onLogout) {
        int length = cfg.authMode() == com.authmebia.dialog.Mode.SLIDER ? cfg.sliderLength() : cfg.pinLength();
        Session session = new Session(cfg, length, statusLine, onConfirm, onLogout);
        SESSIONS.put(player.getUniqueId(), session);
        render(player, session);
    }

    private static void render(Player player, Session session) {
        Inventory inv = Bukkit.createInventory(null, 54, session.cfg.pinTitle());

        for (int i = 0; i < 54; i++) {
            inv.setItem(i, filler());
        }

        inv.setItem(PROGRESS_SLOT, progressItem(session));

        for (int n = 1; n <= 9; n++) {
            inv.setItem(DIGIT_SLOTS[n - 1], digitItem(n));
        }
        inv.setItem(ZERO_SLOT, digitItem(0));
        inv.setItem(DELETE_SLOT, namedItem(Material.RED_STAINED_GLASS_PANE, session.cfg.pinDeleteButton()));
        inv.setItem(CONFIRM_SLOT, namedItem(Material.LIME_STAINED_GLASS_PANE, session.cfg.pinConfirmButton()));
        inv.setItem(LOGOUT_SLOT, namedItem(Material.BARRIER, session.cfg.logoutButton()));

        session.currentInventory = inv;
        session.suppressClose = true;
        player.openInventory(inv);
        session.suppressClose = false;
    }

    private static void handleClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = SESSIONS.get(player.getUniqueId());
        if (session == null) return;
        if (event.getView().getTopInventory() != session.currentInventory) return;

        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        int digit = digitForSlot(slot);
        if (digit >= 0) {
            if (session.entered.length() < session.length) {
                session.entered.append(digit);
                playSound(player, session.cfg.pinButtonSound());
                render(player, session);
            }
            return;
        }

        if (slot == DELETE_SLOT) {
            if (session.entered.length() > 0) {
                session.entered.deleteCharAt(session.entered.length() - 1);
            }
            playSound(player, session.cfg.pinButtonSound());
            render(player, session);
            return;
        }

        if (slot == CONFIRM_SLOT) {
            if (session.entered.length() != session.length) {
                render(player, session);
                return;
            }
            playSound(player, session.cfg.pinButtonSound());
            String code = session.entered.toString();
            SESSIONS.remove(player.getUniqueId());
            session.suppressClose = true;
            player.closeInventory();
            session.onConfirm.accept(code);
            return;
        }

        if (slot == LOGOUT_SLOT) {
            playSound(player, session.cfg.logoutSound());
            SESSIONS.remove(player.getUniqueId());
            session.suppressClose = true;
            player.closeInventory();
            session.onLogout.run();
        }
    }

    private static void handleClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Session session = SESSIONS.get(player.getUniqueId());
        if (session == null) return;
        if (session.suppressClose) return;
        if (event.getInventory() != session.currentInventory) return;
        SESSIONS.remove(player.getUniqueId());
        session.onLogout.run();
    }

    private static int digitForSlot(int slot) {
        if (slot == ZERO_SLOT) return 0;
        for (int i = 0; i < DIGIT_SLOTS.length; i++) {
            if (DIGIT_SLOTS[i] == slot) return i + 1;
        }
        return -1;
    }

    private static ItemStack digitItem(int n) {
        return namedItem(Material.LIGHT_BLUE_STAINED_GLASS_PANE, Component.text(Integer.toString(n)));
    }

    private static ItemStack progressItem(Session session) {
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < session.length; i++) {
            dots.append(i < session.entered.length() ? '\u25CF' : '\u25CB');
            if (i < session.length - 1) dots.append(' ');
        }
        Component name = Component.text(dots.toString(), NamedTextColor.YELLOW);
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        if (session.statusLine != null && !session.statusLine.isBlank()) {
            meta.lore(java.util.List.of(Component.text(session.statusLine, NamedTextColor.GRAY)));
        }
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack namedItem(Material material, Component name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }

    private static void playSound(Player player, String sound) {
        if (sound == null || sound.isBlank()) return;
        Dialoglib.playSound(player, sound);
    }

    private static final class Session {
        final Cfg cfg;
        final int length;
        final String statusLine;
        final Consumer<String> onConfirm;
        final Runnable onLogout;
        final StringBuilder entered = new StringBuilder();
        volatile Inventory currentInventory;
        volatile boolean suppressClose = false;

        Session(Cfg cfg, int length, String statusLine, Consumer<String> onConfirm, Runnable onLogout) {
            this.cfg = cfg;
            this.length = length;
            this.statusLine = statusLine;
            this.onConfirm = onConfirm;
            this.onLogout = onLogout;
        }
    }
}
