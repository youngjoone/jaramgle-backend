package com.fairylearn.backend.config;

import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import com.fairylearn.backend.auth.CustomOAuth2UserService;
import com.fairylearn.backend.auth.CustomOidcUserService; // New import
import com.fairylearn.backend.filter.JwtAuthFilter;
import com.fairylearn.backend.filter.RequestIdFilter; // Import RequestIdFilter
import com.fairylearn.backend.auth.OAuth2SuccessHandler;
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
import jakarta.servlet.http.HttpServletResponse; // Import HttpServletResponse
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.MvcRequestMatcher;
import org.springframework.web.filter.CorsFilter; // Import CorsFilter
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;
import org.springframework.beans.factory.annotation.Autowired; // New import

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private OAuth2SuccessHandler oAuth2SuccessHandler; // No longer final
    private final JwtAuthFilter jwtAuthFilter;
    private final RequestIdFilter requestIdFilter; // Inject RequestIdFilter
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint; // Inject JwtAuthenticationEntryPoint
    private final CustomOAuth2UserService customOAuth2UserService; // Inject CustomOAuth2UserService
    private final CustomOidcUserService customOidcUserService; // Inject CustomOidcUserService

    @Autowired
    public void setOAuth2SuccessHandler(OAuth2SuccessHandler oAuth2SuccessHandler) {
        this.oAuth2SuccessHandler = oAuth2SuccessHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, HandlerMappingIntrospector introspector) throws Exception {
        MvcRequestMatcher.Builder mvcMatcherBuilder = new MvcRequestMatcher.Builder(introspector);

        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(mvcMatcherBuilder.pattern(org.springframework.http.HttpMethod.OPTIONS, "/**")).permitAll() // Permit all OPTIONS requests
                .requestMatchers(mvcMatcherBuilder.pattern("/error")).permitAll()
                .requestMatchers(mvcMatcherBuilder.pattern("/api/health")).permitAll()
                .requestMatchers(mvcMatcherBuilder.pattern("/api/public/**")).permitAll()
                .requestMatchers(mvcMatcherBuilder.pattern("/api/auth/**")).permitAll() // Consolidated auth paths
                .requestMatchers(mvcMatcherBuilder.pattern("/images/**")).permitAll() // Permit access to images
                .requestMatchers(mvcMatcherBuilder.pattern("/characters/**")).permitAll() // Permit access to character images
                .requestMatchers(mvcMatcherBuilder.pattern("/api/audio/**")).permitAll() // Permit access to audio
                .requestMatchers(mvcMatcherBuilder.pattern("/oauth2/authorization/**")).permitAll()
                .requestMatchers(mvcMatcherBuilder.pattern("/login/oauth2/code/**")).permitAll()
                .requestMatchers(mvcMatcherBuilder.pattern("/h2-console/**")).permitAll()
                .requestMatchers(mvcMatcherBuilder.pattern("/swagger-ui.html")).permitAll() // Specific Swagger UI HTML
                .requestMatchers(mvcMatcherBuilder.pattern("/swagger-ui/**")).permitAll()
                .requestMatchers(mvcMatcherBuilder.pattern("/v3/api-docs/**")).permitAll()
                .requestMatchers(mvcMatcherBuilder.pattern("/webjars/**")).permitAll() // Swagger UI static resources
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(userInfo -> userInfo
                    .userService(customOAuth2UserService)
                    .oidcUserService(customOidcUserService) // Add this line
                )
                .successHandler(oAuth2SuccessHandler)
            )
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                .accessDeniedHandler((req, res, ex) -> { // Add this accessDeniedHandler
                    res.setStatus(HttpServletResponse.SC_FORBIDDEN); // 403
                    res.setContentType("application/json");
                    res.getWriter().write("{\"code\": \"ACCESS_DENIED\"}");
                })
            )
            
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(requestIdFilter, CorsFilter.class) // Add RequestIdFilter before CorsFilter
            .headers(headers -> headers.frameOptions(frameOptions -> frameOptions.sameOrigin()));

        return http.build();
    }

    

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }
}
