package de.codex.herobotai;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;

enum PvPKit {
    COPY("copy", null, null),
    IRON("iron", "iron", "iron_sword"),
    DIAMOND("diamond", "diamond", "diamond_sword"),
    AXE("axe", "diamond", "diamond_axe"),
    NETHERITE("netherite", "netherite", "netherite_sword"),
    MACE("mace", "diamond", "mace"),
    CRYSTAL("crystal", "netherite", "netherite_sword"),
    // FireClient-Vorlagen des Users (fireclient/kits/*.json), 1:1 nachgebaut.
    FC_AXE("fc_axe", "diamond", "diamond_axe", null),
    FC_UHC("fc_uhc", "diamond", "diamond_sword", null),
    FC_MISCH("fc_misch", "netherite", "netherite_sword", "minecraft:netherite_axe");

    private final String commandName;
    private final String armorMaterial;
    private final String weapon;
    /** Zweite Waffe im Hotbar (isReady-Check), z. B. die Axt im fc_misch-Kit. */
    private final String secondaryWeapon;

    /** Alte Kits: Waffe im Slot 0, keine Zweitwaffe. */
    PvPKit(String commandName, String armorMaterial, String weapon) {
        this(commandName, armorMaterial, weapon, null);
    }

    PvPKit(String commandName, String armorMaterial, String weapon, String secondaryWeapon) {
        this.commandName = commandName;
        this.armorMaterial = armorMaterial;
        this.weapon = weapon;
        this.secondaryWeapon = secondaryWeapon;
    }

    String commandName() {
        return commandName;
    }

    /** FireClient-Vorlagen: reine Inventar-Kits, kein Elytra-Flugverhalten. */
    boolean isFireClientKit() {
        return this == FC_AXE || this == FC_UHC || this == FC_MISCH;
    }

