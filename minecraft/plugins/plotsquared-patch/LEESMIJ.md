# PlotSquared 7.6.0, aangepast voor Paper 26.2

De officiële PlotSquared 7.6.0 (GPL-3, commit 90fc81f "Release 7.6.0") stuurt op Paper 26.2 geen chatberichten,
en bij het binnenlopen van een plot wordt de speler gekickt (lege titel-tekst, "Failed to encode packet").
Oorzaak: adventure-platform-bukkit werkt niet op Paper 26.x.

Deze patch voegt `NativeAudience` toe: berichten, actiebalk en titels gaan via JSON naar de eigen Adventure-API van Paper.

Bouwen:
```
git clone https://github.com/IntellectualSites/PlotSquared && cd PlotSquared
git checkout 90fc81f
git apply plotsquared-7.6.0-paper26.patch
./gradlew :plotsquared-bukkit:shadowJar      # Java 21
```
Resultaat: `Bukkit/build/libs/plotsquared-bukkit-7.6.0.jar`. Getest op Paper 26.2: berichten, titels, kopen, terugverkopen.
