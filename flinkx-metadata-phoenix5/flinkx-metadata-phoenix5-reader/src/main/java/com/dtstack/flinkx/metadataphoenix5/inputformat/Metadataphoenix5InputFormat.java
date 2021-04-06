/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.flinkx.metadataphoenix5.inputformat;

import com.dtstack.flinkx.constants.ConstantValue;
import com.dtstack.flinkx.metadata.inputformat.BaseMetadataInputFormat;
import com.dtstack.flinkx.metadataphoenix5.util.IPhoenix5Helper;
import com.dtstack.flinkx.metadataphoenix5.util.Phoenix5Util;
import com.dtstack.flinkx.util.ClassUtil;
import com.dtstack.flinkx.util.ExceptionUtil;
import com.dtstack.flinkx.util.GsonUtil;
import com.dtstack.flinkx.util.ReflectionUtils;
import com.dtstack.flinkx.util.ZkHelper;
import com.google.common.collect.Lists;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.flink.runtime.execution.librarycache.FlinkUserCodeClassLoaders;
import org.apache.flink.util.FlinkUserCodeClassLoader;
import org.apache.zookeeper.ZooKeeper;
import org.codehaus.commons.compiler.CompileException;
import sun.misc.URLClassPath;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.dtstack.flinkx.metadata.MetaDataCons.KEY_COLUMN;
import static com.dtstack.flinkx.metadata.MetaDataCons.KEY_COLUMN_DATA_TYPE;
import static com.dtstack.flinkx.metadata.MetaDataCons.KEY_COLUMN_INDEX;
import static com.dtstack.flinkx.metadata.MetaDataCons.KEY_COLUMN_NAME;
import static com.dtstack.flinkx.metadata.MetaDataCons.KEY_CONN_PASSWORD;
import static com.dtstack.flinkx.metadata.MetaDataCons.KEY_FALSE;
import static com.dtstack.flinkx.metadata.MetaDataCons.KEY_TABLE_PROPERTIES;
import static com.dtstack.flinkx.metadata.MetaDataCons.KEY_TRUE;
import static com.dtstack.flinkx.metadata.MetaDataCons.KEY_USER;
import static com.dtstack.flinkx.metadata.MetaDataCons.RESULT_SET_COLUMN_NAME;
import static com.dtstack.flinkx.metadata.MetaDataCons.RESULT_SET_ORDINAL_POSITION;
import static com.dtstack.flinkx.metadata.MetaDataCons.RESULT_SET_TABLE_NAME;
import static com.dtstack.flinkx.metadata.MetaDataCons.RESULT_SET_TYPE_NAME;
import static com.dtstack.flinkx.metadataphoenix5.util.PhoenixMetadataCons.HBASE_MASTER_KERBEROS_PRINCIPAL;
import static com.dtstack.flinkx.metadataphoenix5.util.PhoenixMetadataCons.KEY_CREATE_TIME;
import static com.dtstack.flinkx.metadataphoenix5.util.PhoenixMetadataCons.KEY_DEFAULT;
import static com.dtstack.flinkx.metadataphoenix5.util.PhoenixMetadataCons.KEY_NAMESPACE;
import static com.dtstack.flinkx.metadataphoenix5.util.PhoenixMetadataCons.KEY_PRIMARY_KEY;
import static com.dtstack.flinkx.metadataphoenix5.util.PhoenixMetadataCons.KEY_TABLE_NAME;
import static com.dtstack.flinkx.metadataphoenix5.util.PhoenixMetadataCons.SQL_COLUMN;
import static com.dtstack.flinkx.metadataphoenix5.util.PhoenixMetadataCons.SQL_DEFAULT_COLUMN;
import static com.dtstack.flinkx.metadataphoenix5.util.PhoenixMetadataCons.SQL_DEFAULT_TABLE_NAME;
import static com.dtstack.flinkx.metadataphoenix5.util.PhoenixMetadataCons.SQL_TABLE_NAME;
import static com.dtstack.flinkx.util.ZkHelper.APPEND_PATH;

/**
 * @author kunni@Dtstack.com
 */

public class Metadataphoenix5InputFormat extends BaseMetadataInputFormat {

    private Map<String, Long> createTimeMap;

    public static final String JDBC_PHOENIX_PREFIX = "jdbc:phoenix:";

    public static final String DEFAULT_SCHEMA = "default";

    protected ZooKeeper zooKeeper;

    protected Map<String, Object> hadoopConfig;

    protected String path;

    protected String zooKeeperPath;

    @Override
    protected void closeInternal() throws IOException {
        tableIterator.remove();
        Statement st = statement.get();
        if (null != st) {
            try {
                st.close();
                statement.remove();
            } catch (SQLException e) {
                LOG.error("close statement failed, e = {}", ExceptionUtil.getErrorMessage(e));
                throw new IOException("close statement failed", e);
            }
        }
        currentDb.remove();
    }

