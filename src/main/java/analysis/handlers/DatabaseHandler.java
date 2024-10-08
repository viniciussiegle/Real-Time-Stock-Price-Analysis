package analysis.handlers;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.sql.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * A class that handles all operations to the specified database.
 */
public class DatabaseHandler {

    private final String url;


    /**
     * A class that handles all operations to the specified database.
     * @param url the url from the database
     */
    public DatabaseHandler(String url) {
        this.url = url;
    }


    /**
     * Creates a new table using the data in the given .csv file. Overwrites already existing table with the same name.
     * @param file .csv file to be read from
     */
    protected void updateDB(File file) {
        // Get names and paths
        String csvPath = file.getPath();
        String fileName = file.getName().toLowerCase();
        String tableName = fileName.substring(0, fileName.lastIndexOf('.'));

        // Prepare statement text
        String drop = "DROP TABLE IF EXISTS " + tableName;
        String create = "CREATE TABLE " + tableName + "(Date TEXT, Open REAL, High REAL, Low REAL, Close REAL, Volume REAL)";
        String insert = "INSERT INTO " + tableName + " VALUES (?, ?, ?, ?, ?, ?)";

        try (
                // Connect to database
                Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()
        ) {
            // Reset / Drop table if exists
            statement.executeUpdate(drop);
            statement.executeUpdate(create);

            // Insert data from csv files
            insertCSVRecords(csvPath, connection, insert);
        }
        catch (SQLException e) {
            System.out.println(e.getMessage());
        }
    }

    /**
     * Inserts the data from the csv file in the given path using the given connection and prepared statement text
     * @param csvPath the path from the source csv file
     * @param connection the database connection
     * @param insert the prepared statement text
     * @throws SQLException if a database access error occurs, or this method is called on a closed connection
     */
    private void insertCSVRecords(String csvPath, Connection connection, String insert) throws SQLException {
        try (
                // Open reader for CSV File
                FileReader reader = new FileReader(csvPath);
                CSVParser csvParser = new CSVParser(reader,
                        CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build());

                // Prepare statement
                PreparedStatement preparedStatement = connection.prepareStatement(insert)
        ) {
            // Allow batch processing
            connection.setAutoCommit(false);

            // Iterate over CSV Records
            for (CSVRecord record : csvParser) {
                // Convert date types for better compatibility with SQLite
                SimpleDateFormat format1 = new SimpleDateFormat("MM/dd/yyyy");
                SimpleDateFormat format2 = new SimpleDateFormat("yyyy-MM-dd");
                preparedStatement.setString(1, format2.format(format1.parse(record.get(0))));

                // Set remaining entries
                for (int i = 1; i < record.size(); i++) {
                    preparedStatement.setString(i + 1, record.get(i));
                }

                preparedStatement.addBatch();
            }

            // Execute batch insert and commit transaction
            preparedStatement.executeBatch();
            connection.commit();

            // Return changes to default
            connection.setAutoCommit(true);
        }
        catch (IOException | ParseException e) {
            System.out.println(e.getMessage());
        }
    }


    /**
     * Gets a list of available stock tickers in the database. Can serve as a list of valid tickers to avoid injections.
     * @return the list of available stock tickers if the database is not empty, null otherwise
     */
    protected List<String> getAvailableStocks() {
        // Get available stock tickers based on existing table names
        List<String> availableStocks = null;
        try (
                Connection connection = DriverManager.getConnection(url)
        ) {
            // Get table names from Metadata
            DatabaseMetaData metaData = connection.getMetaData();
            ResultSet tables = metaData.getTables(null, null, "%", null);
            availableStocks = new ArrayList<>();
            tables.next(); // skip header line
            while (tables.next()) {
                availableStocks.add(tables.getString("TABLE_NAME"));
            }
        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }

        return availableStocks;
    }


    /**
     * Gets the Simple Moving Average (SMA) of a stock for a valid stock ticker in the given time period,
     * starting at the most recent data entries.
     * @param stock the stock ticker to analyze
     * @param days the time period to be analyzed in, in past days from most recent entry
     * @return the Simple Moving Average of the stock if it is valid, 0 otherwise
     */
    protected float getSMA (String stock, int days) {
        // Create query for average
        String query =
                "SELECT                                                                       "
                +"    AVG(Close)                                                              "
                +"FROM                                                                        "
                +"    " + stock + "                                                           "
                +"WHERE                                                                       "
                +"    Date > DATE((SELECT MAX(DATE) FROM " + stock + "), '-" + days + " days')";

        return runQuery(query, stock);
    }


