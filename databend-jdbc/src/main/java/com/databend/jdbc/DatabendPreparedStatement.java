package com.databend.jdbc;

import com.databend.client.StageAttachment;
import com.databend.jdbc.cloud.DatabendCopyParams;
import com.databend.jdbc.parser.BatchInsertUtils;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static com.databend.jdbc.ObjectCasts.castToBigDecimal;
import static com.databend.jdbc.ObjectCasts.castToBinary;
import static com.databend.jdbc.ObjectCasts.castToBoolean;
import static com.databend.jdbc.ObjectCasts.castToByte;
import static com.databend.jdbc.ObjectCasts.castToDouble;
import static com.databend.jdbc.ObjectCasts.castToFloat;
import static com.databend.jdbc.ObjectCasts.castToInt;
import static com.databend.jdbc.ObjectCasts.castToLong;
import static com.databend.jdbc.ObjectCasts.castToShort;
import static java.lang.String.format;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_TIME;
import static java.util.Objects.requireNonNull;

public class DatabendPreparedStatement extends DatabendStatement implements PreparedStatement
{
    static final DateTimeFormatter DATE_FORMATTER = ISODateTimeFormat.date();
    static final DateTimeFormatter TIME_FORMATTER = DateTimeFormat.forPattern("HH:mm:ss.SSS");
    static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final java.time.format.DateTimeFormatter LOCAL_DATE_TIME_FORMATTER =
            new DateTimeFormatterBuilder()
                    .append(ISO_LOCAL_DATE)
                    .appendLiteral(' ')
                    .append(ISO_LOCAL_TIME)
                    .toFormatter();
    private static final java.time.format.DateTimeFormatter OFFSET_TIME_FORMATTER =
            new DateTimeFormatterBuilder()
                    .append(ISO_LOCAL_TIME)
                    .appendOffset("+HH:mm", "+00:00")
                    .toFormatter();
    private final String originalSql;
    private final List<String[]> batchValues;
    private final Optional<BatchInsertUtils> batchInsertUtils;
    private final String statementName;
    private int batchSize = 0;

    DatabendPreparedStatement(DatabendConnection connection, Consumer<DatabendStatement> onClose, String statementName, String sql) {
        super(connection, onClose);
        this.statementName = requireNonNull(statementName, "statementName is null");
        this.originalSql = requireNonNull(sql, "sql is null");
        this.batchValues = new ArrayList<>();
        this.batchInsertUtils = BatchInsertUtils.tryParseInsertSql(sql);
    }

    private static String formatBooleanLiteral(boolean x)
    {
        return Boolean.toString(x);
    }

    private static String formatByteLiteral(byte x)
    {
        return Byte.toString(x);
    }

    private static String formatShortLiteral(short x)
    {
        return Short.toString(x);
    }

    private static String formatIntLiteral(int x)
    {
        return Integer.toString(x);
    }

    private static String formatLongLiteral(long x)
    {
        return Long.toString(x);
    }

    private static String formatFloatLiteral(float x)
    {
        return Float.toString(x);
    }

    private static String formatDoubleLiteral(double x)
    {
        return Double.toString(x);
    }

    private static String formatBigDecimalLiteral(BigDecimal x)
    {
        if (x == null) {
            return "null";
        }

        return x.toString();
    }


    private static String formatBytesLiteral(byte[] x)
    {
        return new String(x, StandardCharsets.UTF_8);
    }

    static IllegalArgumentException invalidConversion(Object x, String toType)
    {
        return new IllegalArgumentException(format("Cannot convert instance of %s to %s", x.getClass().getName(), toType));
    }

    @Override
    public void close()
            throws SQLException
    {
        super.close();
    }

