# mpserver

games and services

## Minecraft (Meedia SMP)

Eigen plugins voor de Meedia SMP-server (Paper 26.2, Java 25).

| Plugin | Map | Wat het doet |
|---|---|---|
| **Vaulty** | `minecraft/plugins/vaulty` | Kluis voor spelers (`/kluis`), koppeling met de website en een spelersmarkt |
| **Statusbord** | `minecraft/plugins/statusbord` | Scorebord aan de zijkant met serverinfo, wereld, geld en armor (`/statusbord`) |

### Downloaden

De kant-en-klare jars staan bij de [releases](../../releases) met een tag die begint met `minecraft-plugins-`.
`Update-plugins.bat` op de server haalt nieuwe versies hier automatisch op.

### Zelf bouwen

```
cd minecraft/plugins/vaulty      (of statusbord)
mvn package
```

Het jar-bestand komt in `target/`.
