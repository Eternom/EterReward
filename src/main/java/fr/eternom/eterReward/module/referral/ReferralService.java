package fr.eternom.eterReward.module.referral;

import fr.eternom.eterEconomy.api.EconomyApi;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.player.PlayerDirectory.NetworkPlayer;
import fr.eternom.eterReward.module.pass.PassService;
import fr.eternom.eterSuccess.api.SuccessApi;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Parrainage (/sponsor) : un nouveau (moins de referral.newcomer-max-hours de jeu) désigne un parrain qui a au moins
 * referral.sponsor-min-hours, une seule fois. Quand le filleul atteint referral.goal-hours de jeu actif (AFK exclu,
 * temps d'EterSuccess), les deux reçoivent leur récompense, une seule fois (sponsor-money, sponsor-pass-xp,
 * newcomer-money). Plafond : referral.max-per-sponsor filleuls par parrain. Les heures de jeu exigées rendent coûteux
 * le parrainage de ses propres comptes.
 * Table eterreward_referrals : un filleul par ligne (newcomer, sponsor, noms, date, rewarded). Bloquant : hors du thread
 * principal. Sans EterSuccess (temps de jeu), le parrainage est indisponible.
 */
public class ReferralService implements TabExecutor {

    private static final String TABLE = "referrals";

    private final JavaPlugin plugin;
    private final Database database;
    private final Messages messages;
    private final PassService pass;
    private final boolean enabled;
    private final double newcomerMaxHours;
    private final double sponsorMinHours;
    private final double goalHours;
    private final double sponsorMoney;
    private final int sponsorPassXp;
    private final double newcomerMoney;
    private final int maxPerSponsor;

    public ReferralService(JavaPlugin plugin, Database database, Messages messages, PassService pass, ConfigurationSection config) {
        this.plugin = plugin;
        this.database = database;
        this.messages = messages;
        this.pass = pass;
        this.enabled = config == null || config.getBoolean("enabled", true);
        this.newcomerMaxHours = config == null ? 3 : config.getDouble("newcomer-max-hours", 3);
        this.sponsorMinHours = config == null ? 20 : config.getDouble("sponsor-min-hours", 20);
        this.goalHours = config == null ? 10 : config.getDouble("goal-hours", 10);
        this.sponsorMoney = config == null ? 2000 : config.getDouble("sponsor-money", 2000);
        this.sponsorPassXp = config == null ? 100 : config.getInt("sponsor-pass-xp", 100);
        this.newcomerMoney = config == null ? 1000 : config.getDouble("newcomer-money", 1000);
        this.maxPerSponsor = config == null ? 20 : config.getInt("max-per-sponsor", 20);
        database.createTable(TABLE,
                Column.of("newcomer", Column.Type.UUID).primaryKey(),
                Column.of("newcomer_name", Column.Type.STRING).notNull(),
                Column.of("sponsor", Column.Type.UUID).notNull(),
                Column.of("sponsor_name", Column.Type.STRING).notNull(),
                Column.of("created", Column.Type.LONG).notNull(),
                Column.of("rewarded", Column.Type.BOOLEAN).notNull());
    }

