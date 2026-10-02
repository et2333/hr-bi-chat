package com.hrchat.authz.mcp;

import com.hrchat.authz.model.UserContext;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MCP /mcp：鉴权分发与 tools/list；handler 存在时 tools/call 返回业务结果。 */
class McpControllerTest {

    private final McpAuthService mcpAuthService = Mockito.mock(McpAuthService.class);
    private final McpToolHandler semanticQuery = Mockito.mock(McpToolHandler.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(semanticQuery.toolName()).thenReturn("semantic_query");
        mockMvc = MockMvcBuilders.standaloneSetup(new McpController(mcpAuthService, List.of(semanticQuery))).build();
    }

    @Test
    void toolsList_withServiceToken_returnsThreeTools() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header(McpAuthService.SERVICE_TOKEN_HEADER, "svc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":"1","method":"tools/list","params":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.result.tools.length()").value(3))
                .andExpect(jsonPath("$.result.tools[0].name").value("get_semantic_meta"));
        verify(mcpAuthService).verifyServiceToken("svc");
    }

    @Test
    void toolsList_badServiceToken_returnsBusinessError() throws Exception {
        doThrow(new BizException(ErrorCode.FUNC_FORBIDDEN, "X-Service-Token"))
                .when(mcpAuthService).verifyServiceToken(any());
        mockMvc.perform(post("/mcp")
                        .header(McpAuthService.SERVICE_TOKEN_HEADER, "bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":"1","method":"tools/list"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32000))
                .andExpect(jsonPath("$.error.data.code").value(ErrorCode.FUNC_FORBIDDEN.getCode()));
    }

    @Test
    void toolsCall_dispatchesToHandler() throws Exception {
        when(mcpAuthService.authenticate(eq("svc"), eq("tok"), eq("inv-1")))
                .thenReturn(UserContext.builder().empNo("hr01").tenantId("t01").build());
        when(semanticQuery.call(any(), any(), any()))
                .thenReturn(Map.of("row_count", 1, "permission_rewrite_applied", true));
        mockMvc.perform(post("/mcp")
                        .header(McpAuthService.SERVICE_TOKEN_HEADER, "svc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":"9","method":"tools/call","params":{
                                  "name":"semantic_query",
                                  "arguments":{"metrics":["headcount"]},
                                  "context":{"tool_context_token":"tok","invocation_id":"inv-1"}
                                }}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.row_count").value(1))
                .andExpect(jsonPath("$.result.permission_rewrite_applied").value(true));
    }

    @Test
    void toolsCall_unknownKnownToolWithoutHandler_returnsUnavailable() throws Exception {
        mockMvc = MockMvcBuilders.standaloneSetup(new McpController(mcpAuthService, List.of())).build();
        when(mcpAuthService.authenticate(eq("svc"), eq("tok"), eq("inv-1")))
                .thenReturn(UserContext.builder().empNo("hr01").tenantId("t01").build());
        mockMvc.perform(post("/mcp")
                        .header(McpAuthService.SERVICE_TOKEN_HEADER, "svc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":"9","method":"tools/call","params":{
                                  "name":"permission_check",
                                  "arguments":{},
                                  "context":{"tool_context_token":"tok","invocation_id":"inv-1"}
                                }}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.data.code").value(ErrorCode.SERVICE_UNAVAILABLE.getCode()));
    }

    @Test
    void toolsCall_missingToolToken_propagatesAuthError() throws Exception {
        when(mcpAuthService.authenticate(eq("svc"), isNull(), eq("inv-1")))
                .thenThrow(new BizException(ErrorCode.AUTH_EXPIRED, "tool_context_token"));
        mockMvc.perform(post("/mcp")
                        .header(McpAuthService.SERVICE_TOKEN_HEADER, "svc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":"9","method":"tools/call","params":{
                                  "name":"semantic_query",
                                  "arguments":{},
                                  "context":{"invocation_id":"inv-1"}
                                }}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.data.code").value(ErrorCode.AUTH_EXPIRED.getCode()));
    }

    @Test
    void unknownMethod_returnsMethodNotFound() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":"1","method":"ping"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32601));
    }
}
