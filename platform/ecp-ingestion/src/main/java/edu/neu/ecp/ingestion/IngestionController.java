package edu.neu.ecp.ingestion;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device endpoint. Authenticated by HMAC signature, not by user login (see Hmac in ecp-common).
 *
 * <pre>
 * POST /api/v1/ingest/readings
 * X-Device-Id: SIM-001
 * X-Timestamp: 1761840000
 * X-Signature: 3f1c...
 * {"readings":[{"seq":42,"measuredAt":"2026-11-02T15:00:00Z","temperatureC":22.41,"humidityPct":48.20,"surfaceTempC":20.9}]}
 * </pre>
 * Responds 202 with counts, or 400/401/403 with the rejection reason.
 */
@RestController
@RequestMapping("/api/v1/ingest")
public class IngestionController {

    private final IngestionService service;

    public IngestionController(IngestionService service) {
        this.service = service;
    }

    @PostMapping(value = "/readings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestionService.Result> ingest(@RequestHeader("X-Device-Id") String deviceId,
                                                          @RequestHeader("X-Timestamp") String timestamp,
                                                          @RequestHeader("X-Signature") String signature,
                                                          @RequestBody String body) {
        IngestionService.Result result = service.ingest(deviceId, timestamp, signature, body);
        return ResponseEntity.status(result.httpStatus()).body(result);
    }
}
