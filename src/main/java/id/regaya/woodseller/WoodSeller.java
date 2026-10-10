package id.regaya.woodseller;

import io.th0rgal.oraxen.api.OraxenItems;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WoodSeller - NPC Citizens penjual Papan Kayu (Oraxen) dengan GUI.
 *
 * Alur: klik kanan NPC -> dialog bertahap di chat -> GUI jual terbuka.
 * Di GUI pemain mengatur jumlah dengan tombol -/+ lalu menjual jumlah itu,
 * atau menjual semua sekaligus. Tidak ada pilihan lewat chat.
 */
public final class WoodSeller extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // Slot GUI (chest 4 baris = 36 slot)
    private static final int GUI_SIZE = 36;
    private static final int SLOT_INFO = 4;
    private static final int SLOT_SELECTED = 13;
    private static final int[] SLOTS_DECREASE = {12, 11, 10}; // langkah kecil -> besar, ke arah kiri
    private static final int[] SLOTS_INCREASE = {14, 15, 16}; // langkah kecil -> besar, ke arah kanan
    private static final int SLOT_SELL_SELECTED = 21;
    private static final int SLOT_SELL_ALL = 23;
    private static final int SLOT_CLOSE = 31;

    /** Sesi dialog satu pemain dengan satu NPC. */
    private static final class Session {
        final int npcId;
        volatile long expiresAt;

        Session(int npcId, long expiresAt) {
            this.npcId = npcId;
            this.expiresAt = expiresAt;
        }
    }

    /** Holder inventory untuk mengenali GUI milik plugin ini. */
    private static final class SellerGui implements InventoryHolder {
        final Player player;
        final Session session;
        Inventory inventory;
        int amount = 1;

        SellerGui(Player player, Session session) {
            this.player = player;
            this.session = session;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();

    private Economy economy;

    private String oraxenId;
    private double price;
    private String npcName;
    private Set<Integer> npcIds = new HashSet<>();
    private List<Integer> steps = new ArrayList<>();
    private long timeoutMillis;
    private double maxDistance;
    private long lineDelayTicks;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        getServer().getPluginManager().registerEvents(this, this);
        var command = getCommand("woodseller");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        // Plugin ekonomi kadang baru terdaftar setelah plugin ini aktif.
        Bukkit.getScheduler().runTask(this, () -> {
            if (economy() == null) {
                getLogger().warning("Vault belum menemukan plugin ekonomi. Penjualan tidak akan berjalan sampai ada.");
            }
        });
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof SellerGui) {
                p.closeInventory();
            }
        }
    }

    private void loadSettings() {
        reloadConfig();
        var cfg = getConfig();
        oraxenId = cfg.getString("oraxen-item-id", "papan_kayu");
        price = cfg.getDouble("price-per-item", 50.0);
        npcName = cfg.getString("npc-name", "Penjual");
        npcIds = new HashSet<>(cfg.getIntegerList("npc-ids"));

        List<Integer> raw = new ArrayList<>();
        for (int s : cfg.getIntegerList("gui.adjust-steps")) {
            if (s > 0) {
                raw.add(s);
            }
        }
        Collections.sort(raw);
        steps = raw.size() > 3 ? new ArrayList<>(raw.subList(0, 3)) : raw;
        if (steps.isEmpty()) {
            steps = new ArrayList<>(List.of(1, 8, 64));
        }

        timeoutMillis = Math.max(5, cfg.getLong("session.timeout-seconds", 120)) * 1000L;
        maxDistance = Math.max(1.0, cfg.getDouble("session.max-distance", 6.0));
        lineDelayTicks = Math.max(1, cfg.getLong("dialogue.line-delay-ticks", 30));
    }

    private Economy economy() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                economy = rsp.getProvider();
            }
        }
        return economy;
    }

    private String money(double value) {
        Economy eco = economy();
        return eco != null ? eco.format(value) : String.format(Locale.US, "%.2f", value);
    }

    // ------------------------------------------------------------------ messages & items

    private TagResolver res(Player p, int stock, int amount) {
        return TagResolver.resolver(
                Placeholder.unparsed("player", p.getName()),
                Placeholder.unparsed("npc", npcName),
                Placeholder.unparsed("stock", String.valueOf(stock)),
                Placeholder.unparsed("price", money(price)),
                Placeholder.unparsed("amount", String.valueOf(amount)),
                Placeholder.unparsed("total", money(price * amount))
        );
    }

    private TagResolver res(Player p, int stock, int amount, int step) {
        return TagResolver.resolver(res(p, stock, amount), Placeholder.unparsed("step", String.valueOf(step)));
    }

    private Component msg(String path, TagResolver resolver) {
        return mm.deserialize(getConfig().getString(path, "<red>Teks hilang di config: " + path), resolver);
    }

    private void send(Player p, String path, TagResolver resolver) {
        p.sendMessage(msg(path, resolver));
    }

    private void sound(Player p, String path) {
        String key = getConfig().getString(path, "");
        if (key != null && !key.isBlank()) {
            p.playSound(p.getLocation(), key, 1f, 1f);
        }
    }

    private Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    private Material material(String key, Material fallback) {
        String name = getConfig().getString("gui.materials." + key, fallback.name());
        Material m = Material.matchMaterial(name == null ? "" : name);
        return (m == null || m.isAir()) ? fallback : m;
    }

    /** Membuat item GUI dari section config gui.<section> (name + lore). */
    private ItemStack button(String matKey, Material fallback, String section, TagResolver r) {
        ItemStack item = new ItemStack(material(matKey, fallback));
        decorate(item, section, r);
        return item;
    }

    private void decorate(ItemStack item, String section, TagResolver r) {
        List<Component> lore = new ArrayList<>();
        for (String line : getConfig().getStringList("gui." + section + ".lore")) {
            lore.add(noItalic(mm.deserialize(line, r)));
        }
        Component name = noItalic(msg("gui." + section + ".name", r));
        item.editMeta(meta -> {
            meta.displayName(name);
            meta.lore(lore);
        });
    }

    /** Ikon papan di GUI: salinan item asli dari inventory pemain (tampilan Oraxen ikut), fallback OAK_PLANKS. */
    private ItemStack plankIcon(Player p, int amount) {
        ItemStack icon = null;
        for (ItemStack item : p.getInventory().getStorageContents()) {
            if (isTarget(item)) {
                icon = item.clone();
                break;
            }
        }
        if (icon == null) {
            icon = new ItemStack(Material.OAK_PLANKS);
        }
        icon.setAmount(Math.max(1, Math.min(64, amount)));
        return icon;
    }

    // ------------------------------------------------------------------ NPC click + dialogue

    @EventHandler
    public void onNpcClick(NPCRightClickEvent event) {
        NPC npc = event.getNPC();
        if (!npcIds.contains(npc.getId())) {
            return;
        }
        Player p = event.getClicker();
        if (!p.hasPermission("woodseller.use")) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUniqueId());
        if (last != null && now - last < 500) {
            return; // cegah event ganda
        }
        lastClick.put(p.getUniqueId(), now);

        startDialogue(p, npc);
    }

    private void startDialogue(Player p, NPC npc) {
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof SellerGui) {
            p.closeInventory();
        }

        Session session = new Session(npc.getId(), System.currentTimeMillis() + timeoutMillis);
        sessions.put(p.getUniqueId(), session);

        List<String> lines = getConfig().getStringList("dialogue.lines");
        TagResolver resolver = res(p, count(p), 0);

        long delay = 0;
        for (String line : lines) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!p.isOnline() || sessions.get(p.getUniqueId()) != session) {
                    return;
                }
                p.sendMessage(mm.deserialize(line, resolver));
            }, delay);
            delay += lineDelayTicks;
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline() || sessions.get(p.getUniqueId()) != session) {
                return;
            }
            session.expiresAt = System.currentTimeMillis() + timeoutMillis;
            openGui(p, session);
        }, delay);
    }

    // ------------------------------------------------------------------ GUI

    private void openGui(Player p, Session session) {
        SellerGui gui = new SellerGui(p, session);
        gui.inventory = Bukkit.createInventory(gui, GUI_SIZE, msg("gui.title", res(p, 0, 0)));
        render(gui);
        p.openInventory(gui.inventory);
    }

    private void render(SellerGui gui) {
        Player p = gui.player;
        Inventory inv = gui.inventory;
        int stock = count(p);
        gui.amount = stock <= 0 ? 0 : Math.max(1, Math.min(gui.amount, stock));
        int amount = gui.amount;

        ItemStack filler = new ItemStack(material("filler", Material.BLACK_STAINED_GLASS_PANE));
        filler.editMeta(meta -> meta.displayName(Component.text(" ")));
        for (int i = 0; i < GUI_SIZE; i++) {
            inv.setItem(i, filler);
        }

        inv.setItem(SLOT_INFO, button("info", Material.OAK_SIGN, "info", res(p, stock, amount)));
        inv.setItem(SLOT_CLOSE, button("close", Material.BARRIER, "close", res(p, stock, amount)));

        if (stock <= 0) {
            inv.setItem(SLOT_SELECTED, button("empty", Material.BARRIER, "empty", res(p, 0, 0)));
            return;
        }

        ItemStack selected = plankIcon(p, amount);
        decorate(selected, "selected", res(p, stock, amount));
        inv.setItem(SLOT_SELECTED, selected);

        for (int i = 0; i < steps.size(); i++) {
            int step = steps.get(i);
            TagResolver r = res(p, stock, amount, step);
            inv.setItem(SLOTS_DECREASE[i], button("decrease", Material.RED_CONCRETE, "decrease", r));
            inv.setItem(SLOTS_INCREASE[i], button("increase", Material.LIME_CONCRETE, "increase", r));
        }

        inv.setItem(SLOT_SELL_SELECTED, button("sell-selected", Material.EMERALD, "sell-selected", res(p, stock, amount)));
        inv.setItem(SLOT_SELL_ALL, button("sell-all", Material.GOLD_INGOT, "sell-all", res(p, stock, stock)));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SellerGui gui)) {
            return;
        }
        event.setCancelled(true); // GUI tidak boleh dipakai memindahkan item sama sekali

        if (!(event.getWhoClicked() instanceof Player p) || !p.equals(gui.player)) {
            return;
        }
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }

        if (sessions.get(p.getUniqueId()) != gui.session || validSession(p) == null) {
            p.closeInventory();
            send(p, "messages.session-expired", res(p, 0, 0));
            sound(p, "sounds.error");
            return;
        }
        gui.session.expiresAt = System.currentTimeMillis() + timeoutMillis;

        int slot = event.getSlot();

        if (slot == SLOT_CLOSE) {
            sessions.remove(p.getUniqueId());
            p.closeInventory();
            send(p, "messages.closed", res(p, 0, 0));
            return;
        }

        int stock = count(p);
        if (stock <= 0) {
            render(gui);
            return;
        }

        for (int i = 0; i < steps.size(); i++) {
            if (slot == SLOTS_DECREASE[i]) {
                gui.amount = Math.max(1, gui.amount - steps.get(i));
                sound(p, "sounds.click");
                render(gui);
                return;
            }
            if (slot == SLOTS_INCREASE[i]) {
                gui.amount = Math.min(stock, gui.amount + steps.get(i));
                sound(p, "sounds.click");
                render(gui);
                return;
            }
        }

        if (slot == SLOT_SELL_SELECTED) {
            sell(gui, gui.amount);
        } else if (slot == SLOT_SELL_ALL) {
            sell(gui, stock);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SellerGui) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof SellerGui gui) {
            UUID id = gui.player.getUniqueId();
            if (sessions.get(id) == gui.session) {
                sessions.remove(id);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
        lastClick.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ session helpers

    /** Mengembalikan sesi aktif jika masih valid (belum kedaluwarsa, NPC ada, pemain dekat). */
    private Session validSession(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) {
            return null;
        }
        if (System.currentTimeMillis() > s.expiresAt) {
            sessions.remove(p.getUniqueId());
            return null;
        }
        NPC npc = CitizensAPI.getNPCRegistry().getById(s.npcId);
        if (npc == null || !npc.isSpawned() || !npcIds.contains(s.npcId)) {
            return null;
        }
        Location npcLoc = npc.getEntity().getLocation();
        Location pLoc = p.getLocation();
        if (npcLoc.getWorld() == null || !npcLoc.getWorld().equals(pLoc.getWorld())
                || npcLoc.distanceSquared(pLoc) > maxDistance * maxDistance) {
            return null;
        }
        return s;
    }

    // ------------------------------------------------------------------ inventory

    private boolean isTarget(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        String id = OraxenItems.getIdByItem(item);
        return oraxenId.equals(id);
    }

    private int count(Player p) {
        int total = 0;
        for (ItemStack item : p.getInventory().getStorageContents()) {
            if (isTarget(item)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    /** Mengambil item dari inventory. Mengembalikan item yang diambil, atau null jika gagal (tidak ada perubahan). */
    private List<ItemStack> take(Player p, int amount) {
        ItemStack[] contents = p.getInventory().getStorageContents();
        List<ItemStack> removed = new ArrayList<>();
        int left = amount;

        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack item = contents[i];
            if (!isTarget(item)) {
                continue;
            }
            int take = Math.min(left, item.getAmount());
            ItemStack part = item.clone();
            part.setAmount(take);
            removed.add(part);

            if (take == item.getAmount()) {
                contents[i] = null;
            } else {
                ItemStack rest = item.clone();
                rest.setAmount(item.getAmount() - take);
                contents[i] = rest;
            }
            left -= take;
        }

        if (left > 0) {
            return null;
        }
        p.getInventory().setStorageContents(contents);
        return removed;
    }

    private void giveBack(Player p, List<ItemStack> items) {
        for (ItemStack item : items) {
            Map<Integer, ItemStack> leftover = p.getInventory().addItem(item);
            leftover.values().forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
        }
    }

    // ------------------------------------------------------------------ selling

    private void sell(SellerGui gui, int requested) {
        Player p = gui.player;

        Economy eco = economy();
        if (eco == null) {
            send(p, "messages.economy-error", res(p, 0, 0));
            sound(p, "sounds.error");
            return;
        }

        int stock = count(p);
        if (stock <= 0) {
            render(gui);
            return;
        }
        if (requested <= 0 || requested > stock) {
            send(p, "messages.not-enough", res(p, stock, requested));
            sound(p, "sounds.error");
            render(gui);
            return;
        }

        List<ItemStack> removed = take(p, requested);
        if (removed == null) {
            send(p, "messages.inventory-error", res(p, stock, requested));
            sound(p, "sounds.error");
            render(gui);
            return;
        }

        EconomyResponse response = eco.depositPlayer(p, price * requested);
        if (!response.transactionSuccess()) {
            giveBack(p, removed);
            getLogger().warning("Deposit gagal untuk " + p.getName() + ": " + response.errorMessage);
            send(p, "messages.inventory-error", res(p, stock, requested));
            sound(p, "sounds.error");
            render(gui);
            return;
        }

        send(p, "messages.sold", res(p, stock - requested, requested));
        sound(p, "sounds.sell");
        render(gui);
    }

    // ------------------------------------------------------------------ admin commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("woodseller.admin")) {
            sender.sendMessage(adminMsg("no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(adminMsg("usage"));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                loadSettings();
                sender.sendMessage(adminMsg("reloaded"));
            }
            case "npc" -> handleNpcCommand(sender, args);
            default -> sender.sendMessage(adminMsg("usage"));
        }
        return true;
    }

    private void handleNpcCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(adminMsg("usage"));
            return;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            sender.sendMessage(mm.deserialize(
                    getConfig().getString("admin-messages.npc-list", "<gray>NPC: <list>"),
                    Placeholder.unparsed("list", npcIds.isEmpty() ? "-" : npcIds.toString())));
            return;
        }
        if (args.length < 3 || !(sub.equals("add") || sub.equals("remove"))) {
            sender.sendMessage(adminMsg("usage"));
            return;
        }

        int id;
        try {
            id = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(adminMsg("usage"));
            return;
        }
        TagResolver idRes = Placeholder.unparsed("id", String.valueOf(id));

        if (sub.equals("add")) {
            if (CitizensAPI.getNPCRegistry().getById(id) == null) {
                sender.sendMessage(adminMsg("npc-unknown", idRes));
                return;
            }
            if (!npcIds.add(id)) {
                sender.sendMessage(adminMsg("npc-exists", idRes));
                return;
            }
            saveNpcIds();
            sender.sendMessage(adminMsg("npc-added", idRes));
        } else {
            if (!npcIds.remove(id)) {
                sender.sendMessage(adminMsg("npc-missing", idRes));
                return;
            }
            saveNpcIds();
            sender.sendMessage(adminMsg("npc-removed", idRes));
        }
    }

    private void saveNpcIds() {
        getConfig().set("npc-ids", new ArrayList<>(npcIds));
        saveConfig();
    }

    private Component adminMsg(String key) {
        return adminMsg(key, TagResolver.empty());
    }

    private Component adminMsg(String key, TagResolver resolver) {
        return mm.deserialize(getConfig().getString("admin-messages." + key, "<red>Teks hilang: " + key), resolver);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("woodseller.admin")) {
            return out;
        }
        if (args.length == 1) {
            out.addAll(List.of("npc", "reload"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("npc")) {
            out.addAll(List.of("add", "remove", "list"));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.startsWith(prefix));
        return out;
    }
}