    private StageAttachment uploadBatches() throws SQLException {
        if (this.batchValues == null || this.batchValues.size() == 0) {
            return null;
        }
        File saved = null;
        try {
            saved = batchInsertUtils.get().saveBatchToCSV(batchValues);
            DatabendConnection c = (DatabendConnection) getConnection();
            FileInputStream fis = new FileInputStream(saved);
            String uuid = UUID.randomUUID().toString();
            // format %Y/%m/%d/%H/%M/%S/fileName.csv
            String stagePrefix = String.format("%s/%s/%s/%s/%s/%s/%s/",
                    LocalDateTime.now().getYear(),
                    LocalDateTime.now().getMonthValue(),
                    LocalDateTime.now().getDayOfMonth(),
                    LocalDateTime.now().getHour(),
                    LocalDateTime.now().getMinute(),
                    LocalDateTime.now().getSecond(),
                    uuid);
            String fileName = saved.getName();
            c.uploadStream(null, stagePrefix, fis, fileName, false);
            String stagePath = "@~/" + stagePrefix + fileName;
            StageAttachment attachment = new StageAttachment.Builder().setLocation(stagePath).build();
            return attachment;
        } catch (FileNotFoundException e) {
            throw new SQLException(e);
        } finally{
            try {
                if (saved != null) {
                    saved.delete();
                }
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * delete stage file on stage attachment
     * @param attachment
     * @return true if delete success or resource not found
     */
    private boolean dropStageAttachment(StageAttachment attachment)
    {
        if (attachment == null) {
            return true;
        }
        String sql = String.format("REMOVE %s", attachment.getLocation());
        try {
            execute(sql);
            return true;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1003) {
                return true;
            }
            return false;
        }
    }

    @Override
    public int[] executeBatch() throws SQLException {
        int[] batchUpdateCounts = new int[batchValues.size()];
        if (!batchInsertUtils.isPresent() || batchValues == null || batchValues.isEmpty()) {
            super.execute(this.originalSql);
            return batchUpdateCounts;
        }
        StageAttachment attachment = uploadBatches();
        ResultSet r = null;
        if (attachment == null) {
            super.execute(batchInsertUtils.get().getSql());
            return batchUpdateCounts;
        }
        try {
            super.internalExecute(batchInsertUtils.get().getSql(), attachment);
            r = getResultSet();
            while (r.next()) {

            }
            Arrays.fill(batchUpdateCounts, 1);
            return batchUpdateCounts;
        }
        catch (RuntimeException e) {
            throw new SQLException(e);
        } finally{
            dropStageAttachment(attachment);
        }
    }

    @Override
    public ResultSet executeQuery()
            throws SQLException
    {
        this.executeBatch();
        return getResultSet();
    }

    @Override
    public int executeUpdate()
            throws SQLException
    {
        return 0;
    }

    @Override
    public void setNull(int i, int i1)
            throws SQLException
    {
        checkOpen();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, null));
    }

