package com.refundguard.security;

import com.refundguard.model.AppUser;
import com.refundguard.repository.AppUserRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Loads users from the database; authorities = ROLE_<role> plus every permission of that role. */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser u = users.findByUsernameIgnoreCase(username == null ? "" : username.trim())
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
        List<GrantedAuthority> auth = new ArrayList<>();
        auth.add(new SimpleGrantedAuthority(u.getRole().authority()));
        for (Permission p : RolePermissions.of(u.getRole())) auth.add(new SimpleGrantedAuthority(p.name()));
        return User.withUsername(u.getUsername())
                .password(u.getPasswordHash())
                .authorities(auth)
                .disabled(!u.isEnabled())
                .build();
    }
}
