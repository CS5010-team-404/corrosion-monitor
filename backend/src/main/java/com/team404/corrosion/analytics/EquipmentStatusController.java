package com.team404.corrosion.analytics;

import com.team404.corrosion.web.ApiErrors;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Equipment status and its assessment history for the dashboard (docs/CONTRACTS.md). Any signed-in user may read. */
@RestController
@RequestMapping("/api/v1/equipment")
public class EquipmentStatusController {

    private final AnalyticsService analytics;
    private final ApiErrors errors;

    public EquipmentStatusController(AnalyticsService analytics, ApiErrors errors) {
        this.analytics = analytics;
        this.errors = errors;
    }

    @GetMapping("/status")
    public List<EquipmentStatus> status() {
        return analytics.equipmentStatuses();
    }

    /**
     * The stored assessments of one equipment item from the last {@code minutes} minutes (default 60; a value outside
     * 1 to seven days is cut to that range, as only seven days are kept), oldest first.
     */
    @GetMapping("/{id}/assessments")
    public ResponseEntity<?> assessments(@PathVariable("id") long id,
                                         @RequestParam(name = "minutes", defaultValue = "60") int minutes) {
        Optional<List<AssessmentPoint>> points = analytics.assessments(id, minutes);
        if (points.isEmpty()) {
            return errors.response(HttpStatus.NOT_FOUND, "NOT_FOUND", "There is no equipment with id " + id + ".",
                    Map.of());
        }
        return ResponseEntity.ok(points.get());
    }
}
