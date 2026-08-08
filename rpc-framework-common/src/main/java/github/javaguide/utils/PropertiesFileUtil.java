package github.javaguide.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * @author shuang.kou
 * @createTime 2020年07月21日 14:25:00
 **/
@Slf4j
public final class PropertiesFileUtil {
    private PropertiesFileUtil() {
    }

    public static Properties readPropertiesFile(String fileName) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = PropertiesFileUtil.class.getClassLoader();
        }
        InputStream inputStream = classLoader.getResourceAsStream(fileName);
        if (inputStream == null) {
            log.warn("Properties file [{}] was not found on the classpath", fileName);
            return null;
        }
        try (InputStreamReader inputStreamReader =
                     new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            Properties properties = new Properties();
            properties.load(inputStreamReader);
            return properties;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read properties file " + fileName, e);
        }
    }
}
