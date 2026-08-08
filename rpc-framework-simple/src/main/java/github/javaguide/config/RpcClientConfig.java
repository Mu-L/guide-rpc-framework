package github.javaguide.config;

import github.javaguide.enums.CompressTypeEnum;
import github.javaguide.enums.RpcConfigEnum;
import github.javaguide.enums.SerializationTypeEnum;
import github.javaguide.utils.PropertiesFileUtil;

import java.util.Properties;

/**
 * Client transport settings loaded from {@code rpc.properties}.
 *
 * <p>System properties take precedence over values in the file so the same artifact can be
 * configured in different environments without being rebuilt.</p>
 */
public final class RpcClientConfig {

    static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000;
    static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 10_000L;
    private static final String DEFAULT_SERIALIZATION = "hessian";
    private static final String DEFAULT_COMPRESS = "gzip";

    private final byte serializationCode;
    private final byte compressCode;
    private final int connectTimeoutMillis;
    private final long requestTimeoutMillis;

    private RpcClientConfig(byte serializationCode, byte compressCode,
                            int connectTimeoutMillis, long requestTimeoutMillis) {
        this.serializationCode = serializationCode;
        this.compressCode = compressCode;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    public static RpcClientConfig load() {
        Properties properties = PropertiesFileUtil.readPropertiesFile(
                RpcConfigEnum.RPC_CONFIG_PATH.getPropertyValue());
        return from(properties == null ? new Properties() : properties);
    }

    public static RpcClientConfig from(Properties properties) {
        String serialization = getProperty(properties, RpcConfigEnum.SERIALIZATION,
                DEFAULT_SERIALIZATION);
        String compress = getProperty(properties, RpcConfigEnum.COMPRESS, DEFAULT_COMPRESS);
        int connectTimeoutMillis = parsePositiveInt(
                getProperty(properties, RpcConfigEnum.CONNECT_TIMEOUT_MILLIS,
                        String.valueOf(DEFAULT_CONNECT_TIMEOUT_MILLIS)),
                RpcConfigEnum.CONNECT_TIMEOUT_MILLIS);
        long requestTimeoutMillis = parsePositiveLong(
                getProperty(properties, RpcConfigEnum.REQUEST_TIMEOUT_MILLIS,
                        String.valueOf(DEFAULT_REQUEST_TIMEOUT_MILLIS)),
                RpcConfigEnum.REQUEST_TIMEOUT_MILLIS);
        return new RpcClientConfig(
                parseSerialization(serialization),
                parseCompress(compress),
                connectTimeoutMillis,
                requestTimeoutMillis);
    }

    public byte getSerializationCode() {
        return serializationCode;
    }

    public byte getCompressCode() {
        return compressCode;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public long getRequestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    private static String getProperty(Properties properties, RpcConfigEnum key,
                                      String defaultValue) {
        String propertyName = key.getPropertyValue();
        String systemValue = System.getProperty(propertyName);
        if (systemValue != null && !systemValue.trim().isEmpty()) {
            return systemValue.trim();
        }
        String value = properties.getProperty(propertyName, defaultValue);
        return value == null ? defaultValue : value.trim();
    }

    private static int parsePositiveInt(String value, RpcConfigEnum key) {
        long parsed = parsePositiveLong(value, key);
        if (parsed > Integer.MAX_VALUE) {
            throw invalidPositiveNumber(key, value, null);
        }
        return (int) parsed;
    }

    private static byte parseSerialization(String value) {
        try {
            return SerializationTypeEnum.fromName(value).getCode();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    RpcConfigEnum.SERIALIZATION.getPropertyValue()
                            + " has unsupported value: " + value, e);
        }
    }

    private static byte parseCompress(String value) {
        try {
            return CompressTypeEnum.fromName(value).getCode();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    RpcConfigEnum.COMPRESS.getPropertyValue()
                            + " has unsupported value: " + value, e);
        }
    }

    private static long parsePositiveLong(String value, RpcConfigEnum key) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw invalidPositiveNumber(key, value, null);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw invalidPositiveNumber(key, value, e);
        }
    }

    private static IllegalArgumentException invalidPositiveNumber(RpcConfigEnum key,
                                                                   String value,
                                                                   Throwable cause) {
        return new IllegalArgumentException(
                key.getPropertyValue() + " must be a positive integer, but was: " + value,
                cause);
    }
}
