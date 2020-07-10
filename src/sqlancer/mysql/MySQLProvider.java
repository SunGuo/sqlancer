package sqlancer.mysql;

import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.AbstractAction;
import sqlancer.IgnoreMeException;
import sqlancer.Main.QueryManager;
import sqlancer.MainOptions;
import sqlancer.ProviderAdapter;
import sqlancer.Query;
import sqlancer.QueryAdapter;
import sqlancer.QueryProvider;
import sqlancer.Randomly;
import sqlancer.StateToReproduce;
import sqlancer.StateToReproduce.MySQLStateToReproduce;
import sqlancer.StatementExecutor;
import sqlancer.TestOracle;
import sqlancer.mysql.MySQLSchema.MySQLColumn;
import sqlancer.mysql.MySQLSchema.MySQLTable;
import sqlancer.mysql.gen.MySQLAlterTable;
import sqlancer.mysql.gen.MySQLDeleteGenerator;
import sqlancer.mysql.gen.MySQLDropIndex;
import sqlancer.mysql.gen.MySQLInsertGenerator;
import sqlancer.mysql.gen.MySQLSetGenerator;
import sqlancer.mysql.gen.MySQLTableGenerator;
import sqlancer.mysql.gen.MySQLTruncateTableGenerator;
import sqlancer.mysql.gen.admin.MySQLFlush;
import sqlancer.mysql.gen.admin.MySQLReset;
import sqlancer.mysql.gen.datadef.MySQLIndexGenerator;
import sqlancer.mysql.gen.tblmaintenance.MySQLAnalyzeTable;
import sqlancer.mysql.gen.tblmaintenance.MySQLCheckTable;
import sqlancer.mysql.gen.tblmaintenance.MySQLChecksum;
import sqlancer.mysql.gen.tblmaintenance.MySQLOptimize;
import sqlancer.mysql.gen.tblmaintenance.MySQLRepair;
import sqlancer.mysql.oracle.MySQLTLPWhereOracle;
import sqlancer.sqlite3.gen.SQLite3Common;

public class MySQLProvider extends ProviderAdapter<MySQLGlobalState, MySQLOptions> {

    private QueryManager manager;
    private String databaseName;

    public MySQLProvider() {
        super(MySQLGlobalState.class, MySQLOptions.class);
    }

    enum Action implements AbstractAction<MySQLGlobalState> {
        SHOW_TABLES((g) -> new QueryAdapter("SHOW TABLES")), //
        INSERT(MySQLInsertGenerator::insertRow), //
        SET_VARIABLE(MySQLSetGenerator::set), //
        REPAIR(MySQLRepair::repair), //
        OPTIMIZE(MySQLOptimize::optimize), //
        CHECKSUM(MySQLChecksum::checksum), //
        CHECK_TABLE(MySQLCheckTable::check), //
        ANALYZE_TABLE(MySQLAnalyzeTable::analyze), //
        FLUSH(MySQLFlush::create), RESET(MySQLReset::create), CREATE_INDEX(MySQLIndexGenerator::create), //
        ALTER_TABLE(MySQLAlterTable::create), //
        TRUNCATE_TABLE(MySQLTruncateTableGenerator::generate), //
        SELECT_INFO((g) -> new QueryAdapter(
                "select TABLE_NAME, ENGINE from information_schema.TABLES where table_schema = '" + g.getDatabaseName()
                        + "'")), //
        CREATE_TABLE((g) -> {
            // TODO refactor
            String tableName = SQLite3Common.createTableName(g.getSchema().getDatabaseTables().size());
            return MySQLTableGenerator.generate(tableName, g.getRandomly(), g.getSchema());
        }), //
        DELETE(MySQLDeleteGenerator::delete), //
        DROP_INDEX(MySQLDropIndex::generate);

        private final QueryProvider<MySQLGlobalState> queryProvider;

        Action(QueryProvider<MySQLGlobalState> queryProvider) {
            this.queryProvider = queryProvider;
        }

        @Override
        public Query getQuery(MySQLGlobalState globalState) throws SQLException {
            return queryProvider.getQuery(globalState);
        }
    }

    private static int mapActions(MySQLGlobalState globalState, Action a) {
        Randomly r = globalState.getRandomly();
        int nrPerformed = 0;
        switch (a) {
        case DROP_INDEX:
            nrPerformed = r.getInteger(0, 2);
            break;
        case SHOW_TABLES:
            nrPerformed = r.getInteger(0, 1);
            break;
        case CREATE_TABLE:
            nrPerformed = r.getInteger(0, 1);
            break;
        case INSERT:
            nrPerformed = r.getInteger(0, globalState.getOptions().getMaxNumberInserts());
            break;
        case REPAIR:
            nrPerformed = r.getInteger(0, 1);
            break;
        case SET_VARIABLE:
            nrPerformed = r.getInteger(0, 5);
            break;
        case CREATE_INDEX:
            nrPerformed = r.getInteger(0, 5);
            break;
        case FLUSH:
            nrPerformed = Randomly.getBooleanWithSmallProbability() ? r.getInteger(0, 1) : 0;
            break;
        case OPTIMIZE:
            // seems to yield low CPU utilization
            nrPerformed = Randomly.getBooleanWithSmallProbability() ? r.getInteger(0, 1) : 0;
            break;
        case RESET:
            // affects the global state, so do not execute
            nrPerformed = globalState.getOptions().getNumberConcurrentThreads() == 1 ? r.getInteger(0, 1) : 0;
            break;
        case CHECKSUM:
        case CHECK_TABLE:
        case ANALYZE_TABLE:
            nrPerformed = r.getInteger(0, 2);
            break;
        case ALTER_TABLE:
            nrPerformed = r.getInteger(0, 5);
            break;
        case TRUNCATE_TABLE:
            nrPerformed = r.getInteger(0, 2);
            break;
        case SELECT_INFO:
            nrPerformed = r.getInteger(0, 10);
            break;
        case DELETE:
            nrPerformed = r.getInteger(0, 10);
            break;
        default:
            throw new AssertionError(a);
        }
        return nrPerformed;
    }

