package dev.forge;

import com.google.common.collect.Multimap;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public class ForgePlugin extends JavaPlugin implements Listener {

    enum Menu { MAIN, LIST, EDITOR, ENCH, ATTR, EFFECT, LOOK }
    enum Cat { WEAPON, ARMOR, SPHERE, TALISMAN }

    static class Holder implements InventoryHolder {
        final Menu menu; final int page; Inventory inv;
        Holder(Menu m, int p) { menu = m; page = p; }
        @Override public Inventory getInventory() { return inv; }
    }

    static class Session {
        Cat cat; Material base; ItemStack item; String pendingKind; String pendingKey;
    }

    // key, назва, іконка, крок
    private static final Object[][] ATTRS = {
            {"attack_damage", "Сила (додатковий урон)", Material.IRON_SWORD, 1.0},
            {"attack_speed", "Швидкість атаки", Material.CLOCK, 0.2},
            {"max_health", "Макс. здоров'я", Material.APPLE, 2.0},
            {"armor", "Броня", Material.IRON_CHESTPLATE, 1.0},
            {"armor_toughness", "Міцність броні", Material.NETHERITE_INGOT, 1.0},
            {"movement_speed", "Швидкість руху", Material.SUGAR, 0.02},
            {"knockback_resistance", "Опір відкиданню", Material.SHIELD, 0.1},
            {"luck", "Удача", Material.RABBIT_FOOT, 1.0},
    };

    private static final Object[][] EFFECTS = {
            {"strength", "Сила", Material.BLAZE_POWDER},
            {"speed", "Швидкість", Material.SUGAR},
            {"resistance", "Стійкість", Material.IRON_CHESTPLATE},
            {"regeneration", "Регенерація", Material.GHAST_TEAR},
            {"fire_resistance", "Вогнестійкість", Material.MAGMA_CREAM},
            {"jump_boost", "Стрибок", Material.RABBIT_FOOT},
            {"haste", "Поспішність", Material.GOLDEN_PICKAXE},
            {"night_vision", "Нічний зір", Material.GOLDEN_CARROT},
            {"water_breathing", "Підводне дихання", Material.PUFFERFISH},
            {"absorption", "Поглинання", Material.GOLDEN_APPLE},
            {"health_boost", "Додаткове здоров'я", Material.APPLE},
            {"invisibility", "Невидимість", Material.FERMENTED_SPIDER_EYE},
            {"slow_falling", "Повільне падіння", Material.FEATHER},
            {"luck", "Удача", Material.EMERALD},
    };

    // Базові предмети для сфер і талісманів — змінюй як хочеш
    private static final Material[] SPHERE_BASES = {Material.PLAYER_HEAD};
    private static final Material[] TALISMAN_BASES = {Material.TOTEM_OF_UNDYING};

    // Голови для сфер/талісманів: {назва, Value з minecraft-heads.com}
    // Щоб додати нову — встав ще один рядок {"Назва", "eyJ0ZXh0dXJlcyI6..."},
    private static final String[][] HEADS = {
            {"Spirit Dye", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWJhY2RiNmRkYTA0MzU4YWZmYzRjZDBlZDI3Yzc1NGY4ZmM0ZTBmNTUwMDc3MGNmNjQxODFlYzYxMzMyMmZjNCJ9fX0="},
    };

    private final Map<UUID, Session> sessions = new HashMap<>();
    private NamespacedKey kType, kEff;

    @Override
    public void onEnable() {
        kType = new NamespacedKey(this, "forge_type");
        kEff = new NamespacedKey(this, "forge_effects");
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 40L, 40L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Тільки для гравців."); return true; }
        if (!p.hasPermission("forge.admin")) { p.sendMessage(plain("&cНемає прав.")); return true; }
        ses(p);
        openMain(p);
        return true;
    }

    // ───────────────────────── утиліти ─────────────────────────

    private Session ses(Player p) { return sessions.computeIfAbsent(p.getUniqueId(), k -> new Session()); }

    private Component plain(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack icon(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(plain(name));
        if (lore.length > 0) meta.lore(Arrays.stream(lore).map(this::plain).toList());
        it.setItemMeta(meta);
        return it;
    }

    private Inventory create(Menu m, int page, int size, String title) {
        Holder h = new Holder(m, page);
        Inventory inv = Bukkit.createInventory(h, size, Component.text(title));
        h.inv = inv;
        return inv;
    }

    private void fill(Inventory inv) {
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, pane);
    }

    static String roman(int n) {
        if (n <= 0 || n > 3999) return String.valueOf(n);
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) while (n >= v[i]) { sb.append(s[i]); n -= v[i]; }
        return sb.toString();
    }

    static Integer fromRoman(String str) {
        String t = str.toUpperCase().trim();
        if (t.isEmpty()) return null;
        Map<Character, Integer> m = Map.of('I', 1, 'V', 5, 'X', 10, 'L', 50, 'C', 100, 'D', 500, 'M', 1000);
        int total = 0;
        for (int i = 0; i < t.length(); i++) {
            Integer cur = m.get(t.charAt(i));
            if (cur == null) return null;
            Integer next = i + 1 < t.length() ? m.get(t.charAt(i + 1)) : null;
            if (next != null && next > cur) total -= cur; else total += cur;
        }
        return total;
    }

    static Integer parseLevel(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fromRoman(s); }
    }

    static Double parseDouble(String s) {
        try { return Double.parseDouble(s.trim().replace(',', '.')); } catch (NumberFormatException e) { return null; }
    }

    private String type(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(kType, PersistentDataType.STRING);
    }

    private ItemStack newItem(Cat cat, Material m) {
        ItemStack it = new ItemStack(m);
        if (cat == Cat.SPHERE || cat == Cat.TALISMAN) {
            ItemMeta meta = it.getItemMeta();
            meta.getPersistentDataContainer().set(kType, PersistentDataType.STRING, cat == Cat.SPHERE ? "sphere" : "talisman");
            meta.displayName(plain(cat == Cat.SPHERE ? "&bСфера" : "&6Талісман"));
            it.setItemMeta(meta);
            refreshLore(it);
            if (cat == Cat.SPHERE && HEADS.length > 0) it = applyHead(it, HEADS[0][1]);
        }
        return it;
    }

    private List<Material> materials(Cat c) {
        List<Material> r = new ArrayList<>();
        if (c == Cat.SPHERE) { r.addAll(Arrays.asList(SPHERE_BASES)); return r; }
        if (c == Cat.TALISMAN) { r.addAll(Arrays.asList(TALISMAN_BASES)); return r; }
        if (c == Cat.WEAPON) {
            for (String t : new String[]{"WOODEN", "STONE", "COPPER", "IRON", "GOLDEN", "DIAMOND", "NETHERITE"})
                addRow(r, t + "_SWORD", t + "_AXE", t + "_SPEAR");
            addRow(r, "BOW", "CROSSBOW", "TRIDENT", "MACE");
        } else {
            for (String t : new String[]{"LEATHER", "CHAINMAIL", "COPPER", "IRON", "GOLDEN", "DIAMOND", "NETHERITE"})
                addRow(r, t + "_HELMET", t + "_CHESTPLATE", t + "_LEGGINGS", t + "_BOOTS");
            addRow(r, "TURTLE_HELMET", "ELYTRA", "SHIELD");
        }
        return r;
    }

    /** Додає рядок рівно з 9 слотів: шолом, нагрудник, штани, чоботи... Пропуски = null. */
    private void addRow(List<Material> out, String... names) {
        List<Material> row = new ArrayList<>();
        boolean any = false;
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            row.add(m);
            if (m != null) any = true;
        }
        if (!any) return;
        while (row.size() < 9) row.add(null);
        out.addAll(row);
    }

    // ───────────────────────── меню ─────────────────────────

    private void openMain(Player p) {
        Inventory inv = create(Menu.MAIN, 0, 27, "Forge — головне меню");
        fill(inv);
        inv.setItem(10, icon(Material.DIAMOND_SWORD, "&cЗброя"));
        inv.setItem(12, icon(Material.DIAMOND_CHESTPLATE, "&9Броня"));
        inv.setItem(14, icon(Material.HEART_OF_THE_SEA, "&bСфери"));
        inv.setItem(16, icon(Material.TOTEM_OF_UNDYING, "&6Талісмани"));
        p.openInventory(inv);
    }

    private void openList(Player p, Cat cat, int page) {
        Session s = ses(p);
        s.cat = cat;
        List<Material> list = materials(cat);
        Inventory inv = create(Menu.LIST, page, 54, "Forge — оберіть предмет");
        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= list.size()) break;
            if (list.get(idx) == null) continue;
            ItemStack it = new ItemStack(list.get(idx));
            ItemMeta meta = it.getItemMeta();
            meta.lore(List.of(plain("&eНатисни, щоб редагувати")));
            it.setItemMeta(meta);
            inv.setItem(i, it);
        }
        if (page > 0) inv.setItem(45, icon(Material.ARROW, "&7← Назад"));
        if ((page + 1) * 45 < list.size()) inv.setItem(53, icon(Material.ARROW, "&7Далі →"));
        inv.setItem(49, icon(Material.BARRIER, "&cДо головного меню"));
        fill(inv);
        p.openInventory(inv);
    }

    private void startHead(Player p, Cat cat) {
        Session s = ses(p);
        s.cat = cat;
        s.base = cat == Cat.SPHERE ? Material.PLAYER_HEAD : Material.TOTEM_OF_UNDYING;
        s.item = newItem(cat, s.base);
        openEditor(p);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (type(e.getItemInHand()) != null) e.setCancelled(true);
    }

    private void openEditor(Player p) {
        Session s = ses(p);
        Inventory inv = create(Menu.EDITOR, 0, 54, "Forge — редактор");
        inv.setItem(13, s.item.clone());
        boolean gear = s.cat == Cat.WEAPON || s.cat == Cat.ARMOR;
        if (gear) inv.setItem(29, icon(Material.ENCHANTING_TABLE, "&dЗачарування", "&7Будь-які рівні, навіть понад ванільні"));
        inv.setItem(31, icon(Material.IRON_SWORD, "&cАтрибути", "&7Сила (урон), броня, здоров'я, швидкість..."));
        if (s.cat == Cat.SPHERE) inv.setItem(29, icon(Material.PLAYER_HEAD, "&6Вигляд", "&7Обрати голову-текстуру для предмета"));
        if (!gear) inv.setItem(33, icon(Material.BREWING_STAND, "&bЕфекти", "&7Ефекти, що діють від предмета"));
        inv.setItem(38, icon(Material.NAME_TAG, "&eНазва", "&7Ввести в чаті (можна &-кольори)"));
        inv.setItem(40, icon(Material.EMERALD, "&aВидати собі"));
        inv.setItem(42, icon(Material.LAVA_BUCKET, "&cСкинути все"));
        inv.setItem(49, icon(Material.ARROW, "&7← До списку"));
        fill(inv);
        p.openInventory(inv);
    }

    private List<Enchantment> enchs(ItemStack item) {
        List<Enchantment> r = new ArrayList<>();
        for (Enchantment e : Registry.ENCHANTMENT) r.add(e);
        r.sort(Comparator.comparing(e -> e.getKey().toString()));
        return r;
    }

    private void openEnch(Player p, int page) {
        Session s = ses(p);
        List<Enchantment> list = enchs(s.item);
        Inventory inv = create(Menu.ENCH, page, 54, "Forge — зачарування");
        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= list.size()) break;
            Enchantment e = list.get(idx);
            int cur = s.item.getEnchantmentLevel(e);
            ItemStack it = new ItemStack(cur > 0 ? Material.ENCHANTED_BOOK : Material.BOOK);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(Component.translatable(e.translationKey()).color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    plain("&7Рівень: &f" + (cur > 0 ? roman(cur) + " (" + cur + ")" : "немає")),
                    plain("&8ЛКМ +1 · ПКМ −1"),
                    plain("&8Shift+ЛКМ +5 · Shift+ПКМ −5"),
                    plain("&8Колесо — ввести число або римську")));
            it.setItemMeta(meta);
            inv.setItem(i, it);
        }
        if (page > 0) inv.setItem(45, icon(Material.ARROW, "&7← Назад"));
        if ((page + 1) * 45 < list.size()) inv.setItem(53, icon(Material.ARROW, "&7Далі →"));
        inv.setItem(49, icon(Material.BARRIER, "&cДо редактора"));
        fill(inv);
        p.openInventory(inv);
    }

    private void openAttr(Player p) {
        Session s = ses(p);
        Inventory inv = create(Menu.ATTR, 0, 27, "Forge — атрибути");
        for (int i = 0; i < ATTRS.length; i++) {
            String key = (String) ATTRS[i][0];
            double cur = getAttr(s.item, key);
            double step = (double) ATTRS[i][3];
            inv.setItem(i, icon((Material) ATTRS[i][2], "&e" + ATTRS[i][1],
                    "&7Додано: &f" + (cur == 0 ? "—" : (cur > 0 ? "+" : "") + cur),
                    "&8ЛКМ +" + step + " · ПКМ −" + step,
                    "&8Shift — половина кроку",
                    "&8Колесо — ввести своє (наприклад 1.5)"));
        }
        inv.setItem(22, icon(Material.BARRIER, "&cДо редактора"));
        fill(inv);
        p.openInventory(inv);
    }

    private ItemStack applyHead(ItemStack item, String value) {
        ItemStack it = item.withType(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) it.getItemMeta();
        PlayerProfile prof = Bukkit.createProfile(UUID.nameUUIDFromBytes(value.getBytes()), "forge");
        prof.setProperty(new ProfileProperty("textures", value));
        sm.setPlayerProfile(prof);
        it.setItemMeta(sm);
        return it;
    }

    private void openLook(Player p, int page) {
        Inventory inv = create(Menu.LOOK, page, 54, "Forge — вигляд");
        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= HEADS.length) break;
            ItemStack it = applyHead(new ItemStack(Material.PLAYER_HEAD), HEADS[idx][1]);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(plain("&e" + HEADS[idx][0]));
            meta.lore(List.of(plain("&7Натисни, щоб застосувати")));
            it.setItemMeta(meta);
            inv.setItem(i, it);
        }
        if (page > 0) inv.setItem(45, icon(Material.ARROW, "&7← Назад"));
        if ((page + 1) * 45 < HEADS.length) inv.setItem(53, icon(Material.ARROW, "&7Далі →"));
        inv.setItem(49, icon(Material.BARRIER, "&cДо редактора"));
        fill(inv);
        p.openInventory(inv);
    }

    private void openEffect(Player p) {
        Session s = ses(p);
        Map<String, Integer> cur = getEffects(s.item);
        Inventory inv = create(Menu.EFFECT, 0, 36, "Forge — ефекти");
        for (int i = 0; i < EFFECTS.length; i++) {
            String key = (String) EFFECTS[i][0];
            int lvl = cur.getOrDefault(key, 0);
            inv.setItem(i, icon((Material) EFFECTS[i][2], "&e" + EFFECTS[i][1],
                    "&7Рівень: &f" + (lvl > 0 ? roman(lvl) + " (" + lvl + ")" : "немає"),
                    "&8ЛКМ +1 · ПКМ −1 · Shift ±5",
                    "&8Колесо — ввести число або римську"));
        }
        inv.setItem(31, icon(Material.BARRIER, "&cДо редактора"));
        fill(inv);
        p.openInventory(inv);
    }

    // ───────────────────────── атрибути ─────────────────────────

    private Attribute attr(String key) { return Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key)); }

    private EquipmentSlot primarySlot(Material m) {
        String n = m.name();
        if (n.endsWith("_HELMET")) return EquipmentSlot.HEAD;
        if (n.endsWith("_CHESTPLATE")) return EquipmentSlot.CHEST;
        if (n.endsWith("_LEGGINGS")) return EquipmentSlot.LEGS;
        if (n.endsWith("_BOOTS")) return EquipmentSlot.FEET;
        return EquipmentSlot.HAND;
    }

    private EquipmentSlotGroup slotGroup(ItemStack it) {
        if (type(it) != null) return EquipmentSlotGroup.OFFHAND;
        return switch (primarySlot(it.getType())) {
            case HEAD -> EquipmentSlotGroup.HEAD;
            case CHEST -> EquipmentSlotGroup.CHEST;
            case LEGS -> EquipmentSlotGroup.LEGS;
            case FEET -> EquipmentSlotGroup.FEET;
            default -> EquipmentSlotGroup.MAINHAND;
        };
    }

    /** Щоб меч не втратив свій базовий урон після додавання свого модифікатора. */
    private void ensureDefaults(ItemStack it, ItemMeta meta) {
        if (meta.hasAttributeModifiers()) return;
        Multimap<Attribute, AttributeModifier> d = it.getType().getDefaultAttributeModifiers(primarySlot(it.getType()));
        for (Map.Entry<Attribute, AttributeModifier> e : d.entries()) meta.addAttributeModifier(e.getKey(), e.getValue());
    }

    private double getAttr(ItemStack it, String key) {
        ItemMeta meta = it.getItemMeta();
        Attribute a = attr(key);
        if (meta == null || a == null) return 0;
        Collection<AttributeModifier> col = meta.getAttributeModifiers(a);
        NamespacedKey nk = new NamespacedKey(this, "forge_" + key);
        if (col != null) for (AttributeModifier m : col) if (m.getKey().equals(nk)) return m.getAmount();
        return 0;
    }

    private void setAttr(ItemStack it, String key, double v) {
        Attribute a = attr(key);
        if (a == null) return;
        ItemMeta meta = it.getItemMeta();
        ensureDefaults(it, meta);
        NamespacedKey nk = new NamespacedKey(this, "forge_" + key);
        Collection<AttributeModifier> col = meta.getAttributeModifiers(a);
        if (col != null) for (AttributeModifier m : new ArrayList<>(col)) if (m.getKey().equals(nk)) meta.removeAttributeModifier(a, m);
        v = Math.round(v * 1000.0) / 1000.0;
        if (v != 0) meta.addAttributeModifier(a, new AttributeModifier(nk, v, AttributeModifier.Operation.ADD_NUMBER, slotGroup(it)));
        it.setItemMeta(meta);
    }

    // ───────────────────────── ефекти сфер/талісманів ─────────────────────────

    private Map<String, Integer> getEffects(ItemStack it) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (it == null || !it.hasItemMeta()) return m;
        String raw = it.getItemMeta().getPersistentDataContainer().get(kEff, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return m;
        for (String part : raw.split(",")) {
            String[] a = part.split(":");
            if (a.length == 2) try { m.put(a[0], Integer.parseInt(a[1])); } catch (NumberFormatException ignored) { }
        }
        return m;
    }

    private void setEffect(ItemStack it, String key, int lvl) {
        Map<String, Integer> m = getEffects(it);
        if (lvl <= 0) m.remove(key); else m.put(key, Math.min(lvl, 255));
        ItemMeta meta = it.getItemMeta();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        meta.getPersistentDataContainer().set(kEff, PersistentDataType.STRING, sb.toString());
        it.setItemMeta(meta);
        refreshLore(it);
    }

    private String effName(String key) {
        for (Object[] e : EFFECTS) if (e[0].equals(key)) return (String) e[1];
        return key;
    }

    private void refreshLore(ItemStack it) {
        String t = type(it);
        if (t == null) return;
        ItemMeta meta = it.getItemMeta();
        List<Component> lore = new ArrayList<>();
        for (Map.Entry<String, Integer> e : getEffects(it).entrySet())
            lore.add(plain("&b" + effName(e.getKey()) + " " + roman(e.getValue())));
        lore.add(plain("&8Діє тільки в лівій руці"));
        meta.lore(lore);
        it.setItemMeta(meta);
    }

    private void applyEffects(Player p, ItemStack it) {
        for (Map.Entry<String, Integer> e : getEffects(it).entrySet()) {
            PotionEffectType t = Registry.MOB_EFFECT.get(NamespacedKey.minecraft(e.getKey()));
            if (t != null) p.addPotionEffect(new PotionEffect(t, 100, e.getValue() - 1, true, false, true));
        }
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack off = p.getInventory().getItemInOffHand();
            if (type(off) != null) applyEffects(p, off);
        }
    }

    // ───────────────────────── кліки ─────────────────────────

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != top) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        ClickType ct = e.getClick();
        Bukkit.getScheduler().runTask(this, () -> handle(p, h, slot, ct));
    }

    private int delta(ClickType ct, int small, int big) {
        return switch (ct) {
            case LEFT -> small;
            case RIGHT -> -small;
            case SHIFT_LEFT -> big;
            case SHIFT_RIGHT -> -big;
            default -> 0;
        };
    }

    private void handle(Player p, Holder h, int slot, ClickType ct) {
        Session s = ses(p);
        switch (h.menu) {
            case MAIN -> {
                switch (slot) {
                    case 10 -> openList(p, Cat.WEAPON, 0);
                    case 12 -> openList(p, Cat.ARMOR, 0);
                    case 14 -> startHead(p, Cat.SPHERE);
                    case 16 -> startHead(p, Cat.TALISMAN);
                }
            }
            case LIST -> {
                if (slot == 49) { openMain(p); return; }
                if (slot == 45 && h.page > 0) { openList(p, s.cat, h.page - 1); return; }
                if (slot == 53) { openList(p, s.cat, h.page + 1); return; }
                if (slot < 45) {
                    List<Material> list = materials(s.cat);
                    int idx = h.page * 45 + slot;
                    if (idx < list.size() && list.get(idx) != null) { s.base = list.get(idx); s.item = newItem(s.cat, s.base); openEditor(p); }
                }
            }
            case EDITOR -> {
                switch (slot) {
                    case 29 -> { if (s.cat == Cat.WEAPON || s.cat == Cat.ARMOR) openEnch(p, 0); else if (s.cat == Cat.SPHERE) openLook(p, 0); }
                    case 31 -> openAttr(p);
                    case 33 -> { if (s.cat == Cat.SPHERE || s.cat == Cat.TALISMAN) openEffect(p); }
                    case 38 -> askInput(p, s, "name", null, "Введи нову назву (можна з &-кольорами):");
                    case 40 -> { p.getInventory().addItem(s.item.clone()); p.sendMessage(plain("&aПредмет видано.")); }
                    case 42 -> { s.item = newItem(s.cat, s.base != null ? s.base : s.item.getType()); openEditor(p); }
                    case 49 -> { if (s.cat == Cat.SPHERE || s.cat == Cat.TALISMAN) openMain(p); else openList(p, s.cat, 0); }
                }
            }
            case ENCH -> {
                if (slot == 49) { openEditor(p); return; }
                if (slot == 45 && h.page > 0) { openEnch(p, h.page - 1); return; }
                if (slot == 53) { openEnch(p, h.page + 1); return; }
                if (slot >= 45) return;
                List<Enchantment> list = enchs(s.item);
                int idx = h.page * 45 + slot;
                if (idx >= list.size()) return;
                Enchantment en = list.get(idx);
                if (ct == ClickType.MIDDLE) {
                    askInput(p, s, "ench", en.getKey().toString(), "Введи рівень (число або римською, 0 = прибрати):");
                    return;
                }
                int d = delta(ct, 1, 5);
                if (d == 0) return;
                setEnch(s.item, en, s.item.getEnchantmentLevel(en) + d);
                openEnch(p, h.page);
            }
            case ATTR -> {
                if (slot == 22) { openEditor(p); return; }
                if (slot >= ATTRS.length) return;
                String key = (String) ATTRS[slot][0];
                double step = (double) ATTRS[slot][3];
                if (ct == ClickType.MIDDLE) {
                    askInput(p, s, "attr", key, "Введи значення (наприклад 1.5, 0 = прибрати):");
                    return;
                }
                double d = switch (ct) {
                    case LEFT -> step;
                    case RIGHT -> -step;
                    case SHIFT_LEFT -> step / 2;
                    case SHIFT_RIGHT -> -step / 2;
                    default -> 0;
                };
                if (d == 0) return;
                setAttr(s.item, key, getAttr(s.item, key) + d);
                openAttr(p);
            }
            case LOOK -> {
                if (slot == 49) { openEditor(p); return; }
                if (slot == 45 && h.page > 0) { openLook(p, h.page - 1); return; }
                if (slot == 53) { openLook(p, h.page + 1); return; }
                if (slot >= 45) return;
                int idx = h.page * 45 + slot;
                if (idx < HEADS.length) { s.item = applyHead(s.item, HEADS[idx][1]); openEditor(p); }
            }
            case EFFECT -> {
                if (slot == 31) { openEditor(p); return; }
                if (slot >= EFFECTS.length) return;
                String key = (String) EFFECTS[slot][0];
                if (ct == ClickType.MIDDLE) {
                    askInput(p, s, "effect", key, "Введи рівень (число або римською, 0 = прибрати):");
                    return;
                }
                int d = delta(ct, 1, 5);
                if (d == 0) return;
                setEffect(s.item, key, getEffects(s.item).getOrDefault(key, 0) + d);
                openEffect(p);
            }
        }
    }

    private void setEnch(ItemStack it, Enchantment en, int lvl) {
        if (lvl <= 0) it.removeEnchantment(en);
        else it.addUnsafeEnchantment(en, Math.min(lvl, 255));
    }

    // ───────────────────────── ввід через чат ─────────────────────────

    private void askInput(Player p, Session s, String kind, String key, String msg) {
        s.pendingKind = kind;
        s.pendingKey = key;
        p.closeInventory();
        p.sendMessage(plain("&e" + msg + " &7(напиши 'скасувати' щоб відмінити)"));
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || s.pendingKind == null) return;
        e.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> handleInput(p, s, msg));
    }

    private void handleInput(Player p, Session s, String msg) {
        String kind = s.pendingKind, key = s.pendingKey;
        s.pendingKind = null;
        s.pendingKey = null;
        boolean cancel = msg.equalsIgnoreCase("скасувати") || msg.equalsIgnoreCase("cancel");
        switch (kind) {
            case "name" -> {
                if (!cancel) {
                    ItemMeta meta = s.item.getItemMeta();
                    meta.displayName(plain(msg));
                    s.item.setItemMeta(meta);
                }
                openEditor(p);
            }
            case "ench" -> {
                Integer lvl = cancel ? null : parseLevel(msg);
                if (!cancel && lvl == null) p.sendMessage(plain("&cНе число."));
                if (lvl != null) {
                    Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.fromString(key));
                    if (en != null) setEnch(s.item, en, lvl);
                }
                openEnch(p, 0);
            }
            case "attr" -> {
                Double v = cancel ? null : parseDouble(msg);
                if (!cancel && v == null) p.sendMessage(plain("&cНе число."));
                if (v != null) setAttr(s.item, key, v);
                openAttr(p);
            }
            case "effect" -> {
                Integer lvl = cancel ? null : parseLevel(msg);
                if (!cancel && lvl == null) p.sendMessage(plain("&cНе число."));
                if (lvl != null) setEffect(s.item, key, lvl);
                openEffect(p);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { sessions.remove(e.getPlayer().getUniqueId()); }
}
