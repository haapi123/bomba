package com.betterloka.assistantserver;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what Loka has published into {@code sources/}, one text file per page: the website (from its
 * sitemap), the first post of every thread in the forum's announcement section, and the wiki through
 * its API. Politely: it says who it is, keeps to {@code robots.txt}, waits between requests and
 * backs off when told to. A page already read is skipped, so running it again only fetches what is
 * new.
 *
 * <p>Not read: the forum's members, search and other paths its robots.txt closes, Reddit (closed to
 * crawlers), and anything behind a login.
 */
final class Crawler {
    static final String SITE = "https://lokamc.com";
    static final String FORUM = "https://forums.lokamc.com";
    static final String WIKI_API = "https://loka.fandom.com/api.php";
    static final String WIKI = "https://loka.fandom.com/wiki/";
    /** Site pages that are tools or account pages, not information. */
    static final Set<String> SITE_SKIP = Set.of("/map", "/accounts", "/link", "/cart", "/lore", "/resource-packs");

    private static final Pattern THREAD_LINK = Pattern.compile("^/threads/[^/]+\\.(\\d+)/$");

    private final ServerConfig.Crawl settings;
    private final Path sources;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Map<String, Robots> robots = new HashMap<>();
    private final Map<String, Long> lastRequest = new HashMap<>();
    private int fetched;
    private int written;

    Crawler(ServerConfig.Crawl settings, Path sources) {
        this.settings = settings;
        this.sources = sources;
    }

    void run() throws IOException, InterruptedException {
        Files.createDirectories(sources);
        if (settings.site) {
            site();
        }
        if (settings.forum) {
            for (int forumId : settings.forumIds) {
                forum(forumId);
            }
        }
        if (settings.wiki) {
            wiki();
        }
        Log.info("Crawl done: " + fetched + " requests, " + written + " new pages in " + sources.toAbsolutePath());
    }

    // ---- the website ----

    private void site() throws IOException, InterruptedException {
        String sitemap = get(SITE + "/sitemap.xml");
        if (sitemap == null) {
            Log.warn("No sitemap at " + SITE);
            return;
        }
        Matcher matcher = Pattern.compile("<loc>\\s*([^<\\s]+)\\s*</loc>").matcher(sitemap);
        List<String> urls = new ArrayList<>();
        while (matcher.find()) {
            urls.add(matcher.group(1).replace("&amp;", "&"));
        }
        Log.info("Website: " + urls.size() + " pages in the sitemap");
        for (String url : urls) {
            String path = URI.create(url).getPath();
            if (SITE_SKIP.contains(path) || path.startsWith("/leaderboards")) {
                continue;
            }
            Path file = sources.resolve("site").resolve(slug(path.isEmpty() || path.equals("/") ? "home" : path) + ".md");
            if (Files.exists(file)) {
                continue;
            }
            String html = get(url);
            if (html == null) {
                continue;
            }
            Document doc = Jsoup.parse(html, url);
            Element main = doc.selectFirst("main");
            String text = HtmlText.of(main != null ? main : doc.body());
            String title = doc.title().replaceAll("\\s*[—|-]\\s*Loka.*$", "").strip();
            String date = "";
            Element published = doc.selectFirst("time[datetime], meta[itemprop=datePublished]");
            if (published != null) {
                date = published.hasAttr("datetime") ? published.attr("datetime") : published.attr("content");
            }
            write(file, title, url, "site", date(date), text);
        }
    }

    // ---- the forum's announcements ----

