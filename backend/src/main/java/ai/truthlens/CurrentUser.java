package ai.truthlens;

import java.util.UUID;

public record CurrentUser(UUID id, String role, boolean active) {
  boolean isAdmin() {
    return "ADMIN".equals(role);
  }

  boolean isFactChecker() {
    return "FACT_CHECKER".equals(role);
  }
}
