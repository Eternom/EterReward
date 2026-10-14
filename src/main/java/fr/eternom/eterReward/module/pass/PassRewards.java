package fr.eternom.eterReward.module.pass;

import fr.eternom.eterEconomy.api.EconomyApi;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterReward.module.ResourceRewards;
import fr.eternom.eterReward.module.pass.PassDefinition.ItemReward;
import fr.eternom.eterReward.module.pass.PassDefinition.PermissionReward;
import fr.eternom.eterReward.module.pass.PassDefinition.Reward;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Donner et décrire une récompense du pass. Donner se fait en deux temps : ce qui ne demande pas le joueur connecté
 * (argent, clés de ressource, permissions LuckPerms) en tâche de fond juste après l'enregistrement de la réclamation,
 * puis le reste (objets, commandes, message) sur le thread principal.
 */
class PassRewards {

    private final JavaPlugin plugin;
    private final Messages messages;

    PassRewards(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    /** Tâche de fond, juste après l'enregistrement : ce qui ne demande pas le joueur connecté. */
    void giveRemote(UUID uuid, String name, Reward reward) {
        if (reward.money() > 0) {
            EconomyApi.get().ifPresent(economy -> economy.deposit(uuid, reward.money(), "EterReward · pass"));
        }
        if ((reward.keys() > 0 || reward.resourceMinutes() > 0) && !ResourceRewards.give(uuid, reward.keys(), reward.resourceMinutes())) {
            plugin.getLogger().warning("Pass : clés de ressource non données à " + name + " (EterResource absent)");
        }
        for (PermissionReward permission : reward.permissions()) {
            try {
                LuckPermsGrants.grantFor(uuid, permission.permission(), permission.duration());
            } catch (RuntimeException | LinkageError e) {
                plugin.getLogger().severe("Pass : permission " + permission.permission() + " non donnée à " + name + " : " + e.getMessage());
            }
        }
    }

    /** Thread principal : objets (ce qui ne rentre pas est posé au sol), commandes, message. */
    void giveLocal(Player player, Reward reward, int tier) {
        boolean dropped = false;
        for (ItemReward item : reward.items()) {
            for (ItemStack left : player.getInventory().addItem(ItemStack.of(item.material(), item.amount())).values()) {
                player.getWorld().dropItem(player.getLocation(), left);
                dropped = true;
            }
        }
        for (String command : reward.commands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", player.getName()));
        }
        player.sendMessage(messages.prefix().append(messages.render(raw(player, "pass.claimed"),
                Placeholder.component("content", describe(player, reward)), "tier", String.valueOf(tier))));
        if (dropped) {
            messages.send(player, "pass.dropped");
        }
        player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
    }

    /** « 100 Heloks, 2 clés de ressource, /craft (7 j) » ou le label. */
    Component describe(Player viewer, Reward reward) {
        if (reward.label() != null) {
            return messages.render(reward.label(), TagResolver.empty());
        }
        List<Component> parts = new ArrayList<>();
        if (reward.money() > 0) {
            parts.add(Component.text(Money.format(reward.money())));
        }
        if (reward.keys() > 0) {
            parts.add(messages.get(viewer, "pass.reward.keys", "count", String.valueOf(reward.keys())));
        }
        if (reward.resourceMinutes() > 0) {
            parts.add(messages.get(viewer, "pass.reward.minutes", "count", String.valueOf(reward.resourceMinutes())));
        }
        for (ItemReward item : reward.items()) {
            parts.add(Component.text(item.amount() + "× ").append(Component.translatable(item.material().translationKey())));
        }
        for (PermissionReward permission : reward.permissions()) {
            parts.add(messages.get(viewer, "pass.reward.permission",
                    "permission", permissionLabel(viewer, permission.permission()),
                    "duration", EterLib.get().formatDuration(viewer, permission.duration().toSeconds())));
        }
        if (!reward.commands().isEmpty() && parts.isEmpty()) {
            parts.add(messages.get(viewer, "pass.reward.surprise"));
        }
        return Component.join(JoinConfiguration.commas(true), parts);
    }

    /** Nom lisible d'une permission (lang : pass.permission.<permission>), sinon la permission elle-même. */
    private String permissionLabel(Player viewer, String permission) {
        String label = messages.raw(viewer, "pass.permission." + permission.replace('.', '_'));
        return label == null ? permission : messages.plain(viewer, "pass.permission." + permission.replace('.', '_'));
    }

    private String raw(Player player, String key) {
        String raw = messages.raw(player, key);
        return raw == null ? key : raw;
    }
}
