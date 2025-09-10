package com.hmdp.utils;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;

public class MySQLUserFetcher {
    private static final String DB_URL = "jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC&useUnicode=true&characterEncoding=utf8&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048&useServerPrepStmts=true&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true";
    private static final String USER = "root";
    private static final String PASS = "chenzhuo2005.";
    
    public static void main(String[] args) {
        List<User> users = fetchUsers(args.length > 0 ? Integer.parseInt(args[0]) : 1000);
        System.out.println(new Gson().toJson(users));
    }

    public static List<User> fetchUsers(int limit) {
        List<User> users = new ArrayList<>();
        String query = "SELECT id, nick_name, icon FROM tb_user LIMIT " + limit;

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            while (rs.next()) {
                users.add(new User(
                    rs.getInt("id"),
                    rs.getString("nick_name"),
                    rs.getString("icon")
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return users;
    }

    public static class User {
        public final int id;
        public final String nick_name;
        public final String icon;

        public User(int id, String nick_name, String icon) {
            this.id = id;
            this.nick_name = nick_name;
            this.icon = icon;
        }
    }
}