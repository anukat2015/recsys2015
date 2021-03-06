/*
 * Copyright 2004-2009 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.bench;

import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Properties;
import java.util.Random;
import java.util.StringTokenizer;

import org.h2.test.TestBase;
import org.h2.tools.Server;
import org.h2.util.JdbcUtils;
import org.h2.util.StringUtils;

/**
 * Represents a database in the benchmark test application.
 */
class Database {

    private TestPerformance test;
    private int id;
    private String name, url, user, password;
    private ArrayList<String[]> replace = new ArrayList<String[]>();
    private String action;
    private long startTime;
    private Connection conn;
    private Statement stat;
    private boolean trace = true;
    private long lastTrace;
    private Random random = new Random(1);
    private ArrayList<Object[]> results = new ArrayList<Object[]>();
    private int totalTime;
    private int executedStatements;

    private Server serverH2;
    private Object serverDerby;
    private boolean serverHSQLDB;

    /**
     * Get the database name.
     *
     * @return the database name
     */
    String getName() {
        return name;
    }

    /**
     * Get the total measured time.
     *
     * @return the time
     */
    int getTotalTime() {
        return totalTime;
    }

    /**
     * Get the result array.
     *
     * @return the result array
     */
    ArrayList<Object[]> getResults() {
        return results;
    }

    /**
     * Get the random number generator.
     *
     * @return the generator
     */
    Random getRandom() {
        return random;
    }

    /**
     * Start the server if the this is a remote connection.
     */
    void startServer() throws Exception {
        if (url.startsWith("jdbc:h2:tcp:")) {
            serverH2 = Server.createTcpServer().start();
            Thread.sleep(100);
        } else if (url.startsWith("jdbc:derby://")) {
            serverDerby = Class.forName("org.apache.derby.drda.NetworkServerControl").newInstance();
            Method m = serverDerby.getClass().getMethod("start", PrintWriter.class);
            m.invoke(serverDerby, new Object[] { null });
            // serverDerby = new NetworkServerControl();
            // serverDerby.start(null);
            Thread.sleep(100);
        } else if (url.startsWith("jdbc:hsqldb:hsql:")) {
            if (!serverHSQLDB) {
                Class< ? > c = Class.forName("org.hsqldb.Server");
                Method m = c.getMethod("main", String[].class);
                m.invoke(null, new Object[] { new String[] { "-database.0",
                        "data/mydb;hsqldb.default_table_type=cached", "-dbname.0", "xdb" } });
                // org.hsqldb.Server.main(new String[]{"-database.0", "mydb",
                // "-dbname.0", "xdb"});
                serverHSQLDB = true;
                Thread.sleep(100);
            }
        }
    }

    /**
     * Stop the server if this is a remote connection.
     */
    void stopServer() throws Exception {
        if (serverH2 != null) {
            serverH2.stop();
            serverH2 = null;
        }
        if (serverDerby != null) {
            Method m = serverDerby.getClass().getMethod("shutdown");
            // cast for JDK 1.5
            m.invoke(serverDerby, (Object[]) null);
            // serverDerby.shutdown();
            serverDerby = null;
        } else if (serverHSQLDB) {
            // can not shut down (shutdown calls System.exit)
            // openConnection();
            // update("SHUTDOWN");
            // closeConnection();
            // serverHSQLDB = false;
        }
    }

    /**
     * Parse a database configuration and create a database object from it.
     *
     * @param test the test application
     * @param id the database id
     * @param dbString the configuration string
     * @return a new database object with the given settings
     */
    static Database parse(TestPerformance test, int id, String dbString) {
        try {
            StringTokenizer tokenizer = new StringTokenizer(dbString, ",");
            Database db = new Database();
            db.id = id;
            db.test = test;
            db.name = tokenizer.nextToken().trim();
            String driver = tokenizer.nextToken().trim();
            Class.forName(driver);
            db.url = tokenizer.nextToken().trim();
            db.user = tokenizer.nextToken().trim();
            db.password = "";
            if (tokenizer.hasMoreTokens()) {
                db.password = tokenizer.nextToken().trim();
            }
            return db;
        } catch (Exception e) {
            System.out.println("Cannot load database " + dbString + " :" + e.toString());
            return null;
        }
    }

    /**
     * Get the database connection. The connection must be opened first.
     *
     * @return the connection
     */
    Connection getConnection() {
        return conn;
    }

