package com.team404.corrosion.analytics;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/equipment")
public class EquipmentStatusController {

    private final AnalyticsService analytics;
    public EquipmentStatusController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/status")
    public List<EquipmentStatus> status() {
        return analytics.equipmentStatuses();
    }
}