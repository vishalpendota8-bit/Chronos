package com.chronos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code chronos.retry.*}.
 *
 * @param maxBackoffSec ceiling on the computed delay. Without it, a multiplier of 2 and 20
 *        attempts would reach roughly six days — long past the point where anyone is still
 *        waiting for the job to run.
 * @param jitterRatio fraction by which each delay is randomly nudged, e.g. 0.20 for ±20%.
 */
@ConfigurationProperties(prefix = "chronos.retry")
public record RetryProperties(int maxBackoffSec, double jitterRatio) {
}
