import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public class App {
    private static final int PORT = 8080;
    private static final String STATIC_DIR = "."; // files in project root
    public static void main(String[] args) {
        try {
            DBHelper.initializeDB();
        } catch (Exception e) {
            System.out.println("Error initializing database: " + e.getMessage());
            e.printStackTrace();
            // continue - server will still start but DB calls may fail
        }

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

            // Static file handler
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/") || path.equals("/index") || path.equals("/index.html")) path = "/index.html";
                else if (path.equals("/home")) path = "/home.html";
                else if (path.equals("/app")) path = "/app.html";

                // allow direct linking of files like /styles.css /script.js
                File file = new File(STATIC_DIR, path.startsWith("/") ? path.substring(1) : path);
                if (!file.exists() || file.isDirectory()) {
                    // If not found, return 404 JSON for API calls, else serve index.html (SPA fallback)
                    if (path.startsWith("/api") || path.startsWith("/addExpense") || path.startsWith("/balances")) {
                        sendJson(exchange, Collections.singletonMap("error", "Not found"), 404);
                    } else {
                        File idx = new File(STATIC_DIR, "index.html");
                        if (idx.exists()) {
                            sendFile(exchange, idx);
                        } else {
                            exchange.sendResponseHeaders(404, -1);
                        }
                    }
                    return;
                }
                sendFile(exchange, file);
            });

            // API endpoints
            server.createContext("/addExpense", App::handleAddExpense);
            server.createContext("/expenses", App::handleGetExpenses);
            server.createContext("/balances", App::handleGetBalances);
            server.createContext("/settlements", App::handleGetSettlements);
            server.createContext("/clear", App::handleClear);

            server.setExecutor(null);
            server.start();
            System.out.println("Server started on http://localhost:" + PORT);
        } catch (IOException e) {
            System.out.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ---------------- Static file send ----------------
    private static void sendFile(HttpExchange exchange, File file) throws IOException {
        String path = file.getName();
        String contentType = "text/plain";
        if (path.endsWith(".html")) contentType = "text/html";
        else if (path.endsWith(".css")) contentType = "text/css";
        else if (path.endsWith(".js")) contentType = "application/javascript";
        else if (path.endsWith(".json")) contentType = "application/json";
        else if (path.endsWith(".png")) contentType = "image/png";
        else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) contentType = "image/jpeg";

        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        byte[] bytes = Files.readAllBytes(file.toPath());
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ---------------- API handlers ----------------

    // POST /addExpense
    private static void handleAddExpense(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, Collections.singletonMap("error", "Only POST allowed"), 405);
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = parseAddExpenseJson(body);

        String payer = (String) req.get("payer");
        Double total = (Double) req.get("total");
        @SuppressWarnings("unchecked")
        List<String> participants = (List<String>) req.get("participants");
        String splitType = (String) req.get("splitType");
        @SuppressWarnings("unchecked")
        Map<String, Double> splitDetails = (Map<String, Double>) req.get("splitDetails");
        String description = (String) req.get("description");

        if (payer == null || total == null || participants == null || participants.isEmpty()) {
            sendJson(exchange, Collections.singletonMap("error", "Invalid input"), 400);
            return;
        }

        if (splitType == null) splitType = "equal";
        if (splitDetails == null) splitDetails = new HashMap<>();
        if (description == null) description = "Expense";

        try {
            DBHelper.addExpense(description, total, payer, participants, splitType, splitDetails);
            sendJson(exchange, Collections.singletonMap("success", true), 200);
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, Collections.singletonMap("error", "DB error: " + e.getMessage()), 500);
        }
    }

    // GET /expenses
    private static void handleGetExpenses(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, Collections.singletonMap("error", "Only GET allowed"), 405);
            return;
        }
        try {
            List<Map<String, Object>> expenses = DBHelper.getAllExpenses();
            Map<String, Object> resp = new HashMap<>();
            resp.put("expenses", expenses);
            sendJson(exchange, resp, 200);
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, Collections.singletonMap("error", "DB error: " + e.getMessage()), 500);
        }
    }

    // GET /balances
    private static void handleGetBalances(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, Collections.singletonMap("error", "Only GET allowed"), 405);
            return;
        }
        try {
            Map<String, Double> balances = DBHelper.getBalances();
            Map<String, Object> resp = new HashMap<>();
            resp.put("balances", balances);
            sendJson(exchange, resp, 200);
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, Collections.singletonMap("error", "DB error: " + e.getMessage()), 500);
        }
    }

    // GET /settlements
    private static void handleGetSettlements(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, Collections.singletonMap("error", "Only GET allowed"), 405);
            return;
        }
        try {
            Map<String, Double> balances = DBHelper.getBalances();
            List<Map<String, Object>> settlements = computeSettlements(new HashMap<>(balances));
            Map<String, Object> resp = new HashMap<>();
            resp.put("settlements", settlements);
            sendJson(exchange, resp, 200);
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, Collections.singletonMap("error", "Error computing settlements"), 500);
        }
    }

    // POST /clear
    private static void handleClear(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, Collections.singletonMap("error", "Only POST allowed"), 405);
            return;
        }
        try {
            DBHelper.clearAll();
            sendJson(exchange, Collections.singletonMap("success", true), 200);
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, Collections.singletonMap("error", "DB error: " + e.getMessage()), 500);
        }
    }

    // ---------------- Helpers ----------------

    // Very small JSON serializer for maps/lists used for responses
    private static void sendJson(HttpExchange exchange, Map<String, Object> map, int status) throws IOException {
        String json = mapToJson(map);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // Minimal map->json converter (sufficient for our outputs)
    private static String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escapeJson(e.getKey())).append("\":");
            sb.append(objToJson(e.getValue()));
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String objToJson(Object val) {
        if (val == null) return "null";
        if (val instanceof String) return "\"" + escapeJson((String) val) + "\"";
        if (val instanceof Number || val instanceof Boolean) return val.toString();
        if (val instanceof Map) return mapToJson((Map<String, Object>) val);
        if (val instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            List<?> list = (List<?>) val;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(objToJson(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeJson(val.toString()) + "\"";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    // Simple parser for POST /addExpense JSON (works with your frontend's payload)
    // Expects keys: payer (string), total or amount (number), participants (array), splitType (string), splitDetails (object), description (string)
    private static Map<String, Object> parseAddExpenseJson(String json) {
        Map<String, Object> map = new HashMap<>();
        try {
            // payer
            String payer = extractString(json, "\"payer\"");
            if (payer != null) map.put("payer", payer);

            // description (optional)
            String desc = extractString(json, "\"description\"");
            if (desc != null) map.put("description", desc);

            // total or amount
            Double total = extractDouble(json, "\"total\"");
            if (total == null) total = extractDouble(json, "\"amount\"");
            if (total != null) map.put("total", total);

            // participants array
            List<String> participants = extractStringArray(json, "\"participants\"");
            if (participants != null) map.put("participants", participants);

            // splitType
            String splitType = extractString(json, "\"splitType\"");
            if (splitType != null) map.put("splitType", splitType);

            // splitDetails object -> map
            Map<String, Double> splitDetails = extractNumberObject(json, "\"splitDetails\"");
            if (splitDetails != null) map.put("splitDetails", splitDetails);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return map;
    }

    // small extract helpers (not full JSON parser, but OK for predictable payloads)
    private static String extractString(String json, String key) {
        int idx = json.indexOf(key);
        if (idx == -1) return null;
        int colon = json.indexOf(":", idx);
        if (colon == -1) return null;
        int q1 = json.indexOf("\"", colon);
        if (q1 == -1) return null;
        int q2 = json.indexOf("\"", q1 + 1);
        if (q2 == -1) return null;
        return json.substring(q1 + 1, q2);
    }

    private static Double extractDouble(String json, String key) {
        int idx = json.indexOf(key);
        if (idx == -1) return null;
        int colon = json.indexOf(":", idx);
        if (colon == -1) return null;
        int start = colon + 1;
        // find end (comma or })
        int end = json.indexOf(",", start);
        if (end == -1) end = json.indexOf("}", start);
        if (end == -1) end = json.length();
        String num = json.substring(start, end).replaceAll("[^0-9.\\-eE]", "");
        if (num.isEmpty()) return null;
        try {
            return Double.parseDouble(num);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> extractStringArray(String json, String key) {
        int idx = json.indexOf(key);
        if (idx == -1) return null;
        int arrStart = json.indexOf("[", idx);
        int arrEnd = json.indexOf("]", arrStart);
        if (arrStart == -1 || arrEnd == -1) return null;
        String inner = json.substring(arrStart + 1, arrEnd).trim();
        if (inner.isEmpty()) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        String[] parts = inner.split(",");
        for (String p : parts) {
            p = p.trim();
            if (p.startsWith("\"") && p.endsWith("\"")) p = p.substring(1, p.length() - 1);
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private static Map<String, Double> extractNumberObject(String json, String key) {
        int idx = json.indexOf(key);
        if (idx == -1) return null;
        int objStart = json.indexOf("{", idx);
        int objEnd = json.indexOf("}", objStart);
        if (objStart == -1 || objEnd == -1) return null;
        String inner = json.substring(objStart + 1, objEnd).trim();
        if (inner.isEmpty()) return new HashMap<>();
        Map<String, Double> out = new HashMap<>();
        String[] parts = inner.split(",");
        for (String p : parts) {
            int colon = p.indexOf(":");
            if (colon == -1) continue;
            String k = p.substring(0, colon).trim();
            String v = p.substring(colon + 1).trim();
            if (k.startsWith("\"") && k.endsWith("\"")) k = k.substring(1, k.length() - 1);
            try {
                double dv = Double.parseDouble(v.replaceAll("[^0-9.\\-eE]", ""));
                out.put(k, dv);
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    // Compute settlements from balances map -> list of transactions {from,to,amount}
    private static List<Map<String, Object>> computeSettlements(Map<String, Double> balances) {
        List<Map<String, Object>> transactions = new ArrayList<>();

        // separate debtors (negative) and creditors (positive)
        List<Map.Entry<String, Double>> debtors = new ArrayList<>();
        List<Map.Entry<String, Double>> creditors = new ArrayList<>();
        for (Map.Entry<String, Double> e : balances.entrySet()) {
            double v = e.getValue();
            if (v < -0.01) debtors.add(new AbstractMap.SimpleEntry<>(e.getKey(), Math.abs(v)));
            else if (v > 0.01) creditors.add(new AbstractMap.SimpleEntry<>(e.getKey(), v));
        }

        debtors.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        creditors.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        int di = 0, ci = 0;
        while (di < debtors.size() && ci < creditors.size()) {
            String debtor = debtors.get(di).getKey();
            double debt = debtors.get(di).getValue();
            String creditor = creditors.get(ci).getKey();
            double credit = creditors.get(ci).getValue();

            double amount = Math.min(debt, credit);

            Map<String, Object> t = new HashMap<>();
            t.put("from", debtor);
            t.put("to", creditor);
            t.put("amount", amount);
            transactions.add(t);

            debtors.get(di).setValue(debt - amount);
            creditors.get(ci).setValue(credit - amount);

            if (Math.abs(debtors.get(di).getValue()) < 0.01) di++;
            if (Math.abs(creditors.get(ci).getValue()) < 0.01) ci++;
        }
        return transactions;
    }
}
