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
    private static final String STATIC_DIR = ".";

    // In-memory storage
    private static final List<Expense> expenses = new ArrayList<>();
    private static final Map<String, Double> balances = new HashMap<>();

    static class Expense {
        String payer;
        double total;
        List<String> participants;
        String splitType; // "equal", "exact", "percentage", "shares"
        Map<String, Double> splitDetails; // for exact: amount, percentage: %, shares: share count

        public Expense(String payer, double total, List<String> participants, String splitType, Map<String, Double> splitDetails) {
            this.payer = payer;
            this.total = total;
            this.participants = participants;
            this.splitType = splitType;
            this.splitDetails = splitDetails;
        }
    }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", new StaticFileHandler());
        server.createContext("/addExpense", new AddExpenseHandler());
        server.createContext("/balances", new BalancesHandler());
        server.createContext("/settlements", new SettlementsHandler());
        server.createContext("/expenses", new ExpensesHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("Server started on http://localhost:" + PORT);
    }

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestMethod = exchange.getRequestMethod();
            if (!requestMethod.equals("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/home.html";
            } else if (path.equals("/app")) {
                path = "/app.html";
            }

            String filePath = path.substring(1); // Remove leading slash
            File file = new File(STATIC_DIR, filePath);
            System.out.println("Requested path: " + path + ", filePath: " + filePath + ", full path: " + file.getAbsolutePath() + ", exists: " + file.exists());
            if (!file.exists()) {
                System.out.println("File not found: " + file.getAbsolutePath());
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            byte[] fileBytes = Files.readAllBytes(file.toPath());
            String contentType = getContentType(path);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, fileBytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(fileBytes);
            os.close();
        }

        private String getContentType(String path) {
            if (path.endsWith(".html")) return "text/html";
            if (path.endsWith(".css")) return "text/css";
            if (path.endsWith(".js")) return "application/javascript";
            return "text/plain";
        }
    }

    static class AddExpenseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
            BufferedReader br = new BufferedReader(isr);
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            String json = sb.toString();

            Map<String, Object> requestData = parseAddExpenseJson(json);
            Map<String, Object> response = new HashMap<>();

            String payer = (String) requestData.get("payer");
            double total = (double) requestData.get("total");
            List<String> participants = (List<String>) requestData.get("participants");
            String splitType = (String) requestData.get("splitType");
            Map<String, Double> splitDetails = (Map<String, Double>) requestData.get("splitDetails");

            if (payer == null || payer.isEmpty() || participants.isEmpty() || total <= 0) {
                response.put("error", "Invalid input data");
            } else {
                // Calculate individual shares
                Map<String, Object> individualShares = calculateShares(participants, splitType, splitDetails, total);

                if (individualShares.containsKey("error")) {
                    response.put("error", individualShares.get("error"));
                } else {
                    // Create expense
                    Expense expense = new Expense(payer, total, participants, splitType, splitDetails);
                    expenses.add(expense);

                    // Update balances
                    // Payer paid the total
                    balances.put(payer, balances.getOrDefault(payer, 0.0) + total);

                    // Each participant owes their share
                    for (Map.Entry<String, Object> entry : individualShares.entrySet()) {
                        String person = entry.getKey();
                        double share = (Double) entry.getValue();
                        balances.put(person, balances.getOrDefault(person, 0.0) - share);
                    }

                    response.put("success", true);
                }
            }

            String responseJson = jsonToString(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseJson.getBytes(StandardCharsets.UTF_8).length);
            OutputStream os = exchange.getResponseBody();
            os.write(responseJson.getBytes(StandardCharsets.UTF_8));
            os.close();
        }

        private Map<String, Object> parseAddExpenseJson(String json) {
            Map<String, Object> map = new HashMap<>();

            // Parse payer more robustly
            int payerStartIdx = json.indexOf("\"payer\":");
            if (payerStartIdx != -1) {
                int afterColon = json.indexOf(":", payerStartIdx) + 1;
                int quoteStart = json.indexOf("\"", afterColon);
                if (quoteStart != -1) {
                    int quoteEnd = json.indexOf("\"", quoteStart + 1);
                    if (quoteEnd != -1) {
                        String payer = json.substring(quoteStart + 1, quoteEnd).trim();
                        map.put("payer", payer);
                    }
                }
            }

            // Parse total more robustly
            int totalStartIdx = json.indexOf("\"total\":");
            if (totalStartIdx != -1) {
                int afterColon = json.indexOf(":", totalStartIdx) + 1;
                int end = json.indexOf(",", afterColon);
                if (end == -1) end = json.indexOf("}", afterColon);
                if (end != -1) {
                    String totalStr = json.substring(afterColon, end).trim();
                    try {
                        double total = Double.parseDouble(totalStr);
                        map.put("total", total);
                    } catch (NumberFormatException e) {
                        // Ignore
                    }
                }
            }

            // Parse participants more robustly
            int partStartIdx = json.indexOf("\"participants\":[");
            if (partStartIdx != -1) {
                int arrayStart = json.indexOf("[", partStartIdx) + 1;
                int arrayEnd = json.indexOf("]", arrayStart);
                if (arrayEnd != -1) {
                    String partStr = json.substring(arrayStart, arrayEnd).trim();
                    List<String> participants = new ArrayList<>();
                    if (!partStr.isEmpty()) {
                        String[] partParts = partStr.split(",");
                        for (String part : partParts) {
                            String p = part.trim();
                            if (p.startsWith("\"") && p.endsWith("\"")) {
                                p = p.substring(1, p.length() - 1).trim();
                            }
                            if (!p.isEmpty()) {
                                participants.add(p);
                            }
                        }
                    }
                    map.put("participants", participants);
                }
            }

            // Parse splitType more robustly
            int typeStartIdx = json.indexOf("\"splitType\":");
            if (typeStartIdx != -1) {
                int afterColon = json.indexOf(":", typeStartIdx) + 1;
                int quoteStart = json.indexOf("\"", afterColon);
                if (quoteStart != -1) {
                    int quoteEnd = json.indexOf("\"", quoteStart + 1);
                    if (quoteEnd != -1) {
                        String splitType = json.substring(quoteStart + 1, quoteEnd).trim();
                        map.put("splitType", splitType);
                    }
                }
            }

            // Parse splitDetails more robustly
            Map<String, Double> splitDetails = new HashMap<>();
            int detailsStartIdx = json.indexOf("\"splitDetails\":");
            if (detailsStartIdx != -1) {
                int afterColon = json.indexOf(":", detailsStartIdx) + 1;
                int objStart = json.indexOf("{", afterColon);
                if (objStart != -1) {
                    int objEnd = json.indexOf("}", objStart + 1);
                    if (objEnd != -1) {
                        String detailsStr = json.substring(objStart + 1, objEnd).trim();
                        if (!detailsStr.isEmpty()) {
                            String[] pairs = detailsStr.split(",");
                            for (String pair : pairs) {
                                pair = pair.trim();
                                int colon = pair.indexOf(":");
                                if (colon > 0) {
                                    String keyStr = pair.substring(0, colon).trim();
                                    if (keyStr.startsWith("\"") && keyStr.endsWith("\"")) {
                                        keyStr = keyStr.substring(1, keyStr.length() - 1).trim();
                                    }
                                    String valStr = pair.substring(colon + 1).trim();
                                    try {
                                        double val = Double.parseDouble(valStr);
                                        splitDetails.put(keyStr, val);
                                    } catch (NumberFormatException e) {
                                        // Ignore invalid
                                    }
                                }
                            }
                        }
                    }
                }
            }
            map.put("splitDetails", splitDetails);

            return map;
        }

        private Map<String, Object> calculateShares(List<String> participants, String splitType, Map<String, Double> splitDetails, double total) {
            Map<String, Object> shares = new HashMap<>();

            if ("equal".equals(splitType)) {
                double share = total / participants.size();
                for (String p : participants) {
                    shares.put(p, share);
                }
            } else if ("exact".equals(splitType)) {
                double sum = 0;
                for (Double val : splitDetails.values()) {
                    sum += val;
                }
                if (Math.abs(sum - total) > 0.01) {
                    shares.put("error", "Sum of exact amounts must equal total");
                    return shares;
                }
                for (Map.Entry<String, Double> entry : splitDetails.entrySet()) {
                    shares.put(entry.getKey(), entry.getValue());
                }
            } else if ("percentage".equals(splitType)) {
                double sumPerc = 0;
                for (Double perc : splitDetails.values()) {
                    sumPerc += perc;
                }
                if (Math.abs(sumPerc - 100) > 0.01) {
                    shares.put("error", "Sum of percentages must be 100");
                    return shares;
                }
                for (Map.Entry<String, Double> entry : splitDetails.entrySet()) {
                    double share = (entry.getValue() / 100) * total;
                    shares.put(entry.getKey(), share);
                }
            } else if ("shares".equals(splitType)) {
                double totalShares = 0;
                for (Double sh : splitDetails.values()) {
                    totalShares += sh;
                }
                for (Map.Entry<String, Double> entry : splitDetails.entrySet()) {
                    double share = (entry.getValue() / totalShares) * total;
                    shares.put(entry.getKey(), share);
                }
            } else {
                shares.put("error", "Invalid split type");
            }

            // Ensure all participants have shares
            for (String p : participants) {
                if (!shares.containsKey(p)) {
                    shares.put(p, 0.0);
                }
            }

            return shares;
        }
    }

    static class BalancesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            Map<String, Object> response = new HashMap<>();
            response.put("balances", balances);

            String responseJson = jsonToString(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseJson.getBytes(StandardCharsets.UTF_8).length);
            OutputStream os = exchange.getResponseBody();
            os.write(responseJson.getBytes(StandardCharsets.UTF_8));
            os.close();
        }
    }

    static class SettlementsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            List<Map<String, Object>> settlements = computeSettlements();

            Map<String, Object> response = new HashMap<>();
            response.put("settlements", settlements);

            String responseJson = jsonToString(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseJson.getBytes(StandardCharsets.UTF_8).length);
            OutputStream os = exchange.getResponseBody();
            os.write(responseJson.getBytes(StandardCharsets.UTF_8));
            os.close();
        }

        private List<Map<String, Object>> computeSettlements() {
            List<Map<String, Object>> transactions = new ArrayList<>();

            // Get debtors and creditors
            List<Map.Entry<String, Double>> debtors = new ArrayList<>();
            List<Map.Entry<String, Double>> creditors = new ArrayList<>();

            for (Map.Entry<String, Double> entry : balances.entrySet()) {
                double bal = entry.getValue();
                if (bal < -0.01) { // owes money (negative)
                    debtors.add(new AbstractMap.SimpleEntry<>(entry.getKey(), Math.abs(bal)));
                } else if (bal > 0.01) { // owed money (positive)
                    creditors.add(new AbstractMap.SimpleEntry<>(entry.getKey(), bal));
                }
            }

            // Sort descending by amount
            debtors.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            creditors.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            int dIdx = 0, cIdx = 0;
            while (dIdx < debtors.size() && cIdx < creditors.size()) {
                String debtor = debtors.get(dIdx).getKey();
                double debt = debtors.get(dIdx).getValue();
                String creditor = creditors.get(cIdx).getKey();
                double credit = creditors.get(cIdx).getValue();

                double amount = Math.min(debt, credit);

                Map<String, Object> trans = new HashMap<>();
                trans.put("from", debtor);
                trans.put("to", creditor);
                trans.put("amount", amount);
                transactions.add(trans);

                // Update balances
                balances.put(debtor, balances.get(debtor) + amount);
                balances.put(creditor, balances.get(creditor) - amount);

                // Move indices if settled
                if (Math.abs(balances.get(debtor)) < 0.01) dIdx++;
                if (Math.abs(balances.get(creditor)) < 0.01) cIdx++;

                // Resort if needed, but for simplicity, continue
            }

            return transactions;
        }
    }

    private static String jsonToString(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof String) {
                sb.append("\"").append(value).append("\"");
            } else if (value instanceof Double || value instanceof Integer || value instanceof Boolean) {
                sb.append(value);
            } else if (value instanceof Map) {
                sb.append(mapToJson((Map) value));
            } else if (value instanceof List) {
                sb.append(listToJson((List) value));
            } else {
                sb.append(value);
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String mapToJson(Map<?, ?> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> entry : m.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            Object val = entry.getValue();
            if (val instanceof String) {
                sb.append("\"").append(val).append("\"");
            } else {
                sb.append(val);
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String listToJson(List<?> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            Object item = list.get(i);
            if (item instanceof String) {
                sb.append("\"").append(item).append("\"");
            } else if (item instanceof Map) {
                sb.append(mapToJson((Map) item));
            } else if (item instanceof Expense) {
                sb.append(expenseToJson((Expense) item));
            } else {
                sb.append(item);
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private static String expenseToJson(Expense e) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"payer\":\"").append(e.payer).append("\",");
        sb.append("\"total\":").append(e.total).append(",");
        sb.append("\"participants\":").append(listToJson(e.participants)).append(",");
        sb.append("\"splitType\":\"").append(e.splitType).append("\",");
        sb.append("\"splitDetails\":").append(mapToJson(e.splitDetails));
        sb.append("}");
        return sb.toString();
    }

    static class ExpensesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            Map<String, Object> response = new HashMap<>();
            response.put("expenses", expenses);

            String responseJson = jsonToString(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseJson.getBytes(StandardCharsets.UTF_8).length);
            OutputStream os = exchange.getResponseBody();
            os.write(responseJson.getBytes(StandardCharsets.UTF_8));
            os.close();
        }
    }
}