    @Override
    public void closeInputFormat() throws IOException {
        if(connection.get() != null){
            try{
                connection.get().close();
                connection.remove();
            }catch (SQLException e){
                LOG.error("failed to close connection, e = {}", ExceptionUtil.getErrorMessage(e));
            }
        }
        super.closeInputFormat();
    }


    @Override
    protected List<Object> showTables() {
        String sql;
        if (StringUtils.endsWithIgnoreCase(currentDb.get(), KEY_DEFAULT) ||
                StringUtils.isBlank(currentDb.get())) {
            sql = SQL_DEFAULT_TABLE_NAME;
        } else {
            sql = String.format(SQL_TABLE_NAME, currentDb.get());
        }
        List<Object> table = new LinkedList<>();
        try (ResultSet resultSet = executeQuery0(sql, statement.get())) {
            while (resultSet.next()) {
                table.add(resultSet.getString(RESULT_SET_TABLE_NAME));
            }
        } catch (SQLException e) {
            LOG.error("query table lists failed, {}", ExceptionUtil.getErrorMessage(e));
        }
        return table;
    }

    @Override
    protected void switchDatabase(String databaseName) {
        currentDb.set(databaseName);
    }

    @Override
    protected Map<String, Object> queryMetaData(String tableName) {
        Map<String, Object> result = new HashMap<>(16);
        Map<String, Object> tableProp = queryTableProp(tableName);
        List<Map<String, Object>> column = queryColumn(tableName);
        result.put(KEY_TABLE_PROPERTIES, tableProp);
        result.put(KEY_COLUMN, column);
        return result;
    }

    @Override
    protected String quote(String name) {
        return name;
    }

    /**
     * 获取表级别的元数据信息
     *
     * @param tableName 表名
     * @return 表的元数据
     */
    public Map<String, Object> queryTableProp(String tableName) {
        Map<String, Object> tableProp = new HashMap<>(16);
        if (StringUtils.endsWithIgnoreCase(currentDb.get(), KEY_DEFAULT) || currentDb.get() == null) {
            tableProp.put(KEY_CREATE_TIME, createTimeMap.get(tableName));
        } else {
            tableProp.put(KEY_CREATE_TIME, createTimeMap.get(currentDb.get() + ConstantValue.POINT_SYMBOL + tableName));
        }
        tableProp.put(KEY_NAMESPACE, currentDb.get());
        tableProp.put(KEY_TABLE_NAME, tableName);
        return tableProp;
    }

    /**
     * 获取列级别的元数据信息
     *
     * @param tableName 表名
     * @return 列的元数据信息
     */
    public List<Map<String, Object>> queryColumn(String tableName) {
        List<Map<String, Object>> column = new LinkedList<>();
        String sql;
        if (isDefaultSchema()) {
            sql = String.format(SQL_DEFAULT_COLUMN, tableName);
        } else {
            sql = String.format(SQL_COLUMN, currentDb.get(), tableName);
        }
        Map<String, String> familyMap = new HashMap<>(16);
        try (ResultSet resultSet = executeQuery0(sql, statement.get())) {
            while (resultSet.next()) {
                familyMap.put(resultSet.getString(1), resultSet.getString(2));
            }
        } catch (SQLException e) {
            LOG.error("query column information failed, {}", ExceptionUtil.getErrorMessage(e));
        }
        //default schema需要特殊处理
        String originSchema = currentDb.get();
        if (DEFAULT_SCHEMA.equalsIgnoreCase(originSchema)) {
            originSchema = null;
        }
        try (ResultSet resultSet = connection.get().getMetaData().getColumns(null, originSchema, tableName, null)) {
            while (resultSet.next()) {
                Map<String, Object> map = new HashMap<>(16);
                String index = resultSet.getString(RESULT_SET_ORDINAL_POSITION);
                String family = familyMap.get(index);
                if (StringUtils.isBlank(family)) {
                    map.put(KEY_PRIMARY_KEY, KEY_TRUE);
                    map.put(KEY_COLUMN_NAME, resultSet.getString(RESULT_SET_COLUMN_NAME));
                } else {
                    map.put(KEY_PRIMARY_KEY, KEY_FALSE);
                    map.put(KEY_COLUMN_NAME, family + ConstantValue.COLON_SYMBOL + resultSet.getString(RESULT_SET_COLUMN_NAME));
                }
                map.put(KEY_COLUMN_DATA_TYPE, resultSet.getString(RESULT_SET_TYPE_NAME));
                map.put(KEY_COLUMN_INDEX, index);
                column.add(map);
            }
        } catch (SQLException e) {
            LOG.error("failed to get column information, {} ", ExceptionUtil.getErrorMessage(e));
        }
        return column;
    }

