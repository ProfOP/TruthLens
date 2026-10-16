package ai.truthlens;

import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SRS §4 administrator system control. One persisted setting is exposed: whether the service
 * accepts new analyses. Read is ADMIN-only for the full record; the value itself is published to
 * signed-in users through {@code GET /api/v1/system/status} so the interface can explain a pause.
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
public class AdminSettingsController {
  private static final Set<String> BOOLEAN_VALUES = Set.of("true", "false");

  private final AccessControl access;
  private final SystemSettingsService settings;

  public AdminSettingsController(AccessControl access, SystemSettingsService settings) {
    this.access = access;
    this.settings = settings;
  }

  @GetMapping
  Map<String, Object> read() {
    access.requireRole("ADMIN");
    return settings.current();
  }

  @PatchMapping
  Map<String, Object> update(@RequestBody Map<String, Object> input) {
    CurrentUser actor = access.requireRole("ADMIN");
    Object requested = input.get("submissionsEnabled");
    if (requested == null) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "submissionsEnabled is required");
    }
    String value = String.valueOf(requested).trim().toLowerCase();
    if (!BOOLEAN_VALUES.contains(value)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "submissionsEnabled must be true or false");
    }
    return settings.updateSubmissions(actor, Boolean.parseBoolean(value));
  }
}
