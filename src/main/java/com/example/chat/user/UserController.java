package com.example.chat.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    /** Logs in by username: returns the existing user with that name, or creates one. No password. */
    @PostMapping("/join")
    public User join(@Valid @RequestBody Join body) {
        return users.findOrCreate(body.username());
    }

    @GetMapping("/me")
    public User me(@RequestAttribute(CurrentUserInterceptor.USER_ATTR) User me) {
        return me;
    }

    /** Used by the "start a new chat" search box. */
    @GetMapping
    public List<User> search(@RequestAttribute(CurrentUserInterceptor.USER_ATTR) User me,
                             @RequestParam(defaultValue = "") String q) {
        return users.findTop20ByUsernameContainingIgnoreCaseAndIdNotOrderByUsername(q.trim(), me.getId());
    }

    public record Join(
            // Restricted charset keeps usernames unambiguous when displayed and safe in URLs.
            @NotBlank @Size(min = 3, max = 32) @Pattern(regexp = "[A-Za-z0-9_.-]+") String username) {
    }
}
