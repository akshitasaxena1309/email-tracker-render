import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class EmailTracker {

    private static final List<Map<String, String>> openLogs = Collections.synchronizedList(new ArrayList<>());

    // Deduplication: stores "recipient|campaign" -> last logged timestamp
    private static final ConcurrentHashMap<String, Long> lastLogged = new ConcurrentHashMap<>();

    // Dedup window in milliseconds (5 minutes)
    private static final long DEDUP_WINDOW_MS = 5 * 60 * 1000;

    private static final byte[] TRACKING_PIXEL = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVQI12NgAAIABQAB" +
        "Nl7BcQAAAABJRU5ErkJggg=="
    );

    public static void main(String[] args) throws Exception {
        String portEnv = System.getenv("PORT");
        int port = (portEnv != null) ? Integer.parseInt(portEnv) : 8080;

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        server.createContext("/track", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) handleTrack(exchange);
            else respond(exchange, 405, "text/plain", "Method Not Allowed".getBytes());
        });

        server.createContext("/dashboard", exchange -> handleDashboard(exchange));
        server.createContext("/compose", exchange -> handleCompose(exchange));
        server.createContext("/api/logs", exchange -> handleApi(exchange));
        server.createContext("/health", exchange -> respond(exchange, 200, "text/plain", "OK".getBytes()));

        server.createContext("/", exchange -> {
            if ("/".equals(exchange.getRequestURI().getPath())) {
                exchange.getResponseHeaders().set("Location", "/compose");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            }
        });

        server.setExecutor(null);
        server.start();
        System.out.println("EMAIL TRACKER running on port " + port);
    }

    private static void handleTrack(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
        String recipient = params.getOrDefault("recipient", "unknown");
        String campaign  = params.getOrDefault("campaign", "default");
        String ip        = exchange.getRemoteAddress().getAddress().getHostAddress();
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) ip = forwarded.split(",")[0].trim();
        String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // Detect status from user agent
        String status = detectStatus(userAgent);
        String emailClient = detectEmailClient(userAgent);

        // --- DEDUPLICATION ---
        // Skip if same recipient+campaign was logged within last 5 minutes
        String dedupKey = recipient.toLowerCase() + "|" + campaign.toLowerCase();
        long now = System.currentTimeMillis();
        Long lastTime = lastLogged.get(dedupKey);

        if (lastTime != null && (now - lastTime) < DEDUP_WINDOW_MS) {
            // Duplicate within 5 minutes — skip logging but still serve pixel
            System.out.println("[SKIP-DUPLICATE] " + recipient + " | " + campaign + " | within 5 min window");

            // BUT: if previous was "Delivered" and this one is "Read", upgrade the status
            if ("Read".equals(status)) {
                for (int i = openLogs.size() - 1; i >= 0; i--) {
                    Map<String, String> prev = openLogs.get(i);
                    if (recipient.equalsIgnoreCase(prev.get("recipient")) &&
                        campaign.equalsIgnoreCase(prev.get("campaign")) &&
                        "Delivered".equals(prev.get("status"))) {
                        prev.put("status", "Read \u2705");
                        prev.put("opened_at", timestamp);
                        prev.put("email_client", emailClient);
                        System.out.println("[UPGRADED] " + recipient + " status -> Read");
                        break;
                    }
                }
            }

            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            respond(exchange, 200, "image/png", TRACKING_PIXEL);
            return;
        }

        // New entry — log it
        lastLogged.put(dedupKey, now);

        Map<String, String> log = new LinkedHashMap<>();
        log.put("recipient", recipient);
        log.put("campaign", campaign);
        log.put("status", status);
        log.put("opened_at", timestamp);
        log.put("ip_address", ip);
        log.put("email_client", emailClient);
        log.put("user_agent", userAgent != null ? userAgent : "unknown");
        openLogs.add(log);

        System.out.println("[" + status.toUpperCase() + "] " + timestamp + " | " + recipient + " | " + campaign + " | " + emailClient);

        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("Expires", "0");
        respond(exchange, 200, "image/png", TRACKING_PIXEL);
    }

    /**
     * Detect email status from User-Agent:
     * - Gmail/Google proxy = email was delivered to inbox (proxy prefetches images)
     * - Actual browser/mail client = user actually opened and read the email
     */
    private static String detectStatus(String ua) {
        if (ua == null) return "Read \u2705";
        String lower = ua.toLowerCase();
        if (lower.contains("googleimageproxy") || lower.contains("google-image-proxy")) {
            return "Delivered";
        }
        if (lower.contains("yahoo") && lower.contains("slurp")) {
            return "Delivered";
        }
        if (lower.contains("outlook-ios") || lower.contains("microsoft office")) {
            return "Read \u2705";
        }
        return "Read \u2705";
    }

    private static String detectEmailClient(String ua) {
        if (ua == null) return "Unknown";
        String lower = ua.toLowerCase();
        if (lower.contains("googleimageproxy")) return "Gmail";
        if (lower.contains("thunderbird")) return "Thunderbird";
        if (lower.contains("outlook") || lower.contains("microsoft")) return "Outlook";
        if (lower.contains("apple") || (lower.contains("webkit") && lower.contains("mac"))) return "Apple Mail";
        if (lower.contains("yahoo")) return "Yahoo Mail";
        if (lower.contains("android")) return "Android";
        if (lower.contains("iphone") || lower.contains("ipad")) return "iPhone";
        if (lower.contains("chrome")) return "Chrome";
        if (lower.contains("safari")) return "Safari";
        if (lower.contains("firefox")) return "Firefox";
        return "Mail Client";
    }

    private static void handleCompose(HttpExchange exchange) throws IOException {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null) host = "email-tracker-akshita.onrender.com";
        String baseUrl = "https://" + host;

        String html = "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>"
        + "<meta name='viewport' content='width=device-width, initial-scale=1.0'>"
        + "<title>Compose Tracked Email</title>"
        + "<style>"
        + "*{margin:0;padding:0;box-sizing:border-box}"
        + "body{font-family:-apple-system,'Segoe UI',system-ui,sans-serif;background:#0a0a1a;color:#e2e8f0;padding:1.2rem;min-height:100vh}"
        + "h1{font-size:1.4rem;color:#a78bfa;margin-bottom:0.2rem}"
        + ".sub{color:#64748b;margin-bottom:1.2rem;font-size:0.85rem}"
        + "label{display:block;font-size:0.8rem;color:#94a3b8;margin-bottom:0.3rem;text-transform:uppercase;letter-spacing:0.05em}"
        + "input{width:100%;padding:0.75rem;background:#1a1a2e;border:1px solid #2a2a4a;border-radius:8px;color:#e2e8f0;font-size:1rem;margin-bottom:1rem;outline:none;-webkit-appearance:none}"
        + "input:focus{border-color:#7c3aed}"
        + ".btn{display:block;width:100%;padding:0.9rem;border:none;border-radius:10px;font-size:1rem;font-weight:600;cursor:pointer;margin-bottom:0.8rem;text-align:center;text-decoration:none;-webkit-tap-highlight-color:transparent}"
        + ".btn-primary{background:#7c3aed;color:#fff}"
        + ".btn-primary:active{background:#6d28d9;transform:scale(0.98)}"
        + ".btn-gmail{background:#1a73e8;color:#fff}"
        + ".btn-outline{background:transparent;border:1px solid #2a2a4a;color:#94a3b8}"
        + ".preview{background:#1a1a2e;border:1px solid #2a2a4a;border-radius:10px;padding:1rem;margin:1rem 0;font-size:0.9rem;line-height:1.6}"
        + ".success{background:#0a2a1a;border:1px solid #0f5132;border-radius:10px;padding:0.8rem;color:#4ade80;text-align:center;margin-bottom:1rem;display:none;font-size:0.9rem}"
        + ".nav{display:flex;gap:0.5rem;margin-bottom:1.2rem}"
        + ".nav a{flex:1;text-align:center;padding:0.5rem;background:#1a1a2e;border:1px solid #2a2a4a;border-radius:8px;color:#94a3b8;text-decoration:none;font-size:0.85rem}"
        + ".nav a.active{border-color:#7c3aed;color:#a78bfa}"
        + ".step{display:flex;align-items:center;gap:0.6rem;margin-bottom:0.8rem;font-size:0.85rem;color:#94a3b8}"
        + ".step-num{width:24px;height:24px;border-radius:50%;background:#7c3aed;color:#fff;display:flex;align-items:center;justify-content:center;font-size:0.75rem;font-weight:700;flex-shrink:0}"
        + "</style></head><body>"

        + "<h1>\uD83D\uDCE7 Compose tracked email</h1>"
        + "<p class='sub'>Works on phone, tablet, desktop</p>"

        + "<div class='nav'>"
        + "<a href='/compose' class='active'>Compose</a>"
        + "<a href='/dashboard'>Dashboard</a>"
        + "</div>"

        + "<div class='step'><div class='step-num'>1</div>Enter recipient's email below</div>"
        + "<div class='step'><div class='step-num'>2</div>Tap \"Copy signature\"</div>"
        + "<div class='step'><div class='step-num'>3</div>Open Gmail \u2192 Compose \u2192 Paste</div>"

        + "<div style='margin-top:1rem'>"
        + "<label>Recipient email</label>"
        + "<input type='email' id='recipient' placeholder='hr@company.com' autocapitalize='none' autocorrect='off'/>"
        + "<label>Campaign label (optional)</label>"
        + "<input type='text' id='campaign' placeholder='job-apply' value='gmail-mobile' autocapitalize='none'/>"
        + "</div>"

        + "<div class='success' id='success'>\u2705 Signature copied! Now paste in Gmail</div>"

        + "<button class='btn btn-primary' id='copyBtn'>\uD83D\uDCCB Copy signature with tracking pixel</button>"
        + "<button class='btn btn-gmail' id='gmailBtn'>\u2709\uFE0F Open Gmail app to compose</button>"
        + "<button class='btn btn-outline' id='pixelBtn'>Copy tracking pixel URL only</button>"

        + "<p style='font-size:0.8rem;color:#64748b;margin:1rem 0 0.5rem'>Preview:</p>"
        + "<div class='preview'>"
        + "<strong>Sincerely,</strong><br>"
        + "Akshita Saxena<br>"
        + "<span style='color:#60a5fa'>akshitasaxena38@gmail.com</span><br>"
        + "+91-9571462508<br>"
        + "<span style='color:#4ade80;font-size:0.75rem'>+ invisible tracking pixel \u2713</span>"
        + "</div>"

        + "<textarea id='copyBox' style='position:fixed;left:-9999px;top:0;' readonly></textarea>"

        + "<script>"
        + "var BASE='" + baseUrl + "';"

        + "function getPixelUrl(){"
        + "var r=document.getElementById('recipient').value.trim()||'unknown';"
        + "var c=document.getElementById('campaign').value.trim()||'general';"
        + "return BASE+'/track?recipient='+encodeURIComponent(r)+'&campaign='+encodeURIComponent(c);"
        + "}"

        + "function showMsg(txt){"
        + "var s=document.getElementById('success');"
        + "s.textContent=txt;"
        + "s.style.display='block';"
        + "setTimeout(function(){s.style.display='none';},4000);"
        + "}"

        + "function fallbackCopy(text){"
        + "var box=document.getElementById('copyBox');"
        + "box.value=text;"
        + "box.style.position='static';"
        + "box.style.left='0';"
        + "box.focus();"
        + "box.select();"
        + "try{box.setSelectionRange(0,99999);}catch(e){}"
        + "var ok=document.execCommand('copy');"
        + "box.style.position='fixed';"
        + "box.style.left='-9999px';"
        + "box.blur();"
        + "return ok;"
        + "}"

        + "document.getElementById('copyBtn').addEventListener('click',function(){"
        + "var pixUrl=getPixelUrl();"
        + "var sig='Sincerely,\\nAkshita Saxena\\nakshitasaxena38@gmail.com\\n+91-9571462508';"
        + "var richHtml='<div>Sincerely,<br><b>Akshita Saxena</b><br>"
        + "<a href=\"mailto:akshitasaxena38@gmail.com\">akshitasaxena38@gmail.com</a><br>"
        + "+91-9571462508<br>"
        + "<img src=\"'+pixUrl+'\" width=\"1\" height=\"1\" style=\"display:none\"></div>';"
        + "if(navigator.clipboard&&window.ClipboardItem){"
        + "try{"
        + "var blobH=new Blob([richHtml],{type:'text/html'});"
        + "var blobT=new Blob([sig],{type:'text/plain'});"
        + "navigator.clipboard.write([new ClipboardItem({'text/html':blobH,'text/plain':blobT})])"
        + ".then(function(){showMsg('\u2705 Signature copied! Now paste in Gmail');})"
        + ".catch(function(){if(fallbackCopy(sig)){showMsg('\u2705 Copied (text)! Paste in Gmail');}else{showMsg('\u274C Could not copy. Try long-press to select the preview below.');}});"
        + "}catch(e){if(fallbackCopy(sig)){showMsg('\u2705 Copied! Paste in Gmail');}else{showMsg('\u274C Could not copy.');}}"
        + "}else{"
        + "if(fallbackCopy(sig)){showMsg('\u2705 Copied! Paste in Gmail');}else{showMsg('\u274C Could not copy. Try long-press the preview below.');}"
        + "}"
        + "});"

        + "document.getElementById('pixelBtn').addEventListener('click',function(){"
        + "var pixUrl=getPixelUrl();"
        + "if(navigator.clipboard&&navigator.clipboard.writeText){"
        + "navigator.clipboard.writeText(pixUrl)"
        + ".then(function(){showMsg('\u2705 Pixel URL copied!');})"
        + ".catch(function(){if(fallbackCopy(pixUrl)){showMsg('\u2705 Pixel URL copied!');}});"
        + "}else{if(fallbackCopy(pixUrl)){showMsg('\u2705 Pixel URL copied!');}}"
        + "});"

        + "document.getElementById('gmailBtn').addEventListener('click',function(){"
        + "var r=document.getElementById('recipient').value.trim();"
        + "window.location='mailto:'+(r||'');"
        + "});"

        + "</script>"
        + "</body></html>";

        respond(exchange, 200, "text/html", html.getBytes("UTF-8"));
    }

    private static void handleDashboard(HttpExchange exchange) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>");
        html.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        html.append("<title>Email Tracker | Akshita Saxena</title>");
        html.append("<meta http-equiv='refresh' content='10'>");
        html.append("<style>");
        html.append("*{margin:0;padding:0;box-sizing:border-box}");
        html.append("body{font-family:-apple-system,'Segoe UI',system-ui,sans-serif;background:#0a0a1a;color:#e2e8f0;padding:1.5rem;min-height:100vh}");
        html.append("h1{font-size:1.6rem;color:#a78bfa;margin-bottom:0.3rem}");
        html.append(".sub{color:#64748b;margin-bottom:1rem;font-size:0.9rem}");
        html.append(".nav{display:flex;gap:0.5rem;margin-bottom:1.2rem}");
        html.append(".nav a{flex:1;text-align:center;padding:0.5rem;background:#1a1a2e;border:1px solid #2a2a4a;border-radius:8px;color:#94a3b8;text-decoration:none;font-size:0.85rem}");
        html.append(".nav a.active{border-color:#7c3aed;color:#a78bfa}");
        html.append(".stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:1rem;margin-bottom:1.5rem}");
        html.append(".card{background:#1a1a2e;border:1px solid #2a2a4a;border-radius:10px;padding:1rem 1.2rem}");
        html.append(".card h3{color:#64748b;font-size:0.75rem;text-transform:uppercase;letter-spacing:0.05em}");
        html.append(".card .val{font-size:1.8rem;font-weight:700;color:#a78bfa;margin-top:0.2rem}");
        html.append("table{width:100%;border-collapse:collapse;background:#1a1a2e;border:1px solid #2a2a4a;border-radius:10px;overflow:hidden}");
        html.append("th{background:#12122a;padding:0.7rem 0.8rem;text-align:left;font-size:0.7rem;text-transform:uppercase;letter-spacing:0.05em;color:#64748b}");
        html.append("td{padding:0.6rem 0.8rem;border-top:1px solid #2a2a4a;font-size:0.85rem}");
        html.append("tr:hover td{background:#1e1e36}");
        html.append(".empty{text-align:center;padding:2.5rem;color:#475569}");
        html.append(".badge{padding:3px 10px;border-radius:8px;font-size:0.75rem;font-weight:600}");
        html.append(".badge-read{background:#0a2a1a;color:#4ade80;border:1px solid #166534}");
        html.append(".badge-delivered{background:#1a1a2e;color:#60a5fa;border:1px solid #1e40af}");
        html.append(".badge-campaign{background:#2e1065;color:#c4b5fd;border:1px solid #5b21b6}");
        html.append(".badge-client{background:#164e63;color:#67e8f9;border:1px solid #0e7490}");
        html.append("@media(max-width:600px){th:nth-child(5),td:nth-child(5){display:none}}");
        html.append("</style></head><body>");

        html.append("<h1>\uD83D\uDCE7 Email Open Tracker</h1>");
        html.append("<p class='sub'>by Akshita Saxena \u2022 auto-refreshes every 10s</p>");

        html.append("<div class='nav'>");
        html.append("<a href='/compose'>Compose</a>");
        html.append("<a href='/dashboard' class='active'>Dashboard</a>");
        html.append("</div>");

        // Stats
        long totalRead = openLogs.stream().filter(l -> l.get("status").contains("Read")).count();
        long totalDelivered = openLogs.stream().filter(l -> "Delivered".equals(l.get("status"))).count();
        long unique = openLogs.stream().map(l -> l.get("recipient").toLowerCase()).distinct().count();

        html.append("<div class='stats'>");
        html.append("<div class='card'><h3>Total Emails</h3><div class='val'>").append(openLogs.size()).append("</div></div>");
        html.append("<div class='card'><h3>\u2705 Read</h3><div class='val' style='color:#4ade80'>").append(totalRead).append("</div></div>");
        html.append("<div class='card'><h3>\uD83D\uDCE8 Delivered</h3><div class='val' style='color:#60a5fa'>").append(totalDelivered).append("</div></div>");
        html.append("<div class='card'><h3>Unique People</h3><div class='val'>").append(unique).append("</div></div>");
        html.append("</div>");

        // Table
        html.append("<table><thead><tr>");
        html.append("<th>#</th><th>Recipient</th><th>Status</th><th>Time</th><th>IP</th><th>Via</th>");
        html.append("</tr></thead><tbody>");

        if (openLogs.isEmpty()) {
            html.append("<tr><td colspan='6' class='empty'>No emails tracked yet \u2014 use the Compose page to send a tracked email!</td></tr>");
        } else {
            for (int i = openLogs.size() - 1; i >= 0; i--) {
                Map<String, String> l = openLogs.get(i);
                String status = l.getOrDefault("status", "Read \u2705");
                String badgeClass = status.contains("Read") ? "badge-read" : "badge-delivered";

                html.append("<tr>");
                html.append("<td>").append(i + 1).append("</td>");
                html.append("<td><strong>").append(esc(l.get("recipient"))).append("</strong><br><span style='font-size:0.7rem;color:#64748b'>").append(esc(l.get("campaign"))).append("</span></td>");
                html.append("<td><span class='badge ").append(badgeClass).append("'>").append(esc(status)).append("</span></td>");
                html.append("<td>").append(esc(l.get("opened_at"))).append("</td>");
                html.append("<td>").append(esc(l.get("ip_address"))).append("</td>");
                html.append("<td><span class='badge badge-client'>").append(esc(l.get("email_client"))).append("</span></td>");
                html.append("</tr>");
            }
        }
        html.append("</tbody></table>");

        // Legend
        html.append("<div style='margin-top:1rem;padding:1rem;background:#1a1a2e;border:1px solid #2a2a4a;border-radius:10px;font-size:0.8rem;color:#94a3b8;line-height:1.8'>");
        html.append("<strong style='color:#a78bfa'>Status guide:</strong><br>");
        html.append("<span class='badge badge-delivered'>Delivered</span> = Email reached their inbox (Gmail loaded the image)<br>");
        html.append("<span class='badge badge-read'>Read \u2705</span> = Recipient actually opened and viewed your email<br>");
        html.append("<span style='color:#64748b'>Duplicates within 5 minutes are automatically merged</span>");
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
            json.append("\"status\":\"").append(je(l.get("status"))).append("\",");
            json.append("\"opened_at\":\"").append(je(l.get("opened_at"))).append("\",");
            json.append("\"ip_address\":\"").append(je(l.get("ip_address"))).append("\",");
            json.append("\"email_client\":\"").append(je(l.get("email_client"))).append("\"");
            json.append("}");
        }
        json.append("]");
        respond(exchange, 200, "application/json", json.toString().getBytes("UTF-8"));
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