    @Override
    public void generateAndTestDatabase(MySQLGlobalState globalState) throws SQLException {
        this.databaseName = globalState.getDatabaseName();
        this.manager = globalState.getManager();
        MainOptions options = globalState.getOptions();
        Randomly r = globalState.getRandomly();

        while (globalState.getSchema().getDatabaseTables().size() < Randomly.smallNumber() + 1) {
            String tableName = SQLite3Common.createTableName(globalState.getSchema().getDatabaseTables().size());
            Query createTable = MySQLTableGenerator.generate(tableName, r, globalState.getSchema());
            globalState.executeStatement(createTable);
        }

        StatementExecutor<MySQLGlobalState, Action> se = new StatementExecutor<>(globalState, Action.values(),
                MySQLProvider::mapActions, (q) -> {
                    if (globalState.getSchema().getDatabaseTables().isEmpty()) {
                        throw new IgnoreMeException();
                    }
                });
        se.executeStatements();
        manager.incrementCreateDatabase();

        TestOracle oracle = new MySQLTLPWhereOracle(globalState);
        for (int i = 0; i < options.getNrQueries(); i++) {
            try {
                oracle.check();
                manager.incrementSelectQueryCount();
            } catch (IgnoreMeException e) {

            }
        }

    }

    public static int getNrRows(Connection con, MySQLTable table) throws SQLException {
        try (Statement s = con.createStatement()) {
            try (ResultSet query = s.executeQuery("SELECT COUNT(*) FROM " + table.getName())) {
                query.next();
                return query.getInt(1);
            }
        }
    }

    @Override
    public Connection createDatabase(MySQLGlobalState globalState) throws SQLException {
        String databaseName = globalState.getDatabaseName();
        globalState.getState().statements.add(new QueryAdapter("DROP DATABASE IF EXISTS " + databaseName));
        globalState.getState().statements.add(new QueryAdapter("CREATE DATABASE " + databaseName));
        globalState.getState().statements.add(new QueryAdapter("USE " + databaseName));
        String url = "jdbc:mysql://localhost:3306/?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
        Connection con = DriverManager.getConnection(url, globalState.getOptions().getUserName(),
                globalState.getOptions().getPassword());
        try (Statement s = con.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + databaseName);
        }
        try (Statement s = con.createStatement()) {
            s.execute("CREATE DATABASE " + databaseName);
        }
        try (Statement s = con.createStatement()) {
            s.execute("USE " + databaseName);
        }
        return con;
    }

    @Override
    public String getDBMSName() {
        return "mysql";
    }

    @Override
    public String toString() {
        return String.format("MySQLProvider [database: %s]", databaseName);
    }

    @Override
    public void printDatabaseSpecificState(FileWriter writer, StateToReproduce state) {
        StringBuilder sb = new StringBuilder();
        MySQLStateToReproduce specificState = (MySQLStateToReproduce) state;
        if (specificState.getRandomRowValues() != null) {
            List<MySQLColumn> columnList = specificState.getRandomRowValues().keySet().stream()
                    .collect(Collectors.toList());
            List<MySQLTable> tableList = columnList.stream().map(c -> c.getTable()).distinct().sorted()
                    .collect(Collectors.toList());
            for (MySQLTable t : tableList) {
                sb.append("-- " + t.getName() + "\n");
                List<MySQLColumn> columnsForTable = columnList.stream().filter(c -> c.getTable().equals(t))
                        .collect(Collectors.toList());
                for (MySQLColumn c : columnsForTable) {
                    sb.append("--\t");
                    sb.append(c);
                    sb.append("=");
                    sb.append(specificState.getRandomRowValues().get(c));
                    sb.append("\n");
                }
            }
            sb.append("expected values: \n");
            sb.append(MySQLVisitor.asExpectedValues(((MySQLStateToReproduce) state).getWhereClause()));
        }
        try {
            writer.write(sb.toString());
            writer.flush();
        } catch (IOException e) {
            throw new AssertionError();
        }
    }

    @Override
    public StateToReproduce getStateToReproduce(String databaseName) {
        return new MySQLStateToReproduce(databaseName);
    }

}
