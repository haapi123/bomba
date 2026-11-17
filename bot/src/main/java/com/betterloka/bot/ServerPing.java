package com.betterloka.bot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Hashtable;

/**
 * How many people are on a Minecraft server, asked the way the game's own server list asks: the
 * Server List Ping — a handshake saying "status", one request, and the server's JSON answer with its
 * player count. Nothing is logged in and no account is used.
 *
 * <p>Should the server not answer that (a firewall in front of the bot's host, say), a public status
 * service can be asked instead, {@code api.mcsrvstat.us}, which pings it from elsewhere.
 */
public final class ServerPing {
    /** A server's answer: players on it now, how many it takes, and the version it says it runs. */
    public record Status(int online, int max, String version) {
    }

    /** Any protocol number gets a status answer; -1 is what clients send when they only ask. */
    private static final int PROTOCOL = -1;
    private static final int MAX_PACKET = 1 << 21;

    private ServerPing() {
    }

    /**
     * @param address {@code host} or {@code host:port}; a bare host is looked up as Minecraft does
     *                ({@code _minecraft._tcp} SRV record first, then port 25565)
     */
    public static Status ping(String address, int timeoutMillis) throws IOException {
        String host = address.trim();
        int port = 25565;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            port = Integer.parseInt(host.substring(colon + 1));
            host = host.substring(0, colon);
        } else {
            String[] srv = srv(host);
            if (srv != null) {
                host = srv[0];
                port = Integer.parseInt(srv[1]);
            }
        }
        return ping(host, port, timeoutMillis);
    }

    public static Status ping(String host, int port, int timeoutMillis) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(handshake);
            writeVarInt(data, 0x00);
            writeVarInt(data, PROTOCOL);
            writeString(data, host);
            data.writeShort(port);
            writeVarInt(data, 1);
            sendPacket(out, handshake.toByteArray());
            sendPacket(out, new byte[]{0x00});
            out.flush();

            int length = readVarInt(in);
            if (length <= 0 || length > MAX_PACKET) {
                throw new IOException("Unexpected status packet length " + length);
            }
            byte[] packet = new byte[length];
            in.readFully(packet);
            DataInputStream body = new DataInputStream(new java.io.ByteArrayInputStream(packet));
            int id = readVarInt(body);
            if (id != 0x00) {
                throw new IOException("Unexpected status packet id " + id);
            }
            int textLength = readVarInt(body);
            if (textLength < 0 || textLength > packet.length) {
                throw new IOException("Unexpected status text length " + textLength);
            }
            byte[] text = new byte[textLength];
            body.readFully(text);
            return parse(new String(text, StandardCharsets.UTF_8));
        }
    }

    /** The status JSON's {@code players.online}, {@code players.max} and {@code version.name}. */
    static Status parse(String json) throws IOException {
        try {
            JsonObject status = JsonParser.parseString(json).getAsJsonObject();
            JsonObject players = status.getAsJsonObject("players");
            JsonObject version = status.has("version") ? status.getAsJsonObject("version") : null;
            return new Status(players.get("online").getAsInt(), players.get("max").getAsInt(),
                    version != null && version.has("name") ? version.get("name").getAsString() : "");
        } catch (RuntimeException e) {
            throw new IOException("Unreadable status: " + e.getMessage(), e);
        }
    }

    /**
     * The same numbers from {@code api.mcsrvstat.us}, for when the server cannot be reached directly.
     *
     * @return the status, or {@code null} if the service says the server is offline
     */
    public static Status viaStatusService(HttpClient http, String address) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.mcsrvstat.us/3/" + address.trim()))
                .timeout(Duration.ofSeconds(15))
                // The service asks for a User-Agent naming the program.
                .header("User-Agent", "BetterLokaBot (Discord stats)")
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Status service answered " + response.statusCode());
        }
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!json.has("online") || !json.get("online").getAsBoolean()) {
                return null;
            }
            JsonObject players = json.getAsJsonObject("players");
            return new Status(players.get("online").getAsInt(), players.get("max").getAsInt(),
                    json.has("version") ? json.get("version").getAsString() : "");
        } catch (RuntimeException e) {
            throw new IOException("Unreadable status service answer: " + e.getMessage(), e);
        }
    }

    /** {@code [host, port]} from the {@code _minecraft._tcp} SRV record, or {@code null}. */
    private static String[] srv(String host) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "2000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            javax.naming.directory.DirContext dns = new javax.naming.directory.InitialDirContext(env);
            javax.naming.directory.Attribute records = dns.getAttributes("_minecraft._tcp." + host,
                    new String[]{"SRV"}).get("SRV");
            if (records == null || records.size() == 0) {
                return null;
            }
            // "priority weight port target."
            String[] parts = records.get(0).toString().trim().split("\\s+");
            if (parts.length < 4) {
                return null;
            }
            String target = parts[3].endsWith(".") ? parts[3].substring(0, parts[3].length() - 1) : parts[3];
            return new String[]{target, parts[2]};
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static void sendPacket(OutputStream out, byte[] payload) throws IOException {
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        writeVarInt(new DataOutputStream(framed), payload.length);
        framed.write(payload);
        out.write(framed.toByteArray());
    }

    static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int read = in.read();
            if (read < 0) {
                throw new IOException("The server closed the connection");
            }
            value |= (read & 0x7F) << (7 * i);
            if ((read & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("VarInt too long");
    }

    private static void writeString(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }
}
