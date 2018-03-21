package org.hkd.flume.sink.mysql;

import java.sql.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;

import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.AbstractSink;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

/***
 * ������ʧ���ݱ�
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
    private int batchSize;
    private List<String> fieldsNameList = new ArrayList<String>();
    //���ڱ���Ŀ����������͵�List
    private List<String> fieldsTypeList = new ArrayList<String>();
    private String separator;

    private int fieldSize;

    //�ֵ����ӳ���
    private Map<String, Integer> encodeMap = new HashMap<String, Integer>();
    //��ȡ��ƥ������ԭʼ�ֶ�����
    private String encodeFields;
    private String[] encodeFieldsNames;
    //�ֵ������
    private String encodeTableName;
    //���ϸ����ݵĴ洢��
    private String lossRecordTableName;
    //���ϸ����ݲ����������
    private PreparedStatement lossRecordStatement;
    private static SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");

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

    }

    public void start() {
        super.start();
        try {
            Class.forName("com.mysql.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            log.error("����ע��ʧ�ܣ�{}", e.getMessage());
        }

        String url = "jdbc:mysql://" + hostname + ":" + port + "/" + databaseName;
        try {
            conn = DriverManager.getConnection(url, user, password);
            conn.setAutoCommit(false);
        } catch (SQLException e) {
            e.printStackTrace();
            log.error("��ȡmysql����ʧ�ܣ�{}", e.getMessage());
            System.exit(1);
        }
        //��ȡ����Ŀ�������ݸ�ʽ
        Statement statement;
        try {
            statement = conn.createStatement();
            //�����������ݴ洢��
            statement.execute("CREATE TABLE  IF NOT EXISTS `loss_records` (  `id` int(11) NOT NULL AUTO_INCREMENT, `target_table` varchar(40) DEFAULT NULL," +
                    "`date` TIMESTAMP  DEFAULT  CURRENT_TIMESTAMP ,`exception` varchar(40) DEFAULT  NULL ,`record` varchar(512) DEFAULT NULL,  PRIMARY KEY (`id`))");
            //��ѯĿ���Ԫ����
            ResultSetMetaData rs = statement.executeQuery("select * from " + tableName + " limit 1").getMetaData();
            for (int i = 0; i < rs.getColumnCount(); i++) {
                //��ȡ�ֶ����ݸ�ʽ
                fieldsTypeList.add(rs.getColumnTypeName(i + 1));
                //��ȡ�ֶ������ظ�
                fieldsNameList.add(rs.getColumnName(i + 1));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        //
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
            //���ڲ��벻�ϸ�����,
            lossRecordStatement = conn.prepareStatement("insert into " + lossRecordTableName + " (target_table,exception,record) values (?,?,?)");
        } catch (SQLException e) {
            e.printStackTrace();
            log.error("��ȡmysql����ʧ�ܣ�{}", e.getMessage());
            System.exit(1);
        }
        //��ȡ��ƥ������ԭʼ�ֶ�����
        encodeFieldsNames = encodeFields.split(",", -1);
        log.info("��[" + tableName + "]��Ҫƥ��ı����ֶΰ�����" + encodeFields);

        //��ȡ�����ֵ�
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


        } catch (SQLException e) {
            e.printStackTrace();
            log.error("��ȡmysql����ʧ�ܣ�{}", e.getMessage());
            System.exit(1);
        }
        log.info("��[" + tableName + "]��Ӧ�ֵ���볤��Ϊ��" + encodeMap.size());
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
                    //����head
                    Map<String, String> head = event.getHeaders();
                    for (Map.Entry<String, String> entry : head.entrySet()) {
                        System.out.println("Key = " + entry.getKey() + ", Value = " + entry.getValue());
                    }
                    /**ͷ����������������
                     * Key = timestamp, Value = 1521438722310
                     Key = filePath, Value = /home/hkd3/
                     Key = fileName, Value = test4.csv
                     */
                    content = new String(event.getBody());
                    // ���
                    String[] arr_field = content.split(separator, -1);
                    if (arr_field.length + encodeFieldsNames.length != fieldSize) {
                        writeLossRecords(lossRecordStatement, conn, content, tableName);
                        System.out.println("error1");
                        break;
                    }

