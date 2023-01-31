package com.databend.jdbc;

import org.testng.Assert;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class TestBasicDriver
{
    private Connection createConnection()
            throws SQLException
    {
        String url = "jdbc:databend://localhost:8000";
        return DriverManager.getConnection(url, "root", "root");
    }

    private Connection createConnection(String database) throws SQLException
    {
        String url = "jdbc:databend://localhost:8000/" + database;
        return DriverManager.getConnection(url, "root", "root");
    }

    @BeforeTest
    public void setUp()
            throws SQLException
    {
        // create table
        Connection c = createConnection();
        c.createStatement().execute("drop database if exists test_basic_driver");
        c.createStatement().execute("create database test_basic_driver");
        c.createStatement().execute("create table test_basic_driver.table1(i int)");
        c.createStatement().execute("insert into test_basic_driver.table1 values(1)");
        // json data
    }
    @Test(groups = {"IT"})
    public void testBasic()
            throws SQLException
    {
        try (Connection connection = createConnection()) {
            Statement statement = connection.createStatement();
            statement.execute("SELECT number from numbers(200000) order by number");
            ResultSet r = statement.getResultSet();
            r.next();
            for (int i = 1; i < 1000; i++) {
                r.next();
                Assert.assertEquals(r.getInt(1), i);
            }
            connection.close();
        }
        finally {

        }
    }

    @Test(groups = {"IT"})
    public void testBasicWithDatabase()
            throws SQLException
    {
        try (Connection connection = createConnection("test_basic_driver")) {
            Statement statement = connection.createStatement();
            statement.execute("SELECT i from table1");
            ResultSet r = statement.getResultSet();
            r.next();
            Assert.assertEquals(r.getInt(1), 1);
            r = connection.getMetaData().getColumns(null, null, "table1", null);
            while (r.next()) {
                String tableSchem = r.getString("table_schem");
                String tableName = r.getString("table_name");
                String columnName = r.getString("COLUMN_NAME");
                String dataType = r.getString("data_type");
                String columnType = r.getString("type_name");
                System.out.println(tableSchem + " " + tableName + " " + columnName + " " + dataType + " " + columnType);
            }
            connection.close();
        }
        finally {

        }
    }

    @Test(groups = {"IT"})
    public void testResultException()
    {
        try (Connection connection = createConnection()) {
            Statement statement = connection.createStatement();
            ResultSet r =  statement.executeQuery("SELECT 1e189he 198h");

            connection.close();
        }
        catch (SQLException e) {
            Assert.assertTrue(e.getMessage().contains("Query failed"));
        }
    }
}
