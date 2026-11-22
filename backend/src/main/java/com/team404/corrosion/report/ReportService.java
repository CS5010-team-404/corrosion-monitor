package com.team404.corrosion.report;

import com.team404.corrosion.report.ReportSummary.EquipmentRisk;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR7: the summary behind the report page, the CSV and the PDF. */
@Service
public class ReportService {

    private final ReportRepository repository;

    public ReportService(ReportRepository repository) {
        this.repository = repository;
    }

    /** One read-only transaction, so the figures come from the same moment. */
    @Transactional(readOnly = true)
    public ReportSummary summary(ReportPeriod period) {
        return ReportCalculator.summarize(period, repository.alertsOpened(period), repository.escalations(period),
                repository.actionTypes(period), riskFigures(period.from(), period.to(), period.location()));
    }

    /**
     * Time in each risk level per equipment item. This is where Aayushee's {@code AnalyticsService.riskSummary(from,
     * to)} plugs in (filtered to the location) once it is merged; until then the report has no risk figures, and the
     * CSV and PDF say so.
     */
    List<EquipmentRisk> riskFigures(Instant from, Instant to, String location) {
        return List.of();
    }
}
