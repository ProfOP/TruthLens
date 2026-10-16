package ai.truthlens;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
  private static final Set<String> ROLES = Set.of("USER", "FACT_CHECKER", "ADMIN");
  private static final Set<String> INPUT_TYPES = Set.of("TEXT", "IMAGE", "CLAIM");
  private final JdbcTemplate db;
  private final AccessControl access;
  private final AiGateway ai;

  public AdminController(JdbcTemplate db, AccessControl access, AiGateway ai) {
    this.db = db;
    this.access = access;
    this.ai = ai;
  }

  @GetMapping("/health")
  Map<String, Object> health() {
    access.requireRole("ADMIN");
    try {
      return Map.of("status", "ok", "service", "backend", "aiService", ai.health());
    } catch (Exception e) {
      return Map.of("status", "degraded", "service", "backend", "aiService", "unavailable");
    }
  }

  @GetMapping("/models")
  Map<String, Object> models() {
    access.requireRole("ADMIN");
    try {
      return Map.of("ai", ai.health());
    } catch (Exception e) {
      return Map.of("ai", Map.of("status", "unavailable"));
    }
  }

  @GetMapping("/users")
  List<Map<String, Object>> users() {
    access.requireRole("ADMIN");
    return db.queryForList("SELECT id, display_name AS name, email, role, active, created_at FROM users ORDER BY created_at");
  }

  @GetMapping("/users/{id}")
  Map<String, Object> user(@PathVariable UUID id) {
    access.requireRole("ADMIN");
    return safeUser(id);
  }

  /**
   * ADMIN-only, owner-filtered, paginated analysis history for one account. Returns the same
   * summary columns the user's own History view uses: no input text, no asset or storage paths,
   * no password material. The administrator opens a row through the existing report endpoint,
   * which applies its own authorization.
   */
  @GetMapping("/users/{id}/analyses")
  Map<String, Object> userAnalyses(@PathVariable UUID id,
      @RequestParam(required = false) String type,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size) {
    access.requireRole("ADMIN");
    safeUser(id);
    String normalizedType = type == null || type.isBlank() ? null : type.trim().toUpperCase();
    if (normalizedType != null && !INPUT_TYPES.contains(normalizedType)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Type is invalid");
    }
    int limit = Math.min(Math.max(size, 1), 100);
    int offset = Math.max(page, 0) * limit;
    String filter = normalizedType == null ? "WHERE owner_id=?" : "WHERE owner_id=? AND input_type=?";
    Object[] where = normalizedType == null ? new Object[] {id} : new Object[] {id, normalizedType};
    Integer total = db.queryForObject("SELECT count(*) FROM analyses " + filter, Integer.class, where);
    List<Object> args = new java.util.ArrayList<>(Arrays.asList(where));
    args.add(limit);
    args.add(offset);
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT id, input_type, status, reviewable, review_status, created_at, updated_at FROM analyses "
            + filter + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
        args.toArray());
    return Map.of("owner_id", id.toString(), "type", normalizedType == null ? "ALL" : normalizedType,
        "page", Math.max(page, 0), "size", limit, "total", total == null ? 0 : total, "rows", rows);
  }

  @PatchMapping("/users/{id}")
  Map<String, Object> updateUser(@PathVariable UUID id, @RequestBody Map<String, Object> input) {
    CurrentUser actor = access.requireRole("ADMIN");
    if (actor.id().equals(id) && (input.containsKey("role") || input.containsKey("active"))) {
      throw new ApiException(HttpStatus.FORBIDDEN, "Administrators cannot change their own role or status");
    }
    var rows = db.queryForList("SELECT id, display_name, email, role, active FROM users WHERE id=?", id);
    if (rows.isEmpty()) {
      throw ApiException.notFound();
    }
    String role = input.containsKey("role") ? String.valueOf(input.get("role")).trim().toUpperCase() : String.valueOf(rows.get(0).get("role"));
    if (!ROLES.contains(role)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Role is invalid");
    }
    boolean active = input.containsKey("active") ? Boolean.parseBoolean(String.valueOf(input.get("active"))) : Boolean.TRUE.equals(rows.get(0).get("active"));
    boolean targetIsActiveAdmin = "ADMIN".equals(rows.get(0).get("role")) && Boolean.TRUE.equals(rows.get(0).get("active"));
    boolean removingAdmin = targetIsActiveAdmin && (!"ADMIN".equals(role) || !active);
    if (removingAdmin) {
      Integer otherAdmins = db.queryForObject(
          "SELECT count(*) FROM users WHERE role=? AND active=? AND id<>?", Integer.class, "ADMIN", true, id);
      if (otherAdmins == null || otherAdmins < 1) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "At least one active administrator must remain");
      }
    }
    db.update("UPDATE users SET role=?, active=? WHERE id=?", role, active, id);
    db.update(
        "INSERT INTO audit_events(id,actor_id,action,entity_id,created_at) VALUES (?,?,?,?,?)",
        UUID.randomUUID(),
        actor.id(),
        "ADMIN_USER_UPDATE",
        id,
        OffsetDateTime.now());
    return safeUser(id);
  }

  /** Account administration projection: never includes password_hash or any analysis data. */
  private Map<String, Object> safeUser(UUID id) {
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT id, display_name AS name, email, role, active, created_at FROM users WHERE id=?", id);
    if (rows.isEmpty()) {
      throw ApiException.notFound();
    }
    return rows.get(0);
  }

  @GetMapping("/audit")
  List<Map<String, Object>> audit() {
    access.requireRole("ADMIN");
    return db.queryForList("SELECT id, actor_id, action, entity_id, created_at FROM audit_events ORDER BY created_at DESC LIMIT 200");
  }
}
