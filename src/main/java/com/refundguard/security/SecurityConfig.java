package com.refundguard.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless HTTP Basic authentication against the rg_users table, BCrypt-hashed passwords.
 *
 *  - /api/webhooks/**  stays public: it is authenticated by the HMAC signature of the payment gateway.
 *  - the static UI shell (index.html) is public; every data endpoint under /api/** requires authentication.
 *  - fine-grained authorisation is enforced on the controllers with @PreAuthorize("hasAuthority('PERMISSION')").
 *  - CSRF protection is off ONLY because the API is stateless: there is no session cookie, credentials are
 *    sent explicitly in the Authorization header by the page's JavaScript, and the 401 response has no
 *    WWW-Authenticate header, so browsers never attach credentials on their own.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, SecurityErrorHandlers errors) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.authenticationEntryPoint(errors))
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/webhooks/**").permitAll()
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/error").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                                .hasAuthority(Permission.SYSTEM_CONFIG_VIEW.name())
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll());
        return http.build();
    }
}
