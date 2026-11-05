package edu.neu.ecp.platform.forecast;

import edu.neu.ecp.forecast.model.Forecast;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ForecastController {

    private final ForecastService service;

    public ForecastController(ForecastService service) {
        this.service = service;
    }

    /** Margin forecast at 15/30/60 minutes and early-warning probabilities for one sensor. */
    @GetMapping("/api/v1/sensors/{sensorId}/forecast")
    public Forecast forecast(@PathVariable long sensorId, @RequestParam(required = false) Double offset) {
        return service.forSensor(sensorId, offset)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No recent readings for sensor " + sensorId));
    }

    /** Version and evaluation metrics of the bundled model (shown on the equipment page and in the report). */
    @GetMapping("/api/v1/forecast/model")
    public Map<String, Object> model() {
        return Map.of("version", service.model().version(), "metrics", service.model().metrics());
    }
}
