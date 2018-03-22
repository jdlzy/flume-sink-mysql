package org.hkd.flume.sink.mysql;

import com.google.common.base.Preconditions;
import org.apache.flume.*;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.AbstractSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/***
 * 创建丢失数据表
 * create table loss_records(id int,target_table varchar(20),record varchar(255)
 */
public class MysqlSink extends AbstractSink implements Configurable {

    private static Logger log = LoggerFactory.getLogger(MysqlSink.class);

    private String hostname;
    private String port;
    private String databaseName;
    private String tableName;
    private String user;
    private String password;
    private PreparedStatement preparedStatement;
    private Connection conn;
    private static int batchSize;
    private List<String> fieldsNameList = new ArrayList<String>();
    //用于保存目标表数据类型的List
    private List<String> fieldsTypeList = new ArrayList<String>();
    private String separator;

    private int fieldSize;

    //字典编码映射表
    private Map<String, Integer> encodeMap = new HashMap<String, Integer>();
    //获取需匹配编码的原始字段名称
    private String encodeFields;
    private String[] encodeFieldsNames;
    //字典表名称
    private String encodeTableName;
    //不合格数据的存储表
    private String lossRecordTableName;
    //不合格数据插入表连接器
    private PreparedStatement lossRecordStatement;

    //需要匹配字典编码的字段，从配置文件中获取
    private String dictFields;
    private String[] dictFieldsArr;
    private Map<String, Integer> dictMap = new HashMap<String, Integer>();
    private static SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");

    private int batchOfLossRecord = 0;

    public MysqlSink() {
        log.info("start sink service. name : mysql sink.");
    }

    public void configure(Context context) {
        hostname = context.getString("hostname");
        Preconditions.checkNotNull(hostname, "hostname must be set!!");
        port = context.getString("port");
        Preconditions.checkNotNull(port, "port must be set!!");
        databaseName = context.getString("databaseName");
        Preconditions.checkNotNull(databaseName, "databaseName must be set!!");
        tableName = context.getString("tableName");
        Preconditions.checkNotNull(tableName, "tableName must be set!!");
        user = context.getString("user");
        Preconditions.checkNotNull(user, "user must be set!!");
        password = context.getString("password");
        Preconditions.checkNotNull(password, "password must be set!!");
        batchSize = context.getInteger("batchSize", 100);
        Preconditions.checkNotNull(batchSize > 0, "batchSize must be a positive number!!");
        encodeFields = context.getString("encodeFields");
        Preconditions.checkNotNull(encodeFields, "encodeFields must be set!!");
        encodeTableName = context.getString("encodeTableName");
        Preconditions.checkNotNull(encodeTableName, "encodeTableName must be set!!");
        lossRecordTableName = context.getString("lossRecordTableName");
        Preconditions.checkNotNull(lossRecordTableName, "lossRecordTableName must be set!!");
        separator = context.getString("separator", ",");
        dictFields = context.getString("dictFields");

    }

