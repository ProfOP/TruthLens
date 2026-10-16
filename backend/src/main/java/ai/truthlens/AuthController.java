package ai.truthlens;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class AuthController {
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
  private final JdbcTemplate db;
  private final JwtService jwt;
  private final PasswordEncoder passwords;
  private final AccessControl access;

  public AuthController(JdbcTemplate db, JwtService jwt, PasswordEncoder passwords, AccessControl access) {
    this.db = db;
    this.jwt = jwt;
    this.passwords = passwords;
    this.access = access;
  }

  @PostMapping("/register")
  ResponseEntity<?> register(@RequestBody Map<String, String> input) {
    if (input.containsKey("role") || input.containsKey("roles") || input.containsKey("admin")) {
      return ResponseEntity.badRequest().body(Map.of("error", "Role cannot be assigned during registration"));
    }
    String name = trim(input.get("name"));
    String email = normalize(input.get("email"));
    String password = input.getOrDefault("password", "");
    if (name.length() < 1 || name.length() > 200 || !EMAIL.matcher(email).matches() || password.length() < 8 || password.length() > 200) {
      return ResponseEntity.badRequest().body(Map.of("error", "Name, valid email, and password of 8-200 characters are required"));
    }
    try {
      UUID id = UUID.randomUUID();
      db.update(
          "INSERT INTO users(id,display_name,email,password_hash,role,active) VALUES (?,?,?,?,?,TRUE)",
          id,
          name,
          email,
          passwords.encode(password),
          "USER");
      return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "name", name, "email", email, "role", "USER"));
    } catch (DuplicateKeyException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Registration could not be completed"));
    }
  }

  @PostMapping("/login")
  ResponseEntity<?> login(@RequestBody Map<String, String> input) {
    String email = normalize(input.get("email"));
    String password = input.getOrDefault("password", "");
    var rows = db.queryForList("SELECT id,display_name,email,password_hash,role,active FROM users WHERE email=?", email);
    if (rows.isEmpty()
        || !passwords.matches(password, String.valueOf(rows.get(0).get("password_hash")))
        || !Boolean.TRUE.equals(rows.get(0).get("active"))) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid email or password"));
    }
    var user = rows.get(0);
    String role = String.valueOf(user.get("role"));
    return ResponseEntity.ok(Map.of(
        "token",
        jwt.issue(String.valueOf(user.get("id")), role),
        "expiresIn",
        900,
        "user",
        Map.of("id", user.get("id"), "name", user.get("display_name"), "email", user.get("email"), "role", role)));
  }

  @GetMapping("/profile")
  Map<String, Object> profile() {
    CurrentUser user = access.requireUser();
    return db.queryForMap("SELECT id,display_name AS name,email,role,active,created_at FROM users WHERE id=?", user.id());
  }

  static String normalize(String value) {
    return trim(value).toLowerCase(Locale.ROOT);
  }

  static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
