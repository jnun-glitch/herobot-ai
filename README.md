# HeroBot PvP Training

Fabric-Mod für Minecraft 1.21.11. Sie ergänzt HeroBot um einen manuell startbaren PvP-Trainingsgegner, Debug-Telemetrie, Kits, intelligentes Essen, Rückzugsverhalten und ein persistentes tabellarisches Q-Learning-Modell.

## Installation

- Fabric Loader 0.18.5+ und Fabric API 0.141.6+ installieren.
- HeroBot für Minecraft 1.21.11 auf dem Server installieren (im Einzelspieler auf derselben Instanz).
- Diese Mod auf Server und Client installieren. ModMenu 17.x ist optional und wird nur für den Feedback-Bildschirm benötigt.
- In einer Welt Cheats aktivieren oder den Server mit Operatorrechten verwalten.

Die Mod nutzt HeroBots dokumentierte Befehle `/playerspawn` und `/player`. HeroBot-Fake-Spieler respawnen laut Projektbeschreibung automatisch, sofern HeroBots Einstellung `botLeaveOnDeath` nicht aktiviert ist. [HeroBot-Dokumentation](https://modrinth.com/mod/herobot)

## Render Studio

`tools/render-studio/index.html` ist eine lokale Vorschau-App für die HeroBot-Menüs. Öffne die Datei in einem Browser, wähle Startseite, Kit-Auswahl oder Bestätigung, stelle Auflösung und GUI-Skalierung ein und speichere das Ergebnis über **PNG-Render speichern**. **Layouts testen** prüft die Menü-Geometrie für gängige Auflösungen. Die App rendert UI-Entwürfe und startet Minecraft nicht.

Der Layout-Test lässt sich ohne zusätzliche Node-Pakete starten:

    node tools/render-studio/test-layouts.cjs

## Direktes Duell

    /hba
    /hba kit gui
    /hba spawn
    /hba spawn TrainingsBot Spielername
    /hba spawn kit diamond TrainingsBot Spielername
    /hba spawn kit mace TrainingsBot Spielername
    /hba kit diamond
    /hba kit diamond Spielername

`/hba` öffnet das Control Center. `/hba kit` oder `/hba kit gui` öffnet die Kit-Auswahl mit Vorschau. Die GUI kann ein Kit an dich oder einen ausgewählten Spieler vergeben; vor dem Ersetzen des Inventars erscheint eine Bestätigung. `/hba kit list` zeigt die verfügbaren Kits als Chattext. Die GUI benötigt diese Mod auch auf dem Client und auf dem Server.

Ohne Kit kopiert der Bot Inventar, Rüstung, Offhand und Hotbar-Auswahl des Gegners. Er kopiert ausdrücklich nicht dessen Action-Pack oder Tasteneingaben. In einem Duell steuert ausschließlich der Kampfcontroller Bot-Aktionen; er verfolgt den Gegner, greift an, springt bei Bedarf und lernt seine Bewegungswahl anhand von Abstand, Lebenspunkten, ob das Ziel blockt, eigener Waffe und ausgeteiltem/eingestecktem Schaden.

Der Bot lernt zusätzlich aus deinem Spiel: Aus deiner Bewegung im Kampf wird abgeleitet, welche Aktion du ausgeführt hast (Annäherung, Seitwärts, Rückzug, Halten), und in die Q-Tabelle des gewählten Kits geschrieben — aber nur mit positiven Belohnungen: Schaden, den du dem Bot machst, und +12 für deinen Sieg. Deine Fehler werden nie bestraft. Der Zustand berücksichtigt Abstand, Lebenspunkte, Waffenklasse, Boden/Luft, Blocken, Axtverfügbarkeit, Sichtlinie, Blickrichtung, Angriffscooldown und Elytra-Gleitflug. Jedes Kit lernt separat. Die Erkundungsrate sinkt mit der Zeit von 0,18 auf 0,03, damit der Bot gelerntes statt Zufall anwendet.

`/hba kit <Kit>` gibt dir das gewählte Kit direkt, ohne einen Bot zu spawnen. Optional kann ein Spielername als Ziel angegeben werden: `/hba kit <Kit> <Spielername>`; die umgekehrte Reihenfolge wird ebenfalls akzeptiert. Das Kit ersetzt das bisherige Inventar des Empfängers. Die Waffe liegt danach im ersten Hotbar-Slot.

Im direkten Duell verfolgt der Bot Ziele, die weiter als 2,45 Blöcke entfernt sind, richtet den Blick direkt auf das Ziel und führt zeitlich gesteuerte Einzelschläge aus. Wenn die installierte HeroBot-Version `player <Bot> path entity <Ziel>` anbietet, verwendet er diesen Wegfinder bei Hindernissen und wiederholt festgefahrene Pfade. Ohne den Befehl erkennt er Stillstand schneller; bleibt er trotz gemeldetem Wandkontakt stehen, startet die Ausweichhilfe bereits nach der ersten solchen Bewegungsprüfung. Dabei wählt er einen freien Seitenschritt, der keine Vorwärtsbewegung in dieselbe Wand erzwingt und auf festem Boden bleibt. Auch ein blockierter Rückzug löst diese Ausweichhilfe aus. Beim Crystal-Kit bleibt er auf Nahkampf und Crystal-PvP ausgelegt, statt auf die Armbrust zu wechseln. Ein gelernter oder zufällig gewählter Rückzug wird bei hoher Gesundheit unterbunden. Bei einem Gegner im Kreativmodus zeigt der Start einen Hinweis, weil dieser keinen normalen Kampfschaden nimmt. Die Kampfdaten protokollieren erkannte Bot- und Zielschäden getrennt.

HeroBot kann den Fake-Spieler erst nach einer asynchronen Profilabfrage in die Spielerliste übernehmen. Die Mod wartet deshalb bis zu 20 Sekunden auf den Beitritt und startet Ausrüstung sowie KI erst danach. `/hba status` zeigt währenddessen den Spawn-Fortschritt; `/hba stop` oder `/hba remove` bricht die Anfrage ab und entfernt einen verspätet beitretenden Bot. Botnamen müssen 3–16 Buchstaben, Zahlen oder Unterstriche enthalten.

## Autonome Trainingsarena

Die Arena wurde entfernt. `/hba arena` und `config/herobot-pvp-bot.json` existieren nicht mehr; das Modul beschränkt sich auf das direkte Duell.

## Befehle

- `/hba` oder `/hba menu` — Control Center öffnen.
- `/hba kit` oder `/hba kit gui` — Kit-GUI mit Vorschau öffnen.
- `/hba kit list` — Kits als Chattext anzeigen.
- `/hba status` — Duell-Status, Kit, Q-Learning-Zähler.
- `/hba stop` — aktiven Kampf anhalten.
- `/hba remove [BotName]` — aktiven oder benannten HeroBot entfernen.
- `/hba kit <Kit> [Spieler]` — Preset direkt an dich oder einen Spieler vergeben; kein Bot erforderlich.
- `/hba kits` — Kit-GUI öffnen.
- `/hba help` — Befehlsübersicht.

Im Nahkampf greift er waffenabhaengig zum richtigen Moment an (Schwert ab 11, Axt ab 17 Ticks) und springt auf 2,5 Bloecke fast vor jedem Schlag. Der Angriff erfolgt in der fallenden Phase und wird als Crit-Absicht protokolliert (`crit_intent=true`); ob Minecraft alle Crit-Bedingungen erfuellt, muss im Spiel beobachtet werden. Nach jedem Treffer setzt er den Sprint kurz ab (`wtap=true`). Das Q-Learning-Modell entscheidet die Nahkampfbewegung selbst; sein Zustand umfasst Waffenklasse, Abstand, Lebenspunkte, Boden-/Luftstatus, Blocken, Axtverfügbarkeit, Sichtlinie, Blickrichtung, Angriffscooldown und Elytra-Gleitflug.

Ein Elytra-Mace-Manöver startet automatisch, sobald der Bot tatsächlich eine angelegte Elytra, eine Mace und Feuerwerksraketen im Inventar hat – also auch bei einem kopierten Inventar. Bei freiem Höhenkorridor steigt der Bot für einen Gleitflug auf, nutzt bis zu zwei Raketen und richtet anschließend einen Sturzangriff auf das Ziel. Nach dem Manöver übernimmt derselbe gemeinsame Kampfcontroller. Bei zu wenig freier Höhe überspringt er den Flug und kämpft am Boden. Das Manöver funktioniert im direkten Duell `/hba spawn ...`.

### Item-Nutzung und Slot-Wechsel

Der Bot wechselt aktiv zwischen seinen Hotbar-Slots und nutzt die mitgeführten Items situationsabhängig. Bei Verbrauchs- und Fernkampfitems durchsucht die Mod alle 36 Inventarplätze. Liegt ein benötigtes Item im Rucksack, tauscht die Mod es vorübergehend mit dem ausgewählten Hotbar-Slot, lässt HeroBot die Aktion ausführen und legt den verbleibenden Stack sowie den verdrängten Gegenstand danach zurück. Dafür braucht es keinen zusätzlichen `/player`-Inventarbefehl.

- **Bogen / Armbrust (Fernkampf):** Ab 8 Blöcken Abstand nimmt er zuerst eine geladene Armbrust, sonst einen Bogen mit Munition. Der Bogen wird 20 Ticks gespannt und zum Schuss freigegeben; eine ungeladene Armbrust wird geladen, getrennt abgefeuert und bei vorhandener Munition wieder geladen. Bogen und Armbrust dürfen überall im Inventar liegen. Bei 5 Blöcken stoppt der Fernkampf. Ohne freie Sicht oder passende Munition schiesst er nicht. Das Crystal-Kit nutzt diesen Fernkampf nicht.
- **Splash-Potions:** Ohne Stärke- oder Schnelligkeitseffekt wirft er beim Kampfbeginn die passende Splash-Potion unter seine Füße (Potion-Typ wird über die Item-Komponente erkannt).
- **Wind Charge / Enderperle:** Werden auch aus dem Rucksack geholt und nach dem Wurf samt verdrängtem Hotbar-Item zurücksortiert.
- **XP-Flaschen / Mending:** Ab 8 Blöcken Abstand wirft der Bot im Kampf nur dann einzelne XP-Flaschen, wenn ein angelegtes oder gehaltenes Mending-Teil beschädigt ist und gerade kein unmittelbarer Gegnerdruck besteht. Das funktioniert auch während eines sicheren Rückzugs; die XP-Kugeln reparieren Rüstung sowie gehaltene Mending-Ausrüstung. Unbeschädigte oder nur im Rucksack liegende Gegenstände lösen keinen Wurf aus.
- **Rückzug:** Unter 8 Leben weicht er bis auf etwa 7 Blöcke aus und bleibt dort stehen, bis er sich erholt hat. In dieser Phase wirft er keine Enderperle.
- **Totem:** Bei einem Crystal-Kit, niedrigen Lebenspunkten oder kürzlich erlittenem Schaden bekommt ein verfügbares Totem Vorrang in der Offhand. Bei akuter Lebensgefahr kann ein zweites Totem vorübergehend in die Haupthand kommen; sobald die Gefahr sinkt, wird die vorherige Waffe zurückgelegt. Ein Reservertotem ersetzt ein verbrauchtes Offhand-Totem sofort.
- **Schild:** Bleibt die Standard-Offhand, wenn gerade kein Totem-Schutz Vorrang hat. Gegen einen blockenden Gegner wählt der Bot die Axt, sofern verfügbar.

### Nahkampf-Taktiken (aus Wiki-Recherche, minecraft.wiki)

- **Waffen-Cooldown-Gate:** Der Bot berechnet die Angriffsschwelle aus dem aktiven Attack-Speed-Attribut. Das berücksichtigt die tatsächliche Waffe und Effekte; Speer-Jabs warten auf volle Aufladung, andere Nahkampfangriffe auf mindestens 84,8 %.
- **W-Tap (Sprint-Reset):** Sprint bleibt bis zum Bot-Tick des Schlages an. Danach setzt der Bot Sprint für 2 Ticks zurück und startet ihn für den folgenden Sprint-Knockback-Schlag erneut. Crits bleiben ein eigener Pfad, weil Sprint-Knockback in Java Vorrang vor einem Crit hat.
- **Crit-Spam auf kurzer Distanz:** Bis 2,5 Blöcke springt er vor fast jedem Schlag und schlägt in der fallenden Phase (1,5-facher Schaden, rund 1,6 Crit/s). Auf weiterer Distanz behält er das alte Muster „alle 3 Schwünge" bei, weil der Sprungzyklus dort sonst den Angriffs-Rhythmus ausbremst.
- **Schild-Stunning:** Blockt der Gegner, schlägt er zweimal im selben Tick zu (`attack twice`) — vorausgesetzt HeroBots `shieldStunning` ist aktiv (setzt die Mod beim Kampfstart).
- **Crystal-PvP / D-Tap:** Obsidian und Endkristalle dürfen im gesamten Inventar liegen. Basis und Kristall werden in getrennten Ticks bestätigt. Der Bot setzt den Kristall nur mit Totem in der Offhand, freiem 2×2×2-Platz und einer Position, die näher am Gegner als am Bot liegt. Nach einem kürzlichen Treffer mit Rückstoß versucht er optional einen zweiten Kristall am selben Block, sobald das erste Trefferfenster vorbei ist. Bei fehlendem Platz, zu hohem Eigenrisiko oder fehlendem Totem wird abgebrochen. Die Reihenfolge orientiert sich an der [D-Tap-Anleitung](https://mctrainer.club/guides/d-tap); Totem-Prioritäten an [Totem Management](https://mctrainer.club/guides/totem-management).
- **Enderperlen:** Nach dem Wurf setzt sie den Perlen-Cooldown sofort zurück (`itemCd reset`), kann also sofort wieder geworfen werden.

## Debugging und manuelles Feedback

Das Kampf-Log in `config/herobot-ai-training/fight-data.csv` protokolliert Waffenwechsel, Schild-Heben/Senken, Heilungs- und Essensstarts, Rückzug, Perlen- und Crystal-Einsatz sowie jeden Lernschritt mit Zustand, Aktion und Belohnung. Der Fernkampf meldet `RANGE_START`, `RANGE_LOADED`, `RANGE_FIRE` und `RANGE_STOP`; Crystal-Ereignisse unterscheiden zwischen angefordertem und bestätigtem Platzieren. Beim Kampfstart setzt die Mod HeroBots `shieldStunning` und `noProjectileRandom`, damit `attack twice` Schilder stunnt und Projektilstreuung deaktiviert ist.

Der ModMenu-Bildschirm lässt dich neun Fähigkeiten auf **Unbekannt**, **Kann** oder **Kann nicht** setzen und Beobachtungen ergänzen. **Bericht kopieren** sammelt Feedback und Diagnoseinformationen. Diese manuellen Markierungen werden gespeichert und mit dem Bericht ausgegeben; sie ändern die Q-Learning-Policy nicht automatisch.

Lokale Dateien:

- `config/herobot-ai-training/fight-data.csv` — Kampf- und Debugereignisse; rotiert ab 5 MB in eine `.1`-Datei.
- `config/herobot-ai-training/qtable-v2-<kit>.properties` — persistente tabellarische Q-Werte je Kit. Die alte `qtable.properties` bleibt unangetastet.
- `config/herobot-ai-training/training.properties` — manuelles Fähigkeitenfeedback.
- `config/herobot-ai-training/debug.log` — Client-Diagnose und UI-Aktionen.

Auf einem dedizierten Server liegen Kampf-Log und Q-Modell serverseitig. Sie werden nicht automatisch hochgeladen; der clientseitig kopierte Bericht kann das serverseitige Kampf-Log dort nicht einfügen.

## Grenzen

Das Modell ist tabulares Q-Learning, kein neuronales Netz. Es lernt Bewegungsentscheidungen aus Kampfbelohnungen sowie — nur positiv — aus den Aktionen des menschlichen Gegners; Essen, Rückzug, Schild, Waffenwechsel und Crystal-Einsatz sind regelbasierte Zusätze. Nahkampfangriffe werden manuell getaktet; nur während des Mace-Sturzangriffs läuft HeroBots Angriff kontinuierlich. Wegfindung hängt von der installierten HeroBot-Version ab; Umwege können an nicht erreichbaren Zielen scheitern. Spawn-Schutz wird nicht erkannt. Die Änderungen wurden kompiliert; ein Live-Duell wurde hier nicht ausgeführt.

## Build

Voraussetzung: JDK 21. Im Projektordner:

    .\gradlew.bat remapJar

Das fertige JAR liegt unter `build/libs/`.
