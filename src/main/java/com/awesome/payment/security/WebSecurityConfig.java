package com.awesome.payment.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security. Role rules live on the controllers
 * ({@code @PreAuthorize}); this chain only separates public documentation and
 * health endpoints from authenticated API calls, and guards {@code /internal/**} with the
 * shared service token ({@link ServiceTokenFilter}).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class WebSecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtTokenValidator validator,
                                    @Value("${payment.service-token:}") String serviceToken) {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        // Guarded by ServiceTokenFilter instead of a user JWT.
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                ErrorResponses.send(response, HttpStatus.UNAUTHORIZED, "Unauthorized"))
                        .accessDeniedHandler((request, response, e) ->
                                ErrorResponses.send(response, HttpStatus.FORBIDDEN, "Access denied")))
                .addFilterBefore(new ServiceTokenFilter(serviceToken), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new JwtTokenFilter(validator), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** No local users: replaces Boot's generated default user. */
    @Bean
    UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
