package ai.truthlens;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
public class JwtAuthenticationFilter extends OncePerRequestFilter {
  private final JwtService jwt;
  private final JdbcTemplate db;

  public JwtAuthenticationFilter(JwtService jwt, JdbcTemplate db) {
    this.jwt = jwt;
    this.db = db;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header == null || header.isBlank()) {
      chain.doFilter(request, response);
      return;
    }
    if (!header.startsWith("Bearer ")) {
      unauthorized(response);
      return;
    }
    JwtService.Claims claims = jwt.verify(header.substring(7).trim());
    if (claims == null) {
      unauthorized(response);
      return;
    }
    try {
      UUID id = UUID.fromString(claims.subject());
      var rows = db.queryForList("SELECT id, role, active FROM users WHERE id=?", id);
      if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("active"))) {
        unauthorized(response);
        return;
      }
      String role = String.valueOf(rows.get(0).get("role"));
      if (!List.of("USER", "FACT_CHECKER", "ADMIN").contains(role)) {
        unauthorized(response);
        return;
      }
      CurrentUser user = new CurrentUser(id, role, true);
      var authentication = new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
      SecurityContextHolder.getContext().setAuthentication(authentication);
      chain.doFilter(request, response);
    } catch (IllegalArgumentException e) {
      unauthorized(response);
    }
  }

  private static void unauthorized(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json");
    response.getWriter().write("{\"error\":\"Authentication required\"}");
  }
}
