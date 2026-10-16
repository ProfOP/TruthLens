package ai.truthlens;

import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AdminBootstrap {
  @Bean
  CommandLineRunner bootstrapAdmin(JdbcTemplate db, PasswordEncoder passwords) {
    return args -> {
      String email = System.getenv("TRUTHLENS_BOOTSTRAP_ADMIN_EMAIL");
      String name = System.getenv("TRUTHLENS_BOOTSTRAP_ADMIN_NAME");
      String password = System.getenv("TRUTHLENS_BOOTSTRAP_ADMIN_PASSWORD");
      if (email == null || name == null || password == null || email.isBlank() || name.isBlank() || password.isBlank()) {
        return;
      }
      if (password.length() < 8) {
        throw new IllegalArgumentException("TRUTHLENS_BOOTSTRAP_ADMIN_PASSWORD must be at least 8 characters");
      }
      String normalized = email.trim().toLowerCase(java.util.Locale.ROOT);
      Integer count = db.queryForObject("SELECT count(*) FROM users WHERE email=?", Integer.class, normalized);
      if (count != null && count > 0) {
        return;
      }
      db.update(
          "INSERT INTO users(id,display_name,email,password_hash,role,active) VALUES (?,?,?,?,?,TRUE)",
          UUID.randomUUID(),
          name.trim(),
          normalized,
          passwords.encode(password),
          "ADMIN");
    };
  }
}
