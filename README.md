# HeroBot PvP Training

Fabric-Mod für Minecraft 1.21.11. Sie ergänzt HeroBot um einen manuell startbaren PvP-Trainingsgegner, Debug-Telemetrie, Kits, intelligentes Essen, Rückzugsverhalten und ein persistentes tabellarisches Q-Learning-Modell.

## Installation

- Fabric Loader 0.18.5+ und Fabric API 0.141.6+ installieren.
- HeroBot für Minecraft 1.21.11 auf dem Server installieren (im Einzelspieler auf derselben Instanz).
- Diese Mod auf Server und Client installieren. ModMenu 17.x ist optional und wird nur für den Feedback-Bildschirm benötigt.
- In einer Welt Cheats aktivieren oder den Server mit Operatorrechten verwalten.

Die Mod nutzt HeroBots dokumentierte Befehle `/playerspawn` und `/player`. HeroBot-Fake-Spieler respawnen laut Projektbeschreibung automatisch, sofern HeroBots Einstellung `botLeaveOnDeath` nicht aktiviert ist. [HeroBot-Dokumentation](https://modrinth.com/mod/herobot)

## Direktes Duell

    /hba spawn
    /hba spawn TrainingsBot Spielername
    /hba spawn kit diamond TrainingsBot Spielername
    /hba spawn kit mace TrainingsBot Spielername
    /hba kit
    /hba kit diamond
    /hba kit diamond Spielername

Ohne Kit kopiert der Bot Inventar, Rüstung, Offhand und Hotbar-Auswahl des Gegners. Er kopiert ausdrücklich nicht dessen Action-Pack oder Tasteneingaben. In einem Duell steuert ausschließlich der Kampfcontroller Bot-Aktionen; er verfolgt den Gegner, greift an, springt bei Bedarf und lernt seine Bewegungswahl anhand von Abstand, Lebenspunkten, ob das Ziel blockt, eigener Waffe und ausgeteiltem/eingestecktem Schaden.

Der Bot lernt zusätzlich aus deinem Spiel: Aus deiner Bewegung im Kampf wird abgeleitet, welche Aktion du ausgeführt hast (Annäherung, Seitwärts, Rückzug, Halten), und in dieselbe Q-Tabelle geschrieben — aber nur mit positiven Belohnungen: Schaden, den du dem Bot machst, und +12 für deinen Sieg. Deine Fehler werden nie bestraft. Die Erkundungsrate sinkt mit der Zeit von 0,18 auf 0,03, damit der Bot gelerntes statt Zufall anwendet.

`/hba kit` zeigt die verfügbaren Preset-Kits. `/hba kit <Kit>` gibt dir das gewählte Kit direkt, ohne einen Bot zu spawnen. Optional kann ein Spielername als Ziel angegeben werden: `/hba kit <Kit> <Spielername>`; die umgekehrte Reihenfolge wird ebenfalls akzeptiert. Das Kit ersetzt das bisherige Inventar des Empfängers. Die Waffe liegt danach im ersten Hotbar-Slot.

Im direkten Duell verfolgt der Bot Ziele, die weiter als 2,45 Blöcke entfernt sind, richtet den Blick direkt auf das Ziel und führt zeitlich gesteuerte Einzelschläge aus. Bei einem Gegner im Kreativmodus zeigt der Start einen Hinweis, weil dieser keinen normalen Kampfschaden nimmt. Die Kampfdaten protokollieren erkannte Bot- und Zielschäden getrennt.

HeroBot kann den Fake-Spieler erst nach einer asynchronen Profilabfrage in die Spielerliste übernehmen. Die Mod wartet deshalb bis zu 20 Sekunden auf den Beitritt und startet Ausrüstung sowie KI erst danach. `/hba status` zeigt währenddessen den Spawn-Fortschritt; `/hba stop` oder `/hba remove` bricht die Anfrage ab und entfernt einen verspätet beitretenden Bot. Botnamen müssen 3–16 Buchstaben, Zahlen oder Unterstriche enthalten.

## Autonome Trainingsarena

Die Arena wurde entfernt. `/hba arena` und `config/herobot-pvp-bot.json` existieren nicht mehr; das Modul beschränkt sich auf das direkte Duell.

## Befehle

- `/hba status` — Duell-Status, Kit, Q-Learning-Zähler.
- `/hba stop` — aktiven Kampf anhalten.
- `/hba remove [BotName]` — aktiven oder benannten HeroBot entfernen.
- `/hba kit [Kit] [Spieler]` — verfügbare Kits anzeigen oder ein Preset direkt an einen Spieler vergeben; kein Bot erforderlich.
- `/hba kits` — Kits anzeigen.
- `/hba help` — Befehlsübersicht.

Im Nahkampf greift er waffenabhaengig zum richtigen Moment an (Schwert ab 11, Axt ab 17 Ticks) und springt auf 2,5 Bloecke fast vor jedem Schlag. Der Angriff erfolgt in der fallenden Phase und wird als Crit-Absicht protokolliert (`crit_intent=true`); ob Minecraft alle Crit-Bedingungen erfuellt, muss im Spiel beobachtet werden. Nach jedem Treffer setzt er den Sprint kurz ab (`wtap=true`). Das Q-Learning-Modell entscheidet die Nahkampfbewegung selbst; sein Zustand beschreibt Abstand, HP-Veraeltnis, Luftstatus, ob das Ziel blockt, die eigenen HP und ob eine Axt verfuegbar ist.

Ein Elytra-Mace-Manöver startet automatisch, sobald der Bot tatsächlich eine angelegte Elytra, eine Mace und Feuerwerksraketen im Inventar hat – also auch bei einem kopierten Inventar. Bei freiem Höhenkorridor steigt der Bot für einen Gleitflug auf, nutzt bis zu zwei Raketen und richtet anschließend einen Sturzangriff auf das Ziel. Nach dem Manöver übernimmt derselbe gemeinsame Kampfcontroller. Bei zu wenig freier Höhe überspringt er den Flug und kämpft am Boden. Das Manöver funktioniert im direkten Duell `/hba spawn ...`.

### Item-Nutzung und Slot-Wechsel

Der Bot wechselt aktiv zwischen seinen Hotbar-Slots und nutzt die mitgeführten Items situationsabhängig:

- **Bogen / Armbrust (Fernkampf):** Ab 8 Blöcken Abstand greift er nach Bogen oder Armbrust (Armbrust hat Vorrang, da bereits geladen), hält die Benutzung und schiesst, bis das Ziel auf 5 Blöcke heran ist. Er bleibt beim Schiessen stehen und läuft nicht ins Messer. Ohne Pfeile im Inventar greift er nicht zum Bogen.
- **Splash-Potions:** Ohne Stärke- oder Schnelligkeitseffekt wirft er beim Kampfbeginn die passende Splash-Potion unter seine Füße (Potion-Typ wird über die Item-Komponente erkannt).
- **Wind Charge:** Beim Rückzug unter 4 Herzen wirft er eine Wind Charge, um per Rückstoß Abstand zu gewinnen.
- **Axt gegen Schild und Totem in den Offhand:** wie zuvor, unverändert.

### Nahkampf-Taktiken (aus Wiki-Recherche, minecraft.wiki)

- **Waffen-Cooldown-Gate:** Er schlägt nicht mehr starr alle 12 Ticks, sondern erst, wenn die konkrete Waffe wieder bereit ist (Schwert 11, Axt 17, Mace 28, Speer 12 Ticks) — also ab ca. 84,8 % ihres Cooldowns. Unter dieser Schwelle wäre der Schlag ein normaler Treffer ohne Crit, Sweep und Knockback.
- **W-Tap (Sprint-Reset):** Nach jedem Treffer lässt er 2 Ticks den Sprint los. Ein laufender Spieler-Schlag drückt das Ziel weiter weg; erst nach dem Neustarten des Sprints trifft der nächste Schlag mit vollem Knockback. Das Bewegungs-Update respektiert dieses Fenster und schaltet den Sprint nicht vorzeitig wieder an.
- **Crit-Spam auf kurzer Distanz:** Bis 2,5 Blöcke springt er vor fast jedem Schlag und schlägt in der fallenden Phase (1,5-facher Schaden, rund 1,6 Crit/s). Auf weiterer Distanz behält er das alte Muster „alle 3 Schwünge" bei, weil der Sprungzyklus dort sonst den Angriffs-Rhythmus ausbremst.
- **Schild-Stunning:** Blockt der Gegner, schlägt er zweimal im selben Tick zu (`attack twice`) — vorausgesetzt HeroBots `shieldStunning` ist aktiv (setzt die Mod beim Kampfstart).
- **Enderperlen:** Nach dem Wurf setzt sie den Perlen-Cooldown sofort zurück (`itemCd reset`), kann also sofort wieder geworfen werden.

## Debugging und manuelles Feedback

Das Kampf-Log in `config/herobot-ai-training/fight-data.csv` protokolliert Waffenwechsel, Schild-Heben/Senken, Heilungs- und Essensstarts, Rückzug, Perlen- und Crystal-Einsatz sowie jeden Lernschritt mit Zustand, Aktion und Belohnung. Neu dazugekommen: `RANGE_DRAW`/`RANGE_STOP` (Bogen/Armbrust im Fernkampf), `SPLASH_BUFF` (Stärke/Schnelligkeit geworfen), `WIND_CHARGE` (Rückstoß beim Rückzug), `SHIELD_STUN` (Doppelschlag gegen blockende Gegner) und `COMBAT_SETTINGS` (shieldStunning + noProjectileRandom beim Kampfstart). Beim Kampfstart setzt die Mod zusätzlich HeroBots `shieldStunning` und `noProjectileRandom`, damit `attack twice` Schilder stunnt und die Bogenschüsse treffen.

Der ModMenu-Bildschirm lässt dich neun Fähigkeiten auf **Unbekannt**, **Kann** oder **Kann nicht** setzen und Beobachtungen ergänzen. **Bericht kopieren** sammelt Feedback und Diagnoseinformationen. Diese manuellen Markierungen werden gespeichert und mit dem Bericht ausgegeben; sie ändern die Q-Learning-Policy nicht automatisch.

Lokale Dateien:

- `config/herobot-ai-training/fight-data.csv` — Kampf- und Debugereignisse; rotiert ab 5 MB in eine `.1`-Datei.
- `config/herobot-ai-training/qtable.properties` — persistente tabellarische Q-Werte.
- `config/herobot-ai-training/training.properties` — manuelles Fähigkeitenfeedback.
- `config/herobot-ai-training/debug.log` — Client-Diagnose und UI-Aktionen.

Auf einem dedizierten Server liegen Kampf-Log und Q-Modell serverseitig. Sie werden nicht automatisch hochgeladen; der clientseitig kopierte Bericht kann das serverseitige Kampf-Log dort nicht einfügen.

## Grenzen

Das Modell ist tabulares Q-Learning, kein neuronales Netz. Es lernt Bewegungsentscheidungen aus Kampfbelohnungen sowie — nur positiv — aus den Aktionen des menschlichen Gegners; Essen, Rückzug, Schild, Waffenwechsel und der Crystal-Einsatz sind regelbasierte Zusätze. Nahkampfangriffe werden manuell getaktet; nur während des Mace-Sturzangriffs läuft HeroBots Angriff kontinuierlich. Komplexe Hindernisse, echte Umwege, Spawn-Schutz-Erkennung und vollständiges Pathfinding sind nicht enthalten. Die Crystal-Routine setzt voraus, dass HeroBot `use` auf dem Boden vor dem Bot, `use` auf der Obsidian-Basis und `attack` auf dem Kristall ausführt; das muss im Spiel bestätigt werden.

## Build

Voraussetzung: JDK 21. Im Projektordner:

    .\gradlew.bat remapJar

Das fertige JAR liegt unter `build/libs/`.