    private void forum(int forumId) throws IOException, InterruptedException {
        Set<String> threads = new LinkedHashSet<>();
        String base = FORUM + "/forums/" + forumId + "/";
        for (int page = 1; page <= 100 && threads.size() < settings.maxForumThreads; page++) {
            String url = page == 1 ? base : base + "page-" + page;
            String html = get(url);
            if (html == null) {
                break;
            }
            Document doc = Jsoup.parse(html, url);
            int before = threads.size();
            for (Element link : doc.select(".structItem-title a[href]")) {
                String href = URI.create(link.absUrl("href")).getPath();
                if (THREAD_LINK.matcher(href).matches()) {
                    threads.add(FORUM + href);
                }
            }
            if (threads.size() == before || doc.selectFirst("a.pageNav-jump--next") == null) {
                break;
            }
        }
        Log.info("Forum section " + forumId + ": " + threads.size() + " threads");
        int count = 0;
        for (String url : threads) {
            if (count++ >= settings.maxForumThreads) {
                break;
            }
            Matcher id = THREAD_LINK.matcher(URI.create(url).getPath());
            String threadId = id.matches() ? id.group(1) : slug(url);
            Path file = sources.resolve("forum").resolve(forumId + "-" + threadId + ".md");
            if (Files.exists(file)) {
                continue;
            }
            String html = get(url);
            if (html == null) {
                continue;
            }
            Document doc = Jsoup.parse(html, url);
            Element first = doc.selectFirst("article.message--post");
            if (first == null) {
                continue;
            }
            Element body = first.selectFirst(".bbWrapper");
            Element time = first.selectFirst("time.u-dt[datetime]");
            // The page's own h1 is not always the thread's: a logged-out notice can come first.
            Element ogTitle = doc.selectFirst("meta[property=og:title]");
            String title = ogTitle != null ? ogTitle.attr("content") : doc.title().replaceAll("\\s*\\|\\s*Loka Forums$", "");
            String author = first.attr("data-author");
            String text = (author.isEmpty() ? "" : "Posted by " + author + ".\n\n") + (body != null ? HtmlText.of(body) : "");
            write(file, title, url, "forum", date(time != null ? time.attr("datetime") : ""), text);
        }
    }

    // ---- the wiki, through its API ----

    private void wiki() throws IOException, InterruptedException {
        Map<Long, String> pages = new LinkedHashMap<>();
        String from = "";
        while (pages.size() < settings.maxWikiPages) {
            String url = WIKI_API + "?action=query&list=allpages&apnamespace=0&apfilterredir=nonredirects&aplimit=500&format=json"
                    + (from.isEmpty() ? "" : "&apcontinue=" + URLEncoder.encode(from, StandardCharsets.UTF_8));
            String json = get(url);
            if (json == null) {
                return;
            }
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (JsonElement page : root.getAsJsonObject("query").getAsJsonArray("allpages")) {
                pages.put(page.getAsJsonObject().get("pageid").getAsLong(), page.getAsJsonObject().get("title").getAsString());
            }
            JsonObject next = root.getAsJsonObject("continue");
            if (next == null || !next.has("apcontinue")) {
                break;
            }
            from = next.get("apcontinue").getAsString();
        }
        Log.info("Wiki: " + pages.size() + " pages");
        Map<Long, String> touched = wikiDates(new ArrayList<>(pages.keySet()));
        int count = 0;
        for (Map.Entry<Long, String> page : pages.entrySet()) {
            if (count++ >= settings.maxWikiPages) {
                break;
            }
            Path file = sources.resolve("wiki").resolve(slug(page.getValue()) + ".md");
            if (Files.exists(file)) {
                continue;
            }
            String json = get(WIKI_API + "?action=parse&pageid=" + page.getKey()
                    + "&prop=text&disableeditsection=1&disabletoc=1&formatversion=2&format=json");
            if (json == null) {
                continue;
            }
            JsonObject parse = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("parse");
            if (parse == null || !parse.has("text")) {
                continue;
            }
            Document doc = Jsoup.parseBodyFragment(parse.get("text").getAsString());
            String text = HtmlText.of(doc.body());
            if (text.length() < 80) {
                continue;
            }
            write(file, page.getValue(), WIKI + URLEncoder.encode(page.getValue().replace(' ', '_'), StandardCharsets.UTF_8),
                    "wiki", date(touched.getOrDefault(page.getKey(), "")), text);
        }
    }

    /** When each wiki page was last edited, fifty pages to a request. */
    private Map<Long, String> wikiDates(List<Long> ids) throws IOException, InterruptedException {
        Map<Long, String> dates = new HashMap<>();
        for (int i = 0; i < ids.size(); i += 50) {
            StringBuilder list = new StringBuilder();
            for (Long id : ids.subList(i, Math.min(ids.size(), i + 50))) {
                list.append(list.length() == 0 ? "" : "|").append(id);
            }
            String json = get(WIKI_API + "?action=query&prop=info&format=json&pageids=" + URLEncoder.encode(list.toString(), StandardCharsets.UTF_8));
            if (json == null) {
                continue;
            }
            JsonObject pages = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("query").getAsJsonObject("pages");
            for (Map.Entry<String, JsonElement> entry : pages.entrySet()) {
                JsonObject page = entry.getValue().getAsJsonObject();
                if (page.has("touched")) {
                    dates.put(Long.parseLong(entry.getKey()), page.get("touched").getAsString());
                }
            }
        }
        return dates;
    }

