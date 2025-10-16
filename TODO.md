# TODO: Add MySQL Database Integration to Fairshare App

## Steps to Complete

1. **Download MySQL JDBC Driver** ✅
   - Downloaded mysql-connector-java.jar (version 8.0.32) to the project directory.
   - Add it to classpath for compilation and runtime.

2. **Modify App.java - Add JDBC Imports and Connection Setup**
   - Add necessary imports: java.sql.*
   - Add static Connection conn;
   - In main(), establish DB connection to localhost:3306/fairshare, user root, pass pass123.
   - Create tables if not exist: expenses (id INT AUTO_INCREMENT PRIMARY KEY, payer VARCHAR(255), total DOUBLE, participants TEXT, splitType VARCHAR(50), splitDetails TEXT), balances (person VARCHAR(255) PRIMARY KEY, balance DOUBLE).

3. **Update AddExpenseHandler**
   - Insert expense into expenses table (serialize participants as comma-separated string, splitDetails as JSON string).
   - Update balances table: payer += total, each participant -= share.

4. **Update BalancesHandler**
   - Select all from balances table and return as map.

5. **Update SettlementsHandler**
   - Select balances from DB.
   - Compute settlements (similar logic).
   - Update balances in DB after computing transactions.
   - Return transactions.

6. **Update ExpensesHandler**
   - Select all from expenses table, deserialize to Expense objects, return list.

7. **Update ClearHandler**
   - Delete all from expenses and balances tables.

8. **Test the Integration**
   - Compile and run the app.
   - Test adding expense, viewing balances, settlements, expenses, clearing.
   - Handle any DB connection or query errors.

## Notes
- Use simple serialization for complex fields (participants: String.join(",", list), splitDetails: mapToJson).
- For deserialization, add helper methods in App.java.
- Ensure balances are maintained accurately in DB.
