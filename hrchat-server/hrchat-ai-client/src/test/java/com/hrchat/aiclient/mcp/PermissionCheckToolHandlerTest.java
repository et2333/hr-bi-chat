package com.hrchat.aiclient.mcp;

import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionCheckToolHandlerTest {

    @Mock private AuthzService authzService;
    @InjectMocks private PermissionCheckToolHandler handler;

    @Test
    void queryAllowed_returnsPolicies() {
        UserContext user = UserContext.builder().empNo("hr01")
                .grantedOrgs(List.of(UserContext.GrantedOrg.builder()
                        .orgNodeId(2L).orgPath("/1/2/").orgName("RD").scope(2)
                        .subtreeOrgKeys(List.of(2L)).build()))
                .build();
        when(authzService.decideFieldPolicy(eq(user), eq("salary"))).thenReturn(2);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.call(user,
                Map.of("intent", "QUERY", "semantic_objects", List.of("metric:salary")), Map.of());

        assertTrue((Boolean) result.get("allowed"));
        assertEquals(1, ((List<?>) result.get("field_policies")).size());
    }

    @Test
    void queryDenied_returnsAllowedFalse() {
        UserContext user = UserContext.builder().empNo("test09").build();
        doThrow(new BizException(ErrorCode.FUNC_FORBIDDEN)).when(authzService).checkFunc(any(), eq("chat:ask"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.call(user,
                Map.of("intent", "QUERY"), Map.of());

        assertFalse((Boolean) result.get("allowed"));
    }
}
