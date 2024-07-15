package fordevs.dynamicqueryengine.service;

import fordevs.dynamicqueryengine.config.DataSourceContextService;
import fordevs.dynamicqueryengine.config.DynamicDataSourceManager;
import fordevs.dynamicqueryengine.dto.DatabaseCredentials;
import fordevs.dynamicqueryengine.dto.DynamicTableData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the DatabaseService interface.
 * This class handles the business logic for database operations.
 */
@Slf4j
@Service
public class DatabaseServiceImpl implements DatabaseService {

    @Autowired
    private DynamicDataSourceManager dynamicDataSourceManager;

    @Autowired
    private DataSourceContextService dataSourceContextService;

    @Autowired
    private SchemaDiscoveryService schemaDiscoveryService;

    @Autowired
    private Environment environment;

    private DatabaseCredentials databaseCredentials;

    @Override
    public ResponseEntity<String> connectToDatabaseDynamically(DatabaseCredentials databaseCredentials) {
        // Validate the provided credentials
        if (!validateCredentials(databaseCredentials)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid credentials provided");
        }

        this.databaseCredentials = databaseCredentials; // Store the credentials for future use

        try {
            // Try to create and test a database connection with the provided credentials.
            // The createAndTestConnection method returns true if the connection is successful.
            // The '!' operator inverts the result, so this condition is true if the connection fails.
            if (!dynamicDataSourceManager.createAndTestConnection(databaseCredentials)) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to connect to database: " + databaseCredentials.getDatabaseName());
            }

            // Retrieve the key for the DataSource
            String dataSourceKey = dynamicDataSourceManager.getKey(databaseCredentials);
            // Retrieve the JdbcTemplate for the database
            JdbcTemplate jdbcTemplate = dynamicDataSourceManager.getJdbcTemplateForDb(dataSourceKey);
            // Set the current JdbcTemplate in the data source context
            dataSourceContextService.setCurrentTemplate(jdbcTemplate);
            return ResponseEntity.ok("Connected successfully to database: " + databaseCredentials.getDatabaseName());
        } catch (Exception e) {
            log.error("Error connecting to the database", e);
            return handleException(e, "Error connecting to the database: ");
        }
    }

    @Override
    public ResponseEntity<List<String>> listTables() {
        // Check if credentials are set
        if (this.databaseCredentials == null) {
            log.error("Credentials must be set before calling this method.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }

        try {
            // Get the list of tables in the database
            List<String> tables = schemaDiscoveryService.listTables(this.databaseCredentials);
            log.info("Tables: {}", tables);
            return ResponseEntity.ok(tables);
        } catch (Exception e) {
            log.error("Error listing tables", e);
            return handleException(e, "Error listing tables: ");
        }
    }

    @Override
    public ResponseEntity<List<Map<String, Object>>> listColumns(String tableName) {
        // Check if credentials are set
        if (this.databaseCredentials == null) {
            log.error("Credentials must be set before calling this method.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }

        try {
            // Retrieve the key for the DataSource
            String dataSourceKey = dynamicDataSourceManager.getKey(this.databaseCredentials);
            // Get the list of columns in the specified table
            List<Map<String, Object>> columns = schemaDiscoveryService.listColumns(tableName, dataSourceKey);
            return ResponseEntity.ok(columns);
        } catch (SQLException e) {
            log.error("SQL error listing columns for table: {}", tableName, e);
            return handleException(e, "SQL error listing columns for table: ");
        } catch (Exception e) {
            log.error("Error listing columns for table: {}", tableName, e);
            return handleException(e, "Error listing columns for table: ");
        }
    }

    @Override
    public ResponseEntity<Map<String, Object>> getTableData(String tableName, int page, int size) {
        // Check if credentials are set
        if (this.databaseCredentials == null) {
            log.error("Credentials must be set before calling this method.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }

        try {
            // Retrieve table data with pagination
            ResponseEntity<DynamicTableData> responseEntity = schemaDiscoveryService.getTableDataWithPagination(tableName, this.databaseCredentials, page, size);
            DynamicTableData tableData = responseEntity.getBody();

            // Create a response map that includes the data and pagination information
            Map<String, Object> response = new HashMap<>();
            response.put("rows", tableData.getRows());
            response.put("columns", tableData.getColumns());
            response.put("currentPage", page);
            response.put("pageSize", size);
            response.put("totalRows", tableData.getTotalRows());
            response.put("tableName", tableName);

            log.info("Table Response Data: {}", response);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error obtaining data from table: {}", tableName, e);
            return handleException(e, "Error obtaining data from table: ");
        }
    }

    @Override
    public ResponseEntity<List<Map<String, Object>>> executeQuery(String query) {
        // Check if credentials are set
        if (this.databaseCredentials == null) {
            log.error("Credentials must be set before calling this method.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }

        try {
            // Retrieve the key for the DataSource
            String dataSourceKey = dynamicDataSourceManager.getKey(this.databaseCredentials);
            // Retrieve the JdbcTemplate for the database
            JdbcTemplate jdbcTemplate = dynamicDataSourceManager.getJdbcTemplateForDb(dataSourceKey);
            // Execute the query and get the result
            List<Map<String, Object>> result = jdbcTemplate.queryForList(query);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error executing query", e);
            return handleException(e, "Error executing query: ");
        }
    }

    /**
     * Validates the provided database credentials.
     *
     * @param databaseCredentials The database credentials.
     * @return True if the credentials are valid, false otherwise.
     */
    private boolean validateCredentials(DatabaseCredentials databaseCredentials) {
        return databaseCredentials.getDatabaseName() != null && !databaseCredentials.getDatabaseName().isEmpty() &&
                databaseCredentials.getHost() != null && !databaseCredentials.getHost().isEmpty() &&
                databaseCredentials.getUserName() != null && !databaseCredentials.getUserName().isEmpty() &&
                databaseCredentials.getPassword() != null && !databaseCredentials.getPassword().isEmpty();
    }

    /**
     * Handles exceptions based on the active environment.
     *
     * @param e       The exception that occurred.
     * @param message The message to log.
     * @return ResponseEntity with the appropriate error message.
     */
    private <T> ResponseEntity<T> handleException(Exception e, String message) {
        if ("prod".equals(environment.getProperty("spring.profiles.active"))) {
            return (ResponseEntity<T>) ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyMap());
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body((T) (message + e.getMessage()));
        }
    }
}
