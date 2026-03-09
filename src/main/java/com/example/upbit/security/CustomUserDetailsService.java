package com.example.upbit.security;

import com.example.upbit.db.AppUserEntity;
import com.example.upbit.db.AppUserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final AppUserRepository repo;

    public CustomUserDetailsService(AppUserRepository repo) {
        this.repo = repo;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUserEntity u = repo.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));
        return new User(u.getUsername(), u.getPassword(),
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
