package io.github.fourilla.endervault.config;

import io.github.fourilla.endervault.filesystem.FileSystemOperations;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Applies the resolved startup policy before domain beans can query optional native capabilities. */
@Configuration(proxyBeanMethods = false)
public class FileSystemConfiguration {

    @Bean
    static BeanFactoryPostProcessor fileSystemPolicyBootstrap(Environment environment) {
        return beanFactory -> {
            boolean nativeEnabled = environment.getProperty("nas.filesystem.native-enabled", Boolean.class, true);
            FileSystemOperations.configureNativePolicy(() -> nativeEnabled);
        };
    }

    @Bean
    FileSystemOperations fileSystemOperations(NasProperties properties) {
        FileSystemOperations.configureNativePolicy(() -> properties.getFilesystem().isNativeEnabled());
        return FileSystemOperations.systemDefault();
    }
}
