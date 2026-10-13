package fr.eternom.eterReward.module.challenge;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Un défi possible (challenges.yml) : une action à répéter target fois. kind dit quoi compter, match restreint à des
 * blocs ou des créatures (vide : tout). Le texte affiché est dans lang/ (challenges.list.<id>).
 */
public record ChallengeDefinition(String id, Kind kind, Set<Material> blocks, Set<EntityType> entities, boolean monsters,
                                  int target, int xp, double money, Material icon) {

    public enum Kind {
        /** Casser des blocs NATURELS (pas posés par un joueur). */
        BREAK,
        /** Récolter des cultures mûres. */
        HARVEST,
        /** Tuer des créatures. */
        KILL,
        /** Pêcher (une prise). */
        FISH,
        /** Valider des quêtes de métier (EterMarket). */
        QUEST
    }

    /** Groupes utilisables dans match : "#ores", "#logs", "#stone", "#dirt", "#monsters"... */
    private static final Map<String, List<Tag<Material>>> GROUPS = Map.of(
            "#ores", List.of(Tag.COAL_ORES, Tag.IRON_ORES, Tag.COPPER_ORES, Tag.GOLD_ORES, Tag.REDSTONE_ORES, Tag.LAPIS_ORES,
                    Tag.DIAMOND_ORES, Tag.EMERALD_ORES),
            "#logs", List.of(Tag.LOGS),
            "#sand", List.of(Tag.SAND));
    private static final Map<String, Set<Material>> EXTRA = Map.of(
            "#ores", Set.of(Material.NETHER_QUARTZ_ORE, Material.NETHER_GOLD_ORE, Material.ANCIENT_DEBRIS),
            "#stone", Set.of(Material.STONE, Material.DEEPSLATE, Material.ANDESITE, Material.DIORITE, Material.GRANITE,
                    Material.TUFF, Material.CALCITE),
            "#dirt", Set.of(Material.DIRT, Material.GRASS_BLOCK, Material.GRAVEL, Material.CLAY, Material.SAND, Material.RED_SAND));

    boolean matchesBlock(Material material) {
        return blocks.isEmpty() || blocks.contains(material);
    }

    boolean matchesEntity(EntityType type, boolean isMonster) {
        if (monsters) {
            return isMonster;
        }
        return entities.isEmpty() || entities.contains(type);
    }

    /** Lit un défi ; null s'il est inutilisable (avec un avertissement). */
    static ChallengeDefinition load(String id, ConfigurationSection section, int defaultXp, double defaultMoney, Logger logger) {
        Kind kind;
        try {
            kind = Kind.valueOf(section.getString("kind", "").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warning("challenges.yml : " + id + " : kind inconnu (break, harvest, kill, fish, quest)");
            return null;
        }
        Set<Material> blocks = EnumSet.noneOf(Material.class);
        Set<EntityType> entities = EnumSet.noneOf(EntityType.class);
        boolean monsters = false;
        for (String entry : section.getStringList("match")) {
            String name = entry.trim().toLowerCase(Locale.ROOT);
            if (name.equals("#monsters")) {
                monsters = true;
            } else if (GROUPS.containsKey(name) || EXTRA.containsKey(name)) {
                GROUPS.getOrDefault(name, List.of()).forEach(tag -> blocks.addAll(tag.getValues()));
                blocks.addAll(EXTRA.getOrDefault(name, Set.of()));
            } else if (kind == Kind.KILL) {
                NamespacedKey entityKey = NamespacedKey.fromString(name);
                EntityType type = entityKey == null ? null : Registry.ENTITY_TYPE.get(entityKey);
                if (type == null) {
                    logger.warning("challenges.yml : " + id + " : créature inconnue ignorée : " + entry);
                } else {
                    entities.add(type);
                }
            } else {
                Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
                if (material == null || !material.isBlock()) {
                    logger.warning("challenges.yml : " + id + " : bloc inconnu ignoré : " + entry);
                } else {
                    blocks.add(material);
                }
            }
        }
        int target = section.getInt("target");
        if (target < 1) {
            logger.warning("challenges.yml : " + id + " : target doit être au moins 1");
            return null;
        }
        Material icon = Material.matchMaterial(section.getString("icon", "PAPER"));
        return new ChallengeDefinition(id, kind, Set.copyOf(blocks), Set.copyOf(entities), monsters, target,
                Math.max(0, section.getInt("xp", defaultXp)), Math.max(0, section.getDouble("money", defaultMoney)),
                icon == null || !icon.isItem() ? Material.PAPER : icon);
    }
}
