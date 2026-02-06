package com.jaramgle.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.jaramgle.backend.util.AssetUrlResolver;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String imageDir = AssetUrlResolver.getImageBaseDir();
        String charDir = AssetUrlResolver.getCharacterImageDir();
        String audioDir = AssetUrlResolver.getAudioBaseDir();

        registry.addResourceHandler("/images/**")
                .addResourceLocations(toFileLocation(imageDir));

        registry.addResourceHandler("/api/image/**")
                .addResourceLocations(toFileLocation(imageDir));

        registry.addResourceHandler("/characters/**")
                .addResourceLocations(toFileLocation(charDir));

        registry.addResourceHandler("/api/audio/**")
                .addResourceLocations(toFileLocation(audioDir));
    }

    private String toFileLocation(String dir) {
        Path path = Paths.get(dir).toAbsolutePath().normalize();
        String uri = path.toUri().toString();
        if (!uri.endsWith("/")) {
            uri = uri + "/";
        }
        return uri;
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