    /**
     * 查询表的创建时间
     * 如果zookeeper没有权限访问，返回空map
     *
     * @param hosts zookeeper地址
     * @return 表名与创建时间的映射
     */
    protected Map<String, Long> queryCreateTimeMap(String hosts) {
        Map<String, Long> createTimeMap = new HashMap<>(16);
        try {
            zooKeeper = ZkHelper.createZkClient(hosts, ZkHelper.DEFAULT_TIMEOUT);
            List<String> tables = ZkHelper.getChildren(zooKeeper, path);
            if (tables != null) {
                for (String table : tables) {
                    createTimeMap.put(table, ZkHelper.getCreateTime(zooKeeper, path + ConstantValue.SINGLE_SLASH_SYMBOL + table));
                }
            }
        } catch (Exception e) {
            LOG.error("query createTime map failed, error {}", ExceptionUtil.getErrorMessage(e));
        } finally {
            ZkHelper.closeZooKeeper(zooKeeper);
        }
        return createTimeMap;
    }


    @Override
    protected void init() {
        //兼容url携带zookeeper路径
        String hosts = dbUrl.substring(JDBC_PHOENIX_PREFIX.length());
        createTimeMap = queryCreateTimeMap(hosts);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Field declaredField = ReflectionUtils.getDeclaredField(getClass().getClassLoader(), "ucp");
        assert declaredField != null;
        declaredField.setAccessible(true);
        URLClassPath urlClassPath;
        try {
            urlClassPath = (URLClassPath) declaredField.get(getClass().getClassLoader());
        } catch (IllegalAccessException e) {
            String message = String.format("cannot get urlClassPath from current classLoader, classLoader = %s, e = %s", getClass().getClassLoader(), ExceptionUtil.getErrorMessage(e));
            throw new RuntimeException(message, e);
        }
        declaredField.setAccessible(false);

        List<URL> needJar = Lists.newArrayList();
        for (URL url : urlClassPath.getURLs()) {
            String urlFileName = FilenameUtils.getName(url.getPath());
            if (urlFileName.startsWith("flinkx-metadata-phoenix5-reader")) {
                needJar.add(url);
                break;
            }
        }

        ClassLoader parentClassLoader = getClass().getClassLoader();
        List<String> list = new LinkedList<>();
        list.add("org.apache.flink");
        list.add("com.dtstack.flinkx");

        URLClassLoader childFirstClassLoader = FlinkUserCodeClassLoaders.childFirst(needJar.toArray(new URL[0]), parentClassLoader, list.toArray(new String[0]), FlinkUserCodeClassLoader.NOOP_EXCEPTION_HANDLER, true);
        Properties properties = new Properties();
        ClassUtil.forName(driverName, childFirstClassLoader);
        if (StringUtils.isNotEmpty(username)) {
            properties.setProperty(KEY_USER, username);
        }
        if (StringUtils.isNotEmpty(password)) {
            properties.setProperty(KEY_CONN_PASSWORD, password);
        }
        String jdbcUrl = dbUrl + ConstantValue.COLON_SYMBOL + zooKeeperPath;
        try {
            IPhoenix5Helper helper = Phoenix5Util.getHelper(childFirstClassLoader);
            if (StringUtils.isNotEmpty(MapUtils.getString(hadoopConfig, HBASE_MASTER_KERBEROS_PRINCIPAL))) {
                Phoenix5Util.setKerberosParams(properties, hadoopConfig);
                return Phoenix5Util.getConnectionWithKerberos(hadoopConfig, properties, jdbcUrl, helper);
            }
            return helper.getConn(jdbcUrl, properties);
        } catch (IOException | CompileException e) {
            String message = String.format("cannot get phoenix connection, dbUrl = %s, properties = %s, e = %s", dbUrl, GsonUtil.GSON.toJson(properties), ExceptionUtil.getErrorMessage(e));
            throw new RuntimeException(message, e);
        }
    }

    /**
     * phoenix 默认schema为空，在平台层设置值为default
     *
     * @return 是否为默认schema
     */
    public boolean isDefaultSchema() {
        return StringUtils.endsWithIgnoreCase(currentDb.get(), KEY_DEFAULT) ||
                StringUtils.isBlank(currentDb.get());
    }

    /**
     * 传入为hbase在zookeeper中的路径，增加/table表示table所在路径
     *
     * @param path hbase路径
     */
    public void setPath(String path) {
        this.zooKeeperPath = path;
        this.path = path + APPEND_PATH;
    }


    public void setHadoopConfig(Map<String, Object> hadoopConfig) {
        this.hadoopConfig = hadoopConfig;
    }
}