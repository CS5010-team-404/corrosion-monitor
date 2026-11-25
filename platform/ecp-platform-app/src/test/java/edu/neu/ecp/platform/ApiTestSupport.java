package edu.neu.ecp.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.neu.ecp.common.domain.Hmac;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;

/**
 * The whole platform (real SecurityConfig, Flyway, ingestion, health, devices, simulator API, forecast) on MySQL 8.4
 * with the production SQL settings. One container per test JVM, so the cached Spring context never points at a
 * stopped database. Background jobs, the embedded simulator and demo devices are off; tests drive everything.
 * Users sign in with the backend's tokens from fixtures/backend-jwt.txt (TECHNICIAN and ADMIN).
 */
@SpringBootTest(properties = {
        "ecp.platform.auth=jwt",
        "ecp.security.jwt-secret=" + ApiTestSupport.FIXTURE_SECRET,
        "ecp.scheduling.enabled=false",
        "ecp.simulator.enabled=false",
        "ecp.demo.seed=false"})
@AutoConfigureMockMvc
public abstract class ApiTestSupport {

    /** The backend TokenFixtureTest's obviously fake secret; never used in configuration. */
    static final String FIXTURE_SECRET = "test-only-secret-never-use-in-production-0123";

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withCommand(
            "--sql-mode=REAL_AS_FLOAT,PIPES_AS_CONCAT,ANSI_QUOTES,IGNORE_SPACE,ONLY_FULL_GROUP_BY,ANSI,STRICT_ALL_TABLES,"
                    + "NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION",
            "--sql-require-primary-key=ON",
            "--log-bin-trust-function-creators=1");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&rewriteBatchedStatements=true");
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
    }

    private static final AtomicInteger DEVICES = new AtomicInteger();
    private static Map<String, String> tokens;

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ObjectMapper json;

    protected static String bearer(String role) {
        if (tokens == null) {
            tokens = new LinkedHashMap<>();
            try (InputStream in = ApiTestSupport.class.getResourceAsStream("/fixtures/backend-jwt.txt")) {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                    if (!line.isBlank() && !line.startsWith("#")) {
                        tokens.put(line.substring(0, line.indexOf('=')), line.substring(line.indexOf('=') + 1).trim());
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return "Bearer " + tokens.get(role);
    }

    /** A device id no other test uses, so tests stay independent on the shared database. */
    protected static String newDeviceId(String prefix) {
        return prefix + "-" + DEVICES.incrementAndGet() + "-" + System.nanoTime() % 100000;
    }

    public record Device(String deviceId, long id, String secret) {
    }

    /** Registers a device through the admin API and returns its id and one-time secret. */
    protected Device register(String deviceId, long equipmentId) throws Exception {
        String body = """
                {"deviceId":"%s","equipmentId":%d,"equipmentName":"Rack A1","locationName":"Server Room A","kind":"physical"}
                """.formatted(deviceId, equipmentId);
        String res = mvc.perform(post("/api/v1/admin/sensors").header("Authorization", bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode n = json.readTree(res);
        long id = jdbc.queryForObject("SELECT id FROM sensors WHERE device_id = ?", Long.class, deviceId);
        return new Device(deviceId, id, n.get("secret").asText());
    }

    /** One reading as the device sends it. */
    protected static String reading(long seq, Instant at, double t, double rh) {
        return """
                {"seq":%d,"measuredAt":"%s","temperatureC":%s,"humidityPct":%s}""".formatted(seq, at, t, rh);
    }

    protected static String batch(String... readings) {
        return "{\"readings\":[" + String.join(",", readings) + "]}";
    }

    /** POSTs a body signed with the device secret at the given time, exactly as a device does. */
    protected ResultActions ingest(String deviceId, String secret, String body, Instant signedAt) throws Exception {
        long ts = signedAt.getEpochSecond();
        return mvc.perform(post("/api/v1/ingest/readings")
                .header("X-Device-Id", deviceId)
                .header("X-Timestamp", String.valueOf(ts))
                .header("X-Signature", Hmac.signRequest(secret, ts, body))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
