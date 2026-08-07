package com.chronos.auth;

import com.chronos.auth.dto.UpdateRoleRequest;
import com.chronos.auth.dto.UserResponse;
import com.chronos.common.PageResponse;
import com.chronos.security.ChronosUserDetails;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User administration.
 *
 * <p>Belt and braces: {@code /users/**} is already restricted to ADMIN by URL in
 * {@link com.chronos.config.SecurityConfig}, and {@code @PreAuthorize} repeats it here. The URL
 * rule is the one that cannot be forgotten when a method is added; the annotation is the one
 * that survives someone reorganising the URL structure.
 */
@RestController
@RequestMapping("/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public PageResponse<UserResponse> list(
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return userService.list(pageable);
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable Long id) {
        return userService.get(id);
    }

    @PatchMapping("/{id}/role")
    public UserResponse updateRole(@PathVariable Long id,
                                   @Valid @RequestBody UpdateRoleRequest request,
                                   @AuthenticationPrincipal ChronosUserDetails principal) {
        return userService.updateRole(id, request.role(), principal.id());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @AuthenticationPrincipal ChronosUserDetails principal) {
        userService.delete(id, principal.id());
        return ResponseEntity.noContent().build();
    }
}
