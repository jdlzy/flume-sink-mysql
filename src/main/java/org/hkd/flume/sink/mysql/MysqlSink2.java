package org.hkd.flume.sink.mysql;

import com.google.common.base.Preconditions;
import org.apache.flume.*;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.AbstractSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/***
 * ������ʧ���ݱ�
 * create table loss_records(id int,target_table varchar(20),record varchar(255)
 */
public class MysqlSink2 extends AbstractSink implements Configurable {

    private static Logger log = LoggerFactory.getLogger(MysqlSink2.class);

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
    private List<String> fieldsTypeList=new ArrayList<String>();
    private String separator;

    private int fieldSize;

    //�ֵ����ӳ���
    private Map<String, String> encodeMap = new HashMap<String, String>();
    //��ȡ��ƥ������ԭʼ�ֶ�����
    private String encodeFields;
    private String[] encodeFieldsNames;
    //�ֵ������
    private String encodeTableName;
    //���ϸ����ݵĴ洢��
    private String lossRecordTableName;
    //���ϸ����ݲ����������
    private PreparedStatement lossRecordStatement;
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
    public MysqlSink2() {
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

        //��ȡ����Ŀ�������ݸ�ʽ
        Statement statement;
        try {
            conn = DriverManager.getConnection(url, user, password);
        conn.setAutoCommit(false);
        } catch (SQLException e) {
            e.printStackTrace();
        }
//        try {
//            statement =conn.createStatement();
//            ResultSetMetaData rs= statement.executeQuery("select * from "+tableName+" limit 1").getMetaData();
//            for(int i=0;i<rs.getColumnCount();i++){
//                //��ȡ�ֶ����ݸ�ʽ
//               fieldsTypeList.add(rs.getColumnTypeName(i+1));
//               //��ȡ�ֶ������ظ�
//               fieldsNameList.add(rs.getColumnName(i+1));
//            }
//
//        } catch (SQLException e) {
//            e.printStackTrace();
//        }
//
        fieldsNameList.add("f1");
        fieldsNameList.add("f2");
        fieldsNameList.add("f3");
        fieldsNameList.add("f1_id");
        fieldsNameList.add("f2_id");
        fieldsNameList.add("f3_id");
        System.out.println("length:"+fieldsTypeList.size());
        for(int i=0;i<fieldsTypeList.size();i++){
            System.out.print(fieldsTypeList.get(i));
            System.out.println(fieldsNameList.get(i));
        }
        //
        fieldSize =6;
//                fieldsNameList.size();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < fieldSize; i++) {
            sb.append("?");
            if (i == fieldSize - 1)
                break;
            else
                sb.append(",");
        }
//        String sql = "insert into " + tableName +" values ( " + sb.toString() + " )";
        String sql = "insert into " + tableName + " ( f1,f2,f3,f1_id,f2_id,f3_id) values ( " + sb.toString() + " )";

        try {

            preparedStatement = conn.prepareStatement(sql);
            //���ڲ��벻�ϸ�����,
            lossRecordStatement = conn.prepareStatement("insert into " + lossRecordTableName + " (target_table,record) values (?,?)");
        } catch (SQLException e) {
            e.printStackTrace();
            log.error("��ȡmysql����ʧ�ܣ�{}", e.getMessage());
            System.exit(1);
        }
        //��ȡ��ƥ������ԭʼ�ֶ�����
        encodeFieldsNames = encodeFields.split(",");
        for (String name : encodeFieldsNames) {
            System.out.println("��Ҫƥ����ֶ�:" + name);
        }
        //��ȡ�����ֵ�

        try {
            for (String encodeFieldName : encodeFieldsNames) {
                statement = conn.createStatement();
                String encodeFieldName_ID = encodeFieldName + "_ID";
                ResultSet rs = statement.executeQuery("select " + encodeFieldName + "," + encodeFieldName_ID + " from " + encodeTableName);
                while (rs.next()) {
                    encodeMap.put(rs.getString(1), rs.getString(2));
                }
            }
            //���Դ�ӡmap����
            for (Map.Entry<String, String> entry : encodeMap.entrySet()) {
                System.out.println("Key = " + entry.getKey() + ", Value = " + entry.getValue());
            }
        } catch (SQLException e) {
            e.printStackTrace();
            log.error("��ȡmysql����ʧ�ܣ�{}", e.getMessage());
            System.exit(1);
        }
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
                    content = new String(event.getBody());
                    // ���
                    String[] arr_field = content.split(separator);
                    if(arr_field.length+3 != fieldSize) {
                        lossRecordStatement.setObject(1,tableName);
                        lossRecordStatement.setObject(2,content);
                        Boolean isExecute= lossRecordStatement.execute();
                        conn.commit();
                        log.warn("���ݴ���{}", content );
                        log.warn("���������Ƿ񱣴�ɹ���"+isExecute);
                        break;
                    }

                    for(int j = 1; j <= arr_field.length; j++) {
                        preparedStatement.setObject(j, arr_field[j - 1]);
                    }
                    //�����ı����ֶμ���
                    for(int j=1;j<=encodeFieldsNames.length;j++){
                        //��Ҫ��ӱ�����ֶ�����
                        String encodeFieldsName=encodeFieldsNames[j-1];
                        //��Ҫ��ӱ�����ֶε��±�
                        Integer fieldIndex= fieldsNameList.indexOf(encodeFieldsName);
                        preparedStatement.setObject(arr_field.length+j,
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
     * @param list
     * @param separative
     * @return
     */
    private  String mkString(List<String> list,String separative){
        StringBuilder sb=new StringBuilder("");
        for(String str:list) {
            sb.append(str+separative);
        }
        sb.deleteCharAt(sb.length()-1);
        return sb.toString();
    }
    private static void writeLossRecords(PreparedStatement lossRecordStatement,Connection conn,String record,String tableName){
        try {
        lossRecordStatement.setObject(1, tableName);
        lossRecordStatement.setObject(2, record);
            conn.commit();

        } catch (SQLException e) {
            e.printStackTrace();
        }
        log.warn("���ݳ��ȣ�{}", record);
    };
    private static void writeLossRecords(PreparedStatement lossRecordStatement,Connection conn,String record,String tableName,Exception recordException){
        try {
            lossRecordStatement.setObject(1, tableName);
            lossRecordStatement.setObject(2, record);
            conn.commit();
        } catch (SQLException e) {
            e.printStackTrace();
            log.error("�д������ݣ���д������ʧ��:"+e.getMessage());
        }
        log.warn("���ݴ���{}", record);
        log.warn("������Ϣ��{}", recordException.getMessage());
    };
}
