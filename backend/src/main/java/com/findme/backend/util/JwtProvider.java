package com.findme.backend.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Jwts.SIG;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtProvider {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration-minutes}")
    private long expirationMinutes;

    @Value("${jwt.refresh-expiration-minutes}") // New property
    private long refreshExpirationMinutes; // New field

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(String subject) {
        Map<String, Object> claims = new HashMap<>();
        return createToken(claims, subject);
    }

    public String generateRefreshToken(String subject) {
        Map<String, Object> claims = new HashMap<>();
        return createRefreshToken(claims, subject);
    }

    private String createToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .subject(subject)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMinutes * 60 * 1000)) // Convert minutes to milliseconds
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    private String createRefreshToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .subject(subject)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpirationMinutes * 60 * 1000))
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    public Boolean validateToken(String token) {
        try {
            return !extractExpiration(token).before(new Date());
        } catch (Exception e) {
            return false; // Token is invalid or expired
        }
    }

    public String extractSubject(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
    }
}