    /**
     * Open a new database connection. This connection must be closed
     * by calling conn.close().
     *
     * @return the opened connection
     */
    Connection openNewConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(url, user, password);
        if (url.startsWith("jdbc:derby:")) {
            // Derby: use higher cache size
            Statement stat = null;
            try {
                stat = conn.createStatement();
                // stat.execute("CALL
                // SYSCS_UTIL.SYSCS_SET_DATABASE_PROPERTY('derby.storage.pageCacheSize',
                // '64')");
                // stat.execute("CALL
                // SYSCS_UTIL.SYSCS_SET_DATABASE_PROPERTY('derby.storage.pageSize',
                // '8192')");
            } finally {
                JdbcUtils.closeSilently(stat);
            }
        } else if (url.startsWith("jdbc:hsqldb:")) {
            // HSQLDB: use a WRITE_DELAY of 1 second
            Statement stat = null;
            try {
                stat = conn.createStatement();
                stat.execute("SET WRITE_DELAY 1");
            } finally {
                JdbcUtils.closeSilently(stat);
            }
        }
        return conn;
    }

    /**
     * Open the database connection.
     */
    void openConnection() throws SQLException {
        conn = openNewConnection();
        stat = conn.createStatement();
    }

    /**
     * Close the database connection.
     */
    void closeConnection() throws SQLException {
//        if(!serverHSQLDB && url.startsWith("jdbc:hsqldb:")) {
//            stat.execute("SHUTDOWN");
//        }
        conn.close();
        stat = null;
        conn = null;
    }

    /**
     * Initialize the SQL statement translation of this database.
     *
     * @param prop the properties with the translations to use
     */
    void setTranslations(Properties prop) {
        String id = url.substring("jdbc:".length());
        id = id.substring(0, id.indexOf(':'));
        for (Object k : prop.keySet()) {
            String key = (String) k;
            if (key.startsWith(id + ".")) {
                String pattern = key.substring(id.length() + 1);
                pattern = StringUtils.replaceAll(pattern, "_", " ");
                pattern = StringUtils.toUpperEnglish(pattern);
                String replacement = prop.getProperty(key);
                replace.add(new String[]{pattern, replacement});
            }
        }
    }

    /**
     * Prepare a SQL statement.
     *
     * @param sql the SQL statement
     * @return the prepared statement
     */
    PreparedStatement prepare(String sql) throws SQLException {
        sql = getSQL(sql);
        return conn.prepareStatement(sql);
    }

    private String getSQL(String sql) {
        for (String[] pair : replace) {
            String pattern = pair[0];
            String replace = pair[1];
            sql = StringUtils.replaceAll(sql, pattern, replace);
        }
        return sql;
    }

    /**
     * Start the benchmark.
     *
     * @param bench the benchmark
     * @param action the action
     */
    void start(Bench bench, String action) {
        this.action = bench.getName() + ": " + action;
        this.startTime = System.currentTimeMillis();
    }

    /**
     * This method is called when the test run ends. This will stop collecting
     * data.
     */
    void end() {
        long time = System.currentTimeMillis() - startTime;
        log(action, "ms", (int) time);
        if (test.collect) {
            totalTime += time;
        }
    }

    /**
     * Drop a table. Errors are ignored.
     *
     * @param table the table name
     */
    void dropTable(String table) {
        try {
            update("DROP TABLE " + table);
        } catch (Exception e) {
            // ignore - table may not exist
        }
    }

    /**
     * Execute an SQL statement.
     *
     * @param prep the prepared statement
     * @param trace the trace message
     */
    void update(PreparedStatement prep, String trace) throws SQLException {
        test.trace(trace);
        prep.executeUpdate();
        if (test.collect) {
            executedStatements++;
        }
    }

    /**
     * Execute an SQL statement.
     *
     * @param sql the SQL statement
     */
    void update(String sql) throws SQLException {
        sql = getSQL(sql);
        if (sql.trim().length() > 0) {
            if (test.collect) {
                executedStatements++;
            }
            stat.execute(sql);
        } else {
            System.out.println("?");
        }
    }

    /**
     * Enable or disable auto-commit.
     *
     * @param b false to disable
     */
    void setAutoCommit(boolean b) throws SQLException {
        conn.setAutoCommit(b);
    }

    /**
     * Commit a transaction.
     */
    void commit() throws SQLException {
        conn.commit();
    }

    /**
     * Roll a transaction back.
     */
    void rollback() throws SQLException {
        conn.rollback();
    }

    /**
     * Print trace information if trace is enabled.
     *
     * @param action the action
     * @param i the current value
     * @param max the maximum value
     */
    void trace(String action, int i, int max) {
        if (trace) {
            long time = System.currentTimeMillis();
            if (i == 0 || lastTrace == 0) {
                lastTrace = time;
            } else if (time > lastTrace + 1000) {
                System.out.println(action + ": " + ((100 * i / max) + "%"));
                lastTrace = time;
            }
        }
    }

    /**
     * If data collection is enabled, add the currently used memory size to the
     * log.
     *
     * @param bench the benchmark
     * @param action the action
     */
    void logMemory(Bench bench, String action) {
        log(bench.getName() + ": " + action, "MB", TestBase.getMemoryUsed());
    }

    /**
     * If data collection is enabled, add this information to the log.
     *
     * @param action the action
     * @param scale the scale
     * @param value the value
     */
    void log(String action, String scale, int value) {
        if (test.collect) {
            results.add(new Object[] { action, scale, new Integer(value) });
        }
    }

    /**
     * Execute a query.
     *
     * @param prep the prepared statement
     * @return the result set
     */
    ResultSet query(PreparedStatement prep) throws SQLException {
//        long time = System.currentTimeMillis();
        ResultSet rs = prep.executeQuery();
//        time = System.currentTimeMillis() - time;
//        if(time > 100) {
//            System.out.println("time="+time);
//        }
        if (test.collect) {
            executedStatements++;
        }
        return rs;
    }

    /**
     * Execute a query and read all rows.
     *
     * @param prep the prepared statement
     */
    void queryReadResult(PreparedStatement prep) throws SQLException {
        ResultSet rs = prep.executeQuery();
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        while (rs.next()) {
            for (int i = 0; i < columnCount; i++) {
                rs.getString(i + 1);
            }
        }
    }

    /**
     * Get the number of executed statements.
     *
     * @return the number of statements
     */
    int getExecutedStatements() {
        return executedStatements;
    }

    /**
     * Get the database id.
     *
     * @return the id
     */
    int getId() {
        return id;
    }

}
