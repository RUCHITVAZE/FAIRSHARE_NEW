import java.sql.*;
import java.util.*;

public class DBHelper {
    private static final String DB_URL = "jdbc:sqlite:fairshare.db";

    // Initialize DB and tables
    public static void initializeDB() throws Exception {
        // Ensure driver is loaded
        Class.forName("org.sqlite.JDBC");

        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {

            // expenses table: stores full expense details
            String createExpenses = "CREATE TABLE IF NOT EXISTS expenses (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "description TEXT," +
                    "amount REAL NOT NULL," +
                    "payer TEXT NOT NULL," +
                    "participants TEXT NOT NULL," +        // comma-separated
                    "splitType TEXT NOT NULL," +
                    "splitDetails TEXT," +                // JSON-like string: key:value,...
                    "created_at DATETIME DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            stmt.execute(createExpenses);

            // balances table: one row per user
            String createBalances = "CREATE TABLE IF NOT EXISTS balances (" +
                    "user TEXT PRIMARY KEY," +
                    "balance REAL NOT NULL" +
                    ")";
            stmt.execute(createBalances);
        }
    }

    // Add expense row and update balances accordingly
    // splitDetails param expects Map<String, Double> (can be empty for equal split)
    public static void addExpense(String description,
                                  double amount,
                                  String payer,
                                  List<String> participants,
                                  String splitType,
                                  Map<String, Double> splitDetails) throws SQLException {
        // Save expense
        String participantsStr = String.join(",", participants);
        String splitDetailsStr = mapToString(splitDetails); // custom serialization

        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO expenses(description, amount, payer, participants, splitType, splitDetails) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, description);
                ps.setDouble(2, amount);
                ps.setString(3, payer);
                ps.setString(4, participantsStr);
                ps.setString(5, splitType);
                ps.setString(6, splitDetailsStr);
                ps.executeUpdate();
            }

            // Calculate shares
            Map<String, Double> shares = calculateShares(participants, splitType, splitDetails, amount);

            // Update balances:
            // payer gets +amount (we'll add amount first), then each participant gets -share
            // We perform upsert using SQLite ON CONFLICT clause
            try (PreparedStatement upsert = conn.prepareStatement(
                    "INSERT INTO balances(user, balance) VALUES (?, ?) " +
                            "ON CONFLICT(user) DO UPDATE SET balance = balance + ?")) {
                // Add payer the total amount (they paid)
                upsert.setString(1, payer);
                upsert.setDouble(2, amount);
                upsert.setDouble(3, amount);
                upsert.executeUpdate();

                // Subtract each participant's share
                for (Map.Entry<String, Double> e : shares.entrySet()) {
                    String person = e.getKey();
                    double share = e.getValue();
                    upsert.setString(1, person);
                    upsert.setDouble(2, -share);
                    upsert.setDouble(3, -share);
                    upsert.executeUpdate();
                }
            }

            conn.commit();
        }
    }

    // Returns list of expense maps for JSON serialization by App
    public static List<Map<String, Object>> getAllExpenses() throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, description, amount, payer, participants, splitType, splitDetails, created_at FROM expenses ORDER BY id ASC")) {

            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("description", rs.getString("description"));
                row.put("amount", rs.getDouble("amount"));
                row.put("payer", rs.getString("payer"));
                // Participants back to array
                String p = rs.getString("participants");
                row.put("participants", p == null || p.isEmpty() ? new ArrayList<String>() : Arrays.asList(p.split(",")));
                row.put("splitType", rs.getString("splitType"));
                row.put("splitDetails", stringToMap(rs.getString("splitDetails")));
                row.put("created_at", rs.getString("created_at"));
                list.add(row);
            }
        }
        return list;
    }

    // Return balances as Map<String, Double>
    public static Map<String, Double> getBalances() throws SQLException {
        Map<String, Double> map = new HashMap<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT user, balance FROM balances")) {
            while (rs.next()) map.put(rs.getString("user"), rs.getDouble("balance"));
        }
        return map;
    }

    // Clear both tables
    public static void clearAll() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM expenses");
            stmt.executeUpdate("DELETE FROM balances");
        }
    }

    // Helper: simple serialization map -> "key1:val1,key2:val2"
    private static String mapToString(Map<String, Double> m) {
        if (m == null || m.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Double> e : m.entrySet()) {
            if (!first) sb.append(",");
            sb.append(escape(e.getKey())).append(":").append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    // Helper: parse string back to map
    private static Map<String, Double> stringToMap(String s) {
        Map<String, Double> m = new HashMap<>();
        if (s == null || s.trim().isEmpty()) return m;
        String[] parts = s.split(",");
        for (String p : parts) {
            int idx = p.indexOf(':');
            if (idx > 0) {
                String k = unescape(p.substring(0, idx));
                String v = p.substring(idx + 1);
                try {
                    m.put(k, Double.parseDouble(v));
                } catch (NumberFormatException ignore) {}
            }
        }
        return m;
    }

    // Basic escape/unescape for ":" and "," in names
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace(":", "\\:").replace(",", "\\,");
    }

    private static String unescape(String s) {
        return s.replace("\\:", ":").replace("\\,", ",").replace("\\\\", "\\");
    }

    // Calculate shares based on split type - similar logic to your previous code
    private static Map<String, Double> calculateShares(List<String> participants, String splitType, Map<String, Double> splitDetails, double total) {
        Map<String, Double> shares = new HashMap<>();
        if ("equal".equals(splitType)) {
            double share = total / participants.size();
            for (String p : participants) shares.put(p, share);
            return shares;
        } else if ("exact".equals(splitType)) {
            double sum = 0;
            for (Double v : splitDetails.values()) sum += v;
            if (Math.abs(sum - total) > 0.01) {
                // If mismatch, fallback to equal split
                double share = total / participants.size();
                for (String p : participants) shares.put(p, share);
                return shares;
            }
            for (Map.Entry<String, Double> e : splitDetails.entrySet()) shares.put(e.getKey(), e.getValue());
            return shares;
        } else if ("percentage".equals(splitType)) {
            double sumPerc = 0;
            for (Double v : splitDetails.values()) sumPerc += v;
            if (Math.abs(sumPerc - 100) > 0.01) {
                double share = total / participants.size();
                for (String p : participants) shares.put(p, share);
                return shares;
            }
            for (Map.Entry<String, Double> e : splitDetails.entrySet()) {
                shares.put(e.getKey(), (e.getValue() / 100.0) * total);
            }
            return shares;
        } else if ("shares".equals(splitType)) {
            double totalShares = 0;
            for (Double v : splitDetails.values()) totalShares += v;
            if (totalShares <= 0) {
                double share = total / participants.size();
                for (String p : participants) shares.put(p, share);
                return shares;
            }
            for (Map.Entry<String, Double> e : splitDetails.entrySet()) {
                shares.put(e.getKey(), (e.getValue() / totalShares) * total);
            }
            return shares;
        } else {
            // default equal
            double share = total / participants.size();
            for (String p : participants) shares.put(p, share);
            return shares;
        }
    }
}
