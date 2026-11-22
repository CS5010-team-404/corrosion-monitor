package com.team404.corrosion.report;

import com.team404.corrosion.web.ApiErrors;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * FR7 reports for managers and admins (SecurityConfig: /api/v1/reports/**). {@code from} and {@code to} are ISO-8601
 * instants (the period is [from, to), either may be left out); {@code location} is a location name, or "all".
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ReportService reports;
    private final ApiErrors errors;
    private final Clock clock;

    public ReportController(ReportService reports, ApiErrors errors, Clock clock) {
        this.reports = reports;
        this.errors = errors;
        this.clock = clock;
    }

    @GetMapping("/summary")
    public ReportSummary summary(@RequestParam(name = "from", required = false) Instant from,
                                 @RequestParam(name = "to", required = false) Instant to,
                                 @RequestParam(name = "location", required = false) String location) {
        return reports.summary(new ReportPeriod(from, to, location));
    }

    @GetMapping("/summary.csv")
    public ResponseEntity<byte[]> csv(@RequestParam(name = "from", required = false) Instant from,
                                      @RequestParam(name = "to", required = false) Instant to,
                                      @RequestParam(name = "location", required = false) String location) {
        String csv = ReportCsv.write(reports.summary(new ReportPeriod(from, to, location)));
        return file(csv.getBytes(StandardCharsets.UTF_8), CSV, "corrosion-watch-report.csv");
    }

    @GetMapping("/summary.pdf")
    public ResponseEntity<byte[]> pdf(@RequestParam(name = "from", required = false) Instant from,
                                      @RequestParam(name = "to", required = false) Instant to,
                                      @RequestParam(name = "location", required = false) String location) {
        byte[] pdf = ReportPdf.write(reports.summary(new ReportPeriod(from, to, location)), clock.instant());
        return file(pdf, MediaType.APPLICATION_PDF, "corrosion-watch-report.pdf");
    }

    private static ResponseEntity<byte[]> file(byte[] body, MediaType type, String name) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> badParameter(MethodArgumentTypeMismatchException e) {
        return errors.response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "'" + e.getName() + "' must be an ISO-8601 time such as 2026-11-01T00:00:00Z.", Map.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badPeriod(IllegalArgumentException e) {
        return errors.response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage(), Map.of());
    }
}