    @Override
    public void setBoolean(int i, boolean b)
            throws SQLException
    {
        checkOpen();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, formatBooleanLiteral(b)));
    }

    @Override
    public void setByte(int i, byte b)
            throws SQLException
    {
        checkOpen();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, formatByteLiteral(b)));
    }

    @Override
    public void setShort(int i, short i1)
            throws SQLException
    {
        checkOpen();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, formatShortLiteral(i1)));
    }

    @Override
    public void setInt(int i, int i1)
            throws SQLException
    {
        checkOpen();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, formatIntLiteral(i1)));
    }

    @Override
    public void setLong(int i, long l)
            throws SQLException
    {
        checkOpen();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, formatLongLiteral(l)));
    }

    @Override
    public void setFloat(int i, float v)
            throws SQLException
    {
        checkOpen();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, formatFloatLiteral(v)));
    }

    @Override
    public void setDouble(int i, double v)
            throws SQLException
    {
        checkOpen();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, formatDoubleLiteral(v)));
    }

    @Override
    public void setBigDecimal(int i, BigDecimal bigDecimal)
            throws SQLException
    {
        checkOpen();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, formatBigDecimalLiteral(bigDecimal)));
    }

    @Override
    public void setString(int i, String s)
            throws SQLException
    {
        checkOpen();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, s));
    }

    @Override
    public void setBytes(int i, byte[] bytes)
            throws SQLException
    {
        checkOpen();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, formatBytesLiteral(bytes)));
    }

    @Override
    public void setDate(int i, Date date)
            throws SQLException
    {
        checkOpen();
        if (date == null) {
            batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, null));
        }
        else {
            batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, toDateLiteral(date)));
        }
    }

    @Override
    public void setTime(int i, Time time)
            throws SQLException
    {
        checkOpen();
        if (time == null) {
            batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, null));
        }
        else {
            batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, toTimeLiteral(time)));
        }
    }

    @Override
    public void setTimestamp(int i, Timestamp timestamp)
            throws SQLException
    {
        checkOpen();
        if (timestamp == null) {
            batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, null));
        }
        else {
            batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, toTimestampLiteral(timestamp)));
        }
    }

    @Override
    public void setAsciiStream(int i, InputStream inputStream, int i1)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("setAsciiStream not supported");
    }

    @Override
    public void setUnicodeStream(int i, InputStream inputStream, int i1)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("setUnicodeStream not supported");
    }

    @Override
    public void setBinaryStream(int i, InputStream inputStream, int i1)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("setBinaryStream not supported");
    }

    @Override
    public void clearParameters()
            throws SQLException
    {
        checkOpen();
        batchInsertUtils.ifPresent(BatchInsertUtils::clean);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType)
            throws SQLException
    {
        checkOpen();
        if (x == null) {
            batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(parameterIndex, null));
        }
        switch (targetSqlType) {
            case Types.BOOLEAN:
            case Types.BIT:
                setBoolean(parameterIndex, castToBoolean(x, targetSqlType));
                return;
            case Types.TINYINT:
                setByte(parameterIndex, castToByte(x, targetSqlType));
                return;
            case Types.SMALLINT:
                setShort(parameterIndex, castToShort(x, targetSqlType));
                return;
            case Types.INTEGER:
                setInt(parameterIndex, castToInt(x, targetSqlType));
                return;
            case Types.BIGINT:
                setLong(parameterIndex, castToLong(x, targetSqlType));
                return;
            case Types.FLOAT:
            case Types.REAL:
                setFloat(parameterIndex, castToFloat(x, targetSqlType));
                return;
            case Types.DOUBLE:
                setDouble(parameterIndex, castToDouble(x, targetSqlType));
                return;
            case Types.DECIMAL:
            case Types.NUMERIC:
                setBigDecimal(parameterIndex, castToBigDecimal(x, targetSqlType));
                return;
            case Types.CHAR:
            case Types.NCHAR:
            case Types.VARCHAR:
            case Types.NVARCHAR:
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR:
                setString(parameterIndex, x.toString());
                return;
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                setBytes(parameterIndex, castToBinary(x, targetSqlType));
                return;
            case Types.DATE:
                setString(parameterIndex, toDateLiteral(x));
                return;
            case Types.TIME:
                setString(parameterIndex, toTimeLiteral(x));
                return;
            case Types.TIME_WITH_TIMEZONE:
                setString(parameterIndex, toTimeWithTimeZoneLiteral(x));
                return;
            case Types.TIMESTAMP:
                setString(parameterIndex, toTimestampLiteral(x));
                return;
            case Types.TIMESTAMP_WITH_TIMEZONE:
                setString(parameterIndex, toTimestampWithTimeZoneLiteral(x));
                return;
        }
        throw new SQLException("Unsupported target SQL type: " + targetSqlType);
    }

    @Override
    public void setObject(int parameterIndex, Object x)
            throws SQLException
    {
        checkOpen();
        if (x == null) {
            setNull(parameterIndex, Types.NULL);
        }
        else if (x instanceof Boolean) {
            setBoolean(parameterIndex, (Boolean) x);
        }
        else if (x instanceof Byte) {
            setByte(parameterIndex, (Byte) x);
        }
        else if (x instanceof Short) {
            setShort(parameterIndex, (Short) x);
        }
        else if (x instanceof Integer) {
            setInt(parameterIndex, (Integer) x);
        }
        else if (x instanceof Long) {
            setLong(parameterIndex, (Long) x);
        }
        else if (x instanceof Float) {
            setFloat(parameterIndex, (Float) x);
        }
        else if (x instanceof Double) {
            setDouble(parameterIndex, (Double) x);
        }
        else if (x instanceof BigDecimal) {
            setBigDecimal(parameterIndex, (BigDecimal) x);
        }
        else if (x instanceof String) {
            setString(parameterIndex, (String) x);
        }
        else if (x instanceof byte[]) {
            setBytes(parameterIndex, (byte[]) x);
        }
        else if (x instanceof Date) {
            setDate(parameterIndex, (Date) x);
        }
        else if (x instanceof LocalDate) {
            setString(parameterIndex, toDateLiteral(x));
        }
        else if (x instanceof Time) {
            setTime(parameterIndex, (Time) x);
        }
        // TODO (https://github.com/trinodb/trino/issues/6299) LocalTime -> setAsTime
        else if (x instanceof OffsetTime) {
            setString(parameterIndex, toTimeWithTimeZoneLiteral(x));
        }
        else if (x instanceof Timestamp) {
            setTimestamp(parameterIndex, (Timestamp) x);
        }
        else {
            throw new SQLException("Unsupported object type: " + x.getClass().getName());
        }
    }

    @Override
    public boolean execute()
            throws SQLException
    {
        return false;
    }

    @Override
    public void addBatch()
            throws SQLException
    {
        checkOpen();
        if (batchInsertUtils.isPresent()) {
            String[] val = batchInsertUtils.get().getValues();
            batchValues.add(val);
            batchInsertUtils.get().clean();
            batchSize++;
        }
    }

    @Override
    public void clearBatch() throws SQLException {
        checkOpen();
        batchValues.clear();
        batchSize = 0;
        batchInsertUtils.ifPresent(BatchInsertUtils::clean);
    }

    @Override
    public void setCharacterStream(int i, Reader reader, int i1)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setCharacterStream");
    }

    @Override
    public void setRef(int i, Ref ref)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setRef");
    }

    @Override
    public void setBlob(int i, Blob blob)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setBlob");
    }

    @Override
    public void setClob(int i, Clob clob)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setClob");
    }

    @Override
    public void setArray(int i, Array array)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setArray");
    }

    @Override
    public ResultSetMetaData getMetaData()
            throws SQLException
    {
        return null;
    }

    @Override
    public void setDate(int i, Date date, Calendar calendar)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setDate");
    }

    @Override
    public void setTime(int i, Time time, Calendar calendar)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setTime");
    }

    @Override
    public void setTimestamp(int i, Timestamp timestamp, Calendar calendar)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setTimestamp");
    }

    @Override
    public void setNull(int i, int i1, String s)
            throws SQLException
    {
        setNull(i, i1);
    }

    @Override
    public void setURL(int i, URL url)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setURL");
    }

    @Override
    public ParameterMetaData getParameterMetaData()
            throws SQLException
    {
        return null;
    }

    @Override
    public void setRowId(int i, RowId rowId)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setRowId");
    }

    @Override
    public void setNString(int i, String s)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setNString");
    }

    @Override
    public void setNCharacterStream(int i, Reader reader, long l)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setNCharacterStream");
    }

    @Override
    public void setNClob(int i, NClob nClob)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setNClob");
    }

    @Override
    public void setClob(int i, Reader reader, long l)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setClob");
    }

    @Override
    public void setBlob(int i, InputStream inputStream, long l)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setBlob");
    }

    @Override
    public void setNClob(int i, Reader reader, long l)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setNClob");
    }

    @Override
    public void setSQLXML(int i, SQLXML sqlxml)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setSQLXML");
    }

    @Override
    public void setObject(int i, Object o, int i1, int i2)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setObject");
    }

    @Override
    public void setAsciiStream(int i, InputStream inputStream, long l)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setAsciiStream");
    }

    @Override
    public void setBinaryStream(int i, InputStream inputStream, long l)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setBinaryStream");
    }

    @Override
    public void setCharacterStream(int i, Reader reader, long l)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setCharacterStream");
    }

    @Override
    public void setAsciiStream(int i, InputStream inputStream)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setAsciiStream");
    }

    @Override
    public void setBinaryStream(int i, InputStream inputStream)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setBinaryStream");
    }

    @Override
    public void setCharacterStream(int i, Reader reader)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setCharacterStream");
    }

    @Override
    public void setNCharacterStream(int i, Reader reader)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setNCharacterStream");
    }

    @Override
    public void setClob(int i, Reader reader)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setClob");
    }

    @Override
    public void setBlob(int i, InputStream inputStream)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setBlob");
    }

    @Override
    public void setNClob(int i, Reader reader)
            throws SQLException
    {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setNClob");
    }

    private String toDateLiteral(Object value) throws IllegalArgumentException
    {
        requireNonNull(value, "value is null");
        if (value instanceof java.util.Date) {
            return DATE_FORMATTER.print(((java.util.Date) value).getTime());
        }
        if (value instanceof LocalDate) {
            return ISO_LOCAL_DATE.format(((LocalDate) value));
        }
        if (value instanceof LocalDateTime) {
            return ISO_LOCAL_DATE.format(((LocalDateTime) value));
        }
        if (value instanceof String) {
            // TODO validate proper format
            return (String) value;
        }
        throw invalidConversion(value, "date");
    }

    private String toTimeLiteral(Object value)
            throws IllegalArgumentException
    {
        if (value instanceof java.util.Date) {
            return TIME_FORMATTER.print(((java.util.Date) value).getTime());
        }
        if (value instanceof LocalTime) {
            return ISO_LOCAL_TIME.format((LocalTime) value);
        }
        if (value instanceof LocalDateTime) {
            return ISO_LOCAL_TIME.format((LocalDateTime) value);
        }
        if (value instanceof String) {
            // TODO validate proper format
            return (String) value;
        }
        throw invalidConversion(value, "time");
    }

    private String toTimestampLiteral(Object value)
            throws IllegalArgumentException
    {
        if (value instanceof java.util.Date) {
            return TIMESTAMP_FORMATTER.print(((java.util.Date) value).getTime());
        }
        if (value instanceof LocalDateTime) {
            return LOCAL_DATE_TIME_FORMATTER.format(((LocalDateTime) value));
        }
        if (value instanceof String) {
            // TODO validate proper format
            return (String) value;
        }
        throw invalidConversion(value, "timestamp");
    }

    private String toTimestampWithTimeZoneLiteral(Object value)
            throws SQLException
    {
        if (value instanceof String) {
            return (String) value;
        }
        throw invalidConversion(value, "timestamp with time zone");
    }

    private String toTimeWithTimeZoneLiteral(Object value)
            throws SQLException
    {
        if (value instanceof OffsetTime) {
            return OFFSET_TIME_FORMATTER.format((OffsetTime) value);
        }
        if (value instanceof String) {
            // TODO validate proper format
            return (String) value;
        }
        throw invalidConversion(value, "time with time zone");
    }

}
