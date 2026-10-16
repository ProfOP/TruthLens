package ai.truthlens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guard rails of admin account management, asserted directly against the controller so the
 * last-administrator rule can be exercised without changing any real administrator row.
 * The HTTP authorization boundary itself is covered by AdminUserManagementTest and RoleReportTest.
 */
class AdminUserGuardTest {
  private static final String TARGET_LOOKUP = "SELECT id, display_name, email, role, active FROM users WHERE id=?";
  private static final String SAFE_PROJECTION = "SELECT id, display_name AS name, email, role, active, created_at FROM users WHERE id=?";
  private static final String ADMIN_COUNT = "SELECT count(*) FROM users WHERE role=? AND active=? AND id<>?";

  private final JdbcTemplate db = mock(JdbcTemplate.class);
  private final AccessControl access = mock(AccessControl.class);
  private final AdminController controller = new AdminController(db, access, mock(AiGateway.class));
  private final UUID actorId = UUID.randomUUID();
  private final UUID targetId = UUID.randomUUID();

  @BeforeEach
  void adminActor() {
    when(access.requireRole("ADMIN")).thenReturn(new CurrentUser(actorId, "ADMIN", true));
  }

  @Test
  void administratorCannotChangeTheirOwnAccount() {
    ApiException error = assertThrows(ApiException.class,
        () -> controller.updateUser(actorId, Map.of("role", "FACT_CHECKER")));

    assertEquals(HttpStatus.FORBIDDEN, error.status());
    assertEquals("Administrators cannot change their own role or status", error.getMessage());
    verifyNoInteractions(db);
  }

  @Test
  void administratorCannotDeactivateThemselves() {
    ApiException error = assertThrows(ApiException.class,
        () -> controller.updateUser(actorId, Map.of("active", false)));

    assertEquals(HttpStatus.FORBIDDEN, error.status());
    verifyNoInteractions(db);
  }

  @Test
  void onlyWhitelistedRolesAreAccepted() {
    givenTarget("USER", true);

    ApiException error = assertThrows(ApiException.class, () -> controller.updateUser(targetId, Map.of("role", "SUPERUSER")));

    assertEquals(HttpStatus.BAD_REQUEST, error.status());
    verify(db, never()).update(startsWith("UPDATE users SET role=?"), any(Object[].class));
  }

  @Test
  void unknownAccountIsNotFoundAndNeverCreated() {
    when(db.queryForList(startsWith(TARGET_LOOKUP), any(Object[].class))).thenReturn(List.of());

    ApiException error = assertThrows(ApiException.class, () -> controller.updateUser(targetId, Map.of("role", "ADMIN")));

    assertEquals(HttpStatus.NOT_FOUND, error.status());
    verify(db, never()).update(any(String.class), any(Object[].class));
  }

  @Test
  void lastActiveAdministratorCannotBeDemoted() {
    givenTarget("ADMIN", true);
    when(db.queryForObject(startsWith(ADMIN_COUNT), eq(Integer.class), any(Object[].class))).thenReturn(0);

    ApiException error = assertThrows(ApiException.class, () -> controller.updateUser(targetId, Map.of("role", "USER")));

    assertEquals(HttpStatus.BAD_REQUEST, error.status());
    assertEquals("At least one active administrator must remain", error.getMessage());
    verify(db, never()).update(startsWith("UPDATE users SET role=?"), any(Object[].class));
  }

  @Test
  void lastActiveAdministratorCannotBeDeactivated() {
    givenTarget("ADMIN", true);
    when(db.queryForObject(startsWith(ADMIN_COUNT), eq(Integer.class), any(Object[].class))).thenReturn(0);

    ApiException error = assertThrows(ApiException.class, () -> controller.updateUser(targetId, Map.of("active", false)));

    assertEquals(HttpStatus.BAD_REQUEST, error.status());
    verify(db, never()).update(startsWith("UPDATE users SET role=?"), any(Object[].class));
  }

  @Test
  void demotionSucceedsAndIsAuditedWhenAnotherAdministratorRemains() {
    givenTarget("ADMIN", true);
    givenSafeProjection("USER");
    when(db.queryForObject(startsWith(ADMIN_COUNT), eq(Integer.class), any(Object[].class))).thenReturn(1);

    Map<String, Object> result = controller.updateUser(targetId, Map.of("role", "USER"));

    assertEquals("USER", result.get("role"));
    verify(db).update(startsWith("UPDATE users SET role=?, active=? WHERE id=?"), eq("USER"), eq(true), eq(targetId));
    verify(db).update(startsWith("INSERT INTO audit_events"), any(java.util.UUID.class), eq(actorId), eq("ADMIN_USER_UPDATE"), eq(targetId), any(java.time.OffsetDateTime.class));
    assertFalse(result.containsKey("password_hash"));
  }

  @Test
  void accountListProjectionCarriesNoSecrets() {
    when(db.queryForList(startsWith("SELECT id, display_name AS name, email, role, active, created_at FROM users ORDER BY created_at")))
        .thenReturn(List.of(Map.of("id", targetId, "name", "Target", "email", "t@example.com", "role", "USER")));

    controller.users();

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(db).queryForList(sql.capture());
    assertFalse(sql.getValue().toLowerCase().contains("password"));
  }

  @Test
  void singleAccountDetailUsesTheSafeProjectionOnly() {
    givenSafeProjection("FACT_CHECKER");

    Map<String, Object> detail = controller.user(targetId);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(db).queryForList(sql.capture(), eq(targetId));
    assertEquals(SAFE_PROJECTION, sql.getValue());
    assertFalse(sql.getValue().toLowerCase().contains("password"));
    assertEquals("FACT_CHECKER", detail.get("role"));
  }

  @Test
  void missingAccountDetailIsNotFound() {
    when(db.queryForList(startsWith(SAFE_PROJECTION), any(Object[].class))).thenReturn(List.of());

    ApiException error = assertThrows(ApiException.class, () -> controller.user(UUID.randomUUID()));

    assertEquals(HttpStatus.NOT_FOUND, error.status());
  }

  @Test
  void everyAdminEndpointDelegatesTheRoleCheck() {
    when(access.requireRole("ADMIN")).thenThrow(ApiException.forbidden());

    assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, () -> controller.users()).status());
    assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, () -> controller.user(targetId)).status());
    assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, () -> controller.updateUser(targetId, Map.of("role", "USER"))).status());
    assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, () -> controller.userAnalyses(targetId, null, 0, 10)).status());
    assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, controller::models).status());
    assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, controller::health).status());
    assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, controller::audit).status());
  }

  private void givenTarget(String role, boolean active) {
    when(db.queryForList(startsWith(TARGET_LOOKUP), any(Object[].class)))
        .thenReturn(List.of(Map.of("id", targetId, "display_name", "Target", "email", "t@example.com", "role", role, "active", active)));
  }

  private void givenSafeProjection(String role) {
    when(db.queryForList(startsWith(SAFE_PROJECTION), any(Object[].class)))
        .thenReturn(List.of(Map.of("id", targetId, "name", "Target", "email", "t@example.com", "role", role, "active", true)));
  }
}