    /** Toutes les 10 min : les filleuls connectés ici qui ont atteint l'objectif sont récompensés (avec leur parrain). */
    public void start() {
        if (!enabled) {
            return;
        }
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!available()) {
                return;
            }
            for (Player online : List.copyOf(Bukkit.getOnlinePlayers())) {
                try {
                    check(online.getUniqueId(), online.getName());
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Parrainage non vérifié pour " + online.getName() + " : " + e.getMessage());
                }
            }
        }, 20 * 60, 20 * 60 * 10);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
            return true;
        }
        if (!enabled || !available()) {
            messages.send(player, "referral.unavailable");
            return true;
        }
        if (args.length == 0) {
            info(player);
        } else {
            declare(player, args[0]);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return args.length == 1 ? EterLib.get().getOnlineNames().complete(args[0]) : List.of();
    }

    /** /sponsor &lt;pseudo&gt; : le nouveau désigne son parrain. */
    private void declare(Player player, String name) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            if (database.getFirst(TABLE, Map.of("newcomer", uuid)).isPresent()) {
                return new String[]{"referral.already"};
            }
            if (hours(uuid) > newcomerMaxHours) {
                return new String[]{"referral.too-late", "hours", format(newcomerMaxHours)};
            }
            Optional<NetworkPlayer> found = EterLib.get().getPlayers().find(name);
            if (found.isEmpty()) {
                return new String[]{"player.unknown", "player", name};
            }
            NetworkPlayer sponsor = found.get();
            if (sponsor.uuid().equals(uuid)) {
                return new String[]{"referral.self"};
            }
            if (hours(sponsor.uuid()) < sponsorMinHours) {
                return new String[]{"referral.sponsor-too-new", "player", sponsor.name(), "hours", format(sponsorMinHours)};
            }
            if (database.get(TABLE, Map.of("sponsor", sponsor.uuid())).size() >= maxPerSponsor) {
                return new String[]{"referral.sponsor-full", "player", sponsor.name()};
            }
            database.insert(TABLE, Map.of("newcomer", uuid, "newcomer_name", player.getName(), "sponsor", sponsor.uuid(),
                    "sponsor_name", sponsor.name(), "created", System.currentTimeMillis(), "rewarded", false));
            pass.bus().notify(sponsor.uuid(), "referral.new", true, "player", player.getName(), "hours", format(goalHours));
            return new String[]{"referral.declared", "player", sponsor.name(), "hours", format(goalHours)};
        }, reply -> messages.send(player, reply[0], java.util.Arrays.copyOfRange(reply, 1, reply.length)),
                () -> messages.send(player, "error.generic"));
    }

    /** /sponsor : son parrain, et ses filleuls avec leur avancée. */
    private void info(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            Optional<Row> mine = database.getFirst(TABLE, Map.of("newcomer", uuid));
            List<String[]> lines = new java.util.ArrayList<>();
            mine.ifPresentOrElse(row -> lines.add(new String[]{row.getBoolean("rewarded") ? "referral.info.sponsor-done" : "referral.info.sponsor",
                            "player", row.getString("sponsor_name"), "hours", format(hours(uuid)), "goal", format(goalHours)}),
                    () -> lines.add(hours(uuid) <= newcomerMaxHours
                            ? new String[]{"referral.info.none", "hours", format(newcomerMaxHours)}
                            : new String[]{"referral.info.none-late"}));
            List<Row> godchildren = database.get(TABLE, Map.of("sponsor", uuid));
            lines.add(new String[]{"referral.info.godchildren", "count", String.valueOf(godchildren.size()), "max", String.valueOf(maxPerSponsor)});
            for (Row row : godchildren) {
                lines.add(row.getBoolean("rewarded")
                        ? new String[]{"referral.info.godchild-done", "player", row.getString("newcomer_name")}
                        : new String[]{"referral.info.godchild", "player", row.getString("newcomer_name"),
                        "hours", format(hours(row.getUUID("newcomer"))), "goal", format(goalHours)});
            }
            lines.add(new String[]{"referral.info.rules", "goal", format(goalHours), "sponsor_money", Money.format(sponsorMoney),
                    "newcomer_money", Money.format(newcomerMoney)});
            return lines;
        }, lines -> {
            messages.send(player, "referral.info.title");
            lines.forEach(line -> player.sendMessage(messages.get(player, line[0], java.util.Arrays.copyOfRange(line, 1, line.length))));
        }, () -> messages.send(player, "error.generic"));
    }

    /** Bloquant : récompense le filleul et son parrain si l'objectif est atteint (une seule fois, même entre serveurs). */
    private void check(UUID newcomer, String name) {
        Optional<Row> row = database.getFirst(TABLE, Map.of("newcomer", newcomer));
        if (row.isEmpty() || row.get().getBoolean("rewarded") || hours(newcomer) < goalHours) {
            return;
        }
        // Une seule fois : la ligne passe à rewarded = vrai ici seulement si elle ne l'était pas
        if (database.update(TABLE, Map.of("rewarded", true), Map.of("newcomer", newcomer, "rewarded", false)) == 0) {
            return;
        }
        UUID sponsor = row.get().getUUID("sponsor");
        EconomyApi.get().ifPresent(economy -> {
            if (newcomerMoney > 0) {
                economy.deposit(newcomer, newcomerMoney, "EterReward · parrainage");
            }
            if (sponsorMoney > 0) {
                economy.deposit(sponsor, sponsorMoney, "EterReward · parrainage");
            }
        });
        if (sponsorPassXp > 0) {
            pass.addXp(sponsor, sponsorPassXp);
        }
        pass.bus().notify(newcomer, "referral.rewarded-newcomer", true, "player", row.get().getString("sponsor_name"),
                "money", Money.format(newcomerMoney));
        pass.bus().notify(sponsor, "referral.rewarded-sponsor", true, "player", name, "money", Money.format(sponsorMoney),
                "xp", String.valueOf(sponsorPassXp));
    }

    private static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("EterSuccess");
    }

    /** Bloquant : heures de jeu actif (EterSuccess). */
    private static double hours(UUID player) {
        return SuccessApi.get().map(api -> api.playtimeMinutes(player) / 60.0).orElse(0.0);
    }

    private static String format(double hours) {
        return hours == Math.floor(hours) ? String.valueOf((long) hours) : String.format(java.util.Locale.ROOT, "%.1f", hours);
    }
}
