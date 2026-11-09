package fr.eternom.eterReward.module.pass;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sql.Row;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterReward.module.pass.PassService.Season;
import fr.eternom.eterReward.module.pass.PassStore.Progress;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * /pass [colis | historique | premium] : le menu du pass, la boîte de colis, l'XP des dernières 24 h, le premium.
 * /eterreward pass ... (staff, aussi depuis la console, ex : la boutique) :
 * - premium <joueur> [acheteur] [paliers] : premium du mois (cadeau si acheteur, paliers sautés en plus) ;
 * - gift <joueur> <acheteur>, skip <joueur> <paliers> ;
 * - set <pass> (pass du mois, tout le réseau), reload (config et passes/, tout le réseau) ;
 * - xp <joueur> <montant> [source], setxp <joueur> <xp>, info <joueur>, reset <joueur>, unclaim <joueur> <palier> [premium] ;
 * - event <durée> [multiplicateur] [sans-plafond] | event stop.
 */
public class PassCommand implements TabExecutor {

    public static final String ADMIN_PERMISSION = "eterreward.admin";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private final PassService pass;
    private final Messages messages;
    private final boolean admin;

    public PassCommand(PassService pass, Messages messages, boolean admin) {
        this.pass = pass;
        this.messages = messages;
        this.admin = admin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!admin) {
            if (!(sender instanceof Player player)) {
                messages.send(sender, "command.players-only");
                return true;
            }
            if (!pass.enabled()) {
                messages.send(player, "pass.none");
                return true;
            }
            switch (args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT)) {
                case "colis", "box", "boite" -> pass.openBox(player);
                case "historique", "history", "xp" -> pass.history(player);
                case "premium" -> pass.premium().shopLink(player);
                default -> pass.open(player);
            }
            return true;
        }
        if (args.length < 2 || !args[0].equalsIgnoreCase("pass")) {
            messages.send(sender, "pass.admin-usage");
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "premium" -> {
                if (args.length < 3) {
                    usage(sender);
                    return true;
                }
                // premium <joueur> [acheteur] [paliers] ; un nombre seul en 4e position = paliers
                String from = "";
                int skip = 0;
                if (args.length >= 4) {
                    int number = parse(args[3]);
                    if (number > 0 && args.length == 4) {
                        skip = number;
                    } else {
                        from = args[3];
                        skip = args.length >= 5 ? Math.max(0, parse(args[4])) : 0;
                    }
                }
                pass.premium().give(sender, args[2], from, skip);
            }
            case "gift" -> {
                if (args.length < 4) {
                    usage(sender);
                    return true;
                }
                pass.premium().give(sender, args[2], args[3], 0);
            }
            case "skip" -> {
                int tiers = args.length < 4 ? 0 : parse(args[3]);
                if (tiers <= 0) {
                    usage(sender);
                    return true;
                }
                withPlayer(sender, args[2], (uuid, name) -> {
                    Season season = pass.season();
                    int xp = pass.store().xp(uuid, season.key());
                    int gained = pass.addXp(uuid, season, pass.curve().xpToSkip(xp, tiers), PassService.Source.SHOP);
                    reply(sender, "pass.xp-given", "player", name, "xp", String.valueOf(gained));
                    Bukkit.getScheduler().runTask(pass.plugin(), () ->
                            pass.bus().notify(uuid, "pass.premium-skip", true, "count", String.valueOf(tiers)));
                });
            }
            case "set" -> {
                if (args.length < 3) {
                    usage(sender);
                    return true;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                Tasks.async(pass.plugin(), () -> {
                    boolean done = pass.force(id);
                    reply(sender, done ? "pass.set" : "pass.unknown", "pass", id, "list", String.join(", ", pass.passIds()));
                }, "Pass du mois non changé");
            }
            case "reload" -> {
                pass.reload();
                messages.send(sender, "pass.reloaded", "count", String.valueOf(pass.passIds().size()));
            }
            case "xp" -> {
                int amount = args.length < 4 ? 0 : parse(args[3]);
                if (amount <= 0) {
                    usage(sender);
                    return true;
                }
                PassService.Source source = args.length >= 5 ? source(args[4]) : PassService.Source.STAFF;
                withPlayer(sender, args[2], (uuid, name) -> {
                    int gained = pass.addXp(uuid, amount, source);
                    reply(sender, "pass.xp-given", "player", name, "xp", String.valueOf(gained));
                });
            }
            case "setxp" -> {
                int xp = args.length < 4 ? -1 : parse(args[3]);
                if (xp < 0 || args.length < 4) {
                    usage(sender);
                    return true;
                }
                withPlayer(sender, args[2], (uuid, name) -> {
                    pass.setXp(uuid, xp);
                    reply(sender, "pass.xp-set", "player", name, "xp", String.valueOf(xp), "tier", String.valueOf(pass.tier(xp)));
                });
            }
            case "reset" -> {
                if (args.length < 3) {
                    usage(sender);
                    return true;
                }
                withPlayer(sender, args[2], (uuid, name) -> {
                    pass.store().reset(uuid, pass.season().key());
                    reply(sender, "pass.reset", "player", name);
                });
            }
            case "unclaim" -> {
                int tier = args.length < 4 ? 0 : parse(args[3]);
                if (tier <= 0) {
                    usage(sender);
                    return true;
                }
                boolean premium = args.length >= 5 && args[4].toLowerCase(Locale.ROOT).startsWith("p");
                withPlayer(sender, args[2], (uuid, name) -> {
                    boolean done = pass.store().unclaim(uuid, pass.season().key(), tier, premium);
                    reply(sender, done ? "pass.unclaimed" : "pass.unclaim-none", "player", name, "tier", String.valueOf(tier));
                });
            }
            case "info" -> {
                if (args.length < 3) {
                    usage(sender);
                    return true;
                }
                withPlayer(sender, args[2], (uuid, name) -> info(sender, uuid, name));
            }
            case "event" -> event(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    /** /eterreward pass event <durée> [multiplicateur] [uncapped] | stop */
    private void event(CommandSender sender, String[] args) {
        if (args.length >= 3 && args[2].equalsIgnoreCase("stop")) {
            Tasks.async(pass.plugin(), () -> {
                pass.stopEvents();
                reply(sender, "pass.event.stopped");
            }, "Évènement du pass non arrêté");
            return;
        }
        Duration duration = args.length >= 3 ? PassDefinition.duration(args[2]) : null;
        double multiplier = 1;
        if (args.length >= 4) {
            try {
                multiplier = Double.parseDouble(args[3].replace(',', '.'));
            } catch (NumberFormatException e) {
                multiplier = -1;
            }
        }
        boolean uncapped = Arrays.stream(args).skip(3).anyMatch(arg -> arg.equalsIgnoreCase("uncapped")
                || arg.equalsIgnoreCase("sans-plafond"));
        if (duration == null || multiplier < 1 || multiplier > 10 || multiplier == 1 && !uncapped) {
            messages.send(sender, "pass.event.usage");
            return;
        }
        double chosen = multiplier;
        Tasks.async(pass.plugin(), () -> {
            pass.startEvent(duration, chosen, uncapped, sender.getName());
            reply(sender, "pass.event.started");
        }, "Évènement du pass non lancé");
    }

    /** /eterreward pass info : XP, palier, premium, réclamations, colis, temps de jeu du jour, dernières réclamations. */
    private void info(CommandSender sender, UUID uuid, String name) {
        Season season = pass.season();
        PassStore store = pass.store();
        Progress progress = store.progress(uuid, season.key());
        int claims = store.claims(uuid, season.key()).size();
        boolean premium = store.hasPremium(uuid, season.key());
        int parcels = store.parcelCount(uuid);
        int[] played = pass.playedToday(uuid, season);
        List<Row> log = store.claimLog(uuid, 5);
        ZoneId zone = pass.now().getZone();
        Bukkit.getScheduler().runTask(pass.plugin(), () -> {
            messages.send(sender, "pass.info.title", "player", name, "season", season.key(), "pass", season.pass().id());
            messages.send(sender, "pass.info.progress", "xp", String.valueOf(progress.xp()),
                    "tier", String.valueOf(pass.tier(progress.xp())), "level", String.valueOf(pass.curve().level(progress.xp())));
            messages.send(sender, "pass.info.state", "premium", messages.plain(sender, premium ? "pass.info.yes" : "pass.info.no"),
                    "claims", String.valueOf(claims), "parcels", String.valueOf(parcels));
            messages.send(sender, "pass.info.today", "xp", String.valueOf(played[0]), "cap", String.valueOf(played[1]));
            for (Row row : log) {
                String when = row.getLong("claimed_at") > 0
                        ? DATE.format(Instant.ofEpochMilli(row.getLong("claimed_at")).atZone(zone)) : "?";
                messages.send(sender, "pass.info.claim", "season", row.getString("season"), "tier", String.valueOf(row.getInt("tier")),
                        "line", messages.plain(sender, row.getBoolean("premium") ? "pass.info.premium" : "pass.info.free"),
                        "server", row.getString("server") == null ? "?" : row.getString("server"), "date", when);
            }
        });
    }

    /** Retrouve le joueur (connecté ou non), puis action en tâche de fond. */
    private void withPlayer(CommandSender sender, String name, BiConsumer<UUID, String> action) {
        Tasks.async(pass.plugin(), () -> {
            var target = EterLib.get().getPlayers().find(name);
            if (target.isEmpty()) {
                reply(sender, "player.unknown", "player", name);
                return;
            }
            action.accept(target.get().uuid(), target.get().name());
        }, "Commande du pass en erreur pour " + name);
    }

    private void reply(CommandSender sender, String key, String... placeholders) {
        Bukkit.getScheduler().runTask(pass.plugin(), () -> messages.send(sender, key, placeholders));
    }

    private void usage(CommandSender sender) {
        messages.send(sender, "pass.admin-usage");
    }

    private static PassService.Source source(String text) {
        try {
            return PassService.Source.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PassService.Source.EVENT;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!admin) {
            return args.length == 1 ? filter(List.of("colis", "historique", "premium"), args[0]) : List.of();
        }
        return switch (args.length) {
            case 1 -> List.of("pass");
            case 2 -> filter(List.of("premium", "gift", "skip", "set", "reload", "xp", "setxp", "info", "reset", "unclaim", "event"), args[1]);
            case 3 -> switch (args[1].toLowerCase(Locale.ROOT)) {
                case "set" -> filter(List.copyOf(pass.passIds()), args[2]);
                case "reload" -> List.of();
                case "event" -> filter(List.of("1h", "2h", "1d", "stop"), args[2]);
                default -> EterLib.get().getOnlineNames().complete(args[2]);
            };
            case 4 -> switch (args[1].toLowerCase(Locale.ROOT)) {
                case "event" -> List.of("1.5", "2");
                case "gift", "premium" -> EterLib.get().getOnlineNames().complete(args[3]);
                default -> List.of();
            };
            case 5 -> switch (args[1].toLowerCase(Locale.ROOT)) {
                case "event" -> List.of("uncapped");
                case "unclaim" -> List.of("free", "premium");
                case "xp" -> Arrays.stream(PassService.Source.values()).map(PassService.Source::id).toList();
                default -> List.of();
            };
            default -> List.of();
        };
    }

    private static List<String> filter(List<String> options, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(lower)).toList();
    }

    private static int parse(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
