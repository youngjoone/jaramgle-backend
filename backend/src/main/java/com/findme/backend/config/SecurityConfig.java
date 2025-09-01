package com.findme.backend.config;

import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import com.findme.backend.filter.JwtAuthFilter;
import com.findme.backend.filter.RequestIdFilter; // Import RequestIdFilter
import com.findme.backend.auth.OAuth2SuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.MvcRequestMatcher;
import org.springframework.web.filter.CorsFilter; // Import CorsFilter
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final JwtAuthFilter jwtAuthFilter;
    private final RequestIdFilter requestIdFilter; // Inject RequestIdFilter
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint; // Inject JwtAuthenticationEntryPoint

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, HandlerMappingIntrospector introspector) throws Exception {
        MvcRequestMatcher.Builder mvcMatcherBuilder = new MvcRequestMatcher.Builder(introspector);

        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(mvcMatcherBuilder.pattern("/api/health")).permitAll()
                .requestMatchers(mvcMatcherBuilder.pattern("/api/public/**")).permitAll()
                .requestMatchers(mvcMatcherBuilder.pattern("/oauth2/authorization/**")).permitAll()
                .requestMatchers(mvcMatcherBuilder.pattern("/login/oauth2/code/**")).permitAll()
                .requestMatchers(mvcMatcherBuilder.pattern("/h2-console/**")).permitAll()
                .requestMatchers(mvcMatcherBuilder.pattern("/swagger-ui/**")).permitAll()
                .requestMatchers(mvcMatcherBuilder.pattern("/v3/api-docs/**")).permitAll()
                .requestMatchers(mvcMatcherBuilder.pattern("/api/auth/login")).permitAll() // Allow custom login
                .requestMatchers(mvcMatcherBuilder.pattern("/api/auth/refresh")).permitAll() // Allow token refresh
                .requestMatchers(mvcMatcherBuilder.pattern("/api/auth/signup")).permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(exception -> exception.authenticationEntryPoint(jwtAuthenticationEntryPoint)) // Configure custom entry point
            
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(requestIdFilter, CorsFilter.class) // Add RequestIdFilter before CorsFilter
            .headers(headers -> headers.frameOptions(frameOptions -> frameOptions.sameOrigin()));

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }
}

