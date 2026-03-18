package master;

import client.GFSClient;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class WebServer {
    private static GFSClient client;

    public static void main(String[] args) throws IOException {
        int port = 8080;
        String masterHost = System.getProperty("minigfs.master.host", "localhost");
        int masterPort = Integer.parseInt(System.getProperty("minigfs.master.port", "6000"));

        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); } catch (NumberFormatException e) { /* use default */ }
        }

        client = new GFSClient(masterHost, masterPort);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new DashboardHandler());
        server.createContext("/api/status", new StatusApiHandler());
        server.createContext("/api/files", new FilesApiHandler());
        server.createContext("/upload", new UploadHandler());
        server.createContext("/download", new DownloadHandler());
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        System.out.println("Web Dashboard: http://localhost:" + port);
        System.out.println("Master: " + masterHost + ":" + masterPort);
        server.start();
    }

    // === JSON API for real-time AJAX polling ===

    static class StatusApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            t.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            String json;
            try {
                Map<String, Object> status = client.getStatus();
                @SuppressWarnings("unchecked")
                List<String> servers = (List<String>) status.get("activeServers");
                json = "{\"online\":true,\"servers\":" + status.get("serverCount")
                        + ",\"files\":" + status.get("fileCount")
                        + ",\"chunks\":" + status.get("chunkCount")
                        + ",\"serverList\":" + toJsonArray(servers) + "}";
            } catch (Exception e) {
                json = "{\"online\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
            }
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            t.sendResponseHeaders(200, b.length);
            t.getResponseBody().write(b);
            t.getResponseBody().close();
        }
    }

    static class FilesApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            t.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            String json;
            try {
                List<String> files = client.listFiles();
                json = "{\"files\":" + toJsonArray(files) + "}";
            } catch (Exception e) {
                json = "{\"files\":[],\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
            }
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            t.sendResponseHeaders(200, b.length);
            t.getResponseBody().write(b);
            t.getResponseBody().close();
        }
    }

    static String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(list.get(i))).append("\"");
        }
        return sb.append("]").toString();
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    // === Dashboard (HTML + JS auto-refresh) ===

    static class DashboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            try {
                t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");

                String html = "<!DOCTYPE html><html><head><title>MiniGFS Dashboard</title>"
                + "<style>"
                + ":root{--primary:#e67e22;--secondary:#d35400;--good:#16a085;--bad:#c0392b}"
                + "body{font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;padding:40px;background:#fffaf0;min-height:100vh;color:#000;margin:0}"
                + ".container{max-width:900px;margin:0 auto}"
                + ".card{background:rgba(255,255,255,.96);padding:35px;margin-bottom:30px;border-radius:20px;box-shadow:0 10px 30px rgba(0,0,0,.08);border:1px solid rgba(255,255,255,.4);transition:transform .3s}"
                + ".card:hover{transform:translateY(-5px);box-shadow:0 15px 35px rgba(211,84,0,.15)}"
                + ".header-card{text-align:center;background:linear-gradient(to right,var(--secondary),var(--primary));color:#fff;padding:40px;border:none}"
                + "h1{margin:0;font-weight:700;font-size:2.5em;letter-spacing:-1px}"
                + "p.subtitle{opacity:.95;margin-top:10px;font-weight:300;font-size:1.1em;color:#fff}"
                + "h2{color:var(--secondary);font-size:1.1em;text-transform:uppercase;letter-spacing:2px;margin-top:0;margin-bottom:25px;border-bottom:2px solid #edeff0;padding-bottom:10px}"
                + "h3{font-size:1em;color:#000;font-weight:700;margin-bottom:15px}"
                + ".status-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:20px}"
                + ".status-item{background:#fff;padding:20px;border-radius:12px;text-align:center;border:1px solid #eee;box-shadow:0 4px 10px rgba(0,0,0,.03);transition:all .3s}"
                + ".status-item small{display:block;color:#000;font-size:.8em;margin-bottom:8px;text-transform:uppercase;font-weight:800;letter-spacing:.5px;opacity:.7}"
                + ".status-val{font-weight:800;font-size:1.2em;color:var(--good);transition:all .3s}"
                + ".dead{color:var(--bad)}"
                + ".big-num{font-size:2em;font-weight:800;color:var(--primary);transition:all .3s}"
                + ".stats-row{display:flex;gap:20px;margin-top:20px}"
                + ".stats-row .status-item{flex:1}"
                + "input[type=text],textarea{width:100%;padding:15px;border:2px solid #edeff0;border-radius:10px;font-size:15px;transition:all .3s;background:#fafafa;box-sizing:border-box;font-family:inherit;margin-bottom:15px;color:#000}"
                + "input[type=text]:focus,textarea:focus{border-color:var(--primary);outline:none;background:#fff;box-shadow:0 0 0 4px rgba(230,126,34,.15)}"
                + "button{padding:14px 28px;background:var(--primary);color:#fff;border:none;border-radius:10px;cursor:pointer;font-size:15px;font-weight:600;transition:all .3s;box-shadow:0 4px 15px rgba(230,126,34,.4)}"
                + "button:hover{background:var(--secondary);transform:translateY(-2px);box-shadow:0 8px 25px rgba(211,84,0,.4)}"
                + ".upload-layout{display:flex;gap:30px;align-items:stretch}"
                + ".upload-half{flex:1;display:flex;flex-direction:column}"
                + "form{flex:1;display:flex;flex-direction:column}"
                + ".file-drop-zone{border:2px dashed #f39c12;border-radius:12px;padding:20px;text-align:center;background:#fffaf0;transition:all .3s;height:100%;display:flex;flex-direction:column;justify-content:center;align-items:center;box-sizing:border-box;flex:1;position:relative}"
                + ".file-drop-zone:hover{border-color:var(--secondary);background:#fff5e6;cursor:pointer;transform:scale(1.02)}"
                + "input[type=file]{color:transparent;width:100%;height:100%;position:absolute;cursor:pointer;top:0;left:0;opacity:0}"
                + ".custom-file-label{pointer-events:none;color:#d35400;font-weight:700;font-size:.95em}"
                + ".file-icon{font-size:42px;margin-bottom:15px;display:block}"
                + "ul{padding:0;list-style:none}"
                + "li{padding:16px;margin-bottom:8px;background:#fff;border:1px solid #eee;border-radius:10px;display:flex;justify-content:space-between;align-items:center;transition:all .3s;animation:fadeIn .3s ease}"
                + "li:hover{transform:translateX(5px);border-color:var(--primary);box-shadow:0 4px 12px rgba(0,0,0,.05)}"
                + "a.download-link{background:#ecf0f1;color:#000;padding:8px 16px;border-radius:20px;font-size:.8em;font-weight:700;transition:all .2s;text-decoration:none}"
                + "a.download-link:hover{background:var(--good);color:#fff}"
                + ".pulse{animation:pulse 1s ease-in-out}"
                + "@keyframes pulse{0%{transform:scale(1)}50%{transform:scale(1.05)}100%{transform:scale(1)}}"
                + "@keyframes fadeIn{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:translateY(0)}}"
                + ".live-dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:#16a085;margin-right:8px;animation:blink 1.5s infinite}"
                + "@keyframes blink{0%,100%{opacity:1}50%{opacity:.3}}"
                + "</style></head><body>"

                + "<div class='container'>"
                + "<div class='card header-card'>"
                + "<h1>MiniGFS</h1>"
                + "<p class='subtitle'>Distributed File System Dashboard &mdash; <span class='live-dot'></span>Live</p>"
                + "</div>"

                // Status Card (populated by JS)
                + "<div class='card' id='statusCard'>"
                + "<h2>System Status</h2>"
                + "<div id='statusContent'>Loading...</div>"
                + "</div>"

                // Upload Card
                + "<div class='card'>"
                + "<h2>Upload Data</h2>"
                + "<div class='upload-layout'>"
                + "<div class='upload-half'>"
                + "<h3>Quick Note</h3>"
                + "<form method='POST' action='/upload'>"
                + "<input type='text' name='filename' placeholder='Enter filename...' required>"
                + "<textarea name='content' placeholder='Type your content here...' rows='6' style='flex:1' required></textarea>"
                + "<button type='submit' style='margin-top:15px'>Save Note</button>"
                + "</form></div>"
                + "<div class='upload-half'>"
                + "<h3>File Upload</h3>"
                + "<div class='file-drop-zone'>"
                + "<form method='POST' action='/upload' enctype='multipart/form-data' style='height:100%;width:100%;display:flex;flex-direction:column;justify-content:center;align-items:center'>"
                + "<div class='file-icon'>&#128194;</div>"
                + "<div class='custom-file-label'>Drag &amp; Drop File<br>or Click to Browse</div>"
                + "<input type='file' name='file' required title=' '>"
                + "<button type='submit' style='margin-top:20px;z-index:10;position:relative'>Upload to Cluster</button>"
                + "</form></div></div>"
                + "</div></div>"

                // Files Card (populated by JS)
                + "<div class='card'>"
                + "<div style='display:flex;justify-content:space-between;align-items:center;margin-bottom:20px'>"
                + "<h2 style='margin:0;border:none'>Storage</h2>"
                + "<button onclick='toggleFiles()' style='background:transparent;color:#000;font-weight:700;border:2px solid #000;box-shadow:none'>Show / Hide</button>"
                + "</div>"
                + "<div id='fileList' style='display:none'><ul id='fileListUl'>Loading...</ul></div>"
                + "</div>"
                + "</div>"

                // JavaScript — Real-time polling every 2 seconds
                + "<script>"
                + "function refreshStatus(){"
                + "  fetch('/api/status').then(r=>r.json()).then(d=>{"
                + "    if(!d.online){document.getElementById('statusContent').innerHTML="
                + "      '<div class=\"status-grid\"><div class=\"status-item\"><small>Master</small><div class=\"status-val dead\">OFFLINE</div></div></div>';"
                + "      return;}"
                + "    let servers='';"
                + "    d.serverList.forEach((s,i)=>{"
                + "      servers+='<div class=\"status-item\"><small>Storage '+(i+1)+'</small><div class=\"status-val\">ACTIVE</div><div style=\"font-size:.7em;color:#888;margin-top:4px\">'+s+'</div></div>';"
                + "    });"
                + "    document.getElementById('statusContent').innerHTML="
                + "      '<div class=\"status-grid\">"
                + "        <div class=\"status-item\"><small>Master</small><div class=\"status-val\">ONLINE</div></div>'"
                + "        +servers+'</div>'"
                + "        +'<div class=\"stats-row\">"
                + "          <div class=\"status-item\"><small>Files</small><div class=\"big-num\">'+d.files+'</div></div>"
                + "          <div class=\"status-item\"><small>Chunks</small><div class=\"big-num\">'+d.chunks+'</div></div>"
                + "          <div class=\"status-item\"><small>Servers</small><div class=\"big-num\" style=\"color:var(--good)\">'+d.servers+'</div></div>"
                + "        </div>';"
                + "  }).catch(()=>{});"
                + "}"
                + "function refreshFiles(){"
                + "  fetch('/api/files').then(r=>r.json()).then(d=>{"
                + "    let ul=document.getElementById('fileListUl');"
                + "    if(!d.files||d.files.length===0){ul.innerHTML='<li style=\"color:#888;font-style:italic;justify-content:center;background:none;border:none\">Cluster is empty. Upload a file.</li>';return;}"
                + "    let html='';"
                + "    d.files.forEach(f=>{"
                + "      html+='<li><span style=\"font-weight:600\">'+f+'</span><a href=\"/download?file='+encodeURIComponent(f)+'\" class=\"download-link\">Download</a></li>';"
                + "    });"
                + "    ul.innerHTML=html;"
                + "  }).catch(()=>{});"
                + "}"
                + "function toggleFiles(){var x=document.getElementById('fileList');x.style.display=(x.style.display==='none')?'block':'none';if(x.style.display==='block')refreshFiles();}"
                + "refreshStatus();refreshFiles();"
                + "setInterval(refreshStatus,2000);"
                + "setInterval(refreshFiles,3000);"
                + "</script>"
                + "</body></html>";

                byte[] responseBytes = html.getBytes(StandardCharsets.UTF_8);
                t.sendResponseHeaders(200, responseBytes.length);
                OutputStream os = t.getResponseBody();
                os.write(responseBytes);
                os.close();
            } catch (Exception fatal) {
                fatal.printStackTrace();
                String err = "Dashboard Error: " + fatal.getMessage();
                byte[] b = err.getBytes(StandardCharsets.UTF_8);
                t.sendResponseHeaders(500, b.length);
                t.getResponseBody().write(b);
                t.getResponseBody().close();
            }
        }
    }

    // === Upload / Download Handlers ===

    static class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equals(t.getRequestMethod())) {
                String resp = "Method not allowed";
                t.sendResponseHeaders(405, resp.length());
                t.getResponseBody().write(resp.getBytes());
                t.getResponseBody().close();
                return;
            }
            try {
                String contentType = t.getRequestHeaders().getFirst("Content-Type");
                if (contentType != null && contentType.contains("multipart/form-data")) {
                    String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
                    InputStream is = t.getRequestBody();
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    int nRead; byte[] data = new byte[8192];
                    while ((nRead = is.read(data, 0, data.length)) != -1) buffer.write(data, 0, nRead);
                    byte[] bodyBytes = buffer.toByteArray();
                    String bodyStr = new String(bodyBytes, StandardCharsets.ISO_8859_1);
                    String boundaryStr = "--" + boundary;

                    String filename = "uploaded_file";
                    String[] headerLines = bodyStr.substring(
                            bodyStr.indexOf(boundaryStr) + boundaryStr.length(),
                            bodyStr.indexOf("\r\n\r\n")).split("\\r?\\n");
                    for (String line : headerLines) {
                        if (line.toLowerCase().startsWith("content-disposition:")) {
                            for (String part : line.split(";")) {
                                if (part.trim().startsWith("filename=")) {
                                    filename = part.split("=")[1].trim().replace("\"", "").replaceAll("[^a-zA-Z0-9._-]", "_");
                                }
                            }
                        }
                    }

                    byte[] boundaryBytes = boundaryStr.getBytes(StandardCharsets.ISO_8859_1);
                    int finalStart = -1, finalEnd = -1;
                    for (int i = 0; i < bodyBytes.length - 4; i++) {
                        if (bodyBytes[i]==13 && bodyBytes[i+1]==10 && bodyBytes[i+2]==13 && bodyBytes[i+3]==10) { finalStart = i + 4; break; }
                    }
                    for (int i = finalStart; i < bodyBytes.length - boundaryBytes.length; i++) {
                        boolean match = true;
                        for (int j = 0; j < boundaryBytes.length; j++) { if (bodyBytes[i+j] != boundaryBytes[j]) { match = false; break; } }
                        if (match) { finalEnd = i - 2; break; }
                    }
                    if (finalStart > 0 && finalEnd > 0) {
                        byte[] fileData = Arrays.copyOfRange(bodyBytes, finalStart, finalEnd);
                        client.writeFile(filename, fileData);
                    }
                } else {
                    InputStreamReader isr = new InputStreamReader(t.getRequestBody(), StandardCharsets.UTF_8);
                    BufferedReader br = new BufferedReader(isr);
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    String raw = sb.toString();
                    String filename = "text_upload_" + System.currentTimeMillis() + ".txt", content = raw;
                    for (String pair : raw.split("&")) {
                        String[] parts = pair.split("=");
                        if (parts.length == 2) {
                            String key = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                            String val = java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                            if ("filename".equals(key)) filename = val;
                            if ("content".equals(key)) content = val;
                        }
                    }
                    client.writeFile(filename, content.getBytes(StandardCharsets.UTF_8));
                }
                // Redirect back to dashboard (instant feedback)
                t.getResponseHeaders().set("Location", "/");
                t.sendResponseHeaders(302, -1);
                t.getResponseBody().close();
            } catch (Exception e) {
                e.printStackTrace();
                String response = "<html><body style='font-family:Inter,sans-serif;text-align:center;padding:60px'>"
                        + "<h1 style='color:#c0392b'>Upload Failed</h1><p>" + e.getMessage() + "</p>"
                        + "<a href='/' style='color:#e67e22;font-weight:700'>Go Back</a></body></html>";
                byte[] b = response.getBytes(StandardCharsets.UTF_8);
                t.sendResponseHeaders(500, b.length);
                t.getResponseBody().write(b);
                t.getResponseBody().close();
            }
        }
    }

    static class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String query = t.getRequestURI().getQuery();
            if (query == null || !query.contains("=")) {
                String resp = "Missing file parameter";
                t.sendResponseHeaders(400, resp.length());
                t.getResponseBody().write(resp.getBytes());
                t.getResponseBody().close();
                return;
            }
            String filename = java.net.URLDecoder.decode(query.split("=", 2)[1], StandardCharsets.UTF_8);
            try {
                byte[] data = client.readFile(filename);
                t.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
                t.sendResponseHeaders(200, data.length);
                t.getResponseBody().write(data);
                t.getResponseBody().close();
            } catch (Exception e) {
                String response = "<html><body style='font-family:Inter,sans-serif;text-align:center;padding:60px'>"
                        + "<h1 style='color:#c0392b'>Download Failed</h1><p>" + e.getMessage() + "</p>"
                        + "<a href='/' style='color:#e67e22;font-weight:700'>Go Back</a></body></html>";
                byte[] b = response.getBytes(StandardCharsets.UTF_8);
                t.sendResponseHeaders(404, b.length);
                t.getResponseBody().write(b);
                t.getResponseBody().close();
            }
        }
    }
}
