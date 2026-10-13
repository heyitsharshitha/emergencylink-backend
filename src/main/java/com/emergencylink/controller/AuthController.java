package com.emergencylink.controller;

import com.emergencylink.dto.AuthDtos.*;
import com.emergencylink.security.CurrentUser;
import com.emergencylink.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) { return auth.login(req); }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) { return auth.register(req); }

    @GetMapping("/me")
    public UserDto me() { return auth.me(CurrentUser.get()); }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        auth.logout(CurrentUser.get());
        return ResponseEntity.noContent().build();
    }
}
