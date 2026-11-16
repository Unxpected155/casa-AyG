package com.casaayg.shared.security;

import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Lo usa Spring tras verificar una passkey, para cargar al usuario. No hay contraseñas:
 * getPassword() devuelve null, así que ningún login con contraseña puede funcionar.
 */
public class CasaUserDetailsService implements UserDetailsService {

    private final JdbcClient jdbc;

    public CasaUserDetailsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return jdbc.sql("SELECT username FROM auth_find_user(?)")
            .param(username)
            .query((rs, n) -> new PasskeyOnlyUser(rs.getString("username")))
            .optional()
            .orElseThrow(() -> new UsernameNotFoundException("usuario no encontrado"));
    }

    record PasskeyOnlyUser(String username) implements UserDetails {

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return List.of(new SimpleGrantedAuthority("ROLE_USER"));
        }

        @Override
        public String getPassword() {
            return null;
        }

        @Override
        public String getUsername() {
            return username;
        }
    }
}
