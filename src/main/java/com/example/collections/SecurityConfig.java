package com.example.collections;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@Configuration
@EnableMethodSecurity
class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean SecurityFilterChain filterChain(HttpSecurity http, JwtFilter jwtFilter) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/api/auth/login", "/health", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}

@Component
class TokenService {
    private final byte[] key;
    TokenService(@Value("${app.jwt-secret}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("JWT_SECRET must be configured with at least 32 characters");
        key = secret.getBytes(StandardCharsets.UTF_8);
    }
    String issue(Account account) {
        Instant now = Instant.now();
        return Jwts.builder().subject(account.username).claim("role",account.role).issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(now.plusSeconds(8 * 60 * 60))).signWith(Keys.hmacShaKeyFor(key)).compact();
    }
    String username(String token) {
        return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(key)).build().parseSignedClaims(token).getPayload().getSubject();
    }
}

@Component
class JwtFilter extends OncePerRequestFilter {
    private final TokenService tokens;
    private final AccountRepository accounts;
    JwtFilter(TokenService tokens, AccountRepository accounts) { this.tokens=tokens; this.accounts=accounts; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header=request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Account account=accounts.findByUsernameIgnoreCase(tokens.username(header.substring(7))).orElse(null);
                if (account != null && account.enabled) {
                    if (account.mustChangePassword && request.getRequestURI().startsWith("/api/")
                            && !request.getRequestURI().equals("/api/auth/me")
                            && !request.getRequestURI().equals("/api/auth/password")) {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN,"Password change required");
                        return;
                    }
                    var auth=new UsernamePasswordAuthenticationToken(account.username,null,List.of(new SimpleGrantedAuthority("ROLE_"+account.role)));
                    auth.setDetails(account.id);
                    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (RuntimeException ignored) {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request,response);
    }
}
