package com.cuhkcampus.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cuhkcampus.common.CampusPrincipal;
import com.cuhkcampus.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final JdbcClient db;
    private final PasswordEncoder passwords;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpSessionSecurityContextRepository sessions = new HttpSessionSecurityContextRepository();

    public AuthController(JdbcClient db, PasswordEncoder passwords) { this.db = db; this.passwords = passwords; }

    record Register(String email, String password, String displayName) {}
    record Login(String email, String password) {}
    public record User(UUID id, String email, String displayName, String timezone, Map<String, Object> preferences) {}

    @PostMapping("/register")
    User register(@RequestBody Register body, HttpServletRequest request, HttpServletResponse response) {
        if (body.email() == null || body.email().isBlank() || body.password() == null || body.password().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入邮箱和密码");
        String email = body.email().strip().toLowerCase(java.util.Locale.ROOT);
        if (db.sql("select count(*) from users where email = ?").param(email).query(Long.class).single() > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该邮箱已注册");
        UUID id = UUID.randomUUID();
        db.sql("insert into users(id,email,password_hash,display_name,timezone) values(?,?,?,?,?)")
            .params(id, email, passwords.encode(body.password()), body.displayName() == null || body.displayName().isBlank() ? email : body.displayName().strip(), "Asia/Shanghai").update();
        signIn(id, email, request, response);
        return user(id);
    }

    @PostMapping("/login")
    User login(@RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
        var row = db.sql("select id,email,password_hash from users where email = ?")
            .param(body.email().strip().toLowerCase(java.util.Locale.ROOT)).query().listOfRows().stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误"));
        if (!passwords.matches(body.password(), (String) row.get("password_hash")))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误");
        UUID id = (UUID) row.get("id");
        signIn(id, (String) row.get("email"), request, response);
        return user(id);
    }

    @GetMapping("/me") User me() { return user(CurrentUser.id()); }

    @PatchMapping("/me")
    User update(@RequestBody Map<String, Object> body) throws Exception {
        User existing = me();
        Map<String, Object> preferences = new java.util.LinkedHashMap<>(storedPreferences(existing.id()));
        if (body.get("preferences") instanceof Map<?, ?> values) values.forEach((key, value) -> { if (!key.toString().equals("outlookClientId")) preferences.put(key.toString(), value); });
        db.sql("update users set display_name = ?, timezone = ?, preferences = cast(? as jsonb) where id = ?")
            .params(body.getOrDefault("displayName", existing.displayName()), body.getOrDefault("timezone", existing.timezone()), json.writeValueAsString(preferences), existing.id()).update();
        return me();
    }

    private User user(UUID id) {
        return db.sql("select id,email,display_name,timezone,preferences from users where id = ?").param(id).query(this::map).single();
    }

    private User map(ResultSet row, int index) throws SQLException {
        try {
            Map<String, Object> preferences = json.readValue(row.getString("preferences"), new TypeReference<>() {});
            preferences.remove("outlookClientId");
            preferences.remove("aiApiKey");
            preferences.remove("translationAiApiKey");
            return new User(row.getObject("id", UUID.class), row.getString("email"), row.getString("display_name"),
                row.getString("timezone"), preferences);
        } catch (Exception e) { throw new SQLException(e); }
    }

    private Map<String, Object> storedPreferences(UUID id) throws Exception {
        String value = db.sql("select preferences::text from users where id = ?").param(id).query(String.class).single();
        return json.readValue(value, new TypeReference<>() {});
    }

    private void signIn(UUID id, String email, HttpServletRequest request, HttpServletResponse response) {
        request.getSession();
        request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new CampusPrincipal(id, email), null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
        sessions.saveContext(context, request, response);
    }
}
