plugins {
    id("java-library")
    id("maven-publish")
}

repositories {
    // PaperMC en premier : Maven Central limite les téléchargements (429)
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
    // Plugins Eter (EterLib, API des autres plugins) : le jar de leur release GitHub (publiée par la CI à chaque tag)
    ivy {
        url = uri("https://github.com/Eternom/")
        patternLayout { artifact("[module]/releases/download/[revision]/[module]-[revision].[ext]") }
        metadataSources { artifact() }
        content { includeGroup("com.github.Eternom") }
    }
    // PlaceholderAPI (placeholders du classement général)
    maven("https://repo.extendedclip.com/releases/") { content { includeGroup("me.clip") } }
    // Autres dépendances publiées sur JitPack (VaultAPI...)
    maven("https://jitpack.io") { content { excludeGroup("com.github.Eternom") } }
    // Repli : EterLib publié sur cette machine (`gradlew publishToMavenLocal` dans EterLib), pour tester avant de pousser
    mavenLocal()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")

    // Socle commun : base, langues, menus (plugin EterLib installé sur le serveur)
    compileOnly("com.github.Eternom:EterLib:1.18.0")
    // Inventaire en cours de chargement (SyncApi) : aucun objet donné avant (softdepend)
    compileOnly("com.github.Eternom:EterSync:1.2.2")
    // Podium du classement général (PNJ) et placeholders, facultatifs
    compileOnly("com.github.Eternom:EterNpc:1.0.0")
    compileOnly("me.clip:placeholderapi:2.11.6")
    // Argent : l'API d'EterEconomy (chaque mouvement avec sa source)
    compileOnly("com.github.Eternom:EterEconomy:2.3.0")
    // XP du pass pour les quêtes de métier : l'événement d'EterMarket (softdepend)
    compileOnly("com.github.Eternom:EterMarket:1.7.0")
    // Clés et temps de monde ressource : l'API d'EterResource (softdepend)
    compileOnly("com.github.Eternom:EterResource:1.1.0")
    // Permissions temporaires (récompenses, premium) : l'API de LuckPerms (softdepend)
    compileOnly("net.luckperms:api:5.4")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    processResources {
        val props = mapOf("version" to version)
        // Déclarée comme entrée : sinon le cache de Gradle réutilise un plugin.yml avec l'ancienne version
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}

// Après chaque build, copie le jar dans <eterPluginsDir>/paper et y supprime l'ancienne version de ce plugin.
// eterPluginsDir se règle dans ~/.gradle/gradle.properties (propre à ta machine) : sans lui, rien n'est copié (JitPack...).
val deployPlugin by tasks.registering(Copy::class) {
    description = "Copie le jar dans le dossier de plugins local (eterPluginsDir)"
    // Variables locales à la tâche : le cache de configuration de Gradle refuse les variables du script
    val eterPluginsDir = providers.gradleProperty("eterPluginsDir")
    val enabled = eterPluginsDir.isPresent
    onlyIf { enabled }
    val jarName = tasks.jar.flatMap { it.archiveBaseName }
    from(tasks.jar)
    into(eterPluginsDir.map { "$it/paper" }.orElse(layout.buildDirectory.dir("deploy").map { it.asFile.path }))
    doFirst {
        destinationDir.listFiles { file -> file.name.startsWith(jarName.get() + "-") && file.name.endsWith(".jar") }
            ?.forEach { it.delete() }
    }
}
tasks.build { finalizedBy(deployPlugin) }

// Publié pour les autres plugins (son API, fr.eternom.eterReward.api) : compileOnly("com.github.Eternom:EterReward:<tag>")
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "EterReward"
            from(components["java"])
        }
    }
}
