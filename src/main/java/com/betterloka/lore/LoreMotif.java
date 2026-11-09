package com.betterloka.lore;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a lore can be about, with the words that give it away — in English and Polish — and the
 * sentences and names it is written from.
 *
 * <p>The Polish sentences never refer back to the item by a pronoun or a participle: those would have
 * to agree with its gender, and a sword ({@code miecz}), an axe ({@code topór}) and a shield
 * ({@code tarcza}) do not share one.
 */
public enum LoreMotif {
    FIRE(List.of("fire", "flame", "ember", "burn", "blaze", "inferno", "lava", "magma", "dragon", "sun", "ash",
            "ogien", "ognia", "ognist", "plomie", "plomien", "zar", "pozog", "smok", "smocz", "lawa", "slonc", "popiol", "spal"),
            "Embers", "Żaru",
            List.of("Forged in the heart of a dying volcano.",
                    "The embers of an ancient forge still glow within.",
                    "Dragonfire left its mark on every edge.",
                    "Whoever carries this flame shall never know cold.",
                    "Every strike scatters the sparks of a forgotten war.",
                    "Even the rain hisses when it draws near."),
            List.of("Narodzone w sercu konającego wulkanu.",
                    "Żar dawnej kuźni wciąż tli się w środku.",
                    "Smoczy ogień zostawił tu swój ślad.",
                    "Kto niesie ten płomień, nie zazna chłodu.",
                    "Każde uderzenie sypie iskrami zapomnianej wojny.",
                    "Nawet deszcz syczy, gdy się zbliża.")),
    ICE(List.of("ice", "frost", "snow", "cold", "winter", "glacier", "freez", "blizzard",
            "lod", "lodu", "mroz", "snieg", "sniez", "zim", "chlod", "zamiec"),
            "Frost", "Mrozu",
            List.of("Carved from a glacier older than the first kings.",
                    "Its touch turns breath to frost and blood to ice.",
                    "The north wind still whispers through it.",
                    "Snow falls silent wherever it is drawn.",
                    "No fire has ever melted what it guards.",
                    "Winter itself bent the knee to its maker."),
            List.of("Wyciosane z lodowca starszego niż pierwsi królowie.",
                    "Jeden dotyk zamienia oddech w szron, a krew w lód.",
                    "Północny wiatr wciąż szepcze w jego wnętrzu.",
                    "Śnieg milknie wszędzie tam, gdzie się pojawi.",
                    "Żaden ogień nie stopił tego, czego strzeże.",
                    "Sama zima ugięła kolano przed twórcą.")),
    SHADOW(List.of("shadow", "dark", "night", "shade", "assassin", "stealth", "silent", "moon",
            "cien", "mrok", "noc", "ciemn", "skrytob", "ksiezyc", "cisz"),
            "Shadows", "Cienia",
            List.of("Born where no light has ever reached.",
                    "It moves before its wielder does.",
                    "The night keeps its secrets, and this is one of them.",
                    "Those it finds never hear it coming.",
                    "Moonless skies are its only witness.",
                    "Darkness clings to it like a cloak."),
            List.of("Zrodzone tam, gdzie nie dotarło żadne światło.",
                    "Porusza się, zanim drgnie dłoń właściciela.",
                    "Noc strzeże swoich tajemnic, a to jedna z nich.",
                    "Ci, których odnajdzie, nigdy nie słyszą kroków.",
                    "Bezksiężycowe niebo to jedyny świadek.",
                    "Mrok otula je niczym płaszcz.")),
    BLOOD(List.of("blood", "vampir", "crimson", "kill", "slaughter", "hunt", "red",
            "krew", "krwi", "krwaw", "wampir", "rzez", "szkarlat", "polowan", "zabij"),
            "Blood", "Krwi",
            List.of("Tempered in the blood of a hundred foes.",
                    "It thirsts, and it is never satisfied.",
                    "Crimson runes wake when battle begins.",
                    "Every scar on it has a name.",
                    "The fallen still whisper its price.",
                    "It remembers every life it has taken."),
            List.of("Hartowane we krwi stu wrogów.",
                    "Pragnie, a jego pragnienia nic nie gasi.",
                    "Szkarłatne runy budzą się wraz z bitwą.",
                    "Każda rysa na nim ma swoje imię.",
                    "Polegli wciąż szepczą o jego cenie.",
                    "Pamięta każde odebrane życie.")),
    NATURE(List.of("nature", "forest", "tree", "leaf", "wild", "root", "flower", "druid", "beast", "wolf",
            "las", "lesn", "drzew", "lisc", "natur", "kwiat", "puszcz", "korzen", "zwierz", "wilk", "dzik"),
            "the Wild", "Puszczy",
            List.of("Grown, not forged, in the oldest grove of Loka.",
                    "Roots still stir beneath its surface.",
                    "The beasts of the forest bow as it passes.",
                    "It smells of rain, moss and wild honey.",
                    "Spring returns wherever it is laid to rest.",
                    "The ancient trees remember its first song."),
            List.of("Wyrosłe, nie wykute, w najstarszym gaju Loki.",
                    "Pod powierzchnią wciąż poruszają się korzenie.",
                    "Leśne zwierzęta kłaniają się na jego widok.",
                    "Pachnie deszczem, mchem i dzikim miodem.",
                    "Tam, gdzie spocznie, powraca wiosna.",
                    "Pradawne drzewa pamiętają jego pierwszą pieśń.")),
    SEA(List.of("sea", "ocean", "water", "wave", "tide", "deep", "pirate", "ship", "fish", "coral",
            "morz", "morsk", "wod", "fal", "glebin", "pirat", "statek", "okret", "ryb", "koral"),
            "the Deep", "Głębin",
            List.of("Pulled from the wreck of a sunken flagship.",
                    "The tide rises when it is raised.",
                    "Salt and coral cling to it still.",
                    "Sailors swear it hums with the voice of the deep.",
                    "No storm has ever drowned its bearer.",
                    "It was the ocean's gift, and the ocean wants it back."),
            List.of("Wydobyte z wraku zatopionego okrętu flagowego.",
                    "Przypływ rośnie, gdy zostaje uniesione.",
                    "Wciąż lgną do niego sól i koral.",
                    "Żeglarze przysięgają, że nuci głosem głębin.",
                    "Żaden sztorm nie zatopił jeszcze właściciela.",
                    "To dar oceanu, a ocean chce go z powrotem.")),
    STORM(List.of("storm", "thunder", "lightning", "wind", "sky", "tempest", "cloud", "spark",
            "burz", "piorun", "grom", "wiatr", "nieb", "blyskaw", "chmur", "iskr"),
            "Storms", "Burzy",
            List.of("Struck by lightning on the highest peak of Loka.",
                    "Thunder answers when it is raised to the sky.",
                    "The air crackles wherever it is carried.",
                    "It holds the fury of a hundred storms.",
                    "Winds gather and the clouds grow dark.",
                    "Its strike falls faster than the eye can follow."),
            List.of("Trafione piorunem na najwyższym szczycie Loki.",
                    "Grom odpowiada, gdy zostaje uniesione ku niebu.",
                    "Powietrze trzeszczy wszędzie, gdzie się pojawi.",
                    "Kryje w sobie furię stu burz.",
                    "Wiatry gęstnieją, a chmury ciemnieją.",
                    "Uderza szybciej, niż nadąży wzrok.")),
    HOLY(List.of("holy", "light", "angel", "divine", "god", "bless", "sacred", "heaven", "saint", "paladin",
            "swiet", "swiatl", "aniol", "bog", "niebios", "blogoslaw", "swiety", "paladyn"),
            "Light", "Światła",
            List.of("Blessed at dawn by the last of the old priests.",
                    "It shines brightest in the darkest hour.",
                    "No curse can touch the hand that holds it.",
                    "Angels once sang over its making.",
                    "Its light reveals what darkness hides.",
                    "The faithful kneel when they see its glow."),
            List.of("Pobłogosławione o świcie przez ostatniego z dawnych kapłanów.",
                    "Lśni najjaśniej w najczarniejszej godzinie.",
                    "Żadna klątwa nie dosięgnie dłoni, która je trzyma.",
                    "Anioły śpiewały niegdyś nad jego powstaniem.",
                    "Jego blask odsłania to, co skrywa ciemność.",
                    "Wierni klękają, widząc jego poświatę.")),
    VOID(List.of("void", "end", "ender", "star", "cosmic", "space", "abyss", "dimension", "portal", "galax",
            "pustk", "gwiazd", "kosm", "otchlan", "wymiar", "galakt"),
            "the Void", "Pustki",
            List.of("It came through a rift that has never closed.",
                    "Stars die quietly inside it.",
                    "The End whispers to those who listen too long.",
                    "It weighs nothing, and yet the world bends around it.",
                    "No map shows the place it was found.",
                    "Silence follows it like a shadow."),
            List.of("Przybyło przez szczelinę, która nigdy się nie zamknęła.",
                    "W jego wnętrzu po cichu gasną gwiazdy.",
                    "End szepcze do tych, którzy słuchają zbyt długo.",
                    "Nic nie waży, a jednak świat ugina się wokół.",
                    "Żadna mapa nie wskazuje miejsca znaleziska.",
                    "Cisza podąża za nim jak cień.")),
    DEATH(List.of("death", "soul", "undead", "grave", "bone", "skull", "curse", "poison", "toxic", "necro", "ghost", "plague",
            "smierc", "dusz", "nieumar", "grob", "kosc", "czaszk", "klatw", "trucizn", "jad", "duch", "zaraz", "nekro"),
            "the Fallen", "Poległych",
            List.of("Cursed by a necromancer with his final breath.",
                    "The souls it has claimed still wander inside.",
                    "Its poison works slowly, and never fails.",
                    "Graves open when it passes by at night.",
                    "Death walks a step behind its bearer.",
                    "Bone and ash are all its enemies leave."),
            List.of("Przeklęte przez nekromantę ostatnim tchnieniem.",
                    "Zabrane dusze wciąż błąkają się w środku.",
                    "Jego trucizna działa powoli, lecz nigdy nie zawodzi.",
                    "Groby otwierają się, gdy przechodzi nocą.",
                    "Śmierć kroczy krok za właścicielem.",
                    "Po wrogach zostają tylko kości i popiół.")),
    ROYAL(List.of("king", "queen", "royal", "crown", "gold", "throne", "empire", "noble", "prince", "rich", "treasure",
            "krol", "koron", "zlot", "tron", "imperi", "cesarz", "szlach", "ksiaz", "skarb", "bogat"),
            "Kings", "Królów",
            List.of("Once carried by the first king of Loka.",
                    "Gold and gemstones crown its every line.",
                    "Only the worthy may claim it as their own.",
                    "Kingdoms rose and fell around it.",
                    "Its value is counted in castles, not coins.",
                    "The royal seal still marks it as untouchable."),
            List.of("Niegdyś nosił je pierwszy król Loki.",
                    "Złoto i klejnoty zdobią każdą linię.",
                    "Tylko godni mogą nazwać je swoim.",
                    "Królestwa rosły i upadały w jego cieniu.",
                    "Jego wartość liczy się w zamkach, nie w monetach.",
                    "Królewska pieczęć wciąż czyni je nietykalnym.")),
    WAR(List.of("war", "battle", "conquest", "soldier", "siege", "warrior", "army", "champion", "victory", "fight",
            "wojn", "bitw", "wojownik", "zolnierz", "oblezen", "podboj", "armi", "czempion", "zwyciest", "walk"),
            "Conquest", "Podboju",
            List.of("It has seen more battles than any living soldier.",
                    "Carried at the front of every great siege.",
                    "Victory follows it like a loyal hound.",
                    "Its edge was sharpened on the walls of fallen towns.",
                    "Warriors fight harder when it is near.",
                    "It was made for one thing: conquest."),
            List.of("Widziało więcej bitew niż jakikolwiek żyjący żołnierz.",
                    "Niesione na czele każdego wielkiego oblężenia.",
                    "Zwycięstwo podąża za nim jak wierny ogar.",
                    "Ostrzone na murach upadłych miast.",
                    "Wojownicy walczą zacieklej, gdy jest blisko.",
                    "Stworzone dla jednego celu: podboju.")),
    TIME(List.of("time", "ancient", "old", "age", "forgotten", "legend", "eternal", "ruin", "relic", "history",
            "czas", "pradaw", "staroz", "zapomnian", "legend", "wieczn", "ruin", "relikw", "histori", "dawn"),
            "Ages", "Wieków",
            List.of("Older than the oldest town on the map.",
                    "Forgotten by history, remembered by legend.",
                    "Dust of a hundred ages has settled on it.",
                    "Its maker's name was lost long ago.",
                    "Time seems to slow wherever it rests.",
                    "It has outlived every one of its owners."),
            List.of("Starsze niż najstarsze miasto na mapie.",
                    "Zapomniane przez historię, zapamiętane przez legendy.",
                    "Pokrywa je kurz stu epok.",
                    "Imię twórcy zaginęło dawno temu.",
                    "Czas zdaje się zwalniać tam, gdzie spoczywa.",
                    "Przeżyło każdego ze swoich właścicieli.")),
    LOVE(List.of("love", "heart", "friend", "promise", "gift", "memory", "wedding", "kiss",
            "milosc", "kocha", "serc", "przyjaz", "przyjaci", "obietnic", "prezent", "dar", "wspomnien", "slub", "pocalun"),
            "the Heart", "Serca",
            List.of("A gift given with a promise to return.",
                    "It carries a memory no one else can see.",
                    "Two names are carved where only the bearer can read them.",
                    "It grows warm when a friend is near.",
                    "Some treasures are measured in loyalty, not gold.",
                    "It was made for one person, and only one."),
            List.of("Dar wręczony z obietnicą powrotu.",
                    "Niesie wspomnienie, którego nikt inny nie widzi.",
                    "Wyryto na nim dwa imiona, czytelne tylko dla właściciela.",
                    "Robi się ciepłe, gdy w pobliżu jest przyjaciel.",
                    "Niektóre skarby mierzy się wiernością, nie złotem.",
                    "Stworzone dla jednej osoby i tylko dla niej.")),
    LEGEND(List.of(),
            "Legends", "Legend",
            List.of("Its story is told in every tavern on Loka.",
                    "Few have held it, and fewer have let it go.",
                    "It waits for a hand worthy of its name.",
                    "Crafted with a skill the world has since forgotten.",
                    "Heroes are remembered for carrying it.",
                    "Its true power has never been fully seen."),
            List.of("Jego historię opowiada się w każdej karczmie Loki.",
                    "Niewielu je trzymało, jeszcze mniej oddało.",
                    "Czeka na dłoń godną jego imienia.",
                    "Wykonane z kunsztem, który świat już zapomniał.",
                    "Bohaterów pamięta się za to, że je nosili.",
                    "Jego prawdziwej mocy nikt jeszcze nie ujrzał w pełni."));

