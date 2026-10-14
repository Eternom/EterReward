package fr.eternom.eterReward;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterReward.api.RewardApi;
import fr.eternom.eterReward.listeners.Commands;
import fr.eternom.eterReward.listeners.Events;
import fr.eternom.eterReward.module.RewardApiService;
import fr.eternom.eterReward.module.challenge.ChallengeCatalog;
import fr.eternom.eterReward.module.challenge.ChallengeService;
import fr.eternom.eterReward.module.challenge.ChallengeStore;
import fr.eternom.eterReward.module.daily.DailyRewards;
import fr.eternom.eterReward.module.daily.DailyService;
import fr.eternom.eterReward.module.daily.DailyStore;
import fr.eternom.eterReward.module.pass.PassCatalog;
import fr.eternom.eterReward.module.pass.PassService;
import fr.eternom.eterReward.module.pass.PassStore;
import fr.eternom.eterReward.module.ranking.Podium;
import fr.eternom.eterReward.module.ranking.PodiumCommand;
import fr.eternom.eterReward.module.ranking.RankingPlaceholders;
import fr.eternom.eterReward.module.ranking.RankingService;
import fr.eternom.eterReward.module.ranking.RankingStore;
import fr.eternom.eterReward.module.vote.VoteRewards;
import fr.eternom.eterReward.module.vote.VoteService;
import fr.eternom.eterReward.module.vote.VoteStore;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.DateTimeException;
import java.time.ZoneId;

/** Récompenses du réseau : la récompense quotidienne (/daily), le pass de saison (/pass), les défis, les classements et les votes. */
public final class Main extends JavaPlugin {

    /** Version minimale d'EterLib : textes communs et outils partagés (Frame, Money, NetworkBus) depuis 1.6.0. */
    private static final String REQUIRED_ETERLIB = "1.14.0";

    /** Préfixe des tables d'EterReward dans la base commune : eterreward_daily, eterreward_pass_... */
    private static final String TABLE_PREFIX = "eterreward_";

    /** Paliers de chaque pass. */
    private static final int PASS_TIERS = 30;

    private Messages messages;
    private DailyService daily;
    private PassService pass;
    private ChallengeService challenges;
    private RankingService ranking;
    private VoteService votes;
    /** /eterreward podium, null sans EterNpc. */
    private TabExecutor podiumCommand;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // En premier : vérifie la version d'EterLib (un EterLib < 1.3.0 n'a pas requireVersion, d'où le catch)
        try {
            if (!EterLib.requireVersion(this, REQUIRED_ETERLIB)) {
                return;
            }
        } catch (LinkageError tooOld) {
            getLogger().severe("EterLib " + REQUIRED_ETERLIB + " ou plus récent est nécessaire.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        EterLib lib = EterLib.get();
        messages = lib.messages(this, "en_us", "fr_fr");
        Database database = lib.database(TABLE_PREFIX);
        ZoneId zone = zone();

        pass = new PassService(this, new PassStore(database), PassCatalog.load(this, PASS_TIERS), PassService.Settings.load(getConfig()),
                messages, zone, lib.backButton(getConfig().getString("menus.pass.back-command", "")));
        daily = new DailyService(this, new DailyStore(database),
                DailyRewards.load(getConfig().getConfigurationSection("daily.days"), getLogger()), messages, zone,
                lib.backButton(getConfig().getString("menus.daily.back-command", "")), pass);
        ChallengeCatalog.Catalog catalog = ChallengeCatalog.load(this);
        challenges = new ChallengeService(this, new ChallengeStore(database), catalog.pools(), catalog.counts(), pass, messages,
                zone, lib.backButton(getConfig().getString("menus.challenges.back-command", "menu progression")));
        pass.setChallengesMenu(challenges::open);
        challenges.start();
        ranking = new RankingService(this, new RankingStore(database), pass, messages, zone,
                lib.backButton(getConfig().getString("menus.ranking.back-command", "menu progression")),
                RankingService.Settings.load(getConfig(), getLogger()));
        challenges.setOnComplete(ranking::challengeCompleted);
        // Podium du classement général (PNJ d'EterNpc) et placeholders (PlaceholderAPI) : seulement s'ils tournent ici
        if (getServer().getPluginManager().isPluginEnabled("EterNpc")) {
            Podium podium = new Podium(this, messages);
            ranking.onGeneral(podium::update);
            podiumCommand = new PodiumCommand(podium, messages);
        }
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new RankingPlaceholders(this, ranking).register();
        }
        ranking.start();
        votes = new VoteService(this, new VoteStore(database), VoteRewards.load(getConfig().getConfigurationSection("votes"), getLogger()),
                pass, ranking, messages, zone, getConfig().getBoolean("votes.announce", true));

        // API pour les autres plugins (RewardApi.get())
        getServer().getServicesManager().register(RewardApi.class, new RewardApiService(daily, pass, challenges), this, ServicePriority.Normal);

        new Commands(this);
        new Events(this);
    }

    /** Fuseau du changement de jour et de mois (daily.time-zone), Europe/Paris si invalide. */
    private ZoneId zone() {
        String zone = getConfig().getString("daily.time-zone", "Europe/Paris");
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException e) {
            getLogger().warning("daily.time-zone invalide (" + zone + "), Europe/Paris utilisé");
            return ZoneId.of("Europe/Paris");
        }
    }

    public Messages getMessages() {
        return messages;
    }

    public DailyService getDaily() {
        return daily;
    }

    public PassService getPass() {
        return pass;
    }

    public ChallengeService getChallenges() {
        return challenges;
    }

    /** /eterreward podium, null sans EterNpc. */
    public TabExecutor getPodiumCommand() {
        return podiumCommand;
    }

    public RankingService getRanking() {
        return ranking;
    }

    public VoteService getVotes() {
        return votes;
    }
}
