# Embers Peek (Exii TXT1) dla Minecraft 26.3

`embers_peek_26.3.zip` to paczka „6;00 embers peek” z 1.8.9 przerobiona na Java Edition 26.3
(format paczki zasobów 97). Wrzuć zip do folderu `resourcepacks` i włącz ją w grze.

Paczkę sprawdziłem w prawdziwym kliencie 26.3: wczytuje się bez błędów i bez ostrzeżenia
o niezgodnej wersji.

## Co przeszło

- **Bloki**: wszystkie tekstury dostały nowe nazwy (np. `wool_colored_red` → `red_wool`,
  `stonebrick` → `stone_bricks`). Animacje (woda, lawa, ogień, portal) zostały zachowane.
- **Łóżko**: w 26.3 łóżka znów są zwykłymi blokami, więc łóżko z paczki działa. Działa tylko
  czerwone, bo w 1.8 był jeden kolor.
- **Woda**: od 1.13 tekstura wody jest szara, a kolor nadaje jej biom. Woda z paczki została
  więc przerobiona na szarość z zachowaną przezroczystością, inaczej wyszłaby za ciemna.
- **Przedmioty**: miecze, kilofy, łuk, wędka, mikstury (zwykłe i splash), złote jabłko, jedzenie, wiadra.
- **Zbroja**: przeniesiona do `entity/equipment/humanoid` (warstwa 1) i `humanoid_leggings` (warstwa 2).
- **HUD**: serca (z wariantami: hardcore, trucizna, wither, absorpcja, wierzchowiec), jedzenie,
  zbroja, bąbelki powietrza, pasek XP, pasek skoku konia, pasek bossa, celownik, hotbar
  z ramką wyboru i slot drugiej ręki.
- **GUI**: przyciski, suwaki, ekwipunek, skrzynie, piec, stół rzemieślniczy, stół do zaklęć,
  stół alchemiczny, kowadło, beacon, lejek, dozownik, koń, zakładki trybu kreatywnego,
  ikony efektów i statystyki.
  - W ekwipunku siatka craftingu została przesunięta tam, gdzie jest od 1.12.
  - Dorysowany jest slot drugiej ręki.
  - W stole alchemicznym dorysowany jest slot na blaze powder.
- **Cząsteczki**: m.in. krytyki, ostrze z zaklęciem, dym, serca, nuty, iskry fajerwerków
  i runy ze stołu do zaklęć.
- **Czcionka**: czcionka HD z `mcpatcher/font` (w 1.8 działała przez OptiFine) jest teraz
  zwykłą czcionką gry.
- **Panorama** menu głównego.

## Co działa tylko z modami

Rzeczy z `mcpatcher/` zostały przeniesione do `optifine/` w formacie dla nowych wersji.
Sama gra ich nie czyta. Działają z OptiFine albo z modami Fabric:

- `optifine/ctm/glass/`: łączone szkło, np. przez Continuity.
- `optifine/sky/world0/`: własne niebo, np. przez FabricSkyBoxes.
- `optifine/colormap/` i `optifine/color.properties`: kolory wody, trawy w bagnach i mikstur,
  np. przez Polytone albo Colormatic.

## Czego nie dało się przenieść

- Ekran handlu z wieśniakiem: w 1.14 został zbudowany od nowa.
- Osiągnięcia: w 1.12 zastąpiono je postępami.
- Logo i ekran ładowania Mojang.
- Strony czcionki unicode (`unicode_page_*`): gra już ich nie obsługuje.
- Kopie i śmieci: `icons — kopia.png`, `in2ventory.png`, `inven2tory.png`, `items/1.png`,
  `Thumbs.db`, `.DS_Store`, `inventory.pdn`.
- Pliki identyczne z grą 1.8.9, które w 26.3 mają inny układ: logo tytułowe, książka, tło opcji
  i ekran paczek zasobów. W tych miejscach zostają tekstury z 26.3.

## Konwerter

`convert_1.8_to_26.3.py` robi to samo z dowolną paczką z 1.8.9:

    pip install pillow numpy
    python3 convert_1.8_to_26.3.py "stara_paczka.zip" client-26.3.jar nowa_paczka.zip

`client-26.3.jar` to oficjalny plik gry od Mojang (`.minecraft/versions/26.3/26.3.jar`).
Konwerter nie kopiuje z niego żadnych tekstur. Bierze z niego tylko ustawienia `.mcmeta`
(np. jak rozciągać przyciski) i sprawdza na nim, czy każdy zapisany plik jest takim, który
26.3 faktycznie wczytuje.

# Orange Edit (Wembarka Urnak) dla Minecraft 26.3

`orange_edit_26.3.zip` to paczka „Orange_Edit2_26.3”. Format paczki (97) ustawił już
packsmc.com, ale część rzeczy nadal była w folderze OptiFine CIT, którego sama gra nie czyta.

- **Miecz z knockbackiem**: w 26.3 działa bez modów, przez wbudowane modele przedmiotów
  (`assets/minecraft/items/diamond_sword.json`).
  - Zwykły diamentowy miecz jest pomarańczowy.
  - Knockback I jest jasnoniebieski.
  - Knockback II i wyżej jest niebieski.
  - Działa też, gdy miecz ma inne enchanty, np. Sharpness V + Knockback I.
- **Enchantowane książki**: każdy z 30 enchantów ma swoją ikonę, też bez modów.
- **Połysk zbroi**: packsmc zostawił starą nazwę `enchanted_glint_entity.png`. Przemianowałem
  ją na `enchanted_glint_armor.png`, której 26.3 szuka.
- **Usunięte pliki**, których 26.3 nie czyta: `particle/particles.png` (krytyki z paczki zostały,
  są w osobnych plikach), `.packsmc/conversion_id` i kopia vanillowego `atlases/items.json`.
- **Bez zmian**: przedmioty rozpoznawane po nazwie albo NBT z Loki (miecze Ascalon, Garama,
  Kalros, łuki, ikony z menu, krew, butelki XP itd.) zostały w `optifine/cit`. Działają tylko
  z modem czytającym CIT (np. CIT Resewn), bo gra nie umie dopasować przedmiotu po nazwie.

Wszystko powyżej sprawdziłem w kliencie 26.3. Dla porównania dałem sobie miecze: bez enchantów,
z KB1, z KB2, z Sharpness V + KB1, z samym Sharpness V i z KB3 + Unbreaking III.

`cit_enchantments_to_26.3.py` robi to samo z dowolną paczką: zamienia reguły CIT oparte tylko
na enchantach na modele przedmiotów 26.3.

    python3 cit_enchantments_to_26.3.py "paczka.zip" client-26.3.jar nowa_paczka.zip
