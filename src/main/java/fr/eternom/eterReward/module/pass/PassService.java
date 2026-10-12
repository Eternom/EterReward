package fr.eternom.eterReward.module.pass;

import fr.eternom.eterEconomy.api.EconomyApi;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterReward.module.pass.PassDefinition.ItemReward;
import fr.eternom.eterReward.module.pass.PassDefinition.PermissionReward;
import fr.eternom.eterReward.module.pass.PassDefinition.Reward;
import fr.eternom.eterReward.module.pass.PassDefinition.Tier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Le pass de saison : chaque mois, un des pass de passes/ est tiré au hasard (jamais celui du mois d'avant), le même pour
 * tout le réseau. On gagne de l'XP en jouant (temps actif, plafonné par jour), avec /daily et les quêtes de métier ;
 * chaque palier atteint débloque une récompense gratuite, et une premium avec eterreward.pass.premium.<pass>.
 * Les récompenses non réclamées à la fin du mois sont perdues.
 *
 * Réclamer : la base enregistre d'abord (INSERT IGNORE, une seule fois même sur deux serveurs), puis on donne. L'argent,
 * les clés et les permissions sont donnés en tâche de fond juste après l'enregistrement (même si le joueur part entre
 * les deux) ; les objets et les commandes sur le thread principal.
 */
public class PassService {

    public static final String PERMISSION = "eterreward.pass";
    public static final String PREMIUM_PERMISSION = "eterreward.pass.premium.";
    private static final DateTimeFormatter SEASON_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    /** Réglages (config.yml > pass). questXp : easy, normal, hard, bonus. */
    public record Settings(int xpPerTier, int playtimeXpPerMinute, int playtimeCap, int dailyXp, Map<String, Integer> questXp) {
    }

    /** La saison en cours : le mois ("2026-10"), son pass et sa fin. */
    public record Season(String key, PassDefinition pass, ZonedDateTime end) {
    }

    /** Ce que montre le menu d'un joueur. */
    record View(Season season, int xp, Set<String> claimed, boolean premium) {
    }

    private final JavaPlugin plugin;
    private final PassStore store;
    private final Map<String, PassDefinition> passes;
    private final Settings settings;
    private final Messages messages;
    private final ZoneId zone;
    private final BackButton backButton;
    private volatile Season current;

    public PassService(JavaPlugin plugin, PassStore store, Map<String, PassDefinition> passes, Settings settings,
                       Messages messages, ZoneId zone, BackButton backButton) {
        this.plugin = plugin;
        this.store = store;
        this.passes = passes;
        this.settings = settings;
        this.messages = messages;
        this.zone = zone;
        this.backButton = backButton;
    }

    public boolean enabled() {
        return !passes.isEmpty();
    }

    // ---------- Saison ----------

    /** La saison du mois, tirée au premier besoin. Bloquant (base). */
    public Season season() {
        YearMonth month = YearMonth.now(zone);
        String key = month.format(SEASON_FORMAT);
        Season season = current;
        if (season != null && season.key().equals(key)) {
            return season;
        }
        String id = store.season(key).orElseGet(() -> store.createSeason(key, pick(month)));
        PassDefinition pass = passes.getOrDefault(id, passes.values().iterator().next());
        season = new Season(key, pass, month.plusMonths(1).atDay(1).atStartOfDay(zone));
        current = season;
        return season;
    }

    /** Au hasard parmi les pass, sauf celui du mois d'avant (s'il y en a d'autres). */
    private String pick(YearMonth month) {
        String previous = store.season(month.minusMonths(1).format(SEASON_FORMAT)).orElse("");
        List<String> choices = new ArrayList<>(passes.keySet());
        if (choices.size() > 1) {
            choices.remove(previous);
        }
        return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
    }

    /** Staff : force le pass de ce mois. Bloquant. */
    public boolean force(String id) {
        if (!passes.containsKey(id)) {
            return false;
        }
        store.setSeason(YearMonth.now(zone).format(SEASON_FORMAT), id);
        current = null;
        return true;
    }

    public Set<String> passIds() {
        return passes.keySet();
    }

    // ---------- XP ----------

    /** Palier atteint avec cette XP (0 à 30). */
    int tier(Season season, int xp) {
        return Math.min(season.pass().tiers().size(), xp / Math.max(1, settings.xpPerTier()));
    }

    /** Temps de jeu actif (minute écoulée), plafonné par jour. Bloquant. */
    void playtime(UUID player) {
        Season season = season();
        int cap = season.pass().bonus().playtimeCap() > 0 ? season.pass().bonus().playtimeCap() : settings.playtimeCap();
        int before = store.xp(player, season.key());
        store.addPlaytime(player, season.key(), settings.playtimeXpPerMinute(), LocalDate.now(zone).toEpochDay(), cap);
        notifyTier(player, season, before, store.xp(player, season.key()));
    }

    /** /daily réclamé (tâche de fond de DailyService). Bloquant. */
    public void dailyClaimed(UUID player) {
        if (!enabled()) {
            return;
        }
        Season season = season();
        addXp(player, season, (int) Math.round(settings.dailyXp() * season.pass().bonus().dailyMultiplier()));
    }

    /** Quête de métier validée (thread principal). tier : easy, normal, hard ou bonus. */
    void questCompleted(Player player, String job, String tier) {
        if (!enabled()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, () -> {
            Season season = season();
            int base = settings.questXp().getOrDefault(tier, 0);
            addXp(uuid, season, (int) Math.round(base * season.pass().questMultiplier(job)));
        }, "XP du pass non donnée à " + player.getName());
    }

    /** XP donnée par un autre plugin ou le staff. Bloquant. */
    public void addXp(UUID player, int amount) {
        if (enabled()) {
            addXp(player, season(), amount);
        }
    }

