package com.jaramgle.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/images/**")
                .addResourceLocations("file:///Users/kyj/testimagedir/");

        registry.addResourceHandler("/api/image/**")
                .addResourceLocations("file:///Users/kyj/testimagedir/");

        registry.addResourceHandler("/characters/**")
                .addResourceLocations("file:///Users/kyj/testchardir/");

        registry.addResourceHandler("/api/audio/**")
                .addResourceLocations("file:///Users/kyj/testaudiodir/");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**") // Apply CORS to all /api endpoints
                .allowedOrigins("http://localhost:5173", "http://localhost:3000") // Frontend origins
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS") // Allowed HTTP methods
                .allowedHeaders("*") // Allow all headers
                .allowCredentials(true) // Allow credentials (e.g., cookies, authorization headers)
                .exposedHeaders("Set-Cookie");
    }
}
