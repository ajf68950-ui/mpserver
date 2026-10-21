# mpserver

games and services

## Minecraft (Meedia SMP)

Eigen plugins voor de Meedia SMP-server (Paper 26.2, Java 25).

| Plugin | Map | Wat het doet |
|---|---|---|
| **Vaulty** | `minecraft/plugins/vaulty` | Kluis voor spelers (`/kluis`), koppeling met de website en een spelersmarkt |
| **Statusbord** | `minecraft/plugins/statusbord` | Scorebord aan de zijkant met serverinfo, wereld, geld en armor (`/statusbord`) |
| **Pocketly** | `minecraft/plugins/pocketly` | Drops gaan automatisch in je inventaris in skyblock en OneBlock (`/pickup`) |
| **Muntly** | `minecraft/plugins/muntly` | Eigen munt (Munten): verdienen met speeltijd en dagbeloning, muntenwinkel, lootboxen en quests, ook via de website (`/munten`, `/quests`). Vanaf 1.2.0 ook rangen (LuckPerms-groepen) kopen en geven via de website, en de LuckPerms-editor vanuit het beheer |
| **Toolwise** | `minecraft/plugins/toolwise` | Pakt automatisch het beste gereedschap of wapen uit je hotbar (`/toolwise`) |
| **Ordely** | `minecraft/plugins/ordely` | Regels: geld en items geven alleen via de website, niets uit creative verkopen (ook creative-blokken en -mobs leveren niets op). Gevangeniswereld met 32 cellen, opsluiten en vrijlaten (ook automatisch na waarschuwingen), logboek met reden, te gebruiken vanuit het beheer van de website (`/gevangenis`). Economie (1.1.0): verkopen alleen via de GUI (Opkoper en winkel) in Skyblock en OneBlock en alleen in survival, getypte verkoopcommando's werken voor niemand, geld werkt alleen in de survivalwereld, Skyblock en OneBlock, vangnet voor Essentials-verkopen en QuickShop-handel in creative. Poorten (1.2.0): `/poort` maakt van elk stuk muur (bijv. 3x3, 1 blok dik) een automatische poort die in een keer opengaat als er een speler dichtbij komt en weer dichtgaat als iedereen weg is; de blokken zijn beschermd tegen slopen, TNT en zuigers |

### Downloaden

De kant-en-klare jars staan in [`minecraft/plugins/releases`](minecraft/plugins/releases), met een
`index.json` die per plugin de nieuwste versie en de controlecode (SHA-1) noemt.
`Update-plugins.bat` op de server leest die index en haalt nieuwe versies automatisch op.

### Zelf bouwen

```
cd minecraft/plugins/vaulty      (of statusbord, pocketly, toolwise, muntly, ordely)
mvn package
```

Het jar-bestand komt in `target/`.

### Scripts voor de server

In [`minecraft/scripts`](minecraft/scripts):

- `Start-alles.bat` en `Start-alles inc  s backup.bat` (in de hoofdmap van de server): servers en website afsluiten, backup, eigen plugins bijwerken, alles starten.
- `eigen-plugins-bijwerken.ps1` (in `Resources`): zet de nieuwste jars uit `Resources\eigen-plugins` in `MPServerSMP\plugins`, met de juiste naam; een nieuwere versie in `plugins` gaat ook terug naar `Resources\eigen-plugins`.