    private void addXp(UUID player, Season season, int amount) {
        if (amount <= 0) {
            return;
        }
        int before = store.xp(player, season.key());
        store.addXp(player, season.key(), amount);
        notifyTier(player, season, before, before + amount);
    }

    /** Un palier vient d'être atteint : prévient le joueur s'il est sur ce serveur, avec un bouton /pass. */
    private void notifyTier(UUID uuid, Season season, int before, int after) {
        int reached = tier(season, after);
        if (reached <= tier(season, before)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                return;
            }
            messages.send(player, "pass.tier-reached", "tier", String.valueOf(reached));
            Component button = messages.get(player, "pass.button")
                    .clickEvent(ClickEvent.runCommand("/pass"))
                    .hoverEvent(HoverEvent.showText(messages.get(player, "pass.button-hover")));
            player.sendMessage(messages.prefix().append(button));
            player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.4f);
        });
    }

    // ---------- Menu et réclamations ----------

    public void open(Player player) {
        if (!enabled()) {
            messages.send(player, "pass.none");
            return;
        }
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            Season season = season();
            return new View(season, store.xp(uuid, season.key()), store.claims(uuid, season.key()), false);
        }, view -> {
            View shown = new View(view.season(), view.xp(), view.claimed(), isPremium(player, view.season()));
            player.openInventory(new PassMenu(this, player, shown, 0).getInventory());
        }, () -> messages.send(player, "error.generic"));
    }

    void reopen(Player player, int page) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            Season season = season();
            return new View(season, store.xp(uuid, season.key()), store.claims(uuid, season.key()), false);
        }, view -> player.openInventory(new PassMenu(this, player,
                new View(view.season(), view.xp(), view.claimed(), isPremium(player, view.season())), page).getInventory()),
                () -> messages.send(player, "error.generic"));
    }

    boolean isPremium(Player player, Season season) {
        return player.hasPermission(PREMIUM_PERMISSION + season.pass().id()) || player.hasPermission(PREMIUM_PERMISSION + "*");
    }

    /** Clic sur un palier (thread principal). */
    void claim(Player player, Season shown, int tierNumber, boolean premium, int page) {
        Tier tier = shown.pass().tiers().get(tierNumber - 1);
        Reward reward = premium ? tier.premium() : tier.free();
        if (reward == null) {
            return;
        }
        if (premium && !isPremium(player, shown)) {
            messages.send(player, "pass.premium-required");
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        if ((reward.keys() > 0 || reward.resourceMinutes() > 0) && !Bukkit.getPluginManager().isPluginEnabled("EterResource")
                || !reward.permissions().isEmpty() && !Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            messages.send(player, "pass.unavailable-here");
            return;
        }
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Tasks.async(plugin, player, () -> {
            Season season = season();
            if (!season.key().equals(shown.key())) {
                return "pass.season-over";
            }
            if (tier(season, store.xp(uuid, season.key())) < tierNumber) {
                return "pass.locked";
            }
            if (!store.claim(uuid, season.key(), tierNumber, premium)) {
                return "pass.already";
            }
            giveRemote(uuid, name, reward);
            return null;
        }, error -> {
            if (error != null) {
                messages.send(player, error);
                player.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            } else {
                giveLocal(player, reward, tierNumber);
            }
            reopen(player, page);
        }, () -> messages.send(player, "error.generic"));
    }

    /** Tâche de fond, juste après l'enregistrement : ce qui ne demande pas le joueur connecté. */
    private void giveRemote(UUID uuid, String name, Reward reward) {
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
    private void giveLocal(Player player, Reward reward, int tier) {
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

    // ---------- Premium (staff, boutique) ----------

    /**
     * /eterreward pass premium <joueur> : le premium du pass en cours, jusqu'à la fin du mois exactement. Marche aussi
     * pour un joueur hors ligne ou ailleurs (LuckPerms). Thread principal.
     */
    public void givePremium(CommandSender sender, String targetName) {
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            messages.send(sender, "pass.luckperms-missing");
            return;
        }
        Tasks.async(plugin, () -> {
            var target = EterLib.get().getPlayers().find(targetName);
            if (target.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> messages.send(sender, "player.unknown", "player", targetName));
                return;
            }
            Season season = season();
            LuckPermsGrants.grantUntil(target.get().uuid(), PREMIUM_PERMISSION + season.pass().id(), season.end().toInstant());
            Bukkit.getScheduler().runTask(plugin, () -> messages.send(sender, "pass.premium-given",
                    "player", target.get().name(), "pass", season.pass().id()));
        }, "Premium du pass non donné à " + targetName);
    }

    // ---------- Textes ----------

    /** « 100 Heloks, 2 clés de ressource, /craft (7 j) » ou le label. */
    Component describe(Player viewer, Reward reward) {
        if (reward.label() != null) {
            return messages.render(reward.label(), TagResolver.empty());
        }
        List<Component> parts = new ArrayList<>();
        if (reward.money() > 0) {
            parts.add(Component.text(fr.eternom.eterLib.helper.economy.Money.format(reward.money())));
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

    /** Temps avant la fin de la saison, mis en forme. */
    String timeLeft(Player viewer, Season season) {
        long seconds = Duration.between(ZonedDateTime.now(zone), season.end()).toSeconds();
        return EterLib.get().formatDuration(viewer, Math.max(0, seconds));
    }

    Settings settings() {
        return settings;
    }

    Messages messages() {
        return messages;
    }

    BackButton backButton() {
        return backButton;
    }

    JavaPlugin plugin() {
        return plugin;
    }

    String raw(Player player, String key) {
        String raw = messages.raw(player, key);
        return raw == null ? key : raw;
    }
}
