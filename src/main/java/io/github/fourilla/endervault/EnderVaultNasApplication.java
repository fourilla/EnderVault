package io.github.fourilla.endervault;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.config.StartupConfigBootstrap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(NasProperties.class)
@EnableScheduling
public class EnderVaultNasApplication {

    public static void main(String[] args) {
        if (StartupConfigBootstrap.prepareDefault(System.out) != StartupConfigBootstrap.Result.READY) {
            return;
        }
        SpringApplication.run(EnderVaultNasApplication.class, args);
    }
}
