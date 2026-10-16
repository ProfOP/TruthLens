package ai.truthlens;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class AccessControl {
  private final JdbcTemplate db;

  public AccessControl(JdbcTemplate db) {
    this.db = db;
  }

  public CurrentUser requireUser() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user) || !user.active()) {
      throw ApiException.unauthorized();
    }
    return user;
  }

  public CurrentUser requireRole(String role) {
    CurrentUser user = requireUser();
    if (!role.equals(user.role())) {
      throw ApiException.forbidden();
    }
    return user;
  }

  public Map<String, Object> requireReadableAnalysis(UUID id) {
    CurrentUser user = requireUser();
    var rows = db.queryForList(
        """
        SELECT id, owner_id, input_type, input_text, status, result_json, error_message,
               reviewable, review_status, created_at, updated_at
        FROM analyses WHERE id=?
        """,
        id);
    if (rows.isEmpty()) {
      throw ApiException.notFound();
    }
    Map<String, Object> analysis = rows.get(0);
    if (!canRead(user, analysis)) {
      if (analysis.get("owner_id") == null) {
        throw ApiException.notFound();
      }
      throw ApiException.forbidden();
    }
    return analysis;
  }

  public boolean canRead(CurrentUser user, Map<String, Object> analysis) {
    Object owner = analysis.get("owner_id");
    if (owner == null) {
      return false;
    }
    UUID ownerId = owner instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(owner));
    if (user.id().equals(ownerId)) {
      return true;
    }
    if (user.isFactChecker() && Boolean.TRUE.equals(analysis.get("reviewable"))) {
      return true;
    }
    return user.isAdmin();
  }

  public String historySql(CurrentUser user) {
    if (user.isAdmin()) {
      return "owner_id IS NOT NULL";
    }
    if (user.isFactChecker()) {
      return "(owner_id=? OR (reviewable=TRUE AND owner_id IS NOT NULL))";
    }
    return "owner_id=?";
  }

  public boolean historyUsesOwnerParameter(CurrentUser user) {
    return !user.isAdmin();
  }
}
