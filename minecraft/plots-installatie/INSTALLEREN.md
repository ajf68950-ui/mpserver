# Plots (De Stad) installeren op MeediaSMP

Getest op de 26.2-testserver op 14-10-2026. **Server eerst uit.** Paden zijn relatief aan `MSP 10 - Claude\MeediaSMP`.

1. `plugins\PlotSquared-Bukkit-7.6.0.jar` overschrijven met de jar uit deze map (gepatcht, sha1 dfb2e0e681ad590dbc7b7e99515b445151ef7f48). Zonder deze patch krijgen spelers geen berichten en worden ze gekickt bij het binnenlopen van een plot.
2. `plugins\PlotSquared\config\settings.yml`: onder `enabled-components` → `economy: true`. `titles.display-titles` en `titles-as-actionbar` laten staan (true / false).
3. `plugins\PlotSquared\config\worlds.yml`: de inhoud van `worlds-plots-sectie.yml` toevoegen onder `worlds:` (sleutel `plots`). Bestaat `plots` al, die sectie vervangen.
4. `plugins\PlotSquared\schematics\GEN_ROAD_SCHEMATIC\plots\sideroad.schem` en `intersection.schem` neerzetten (stadswegen).
5. `plugins\PlotSquared\lang\messages_en.json` (bestaat na eerste start), drie sleutels:
   - `"titles.title_entered_plot": "<gold>Plot <plot></gold>"`
   - `"titles.title_entered_plot_sub": "<dark_aqua>van <owner></dark_aqua>"`
   - `"core.prefix": "<dark_gray>[</dark_gray><gold>Stad</gold><dark_gray>] </dark_gray>"`
6. `plugins\DeluxeMenus\gui_menus\plotmenu.yml` neerzetten en in `plugins\DeluxeMenus\config.yml` onder `gui_menus:` toevoegen:
   ```
     plotmenu:
       file: plotmenu.yml
   ```
7. Server starten, dan in de console / in game:
   - `mv create plots normal --generator PlotSquared` (pas NA stap 3 en 4)
   - `mv modify plots set gamemode creative` (indien nodig)
   - `lp group default permission set plots.permpack.basic true` en `lp group default permission set plots.plot.2 true`
   - NPC: `/npc create &6&lStad`, `/npc command add -p stad`, `/citizens save`

Prijzen (in worlds.yml): kopen $25.000, samenvoegen $25.000, terugverkopen geeft $10.000.
Vaulty, QuickShop en Muntly hebben de wereld `plots` al uitgesloten.
