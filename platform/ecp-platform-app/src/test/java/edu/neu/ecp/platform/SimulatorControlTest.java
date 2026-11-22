package edu.neu.ecp.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.neu.ecp.platform.demo.EmbeddedSimulator;
import edu.neu.ecp.simulator.Scenario;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Switching a simulated device into a failure. The running simulator posts over HTTP to a real port, so here it is
 * replaced by a mock; the security rules, validation and the audit event are real.
 */
class SimulatorControlTest extends ApiTestSupport {

    @MockitoBean
    EmbeddedSimulator simulator;

    @Test
    void adminSetsAScenarioAndItIsAudited() throws Exception {
        when(simulator.setScenario("SIM-010", Scenario.STUCK)).thenReturn(true);
        mvc.perform(post("/api/v1/admin/simulator/SIM-010/scenario").header("Authorization", bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"stuck\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("SIM-010"))
                .andExpect(jsonPath("$.scenario").value("STUCK"));
        verify(simulator).setScenario("SIM-010", Scenario.STUCK);
        assertThat(count("""
                SELECT COUNT(*) FROM audit_outbox WHERE action = 'SIMULATOR_SCENARIO_SET' AND entity_id = 'SIM-010'
                AND actor = 'admin@corrosionwatch.app' AND JSON_EXTRACT(details, '$.scenario') = 'STUCK'""")).isEqualTo(1);
    }
}
