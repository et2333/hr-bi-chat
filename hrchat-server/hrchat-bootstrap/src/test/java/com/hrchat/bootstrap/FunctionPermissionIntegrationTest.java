package com.hrchat.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:function_permissions;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class FunctionPermissionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void customRolesDriveRealAccessAndRevocationWithoutWaitingForTtl() throws Exception {
        assertThat(permissions().at("/functionPerms").size()).isZero();
        access(403);
        long first = createRole("CUSTOM_AUDITOR", "[\"admin:audit:read\",\"admin:audit:read\"]");
        long second = createRole("CUSTOM_REPORTER", "[\"report:view\"]");
        mvc.perform(post("/api/v1/admin/users/9/roles").header("X-User-No", "adm01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCodes\":[\"CUSTOM_AUDITOR\",\"CUSTOM_REPORTER\"]}"))
                .andExpect(status().isOk());
        JsonNode granted = permissions();
        assertThat(granted.at("/functionPerms").toString()).isEqualTo("[\"admin:audit:read\",\"report:view\"]");
        access(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sec_role_permission WHERE role_code='CUSTOM_AUDITOR'", Integer.class)).isEqualTo(1);

        // Omitted grants preserve existing permissions; unknown grants cannot partially overwrite them.
        update(first, "{\"roleName\":\"Renamed\"}", 200);
        update(first, "{\"functionPerms\":[\"unknown:permission\"]}", 400);
        update(first, "{\"functionPerms\":[\"*\"]}", 400);
        access(200);
        update(first, "{\"functionPerms\":[]}", 200);
        access(403);
        JsonNode revoked = permissions();
        assertThat(revoked.at("/functionPerms").toString()).isEqualTo("[\"report:view\"]");
        assertThat(revoked.at("/permissionFingerprint").asText())
                .isNotEqualTo(granted.at("/permissionFingerprint").asText());
        update(second, "{\"functionPerms\":[]}", 200);
        assertThat(permissions().at("/functionPerms").size()).isZero();

        // Self-service permission inspection never grants administration or cross-tenant access.
        mvc.perform(get("/api/v1/admin/authz/permission-catalog").header("X-User-No", "test09"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/permissions").header("X-User-No", "test09").header("X-Tenant-No", "t02"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/permissions").header("X-User-No", "missing-user"))
                .andExpect(status().isUnauthorized());
    }

    private long createRole(String code, String grants) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/authz/roles").header("X-User-No", "adm01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"" + code + "\",\"functionPerms\":" + grants + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).at("/data").asLong();
    }

    private void update(long id, String body, int expected) throws Exception {
        mvc.perform(patch("/api/v1/admin/authz/roles/" + id).header("X-User-No", "adm01")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private JsonNode permissions() throws Exception {
        String body = mvc.perform(get("/api/v1/me/permissions").header("X-User-No", "test09"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).at("/data");
    }

    private void access(int expected) throws Exception {
        mvc.perform(get("/api/v1/admin/audit/logs").header("X-User-No", "test09"))
                .andExpect(status().is(expected));
    }
}