    public void start() {
        super.start();
        try {
            Class.forName("com.mysql.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            log.error("驱动注册失败：{}", e.getMessage());
        }

        String url = "jdbc:mysql://" + hostname + ":" + port + "/" + databaseName;
        try {
            conn = DriverManager.getConnection(url, user, password);
            conn.setAutoCommit(false);
        } catch (SQLException e) {
            e.printStackTrace();
            log.error("获取mysql连接失败：{}", e.getMessage());
            System.exit(1);
        }
        //获取插入目标表的数据格式
        Statement statement = null;
        try {
            statement = conn.createStatement();
            //创建错误数据存储表
            statement.execute("CREATE TABLE  IF NOT EXISTS `loss_records` (  `id` int(11) NOT NULL AUTO_INCREMENT, `target_table` varchar(40) DEFAULT NULL," +
                    "`date` TIMESTAMP  DEFAULT  CURRENT_TIMESTAMP ,`exception` varchar(40) DEFAULT  NULL ,`record` varchar(1025) DEFAULT NULL,  PRIMARY KEY (`id`))");
            //查询目标表元数据
            ResultSetMetaData rs = statement.executeQuery("select * from " + tableName + " limit 1").getMetaData();
            for (int i = 0; i < rs.getColumnCount(); i++) {
                //获取字段数据格式
                fieldsTypeList.add(rs.getColumnTypeName(i + 1));
                //获取字段名称呢个
                fieldsNameList.add(rs.getColumnName(i + 1));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        //删除
        fieldsNameList.remove("INPUT_BATCH");
        fieldSize = fieldsNameList.size();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < fieldSize; i++) {
            sb.append("?");
            if (i == fieldSize - 1)
                break;
            else
                sb.append(",");
        }
        String fields = mkString(fieldsNameList, ",");
        String sql = "insert into " + tableName + " (" + fields + ") values ( " + sb.toString() + " )";
//        String sql = "insert into " + tableName +" values ( " + sb.toString() + " )";

        try {
            preparedStatement = conn.prepareStatement(sql);
            //用于插入不合格数据,
            lossRecordStatement = conn.prepareStatement("insert into " + lossRecordTableName + " (target_table,exception,record) values (?,?,?)");
        } catch (SQLException e) {
            e.printStackTrace();
            log.error("获取mysql连接失败：{}", e.getMessage());
            System.exit(1);
        }
        //获取需匹配编码的原始字段名称
        encodeFieldsNames = encodeFields.split(",");
        log.info("表[" + tableName + "]需要匹配的编码字段包括：" + encodeFields);

        //获取编码地区字典以及枚举字典表
        try {
//            for (String encodeFieldName : encodeFieldsNames) {
//                statement = conn.createStatement();
//                String encodeFieldName_ID = encodeFieldName + "_ID";
//                ResultSet rs = statement.executeQuery("select " + encodeFieldName + "," + encodeFieldName_ID + " from " + encodeTableName);
//                while (rs.next()) {
//                    encodeMap.put(rs.getString(1), rs.getString(2));
//                }
//            }
            statement = conn.createStatement();
            ResultSet rs = statement.executeQuery("select area_Name,area_ID from " + encodeTableName);
            while (rs.next()) {
                encodeMap.put(rs.getString(1), rs.getInt(2));
            }

            //获取枚举字典表
            ResultSet rsDict = statement.executeQuery("select CNNAME,CODE_ID from data_type_def");
            while (rsDict.next()) {
                //需要按照value值获取其int类型的ID值，
                dictMap.put(rsDict.getString(1), rsDict.getInt(2));
            }
        } catch (SQLException e) {
            e.printStackTrace();
            log.error("获取mysql连接失败：{}", e.getMessage());
            System.exit(1);
        } finally {
            try {
                statement.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        log.info("表[" + tableName + "]对应编码字典长度为：" + encodeMap.size());
        log.info("表[" + tableName + "]对应枚举字典长度为：" + dictMap.size());
        dictFieldsArr = dictFields.split(",");
    }

    public Status process() throws EventDeliveryException {

        Status result = Status.READY;
        Channel channel = getChannel();
        Transaction transaction = channel.getTransaction();
        Event event;
        String content;

        transaction.begin();
        try {
            preparedStatement.clearBatch();
            for (int i = 0; i < batchSize; i++) {

                event = channel.take();
                if (event != null) {
                    //测试head
//                    Map<String, String> head = event.getHeaders();
//                    for (Map.Entry<String, String> entry : head.entrySet()) {
//                        System.out.println("Key = " + entry.getKey() + ", Value = " + entry.getValue());
//                    }
                    /**头部包含的三个属性
                     * Key = timestamp, Value = 1521438722310
                     Key = filePath, Value = /home/hkd3/
                     Key = fileName, Value = test4.csv
                     */
                    content = new String(event.getBody());
                    // 添加
                    String[] arr_field = content.split(separator, -1);
                    if (arr_field.length + encodeFieldsNames.length != fieldSize) {
                        writeLossRecords(lossRecordStatement, conn, content, tableName, batchOfLossRecord, "数据长度错误：{}");
                        System.out.println("目标数量：" + fieldSize);
                        break;
                    }
                    //int部分字段匹配字典边
                    for (String dictField : dictFieldsArr) {
                        //获取其对应的下标
                        int index = fieldsNameList.indexOf(dictField);
                        String value = arr_field[index].replace("\"", "");
                        Integer dicInt = dictMap.get(value);
//                        System.out.println("下标：" + index + ",字段：" + dictField + ",原始值：" + value + "，对应值：" + dicInt);
                        //获取该值对应的字典
                        arr_field[index] = String.valueOf(dicInt);
                    }
//                    for (int j = 1; j <= arr_field.length; j++) {
//                        preparedStatement.setObject(j, arr_field[j - 1]);
//                    }
                    try {
                        //从目标表中获取的字段数要比源数据多3个匹配编码的字段
                        for (int j = 0; j < fieldSize - encodeFieldsNames.length; j++) {

                            String dataType = fieldsTypeList.get(j);
                            String value = arr_field[j].replace("\"", "");
                            switch (dataType) {
                                case "TINYINT":
                                    int values = Integer.valueOf(value);
                                    preparedStatement.setInt(j + 1, values);
                                    break;
                                case "INT":
                                    int valueInt = Integer.valueOf(value);
                                    preparedStatement.setInt(j + 1, valueInt);
                                    break;
                                case "DOUBLE":
                                    double valueDouble = Double.valueOf(value);
                                    preparedStatement.setDouble(j + 1, valueDouble);
                                    break;
                                case "FLOAT":
                                    float valueFloat = Float.valueOf(value);
                                    preparedStatement.setFloat(j + 1, valueFloat);
                                case "CHAR":
                                    char valueChar = value.charAt(0);
                                    preparedStatement.setString(j + 1, value);
                                    break;
                                case "DATETIME":
                                    java.sql.Date valueDate = new java.sql.Date(format.parse(arr_field[j]).getTime());
                                    preparedStatement.setDate(j + 1, valueDate);
                                    break;
                                default:

                                    preparedStatement.setString(j + 1, value);
                                    break;
                            }

                        }
                        //新增的编码字段加入
                        for (int j = 0; j < encodeFieldsNames.length; j++) {
                            //需要添加编码的字段名称
                            String encodeFieldsName = encodeFieldsNames[j];
                            //需要添加编码的字段的下标
                            int fieldIndex = fieldsNameList.indexOf(encodeFieldsName);
                            Integer encodeValue = encodeMap.get(arr_field[fieldIndex].replace("\"", ""));
                            preparedStatement.setInt(arr_field.length + j + 1, encodeValue);
                        }
                        //添加批次时间
//                        preparedStatement.setString(fieldSize,);
                        preparedStatement.addBatch();
                    } catch (ParseException e) {
                        writeLossRecords(lossRecordStatement, conn, content, tableName, batchOfLossRecord, e.getMessage());
                        e.printStackTrace();
                        continue;
                    } catch (Exception e) {
                        writeLossRecords(lossRecordStatement, conn, content, tableName, batchOfLossRecord, e.getMessage());
                        e.printStackTrace();
                        continue;
                    }

                } else {
                    result = Status.BACKOFF;
                    break;
                }
                if (i == batchSize - 1) {
                    preparedStatement.executeBatch();
                    conn.commit();
                }
            }
            transaction.commit();
        } catch (SQLException e) {
            transaction.rollback();
            log.error("Failed to commit transaction." + "Transaction rolled back.", e);

        } finally {
            try {
                preparedStatement.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                e.printStackTrace();
            }
            transaction.close();
        }
        return result;
    }

    public void stop() {
        super.stop();
        if (preparedStatement != null) {
            try {
                preparedStatement.close();
                lossRecordStatement.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }
/*
    private Status insertRecord(Channel channel, String tableName) {
        Status result = Status.READY;
        Transaction transaction = channel.getTransaction();
        Event event;
        String content;
        transaction.begin();
        try {
            preparedStatement.clearBatch();
            for (int i = 0; i < batchSize; i++) {
                event = channel.take();
                if (event != null) {

                    content = new String(event.getBody());
                    // 添加
                    String[] arr_field = content.split(separator);
                    if (arr_field.length + 3 != fieldSize) {
                        lossRecordStatement.setObject(1, tableName);
                        lossRecordStatement.setObject(2, content);
                        Boolean isExecute = lossRecordStatement.execute();
                        conn.commit();
                        log.warn("数据错误：{}", content);
                        log.warn("错误数据是否保存成功：" + isExecute);
                        break;
                    }

                    for (int j = 1; j <= arr_field.length; j++) {
                        preparedStatement.setObject(j, arr_field[j - 1]);
                    }
                    //新增的编码字段加入
                    for (int j = 1; j <= encodeFieldsNames.length; j++) {
                        //需要添加编码的字段名称
                        String encodeFieldsName = encodeFieldsNames[j - 1];
                        //需要添加编码的字段的下标
                        Integer fieldIndex = fieldsNamesList.indexOf(encodeFieldsName);
                        preparedStatement.setObject(arr_field.length + j,
                                encodeMap.get(arr_field[fieldIndex]));
                    }
                    preparedStatement.addBatch();
                } else {
                    result = Status.BACKOFF;
                    break;
                }
                if (i == batchSize - 1) {
                    preparedStatement.executeBatch();
                    conn.commit();
                }
            }
            transaction.commit();

        } catch (SQLException e) {
            transaction.rollback();
            log.error("Failed to commit transaction." + "Transaction rolled back.", e);

        } finally {
            transaction.close();
        }
        return result;
    }*/

    /**
     * 拼接字符串
     *
     * @param list
     * @param separative
     * @return
     */
    private String mkString(List<String> list, String separative) {
        StringBuilder sb = new StringBuilder("");
        for (String str : list) {
            sb.append(str + separative);
        }
        sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }

    private static void writeLossRecords(PreparedStatement lossRecordStatement, Connection conn, String record, String tableName, int batchOfLossRecord, String exception) {

        try {
            lossRecordStatement.setString(1, tableName);
            lossRecordStatement.setString(2, "数据长度错误");
            lossRecordStatement.setString(3, record);
            lossRecordStatement.execute();
            conn.commit();
            if (batchOfLossRecord < batchSize) {
                lossRecordStatement.addBatch();
            } else {
                lossRecordStatement.executeBatch();
                conn.commit();
            }
        } catch (SQLException e) {
            e.printStackTrace();
            log.error("有错误数据，且写入错误库失败:" + e.getMessage());
        }
        log.warn(exception, record);
        batchOfLossRecord++;
    }

    ;


}
