package com.seatreservation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Created by {@link SecurityConfig} rather than annotated as a bean, so Boot does not also register it
 * as a servlet filter outside the security chain.
 */
public class MockTokenAuthFilter extends OncePerRequestFilter {

    private final AuthenticationEntryPoint entryPoint;

    public MockTokenAuthFilter(AuthenticationEntryPoint entryPoint) {
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        Optional<AuthenticatedUser> user = MockTokenParser.parse(header);
        if (user.isEmpty()) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, new BadCredentialsException("Invalid token"));
            return;
        }
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                user.get(), null, List.of(new SimpleGrantedAuthority(user.get().role().authority())));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