//                    for (int j = 1; j <= arr_field.length; j++) {
//                        preparedStatement.setObject(j, arr_field[j - 1]);
//                    }
                    try {
                        //��Ŀ����л�ȡ���ֶ���Ҫ��Դ���ݶ�3��ƥ�������ֶ�
                        for (int j = 0; j < fieldsTypeList.size() - encodeFieldsNames.length; j++) {

                            String dataType = fieldsTypeList.get(j);
                            String value = arr_field[j];
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
                        //���Դ�ӡmap����
//        for (Map.Entry<String, String> entry : encodeMap.entrySet()) {
//            System.out.println("Key = " + entry.getKey() + ", Value = " + entry.getValue());
//        }
                        //�����ı����ֶμ���
                        for (int j = 1; j <= encodeFieldsNames.length; j++) {
                            //��Ҫ��ӱ�����ֶ�����
                            String encodeFieldsName = encodeFieldsNames[j - 1];
                            //��Ҫ��ӱ�����ֶε��±�
                            int fieldIndex = fieldsNameList.indexOf(encodeFieldsName);
                            System.out.println(arr_field[fieldIndex] + "-��" + encodeMap.get(arr_field[fieldIndex]));
                            preparedStatement.setInt(arr_field.length + j,
                                    encodeMap.get(arr_field[fieldIndex]));
                        }
                        preparedStatement.addBatch();
                    } catch (ParseException e) {
                        writeLossRecords(lossRecordStatement, conn, content, tableName, e);
                        continue;
                    } catch (Exception e) {
                        writeLossRecords(lossRecordStatement, conn, content, tableName, e);
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
            transaction.close();
        }
        return result;
    }

    public void stop() {
        super.stop();
        if (preparedStatement != null) {
            try {
                preparedStatement.close();
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
                    // ���
                    String[] arr_field = content.split(separator);
                    if (arr_field.length + 3 != fieldSize) {
                        lossRecordStatement.setObject(1, tableName);
                        lossRecordStatement.setObject(2, content);
                        Boolean isExecute = lossRecordStatement.execute();
                        conn.commit();
                        log.warn("���ݴ���{}", content);
                        log.warn("���������Ƿ񱣴�ɹ���" + isExecute);
                        break;
                    }

                    for (int j = 1; j <= arr_field.length; j++) {
                        preparedStatement.setObject(j, arr_field[j - 1]);
                    }
                    //�����ı����ֶμ���
                    for (int j = 1; j <= encodeFieldsNames.length; j++) {
                        //��Ҫ��ӱ�����ֶ�����
                        String encodeFieldsName = encodeFieldsNames[j - 1];
                        //��Ҫ��ӱ�����ֶε��±�
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
     * ƴ���ַ���
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

    private static void writeLossRecords(PreparedStatement lossRecordStatement, Connection conn, String record, String tableName) {
        try {
            lossRecordStatement.setString(1, tableName);
            lossRecordStatement.setString(2, "���ݳ��ȴ���");
            lossRecordStatement.setString(3, record);
            lossRecordStatement.execute();
            conn.commit();

        } catch (SQLException e) {
            e.printStackTrace();
        }
        log.warn("���ݳ��ȴ���{}", record);
    }

    ;

    private static void writeLossRecords(PreparedStatement lossRecordStatement, Connection conn, String record, String tableName, Exception recordException) {
        try {
            lossRecordStatement.setString(1, tableName);
            lossRecordStatement.setString(2, recordException.getMessage());
            lossRecordStatement.setString(3, record);
            lossRecordStatement.execute();

            conn.commit();
        } catch (SQLException e) {
            e.printStackTrace();
            log.error("�д������ݣ���д������ʧ��:" + e.getMessage());
        }
        log.warn("���ݴ���{}", record);
        log.warn("������Ϣ��{}", recordException.getMessage());
    }

    ;
}
