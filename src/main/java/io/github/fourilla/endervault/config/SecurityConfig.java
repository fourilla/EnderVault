package io.github.fourilla.endervault.config;

import io.github.fourilla.endervault.auth.LoginFailureAlertHandler;
import io.github.fourilla.endervault.auth.LoginSuccessAlertHandler;
import io.github.fourilla.endervault.session.DynamicConcurrentSessionStrategy;
import io.github.fourilla.endervault.session.ManagedSessionAuthenticationStrategy;
import io.github.fourilla.endervault.session.SessionManagementService;
import io.github.fourilla.endervault.session.SessionPolicyService;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.security.web.session.SimpleRedirectSessionInformationExpiredStrategy;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            LoginSuccessAlertHandler successHandler,
            LoginFailureAlertHandler failureHandler,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            SessionRegistry sessionRegistry
    ) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/css/**", "/js/**", "/webjars/**", "/_static/**", "/favicon.ico").permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/login", "/s/**").permitAll()
                        .requestMatchers("/r/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/login/passkey/**").permitAll()
                        .requestMatchers("/api/**").hasRole("ADMIN")
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/files/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .headers(headers -> headers
                        .frameOptions(frameOptions -> frameOptions.sameOrigin())
                )
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository)
                )
                .formLogin(login -> login
                        .loginPage("/login")
                        .successHandler(successHandler)
                        .failureHandler(failureHandler)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                )
                .sessionManagement(session -> session
                        .sessionAuthenticationStrategy(sessionAuthenticationStrategy)
                )
                .addFilterAt(
                        new ConcurrentSessionFilter(
                                sessionRegistry,
                                new SimpleRedirectSessionInformationExpiredStrategy("/login?expired")
                        ),
                        ConcurrentSessionFilter.class
                );

        return http.build();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository()
        );
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(
            SessionRegistry sessionRegistry,
            SessionPolicyService sessionPolicyService,
            SessionManagementService sessionManagementService
    ) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new DynamicConcurrentSessionStrategy(sessionRegistry, sessionPolicyService),
                new ChangeSessionIdAuthenticationStrategy(),
                new RegisterSessionAuthenticationStrategy(sessionRegistry),
                new ManagedSessionAuthenticationStrategy(sessionManagementService)
        ));
    }

    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    InMemoryUserDetailsManager userDetailsService(NasProperties nasProperties) {
        String password = nasProperties.getPasskeys().isPasswordLoginEnabled()
                ? nasProperties.getAdmin().getPassword()
                : "{noop}" + UUID.randomUUID();
        UserDetails admin = User.withUsername(nasProperties.getAdmin().getUsername())
                .password(password)
                .roles("ADMIN")
                .build();

        return new InMemoryUserDetailsManager(admin);
    }
}
