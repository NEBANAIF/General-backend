package com.company.erp.auth;

import com.company.erp.auth.dto.BootstrapAdminRequest;
import com.company.erp.auth.dto.CurrentUserResponse;
import com.company.erp.auth.dto.LoginRequest;
import com.company.erp.auth.dto.LoginResponse;
import com.company.erp.security.UserPrincipal;
import com.company.erp.user.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal UserPrincipal principal) {
        return authService.currentUser(principal);
    }

    /**
     * One-time setup endpoint for the very first Super Admin (call it from Postman). There is no one to log in
     * as yet, so it is not protected by a login - it is protected by a secret setup key instead: the server only
     * accepts the request when the header X-Setup-Key equals the ERP_SETUP_KEY set on the server, and the
     * endpoint is switched off completely while that variable is empty. It also refuses with 409 the moment any
     * user exists, so it can never create a second account.
     */
    @PostMapping("/bootstrap-admin")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse bootstrapAdmin(@Valid @RequestBody BootstrapAdminRequest request,
                                       @RequestHeader(value = "X-Setup-Key", required = false) String setupKey) {
        return authService.bootstrapAdmin(request, setupKey);
    }
}