    // ---- fetching ----

    /** The page's body, or null if it may not or could not be fetched. */
    String get(String url) throws InterruptedException {
        URI uri = URI.create(url);
        String host = uri.getHost();
        boolean api = url.startsWith(WIKI_API);
        if (!api && !robots(uri).allows(uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()))) {
            return null;
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            pace(host);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", settings.userAgent)
                    .header("Accept-Language", "en")
                    .GET()
                    .build();
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.warn("Could not fetch " + url + ": " + e.getMessage());
                Thread.sleep(5000L * (attempt + 1));
                continue;
            }
            fetched++;
            int status = response.statusCode();
            if (status == 200) {
                return response.body();
            }
            if (status == 429 || status >= 500) {
                long wait = response.headers().firstValue("Retry-After").filter(v -> v.matches("\\d+"))
                        .map(Long::parseLong).orElse(10L * (attempt + 1));
                Log.warn(url + " answered " + status + ", waiting " + wait + " s");
                Thread.sleep(Math.min(wait, 120) * 1000);
                continue;
            }
            Log.warn(url + " answered " + status);
            return null;
        }
        return null;
    }

    private Robots robots(URI uri) throws InterruptedException {
        String origin = uri.getScheme() + "://" + uri.getHost();
        Robots known = robots.get(origin);
        if (known != null) {
            return known;
        }
        Robots parsed;
        pace(uri.getHost());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(origin + "/robots.txt"))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", settings.userAgent)
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            fetched++;
            if (response.statusCode() == 200) {
                parsed = Robots.parse(response.body(), settings.userAgent);
            } else if (response.statusCode() >= 400 && response.statusCode() < 500) {
                // No robots.txt is no restriction; a challenge page in its place is not one either,
                // but then the pages behind it would not answer anyway.
                parsed = Robots.allowAll();
            } else {
                parsed = Robots.disallowAll();
            }
        } catch (IOException e) {
            parsed = Robots.disallowAll();
        }
        robots.put(origin, parsed);
        return parsed;
    }

    private void pace(String host) throws InterruptedException {
        long now = System.currentTimeMillis();
        long last = lastRequest.getOrDefault(host, 0L);
        long wait = last + settings.delayMillis - now;
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequest.put(host, System.currentTimeMillis());
    }

    private void write(Path file, String title, String url, String kind, String date, String text) throws IOException {
        if (text == null || text.isBlank()) {
            return;
        }
        Files.createDirectories(file.getParent());
        String header = "# " + title.strip() + "\nSource: " + url + "\nKind: " + kind + "\nDate: "
                + (date.isEmpty() ? "unknown" : date) + "\n\n";
        Files.writeString(file, header + text.strip() + "\n", StandardCharsets.UTF_8);
        written++;
        if (written % 25 == 0) {
            Log.info("  " + written + " pages so far");
        }
    }

    /** {@code 2026-03-14} from an ISO time or a MediaWiki timestamp, or empty. */
    static String date(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return OffsetDateTime.parse(value.strip()).toLocalDate().toString();
        } catch (RuntimeException e) {
            Matcher matcher = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})").matcher(value);
            return matcher.find() ? matcher.group(1) : "";
        }
    }

    static String slug(String text) {
        String slug = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.isEmpty()) {
            slug = "page";
        }
        return slug.length() > 80 ? slug.substring(0, 80) : slug;
    }

    /** All source files under {@code sources/}, as read back by the distiller. */
    record Source(String title, String url, String kind, String date, String text) {
        int estimatedTokens() {
            return (int) Math.ceil(text.length() / 4.0) + 30;
        }
    }

    static List<Source> readAll(Path sources) throws IOException {
        List<Source> all = new ArrayList<>();
        if (!Files.isDirectory(sources)) {
            return all;
        }
        try (var walk = Files.walk(sources)) {
            for (Path file : walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String[] lines = content.split("\n", 6);
                if (lines.length < 6 || !lines[0].startsWith("# ")) {
                    continue;
                }
                all.add(new Source(lines[0].substring(2).strip(), after(lines[1], "Source:"), after(lines[2], "Kind:"),
                        after(lines[3], "Date:"), lines[5].strip()));
            }
        }
        return all;
    }

    private static String after(String line, String key) {
        return line.startsWith(key) ? line.substring(key.length()).strip() : "";
    }
}