    private final List<String> keywords;
    private final String nounEn;
    private final String nounPl;
    private final List<String> sentencesEn;
    private final List<String> sentencesPl;

    LoreMotif(List<String> keywords, String nounEn, String nounPl, List<String> sentencesEn, List<String> sentencesPl) {
        this.keywords = keywords;
        this.nounEn = nounEn;
        this.nounPl = nounPl;
        this.sentencesEn = sentencesEn;
        this.sentencesPl = sentencesPl;
    }

    /** {@code Embers}, for a title: {@code Blade of Embers}. */
    public String noun(boolean polish) {
        return polish ? nounPl : nounEn;
    }

    public List<String> sentences(boolean polish) {
        return polish ? sentencesPl : sentencesEn;
    }

    /** The motifs a description mentions, the most mentioned first; none when it names none. */
    public static List<LoreMotif> in(String description) {
        String text = fold(description);
        List<LoreMotif> found = new ArrayList<>();
        List<Integer> hits = new ArrayList<>();
        for (LoreMotif motif : values()) {
            int count = 0;
            for (String keyword : motif.keywords) {
                if (containsWord(text, keyword)) {
                    count++;
                }
            }
            if (count > 0) {
                int at = 0;
                while (at < hits.size() && hits.get(at) >= count) {
                    at++;
                }
                found.add(at, motif);
                hits.add(at, count);
            }
        }
        return found;
    }

    /** Whether a word in the text begins with {@code stem}: {@code ognia} has {@code ogn}, {@code tlen} has no {@code len}. */
    static boolean containsWord(String folded, String stem) {
        int from = 0;
        while (true) {
            int at = folded.indexOf(stem, from);
            if (at < 0) {
                return false;
            }
            if (at == 0 || !Character.isLetter(folded.charAt(at - 1))) {
                return true;
            }
            from = at + 1;
        }
    }

    /** Lower case, Polish letters to their plain ones: {@code Ogień} to {@code ogien}. */
    public static String fold(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT).replace('ł', 'l');
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
