package github.javaguide.utils;

import org.junit.jupiter.api.Test;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PropertiesFileUtilTest {

    @Test
    void shouldReadPropertiesFromClasspathContainingChineseCharacters() throws Exception {
        Path directory = Files.createTempDirectory("中文配置目录");
        Path configFile = directory.resolve("rpc.properties");
        Files.write(configFile, "message=配置读取成功\n".getBytes(StandardCharsets.UTF_8));
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();

        try (URLClassLoader classLoader = new URLClassLoader(
                new java.net.URL[]{directory.toUri().toURL()}, null)) {
            Thread.currentThread().setContextClassLoader(classLoader);
            Properties properties = PropertiesFileUtil.readPropertiesFile("rpc.properties");
            assertEquals("配置读取成功", properties.getProperty("message"));
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
            Files.deleteIfExists(configFile);
            Files.deleteIfExists(directory);
        }
    }
}