    /**
     * Gets the Exponential Moving Average (EMA) of a stock for a valid stock ticker in the given time period,
     * starting at the most recent data entries.
     * @param stock the stock ticker to analyze
     * @param days the time period to be analyzed in, in past days from most recent entry
     * @return the Exponential Moving Average of the stock if it is valid, 0 otherwise
     */
    protected float getEMA (String stock, int days) {
        // Calculate alpha (smoothing factor) of the EMA
        float alpha = 2 / (float)(days + 1);

        // Create query with recursive CTEs
        String query =
                "WITH RECURSIVE                                                                          "
                +"    scope AS (                                                                         "
                +"        -- Isolate necessary values                                                  \n"
                +"        SELECT                                                                         "
                +"            Date,                                                                      "
                +"            Close,                                                                     "
                +"            ROW_NUMBER() OVER (ORDER BY DATE DESC) as row_number                       "
                +"        FROM                                                                           "
                +"            " + stock + "                                                              "
                +"        WHERE                                                                          "
                +"            Date > DATE((SELECT MAX(DATE) FROM " + stock + "), '-" + days + " days')   "
                +"    ),                                                                                 "
                +"    ema_calc AS(                                                                       "
                +"        -- Get Close value of first date as initial EMA                              \n"
                +"        SELECT                                                                         "
                +"            *,                                                                         "
                +"            Close as EMA                                                               "
                +"        FROM                                                                           "
                +"            scope                                                                      "
                +"        WHERE                                                                          "
                +"            Date = (SELECT MIN(Date) FROM scope)                                       "
                +"                                                                                       "
                +"        UNION ALL                                                                      "
                +"                                                                                       "
                +"        -- Calculate EMA for subsequent dates                                        \n"
                +"        SELECT                                                                         "
                +"            scope.Date,                                                                "
                +"            scope.Close,                                                               "
                +"            scope.row_number,                                                          "
                +"            (scope.Close * " + alpha + ") + (ema_calc.EMA * (1 - " + alpha + ")) as EMA"
                +"        FROM                                                                           "
                +"            scope                                                                      "
                +"        JOIN                                                                           "
                +"            ema_calc                                                                   "
                +"        ON                                                                             "
                +"            scope.row_number = ema_calc.row_number - 1                                 "
                +"        WHERE                                                                          "
                +"            scope.Date <= (SELECT MAX(Date) FROM scope)                                "
                +"    )                                                                                  "
                +"                                                                                       "
                +"SELECT EMA, MAX(Date) FROM ema_calc;                                                   ";

        return runQuery(query, stock);
    }


    /**
     * Gets the Price Volatility of a stock for a valid stock ticker in the given time period, starting at
     * the most recent data entries.
     * @param stock the stock ticker to analyze
     * @param days the time period to be analyzed in, in past days from most recent entry
     * @return the Price Volatility of the stock if it is valid, 0 otherwise
     */
    protected float getVolatility (String stock, int days) {
        // Create query for Variance
        String query =
                "WITH scope AS (                                                                   "
                +"    SELECT                                                                       "
                +"        Close as close,                                                          "
                +"        AVG(Close) OVER () AS avg                                                "
                +"    FROM                                                                         "
                +"        " + stock + "                                                            "
                +"    WHERE                                                                        "
                +"        Date >= DATE((SELECT MAX(DATE) FROM " + stock + "), '-" + days + " days')"
                +")                                                                                "
                +"SELECT                                                                           "
                +"    AVG((scope.close - scope.avg) * (scope.close - scope.avg)) as variance       "
                +"FROM scope;                                                                      ";

        // Return Volatility (Standard Deviation)
        float variance = runQuery(query, stock);
        return (float) Math.sqrt(variance);
    }


    /**
     * Runs the analysis query on the database for a given valid stock and returns the resulting value.
     * @param query the query to be executed on the database
     * @param stock the stock based on which the analysis should be executed
     * @return the result of the query if the stock is valid, 0 otherwise
     */
    private float runQuery (String query, String stock) {
        // Restrict again to only valid strings to avoid injections
        if (!getAvailableStocks().contains(stock)) {
            return 0;
        }

        // Execute query
        float result = 0;
        try (
                Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()
        ) {
            ResultSet resultSet = statement.executeQuery(query);
            resultSet.next(); // skip header line
            result = resultSet.getFloat(1);
        }
        catch (SQLException e) {
            System.out.println(e.getMessage());
        }

        return result;
    }


}