    boolean isReady(ServerPlayerEntity bot) {
        if (this == COPY) {
            return true;
        }
        if (this == CRYSTAL) {
            // Crystal-Kit: Waffe (Schwert) liegt in Slot 3, nicht in Slot 0.
            for (int slot = 0; slot < 9; slot++) {
                if (weapon.equals(Registries.ITEM.getId(bot.getInventory().getStack(slot).getItem()).getPath())) {
                    return true;
                }
            }
            return false;
        }
        if (isFireClientKit()) {
            // Feuer-Client-Kits: Waffe muss im Hotbar liegen (Slot 0 oder Zweitwaffe).
            for (int slot = 0; slot < 9; slot++) {
                String held = Registries.ITEM.getId(bot.getInventory().getStack(slot).getItem()).getPath();
                if (weapon.equals(held) || (secondaryWeapon != null && secondaryWeapon.equals(held))) {
                    return true;
                }
            }
            return false;
        }
        if (bot.getInventory().getStack(0).isEmpty()
                || !weapon.equals(Registries.ITEM.getId(bot.getInventory().getStack(0).getItem()).getPath())) {
            return false;
        }
        if (this != MACE) return true;
        if (!bot.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).isOf(Items.DIAMOND_CHESTPLATE)) {
            return false;
        }
        for (int slot = 0; slot < 36; slot++) {
            if (bot.getInventory().getStack(slot).isOf(Items.WIND_CHARGE)) return true;
        }
        return false;
    }

    static PvPKit parse(String name) {
        for (PvPKit kit : values()) {
            if (kit.commandName.equalsIgnoreCase(name)) {
                return kit;
            }
        }
        return null;
    }

    List<String> applyCommands(String botName) {
        if (this == COPY) {
            return List.of();
        }
        if (this == CRYSTAL) {
            return crystalCommands(botName);
        }
        if (isFireClientKit()) {
            return fireClientCommands(botName);
        }
        List<String> commands = new ArrayList<>();
        commands.add("clear " + botName);
        commands.add(this == MACE
                ? itemC(botName, "hotbar.0", "minecraft:mace", 1,
                "minecraft:enchantments={density:5,wind_burst:3,unbreaking:3}")
                : item(botName, "hotbar.0", "minecraft:" + weapon, 1));
        commands.add(item(botName, "hotbar.1", this == MACE ? "minecraft:wind_charge" : "minecraft:ender_pearl",
                this == MACE ? 32 : 8));
        commands.add(item(botName, "hotbar.2", "minecraft:golden_apple", 8));
        commands.add(item(botName, "inventory.0", "minecraft:cooked_beef", 16));
        if (this == MACE) {
            commands.add(item(botName, "hotbar.3", "minecraft:ender_pearl", 8));
        }
        String secondaryWeapon = switch (this) {
            case IRON -> "minecraft:iron_axe";
            case DIAMOND, MACE -> "minecraft:diamond_axe";
            case NETHERITE -> "minecraft:netherite_axe";
            case AXE -> "minecraft:diamond_sword";
            default -> null;
        };
        if (secondaryWeapon != null) {
            commands.add(item(botName, "hotbar.4", secondaryWeapon, 1));
        }
        commands.add(item(botName, "weapon.offhand", "minecraft:shield", 1));
        commands.add(item(botName, "armor.head", "minecraft:" + armorMaterial + "_helmet", 1));
        commands.add(item(botName, "armor.chest", "minecraft:" + armorMaterial + "_chestplate", 1));
        commands.add(item(botName, "armor.legs", "minecraft:" + armorMaterial + "_leggings", 1));
        commands.add(item(botName, "armor.feet", "minecraft:" + armorMaterial + "_boots", 1));
        commands.add("player " + botName + " hotbar 1");
        return commands;
    }

    /** Exakt das Inventar aus dem User-Dump (creator LuisZanz, 1.21.11), inkl. Verzauberungen. */
    private static List<String> crystalCommands(String botName) {
        List<String> c = new ArrayList<>();
        c.add("clear " + botName);
        // Hotbar 0-8
        c.add(itemC(botName, "hotbar.0", "minecraft:totem_of_undying", 1, null));
        c.add(itemC(botName, "hotbar.1", "minecraft:ender_pearl", 16, null));
        c.add(itemC(botName, "hotbar.2", "minecraft:golden_apple", 64, null));
        c.add(itemC(botName, "hotbar.3", "minecraft:netherite_sword", 1,
                "minecraft:enchantments={knockback:1,sharpness:5,sweeping_edge:3}"));
        c.add(itemC(botName, "hotbar.4", "minecraft:obsidian", 64, null));
        c.add(itemC(botName, "hotbar.5", "minecraft:end_crystal", 64, null));
        c.add(itemC(botName, "hotbar.6", "minecraft:totem_of_undying", 1, null));
        c.add(itemC(botName, "hotbar.7", "minecraft:totem_of_undying", 1, null));
        c.add(itemC(botName, "hotbar.8", "minecraft:crossbow", 1,
                "minecraft:enchantments={multishot:1,quick_charge:3,unbreaking:3}"));
        // Inventar-Slots 9-35 -> inventory.0-26
        c.add(itemC(botName, "inventory.0", "minecraft:totem_of_undying", 1, null));
        c.add(itemC(botName, "inventory.1", "minecraft:obsidian", 64, null));
        c.add(itemC(botName, "inventory.2", "minecraft:end_crystal", 64, null));
        c.add(itemC(botName, "inventory.3", "minecraft:totem_of_undying", 1, null));
        c.add(itemC(botName, "inventory.4", "minecraft:totem_of_undying", 1, null));
        c.add(itemC(botName, "inventory.5", "minecraft:tipped_arrow", 64,
                "minecraft:potion_contents={potion:\"minecraft:slow_falling\"}"));
        c.add(itemC(botName, "inventory.6", "minecraft:glowstone", 64, null));
        c.add(itemC(botName, "inventory.7", "minecraft:totem_of_undying", 1, null));
        c.add(itemC(botName, "inventory.8", "minecraft:totem_of_undying", 1, null));
        c.add(itemC(botName, "inventory.9", "minecraft:netherite_axe", 1,
                "minecraft:enchantments={mending:1,sharpness:5,unbreaking:3}"));
        c.add(itemC(botName, "inventory.10", "minecraft:experience_bottle", 64, null));
        c.add(itemC(botName, "inventory.11", "minecraft:ender_pearl", 16, null));
        c.add(itemC(botName, "inventory.12", "minecraft:totem_of_undying", 1, null));
        c.add(itemC(botName, "inventory.13", "minecraft:totem_of_undying", 1, null));
        c.add(itemC(botName, "inventory.14", "minecraft:shield", 1,
                "minecraft:enchantments={mending:1,unbreaking:3}"));
        c.add(itemC(botName, "inventory.15", "minecraft:totem_of_undying", 1, null));
        c.add(itemC(botName, "inventory.16", "minecraft:totem_of_undying", 1, null));
        c.add(itemC(botName, "inventory.17", "minecraft:totem_of_undying", 1, null));
        c.add(itemC(botName, "inventory.18", "minecraft:netherite_pickaxe", 1,
                "minecraft:enchantments={efficiency:1,unbreaking:1}"));
        c.add(itemC(botName, "inventory.19", "minecraft:experience_bottle", 64, null));
        c.add(itemC(botName, "inventory.20", "minecraft:experience_bottle", 64, null));
        c.add(itemC(botName, "inventory.21", "minecraft:ender_pearl", 16, null));
        c.add(itemC(botName, "inventory.22", "minecraft:totem_of_undying", 1, null));
        c.add(itemC(botName, "inventory.23", "minecraft:totem_of_undying", 1, null));
        c.add(itemC(botName, "inventory.24", "minecraft:totem_of_undying", 1, null));
        c.add(itemC(botName, "inventory.25", "minecraft:ender_pearl", 16, null));
        c.add(itemC(botName, "inventory.26", "minecraft:respawn_anchor", 64, null));
        // Rüstung
        c.add(itemC(botName, "armor.feet", "minecraft:netherite_boots", 1,
                "minecraft:enchantments={feather_falling:4,mending:1,protection:4,unbreaking:3}"));
        c.add(itemC(botName, "armor.legs", "minecraft:netherite_leggings", 1,
                "minecraft:enchantments={blast_protection:4,mending:1,unbreaking:3}"));
        c.add(itemC(botName, "armor.chest", "minecraft:netherite_chestplate", 1,
                "minecraft:enchantments={mending:1,protection:4,unbreaking:3}"));
        c.add(itemC(botName, "armor.head", "minecraft:netherite_helmet", 1,
                "minecraft:enchantments={mending:1,protection:4,unbreaking:3}"));
        // Totem im Offhand
        c.add(itemC(botName, "weapon.offhand", "minecraft:totem_of_undying", 1, null));
        // Zum Schwert greifen. Vanilla-Slot hotbar.3 = Index 3; HeroBots hotbar ist
        // 1-basiert (integer(1,9), setSlot(n-1)), also hotbar 4.
        c.add("player " + botName + " hotbar 4");
        return c;
    }

    /**
     * Feuer-Client-Vorlagen exakt nach den User-JSONs (fireclient/kits/*.json).
     * Vanilla setzt die Komponenten-Syntax der JSONs nur teilweise um; die Sets unten bilden
     * das Kit deshalb über sichere Slot-Zuordnungen ab (Details siehe fireClientItem).
     */
    private List<String> fireClientCommands(String botName) {
        List<String> c = new ArrayList<>();
        c.add("clear " + botName);
        // Hotbar 0-8 -> hotbar.0-8
        for (int slot = 0; slot <= 8; slot++) {
            addFcSlot(c, botName, "hotbar." + slot, slot);
        }
        // Rucksack 9-35 -> inventory.0-26 (Vanilla mappt Slot 9 auf inventory.0)
        for (int slot = 9; slot <= 35; slot++) {
            addFcSlot(c, botName, "inventory." + (slot - 9), slot);
        }
        // Rüstung (Slots 100-103) + Offhand (-106)
        addFcArmor(c, botName);
        c.add(itemC(botName, "weapon.offhand", fcOffhandId(), 1, null));
        // Waffe in hotbar.0 greifen. HeroBot lehnt hotbar 0 ab (Bereich 1..9),
        // Slot-Index 0 entspricht dort hotbar 1.
        c.add("player " + botName + " hotbar 1");
        return c;
    }

    /** Offhand laut Quell-JSON: Slot -106 (Totem bei fc_misch, Schild bei fc_axe/fc_uhc). */
    private String fcOffhandId() {
        return this == FC_MISCH ? "minecraft:totem_of_undying" : "minecraft:shield";
    }

    /**
     * Baut einen Slot des Feuer-Client-Kits. Kein Komponenten-String, weil Vanilla einzelne
     * Komponenten der JSONs (damage:0, charged_projectiles, tipped_arrow) ablehnt und den
     * Slot sonst leer lässt bzw. durch eine glass_bottle ersetzt.
     */
    private void addFcSlot(List<String> c, String botName, String targetSlot, int srcSlot) {
        String[] item = fireClientItem(srcSlot);
        if (item == null) {
            return;
        }
        // item[1] ist bereits eine fertige Vanilla-Komponente (enchantments / potion_contents)
        // oder null. Frueher wurde hier noch ueber raw.startsWith("minecraft:") entschieden und
        // rohe Verzauberungslisten in eine enchantments={levels:{..}}-Huelle gewrappt: der Check
        // traf auch die fertigen Strings, die levels-Huelle gibt es ab 25w05a nicht mehr, und
        // beide Fehler liessen den Slot leer.
        c.add(itemC(botName, targetSlot, item[0], fcCount(srcSlot), item[1]));
    }

    private void addFcArmor(List<String> c, String botName) {
        switch (this) {
            case FC_AXE -> {
                c.add(itemC(botName, "armor.feet", "minecraft:diamond_boots", 1, null));
                c.add(itemC(botName, "armor.legs", "minecraft:diamond_leggings", 1, null));
                c.add(itemC(botName, "armor.chest", "minecraft:diamond_chestplate", 1, null));
                c.add(itemC(botName, "armor.head", "minecraft:diamond_helmet", 1, null));
            }
            case FC_UHC -> {
                c.add(itemC(botName, "armor.feet", "minecraft:diamond_boots", 1,
                        "minecraft:enchantments={protection:3}"));
                c.add(itemC(botName, "armor.legs", "minecraft:diamond_leggings", 1,
                        "minecraft:enchantments={protection:2}"));
                c.add(itemC(botName, "armor.chest", "minecraft:diamond_chestplate", 1,
                        "minecraft:enchantments={protection:3}"));
                c.add(itemC(botName, "armor.head", "minecraft:diamond_helmet", 1,
                        "minecraft:enchantments={protection:3}"));
            }
            case FC_MISCH -> {
                c.add(itemC(botName, "armor.feet", "minecraft:netherite_boots", 1,
                        "minecraft:enchantments={depth_strider:3,feather_falling:4,mending:1,protection:4,soul_speed:3,unbreaking:3}"));
                c.add(itemC(botName, "armor.legs", "minecraft:netherite_leggings", 1,
                        "minecraft:enchantments={mending:1,protection:4,swift_sneak:3,unbreaking:3}"));
                c.add(itemC(botName, "armor.chest", "minecraft:netherite_chestplate", 1,
                        "minecraft:enchantments={mending:1,protection:4,unbreaking:3}"));
                c.add(itemC(botName, "armor.head", "minecraft:netherite_helmet", 1,
                        "minecraft:enchantments={aqua_affinity:1,mending:1,protection:4,respiration:3,unbreaking:3}"));
            }
            default -> { }
        }
    }

    /**
     * Liefert {Item-ID, Verzauberungen} pro JSON-Slot des Feuer-Client-Kits; null = Slot leer.
     * Ersetzt beobachtete Vanilla-Fallstricke der Quell-JSONs:
     * - tipped_arrow + potion_contents wird von Vanilla zu einer glass_bottle -> normale Pfeile.
     * - netherite_spear ist kein Vanilla-Item -> bleibt leer (Slot 28).
     * - damage:0 und charged_projectiles werden abgelehnt -> weggelassen (Armb. ladt per use).
     */
    private String[] fireClientItem(int slot) {
        String e = "minecraft:enchantments={%s:%d}";
        return switch (this) {
            case FC_AXE -> switch (slot) {
                case 0 -> new String[]{"minecraft:diamond_axe", null};
                case 1 -> new String[]{"minecraft:diamond_sword", null};
                case 2 -> new String[]{"minecraft:crossbow", null};
                case 3 -> new String[]{"minecraft:bow", null};
                case 8 -> new String[]{"minecraft:arrow", null};
                default -> null;
            };
            case FC_UHC -> switch (slot) {
                case 0 -> new String[]{"minecraft:diamond_sword", e.formatted("sharpness", 4)};
                case 1 -> new String[]{"minecraft:diamond_axe",
                        "minecraft:enchantments={efficiency:3,sharpness:1}"};
                case 2 -> new String[]{"minecraft:crossbow", e.formatted("piercing", 1)};
                case 3 -> new String[]{"minecraft:golden_apple", null};
                case 4 -> new String[]{"minecraft:cobweb", null};
                case 5, 14 -> new String[]{"minecraft:lava_bucket", null};
                case 6 -> new String[]{"minecraft:infested_cobblestone", null};
                case 7, 16, 25, 34 -> new String[]{"minecraft:water_bucket", null};
                case 8 -> new String[]{"minecraft:bow", e.formatted("power", 1)};
                case 10 -> new String[]{"minecraft:diamond_pickaxe", e.formatted("efficiency", 3)};
                case 11 -> new String[]{"minecraft:arrow", null};
                case 15 -> new String[]{"minecraft:oak_planks", null};
                default -> null;
            };
            case FC_MISCH -> switch (slot) {
                case 0 -> new String[]{"minecraft:shield", e.formatted("unbreaking", 3)};
                case 1 -> new String[]{"minecraft:golden_apple", null};
                case 2 -> new String[]{"minecraft:wind_charge", null};
                case 3 -> new String[]{"minecraft:mace",
                        "minecraft:enchantments={density:4,fire_aspect:2,mending:1,unbreaking:3,wind_burst:1}"};
                case 4 -> new String[]{"minecraft:elytra", "minecraft:enchantments={mending:1,unbreaking:3}"};
                case 5 -> new String[]{"minecraft:netherite_sword",
                        "minecraft:enchantments={fire_aspect:2,knockback:1,looting:3,mending:1,sharpness:5,unbreaking:3}"};
                case 6 -> new String[]{"minecraft:obsidian", null};
                case 7 -> new String[]{"minecraft:end_crystal", null};
                case 8 -> new String[]{"minecraft:netherite_axe",
                        "minecraft:enchantments={efficiency:5,mending:1,sharpness:5,unbreaking:3}"};
                case 9, 18 -> new String[]{"minecraft:experience_bottle", null};
                case 10, 11, 12, 13, 14, 15 -> new String[]{"minecraft:splash_potion",
                        "minecraft:potion_contents={potion:\"minecraft:strong_strength\"}"};
                case 16, 17, 25, 26, 34, 35 -> new String[]{"minecraft:totem_of_undying", null};
                case 19, 20, 21, 22, 23, 24 -> new String[]{"minecraft:splash_potion",
                        "minecraft:potion_contents={potion:\"minecraft:strong_swiftness\"}"};
                case 27 -> new String[]{"minecraft:netherite_pickaxe",
                        "minecraft:enchantments={efficiency:5,fortune:3,mending:1,unbreaking:3}"};
                // case 28: netherite_spear -> kein Vanilla-Item, bleibt leer.
                case 29, 30 -> new String[]{"minecraft:ender_pearl", null};
                case 31 -> new String[]{"minecraft:water_bucket", null};
                case 32 -> new String[]{"minecraft:breeze_rod", null};
                case 33 -> new String[]{"minecraft:cobweb", null};
                default -> null;
            };
            default -> null;
        };
    }

    /** Stapelgröße eines Slots laut Quell-JSON. */
    private int fcCount(int slot) {
        return switch (this) {
            case FC_AXE -> slot == 8 ? 5 : 1;
            case FC_UHC -> switch (slot) {
                case 3 -> 13;   // goldene Äpfel
                case 4 -> 8;    // Cobwebs
                case 6 -> 64;   // infested_cobblestone
                case 11 -> 15;  // Pfeile
                case 15 -> 64;  // Bretter
                default -> 1;
            };
            case FC_MISCH -> switch (slot) {
                case 1 -> 64;   // goldene Äpfel
                case 2 -> 64;   // Wind Charges
                case 6 -> 64;   // Obsidian
                case 7 -> 8;    // End-Crystals
                case 9, 18 -> 64; // XP
                case 29, 30 -> 16; // Perlen
                case 32 -> 64;  // Breeze Rods
                case 33 -> 64;  // Cobwebs
                default -> 1;
            };
            default -> 1;
        };
    }

    private static String item(String botName, String slot, String itemId, int count) {
        return "item replace entity " + botName + " " + slot + " with " + itemId
                + (count == 1 ? "" : " " + count);
    }

    private static String itemC(String botName, String slot, String itemId, int count, String components) {
        String base = "item replace entity " + botName + " " + slot + " with " + itemId;
        if (components != null) {
            base += "[" + components + "]";
        }
        return base + (count == 1 ? "" : " " + count);
    }
}
