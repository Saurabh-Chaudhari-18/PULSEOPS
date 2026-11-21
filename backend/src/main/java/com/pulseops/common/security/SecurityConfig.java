package com.pulseops.common.security;

import java.time.Clock;
import java.util.List;

import com.pulseops.common.config.PulseOpsProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless API security.
 *
 * <ul>
 *   <li>Bearer JWT for every {@code /api/**} route except the public auth endpoints.</li>
 *   <li>CSRF tokens are disabled because API calls authenticate with a bearer header, which
 *       browsers never attach automatically. The only cookie (refresh token) is
 *       HttpOnly + SameSite=Strict, scoped to {@code /api/v1/auth}, and its endpoints also
 *       require a custom header that cross-site requests cannot set without a CORS preflight.</li>
 *   <li>Everything not explicitly routed is denied.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final int BCRYPT_STRENGTH = 12;

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JsonSecurityErrorHandler errors,
                                    CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .frameOptions(frame -> frame.deny()));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(PulseOpsProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.security().corsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", AuthCsrfGuard.HEADER));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
