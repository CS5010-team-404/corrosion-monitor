package com.team404.corrosion.alert.signal;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled for the signal consumer. Nothing else in the backend schedules yet. */
@Configuration
@EnableScheduling
public class AlertSchedulingConfig {
}
