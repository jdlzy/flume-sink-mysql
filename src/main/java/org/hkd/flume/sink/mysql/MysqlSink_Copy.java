package org.hkd.flume.sink.mysql;

import com.google.common.base.Preconditions;
import org.apache.flume.*;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.AbstractSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/***
 * ������ʧ���ݱ�
 * create table loss_records(id int,target_table varchar(20),record varchar(255)
 */
public class MysqlSink_Copy extends AbstractSink implements Configurable {

    private Logger log = LoggerFactory.getLogger(MysqlSink_Copy.class);

    private String hostname;
    private String port;
    private String databaseName;
    private String tableName;
    private String user;
    private String password;
    private PreparedStatement preparedStatement;
    private Connection conn;
    private int batchSize;
    private String fields;
    private String[] fieldsNames;
    private List<String> fieldsNamesList=new ArrayList<String>();
    private String separator;

    private int fieldSize;
    //��ѯ�����ֵ�������
    private Statement statement;

    //�ֵ����ӳ���
    private Map<String,String> encodeMap=new HashMap<String,String>();
    //��ȡ��ƥ������ԭʼ�ֶ�����
    private String encodeFields;
    private String[] encodeFieldsNames;
    //�ֵ������
    private String encodeTableName;
    //���ϸ����ݵĴ洢��
    private String lossRecordTableName;
    //���ϸ����ݲ����������
    private PreparedStatement lossRecordStatement;
    public MysqlSink_Copy() {
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
        fields = context.getString("fields");
        Preconditions.checkNotNull(fields, "fields must be set!!");
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
        // ƴ�Ӳ������
        fieldsNames=fields.split(",");
        fieldSize = fieldsNames.length;
        for(int i=0;i<fieldSize;i++){
            fieldsNamesList.add(fieldsNames[i]);
        }
        StringBuffer sb = new StringBuffer();
        for(int i = 0; i < fieldSize; i++) {
            sb.append("?");
            if(i == fieldSize - 1)
                break;
            else
                sb.append(",");
        }
        String sql = "insert into " + tableName + " (" + fields + ") values ( " + sb.toString() + " )";

        try {
            conn = DriverManager.getConnection(url, user, password);
            conn.setAutoCommit(false);
            preparedStatement = conn.prepareStatement(sql);
            //���ڲ��벻�ϸ�����,

            lossRecordStatement=conn.prepareStatement("insert into "+lossRecordTableName+" (target_table,record) values (?,?)");

        } catch (SQLException e) {
            e.printStackTrace();
            log.error("��ȡmysql����ʧ�ܣ�{}", e.getMessage());
            System.exit(1);
        }
        //��ȡ��ƥ������ԭʼ�ֶ�����
        encodeFieldsNames=encodeFields.split(",");
        for (String name:encodeFieldsNames) {
            System.out.println("��Ҫƥ����ֶ�:" + name);
        }
        //��ȡ�����ֵ�

        try {
            for(String encodeFieldName:encodeFieldsNames){
                statement= conn.createStatement();
                String encodeFieldName_ID=encodeFieldName+"_ID";
                ResultSet rs=statement.executeQuery("select "+encodeFieldName+","+encodeFieldName_ID+" from "+encodeTableName);
                while(rs.next()){
                    encodeMap.put(rs.getString(1),rs.getString(2));
                }
            }
            Iterator<Map.Entry<String, String>> entries = encodeMap.entrySet().iterator();

            while (entries.hasNext()) {

                Map.Entry<String, String> entry = entries.next();

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
                    // ����
                    String[] arr_field = content.split(separator);
                    if(arr_field.length+encodeFieldsNames.length != fieldSize) {
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
                        //��Ҫ���ӱ�����ֶ�����
                        String encodeFieldsName=encodeFieldsNames[j-1];
                        //��Ҫ���ӱ�����ֶε��±�
                        Integer fieldIndex= fieldsNamesList.indexOf(encodeFieldsName);
                        preparedStatement.setObject(arr_field.length+j,
                                encodeMap.get(arr_field[fieldIndex]));
                    }
                    preparedStatement.addBatch();
                } else {
                    result = Status.BACKOFF;
                    break;
                }
                if(i == batchSize - 1) {
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

//    private void insertRecord(String targetTableName,Channel channel){
//        Status result = Status.READY;
//        Transaction transaction = channel.getTransaction();
//        Event event;
//        String content;
//        transaction.begin();
//        try {
//            preparedStatement.clearBatch();
//            for (int i = 0; i < batchSize; i++) {
//                event = channel.take();
//                if (event != null) {
//                    content = new String(event.getBody());
//                    // ����
//                    String[] arr_field = content.split(separator);
//                    if(arr_field.length+3 != fieldSize) {
//                        lossRecordStatement.setObject(1,tableName);
//                        lossRecordStatement.setObject(2,content);
//                        Boolean isExecute= lossRecordStatement.execute();
//                        conn.commit();
//                        log.warn("���ݴ���{}", content );
//                        log.warn("���������Ƿ񱣴�ɹ���"+isExecute);
//                        break;
//                    }
//
//                    for(int j = 1; j <= arr_field.length; j++) {
//                        preparedStatement.setObject(j, arr_field[j - 1]);
//                    }
//                    //�����ı����ֶμ���
//                    for(int j=1;j<=encodeFieldsNames.length;j++){
//                        //��Ҫ���ӱ�����ֶ�����
//                        String encodeFieldsName=encodeFieldsNames[j-1];
//                        //��Ҫ���ӱ�����ֶε��±�
//                        Integer fieldIndex= fieldsNamesList.indexOf(encodeFieldsName);
//                        preparedStatement.setObject(arr_field.length+j,
//                                encodeMap.get(arr_field[fieldIndex]));
//                    }
//                    preparedStatement.addBatch();
//                } else {
//                    result = Status.BACKOFF;
//                    break;
//                }
//                if(i == batchSize - 1) {
//                    preparedStatement.executeBatch();
//                    conn.commit();
//                }
//            }
//            transaction.commit();
//        } catch (SQLException e) {
//            transaction.rollback();
//            log.error("Failed to commit transaction." + "Transaction rolled back.", e);
//
//        } finally {
//            transaction.close();
//        }
//}

}