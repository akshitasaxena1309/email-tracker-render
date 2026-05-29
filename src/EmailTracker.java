import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Email Open Tracker — Render.com Deployment Ready
 * 
 * Reads PORT from environment variable (required by Render).
 * Logs email opens via a 1x1 tracking pixel.
 */
public class EmailTracker {

    private static final List<Map<String, String>> openLogs = Collections.synchronizedList(new ArrayList<>());

    // 1x1 transparent PNG
    private static final byte[] TRACKING_PIXEL = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVQI12NgAAIABQAB" +
        "Nl7BcQAAAABJRU5ErkJggg=="
    );

    public static void main(String[] args) throws Exception {
        // Render.com provides PORT as env variable
        String portEnv = System.getenv("PORT");
        int port = (portEnv != null) ? Integer.parseInt(portEnv) : 8080;

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        // Tracking pixel endpoint
        server.createContext("/track", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                handleTrack(exchange);
            } else {
                respond(exchange, 405, "text/plain", "Method Not Allowed".getBytes());
            }
        });

        // Dashboard
        server.createContext("/dashboard", exchange -> handleDashboard(exchange));

        // JSON API
        server.createContext("/api/logs", exchange -> handleApi(exchange));

        // Health check (Render uses this to verify your app is running)
        server.createContext("/health", exchange -> {
            respond(exchange, 200, "text/plain", "OK".getBytes());
        });

        // Root redirect to dashboard
        server.createContext("/", exchange -> {
            if ("/".equals(exchange.getRequestURI().getPath())) {
                exchange.getResponseHeaders().set("Location", "/dashboard");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            }
        });

        server.setExecutor(null);
        server.start();

        System.out.println("========================================");
        System.out.println("  EMAIL TRACKER running on port " + port);
        System.out.println("========================================");
    }

    private static void handleTrack(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());

        String recipient = params.getOrDefault("recipient", "unknown");
        String campaign  = params.getOrDefault("campaign", "default");
        String subject   = params.getOrDefault("subject", "");
        String ip        = exchange.getRemoteAddress().getAddress().getHostAddress();

        // Check for forwarded IP (Render uses a reverse proxy)
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            ip = forwarded.split(",")[0].trim();
        }

        String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // Detect email client from User-Agent
        String emailClient = detectEmailClient(userAgent);

        Map<String, String> log = new LinkedHashMap<>();
        log.put("recipient", recipient);
        log.put("campaign", campaign);
        log.put("subject", subject);
        log.put("opened_at", timestamp);
        log.put("ip_address", ip);
        log.put("email_client", emailClient);
        log.put("user_agent", userAgent != null ? userAgent : "unknown");
        openLogs.add(log);

        System.out.println("[OPEN] " + timestamp + " | " + recipient + " | " + campaign + " | " + emailClient);

        // Return pixel with strict no-cache headers
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("Expires", "0");
        respond(exchange, 200, "image/png", TRACKING_PIXEL);
    }

    private static void handleDashboard(HttpExchange exchange) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>");
        html.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        html.append("<title>Email Tracker | Akshita Saxena</title>");
        html.append("<meta http-equiv='refresh' content='10'>");
        html.append("<style>");
        html.append("*{margin:0;padding:0;box-sizing:border-box}");
        html.append("body{font-family:'Segoe UI',system-ui,sans-serif;background:#0a0a1a;color:#e2e8f0;padding:1.5rem;min-height:100vh}");
        html.append("h1{font-size:1.6rem;color:#a78bfa;margin-bottom:0.3rem}");
        html.append(".sub{color:#64748b;margin-bottom:1.5rem;font-size:0.9rem}");
        html.append(".stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:1rem;margin-bottom:1.5rem}");
        html.append(".card{background:#1a1a2e;border:1px solid #2a2a4a;border-radius:10px;padding:1rem 1.2rem}");
        html.append(".card h3{color:#64748b;font-size:0.75rem;text-transform:uppercase;letter-spacing:0.05em}");
        html.append(".card .val{font-size:1.8rem;font-weight:700;color:#a78bfa;margin-top:0.2rem}");
        html.append("table{width:100%;border-collapse:collapse;background:#1a1a2e;border:1px solid #2a2a4a;border-radius:10px;overflow:hidden;margin-bottom:1.5rem}");
        html.append("th{background:#12122a;padding:0.7rem 0.8rem;text-align:left;font-size:0.7rem;text-transform:uppercase;letter-spacing:0.05em;color:#64748b}");
        html.append("td{padding:0.6rem 0.8rem;border-top:1px solid #2a2a4a;font-size:0.85rem}");
        html.append("tr:hover td{background:#1e1e36}");
        html.append(".empty{text-align:center;padding:2.5rem;color:#475569}");
        html.append(".badge{background:#7c3aed;color:#fff;padding:2px 8px;border-radius:8px;font-size:0.7rem;font-weight:600}");
        html.append(".badge-client{background:#0e7490;color:#fff;padding:2px 8px;border-radius:8px;font-size:0.7rem}");
        html.append(".howto{background:#1a1a2e;border:1px solid #2a2a4a;border-radius:10px;padding:1.2rem;margin-top:1rem}");
        html.append(".howto h3{color:#a78bfa;margin-bottom:0.6rem;font-size:1rem}");
        html.append("code{display:block;background:#0a0a1a;padding:0.8rem;border-radius:6px;color:#22d3ee;font-size:0.8rem;word-break:break-all;margin:0.5rem 0;line-height:1.5}");
        html.append(".note{color:#475569;font-size:0.78rem;margin-top:0.5rem}");
        html.append("@media(max-width:600px){.stats{grid-template-columns:1fr 1fr}th:nth-child(5),td:nth-child(5),th:nth-child(6),td:nth-child(6){display:none}}");
        html.append("</style></head><body>");

        html.append("<h1>📧 Email Open Tracker</h1>");
        html.append("<p class='sub'>by Akshita Saxena &bull; auto-refreshes every 10s</p>");

        // Stats
        long unique = openLogs.stream().map(l -> l.get("recipient")).distinct().count();
        long camps = openLogs.stream().map(l -> l.get("campaign")).distinct().count();
        String lastOpen = openLogs.isEmpty() ? "—" : openLogs.get(openLogs.size() - 1).get("opened_at");

        html.append("<div class='stats'>");
        html.append("<div class='card'><h3>Total Opens</h3><div class='val'>").append(openLogs.size()).append("</div></div>");
        html.append("<div class='card'><h3>Unique Recipients</h3><div class='val'>").append(unique).append("</div></div>");
        html.append("<div class='card'><h3>Campaigns</h3><div class='val'>").append(camps).append("</div></div>");
        html.append("<div class='card'><h3>Last Open</h3><div class='val' style='font-size:0.95rem;color:#94a3b8'>").append(esc(lastOpen)).append("</div></div>");
        html.append("</div>");

        // Table
        html.append("<table><thead><tr>");
        html.append("<th>#</th><th>Recipient</th><th>Campaign</th><th>Opened At</th><th>IP</th><th>Client</th>");
        html.append("</tr></thead><tbody>");

        if (openLogs.isEmpty()) {
            html.append("<tr><td colspan='6' class='empty'>No opens yet — send an email with the tracking pixel!</td></tr>");
        } else {
            for (int i = openLogs.size() - 1; i >= 0; i--) {
                Map<String, String> l = openLogs.get(i);
                html.append("<tr>");
                html.append("<td>").append(i + 1).append("</td>");
                html.append("<td><strong>").append(esc(l.get("recipient"))).append("</strong></td>");
                html.append("<td><span class='badge'>").append(esc(l.get("campaign"))).append("</span></td>");
                html.append("<td>").append(esc(l.get("opened_at"))).append("</td>");
                html.append("<td>").append(esc(l.get("ip_address"))).append("</td>");
                html.append("<td><span class='badge-client'>").append(esc(l.get("email_client"))).append("</span></td>");
                html.append("</tr>");
            }
        }
        html.append("</tbody></table>");

        // How-to section with user's actual Render URL
        html.append("<div class='howto'>");
        html.append("<h3>🔗 Your Tracking Pixel Code</h3>");
        html.append("<p style='color:#94a3b8;font-size:0.85rem'>Paste this in your email signature (HTML mode):</p>");
        html.append("<code>");
        html.append("&lt;img src=\"https://YOUR-APP-NAME.onrender.com/track?recipient=CLIENT_EMAIL&amp;campaign=LABEL\" width=\"1\" height=\"1\" style=\"display:none\" /&gt;");
        html.append("</code>");
        html.append("<p class='note'>Replace YOUR-APP-NAME with your Render app name, CLIENT_EMAIL with recipient's email, LABEL with a campaign name.</p>");
        html.append("</div>");

        html.append("</body></html>");
        respond(exchange, 200, "text/html", html.toString().getBytes("UTF-8"));
    }

    private static void handleApi(HttpExchange exchange) throws IOException {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < openLogs.size(); i++) {
            if (i > 0) json.append(",");
            Map<String, String> l = openLogs.get(i);
            json.append("{");
            json.append("\"recipient\":\"").append(je(l.get("recipient"))).append("\",");
            json.append("\"campaign\":\"").append(je(l.get("campaign"))).append("\",");
            json.append("\"subject\":\"").append(je(l.get("subject"))).append("\",");
            json.append("\"opened_at\":\"").append(je(l.get("opened_at"))).append("\",");
            json.append("\"ip_address\":\"").append(je(l.get("ip_address"))).append("\",");
            json.append("\"email_client\":\"").append(je(l.get("email_client"))).append("\",");
            json.append("\"user_agent\":\"").append(je(l.get("user_agent"))).append("\"");
            json.append("}");
        }
        json.append("]");
        respond(exchange, 200, "application/json", json.toString().getBytes("UTF-8"));
    }

    // --- Helpers ---

    private static String detectEmailClient(String ua) {
        if (ua == null) return "Unknown";
        ua = ua.toLowerCase();
        if (ua.contains("thunderbird")) return "Thunderbird";
        if (ua.contains("outlook")) return "Outlook";
        if (ua.contains("apple mail") || ua.contains("webkit") && ua.contains("mac")) return "Apple Mail";
        if (ua.contains("googleimageproxy")) return "Gmail (proxy)";
        if (ua.contains("yahoo")) return "Yahoo Mail";
        if (ua.contains("android")) return "Android Mail";
        if (ua.contains("iphone") || ua.contains("ipad")) return "iOS Mail";
        if (ua.contains("windows nt")) return "Windows Client";
        if (ua.contains("linux")) return "Linux Client";
        return "Other";
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> p = new HashMap<>();
        if (query == null || query.isEmpty()) return p;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                try {
                    p.put(java.net.URLDecoder.decode(kv[0], "UTF-8"),
                          java.net.URLDecoder.decode(kv[1], "UTF-8"));
                } catch (Exception e) { p.put(kv[0], kv[1]); }
            }
        }
        return p;
    }

    private static void respond(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }

    private static String je(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n");
    }
}
