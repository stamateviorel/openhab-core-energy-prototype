/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.core.energy.forecast;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.forecast.internal.ForecastRegistryImpl;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;

/**
 * _Source-agnostic consumption_, `extension-surface` _Multiple contributors, user selection_ and the reporting half of
 * _Graceful degradation on contributor loss_.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ForecastRegistryImplTest {

    private static final Instant NOW = Instant.parse("2026-01-15T06:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    /**
     * _Swapping forecast services_: the consumer asks for a role, one service is replaced by another, and nothing on
     * the consumer's side changes.
     */
    @Test
    public void swappingOneForecastServiceForAnotherChangesNothingAboveTheRegistry() {
        ForecastRegistryImpl registry = new ForecastRegistryImpl(clock, Map.of());
        ForecastSeriesSource first = ForecastFixtures.source("solarforecast", ForecastRole.SOLAR_PRODUCTION,
                ForecastFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, "solarforecast", Units.WATT,
                        ForecastFixtures.GENERATED_AT, 1000, 2000),
                0);
        registry.addForecastSeriesSource(first);

        assertThat(read(registry), is(1000.0));

        registry.removeForecastSeriesSource(first);
        registry.addForecastSeriesSource(ForecastFixtures.source("otherforecast", ForecastRole.SOLAR_PRODUCTION,
                ForecastFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, "otherforecast", Units.WATT,
                        ForecastFixtures.GENERATED_AT, 1500, 2500),
                0));

        assertThat("the same call, a different add-on behind it", read(registry), is(1500.0));
        assertThat(registry.getAnsweringSourceId(ForecastRole.SOLAR_PRODUCTION), is(Optional.of("otherforecast")));
    }

    /**
     * _Nothing configured, ranking decides_.
     */
    @Test
    public void theHigherRankedSourceAnswersWhenTheSiteNamesNoPreference() {
        ForecastRegistryImpl registry = new ForecastRegistryImpl(clock, Map.of());
        registry.addForecastSeriesSource(ForecastFixtures.source("core-default", ForecastRole.SOLAR_PRODUCTION,
                ForecastFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, "core-default", Units.WATT,
                        ForecastFixtures.GENERATED_AT, 100),
                -2));
        registry.addForecastSeriesSource(ForecastFixtures.source("contributed", ForecastRole.SOLAR_PRODUCTION,
                ForecastFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, "contributed", Units.WATT,
                        ForecastFixtures.GENERATED_AT, 900),
                0));

        assertThat("a contributed source outranks a core-shipped one with no configuration at all", read(registry),
                is(900.0));
        assertThat(registry.getSourceIds(ForecastRole.SOLAR_PRODUCTION), is(List.of("contributed", "core-default")));
    }

    /**
     * ...and naming the other one in the role's configuration overrides that, with no code change.
     */
    @Test
    public void namingASourceForARoleOverridesTheRanking() {
        ForecastRegistryImpl registry = new ForecastRegistryImpl(clock,
                Map.of("sources", List.of("solar-production=core-default")));
        registry.addForecastSeriesSource(ForecastFixtures.source("core-default", ForecastRole.SOLAR_PRODUCTION,
                ForecastFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, "core-default", Units.WATT,
                        ForecastFixtures.GENERATED_AT, 100),
                -2));
        registry.addForecastSeriesSource(ForecastFixtures.source("contributed", ForecastRole.SOLAR_PRODUCTION,
                ForecastFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, "contributed", Units.WATT,
                        ForecastFixtures.GENERATED_AT, 900),
                0));

        assertThat(read(registry), is(100.0));
        assertThat(registry.getConditions(ForecastRole.SOLAR_PRODUCTION),
                not(hasItem(ForecastPlaneCondition.PREFERRED_SOURCE_ABSENT)));
    }

    /**
     * A named source that is not installed falls through to the ranking and says so, because a mistyped id and an
     * uninstalled add-on look identical from here and both need saying out loud.
     */
    @Test
    public void aNamedSourceThatIsNotInstalledFallsThroughAndIsReported() {
        ForecastRegistryImpl registry = new ForecastRegistryImpl(clock,
                Map.of("sources", List.of("solar-production=typo")));
        registry.addForecastSeriesSource(ForecastFixtures.source("contributed", ForecastRole.SOLAR_PRODUCTION,
                ForecastFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, "contributed", Units.WATT,
                        ForecastFixtures.GENERATED_AT, 900),
                0));

        assertThat(read(registry), is(900.0));
        assertThat(registry.getConditions(ForecastRole.SOLAR_PRODUCTION),
                hasItem(ForecastPlaneCondition.PREFERRED_SOURCE_ABSENT));
    }

    /**
     * _Forecast source fails_ and _Forecast source removed mid-day_: the live service goes dark, the stored baseline
     * is simply the next source down, and the plane keeps answering.
     */
    @Test
    public void aDarkForecastServiceDegradesToTheBaselineRatherThanToNothing() {
        ForecastRegistryImpl registry = new ForecastRegistryImpl(clock, Map.of());
        ForecastSeriesSource live = ForecastFixtures.source("solarforecast", ForecastRole.SOLAR_PRODUCTION,
                ForecastFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, "solarforecast", Units.WATT,
                        ForecastFixtures.GENERATED_AT, 4000),
                0);
        registry.addForecastSeriesSource(live);
        registry.addForecastSeriesSource(ForecastFixtures.source("stored-baseline", ForecastRole.SOLAR_PRODUCTION,
                ForecastFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, "stored-baseline", Units.WATT,
                        ForecastFixtures.GENERATED_AT.minusSeconds(86_400), 2500),
                -5));

        assertThat(read(registry), is(4000.0));

        registry.removeForecastSeriesSource(live);

        assertThat("planning continues on the baseline", read(registry), is(2500.0));
        assertThat(registry.getAnsweringSourceId(ForecastRole.SOLAR_PRODUCTION), is(Optional.of("stored-baseline")));
    }

    /**
     * A source that is installed but has nothing to say is skipped rather than answered with - a service that has just
     * started is a normal state, not a fault.
     */
    @Test
    public void aSourceWithNothingToSayIsSkipped() {
        ForecastRegistryImpl registry = new ForecastRegistryImpl(clock, Map.of());
        registry.addForecastSeriesSource(
                ForecastFixtures.source("just-started", ForecastRole.SOLAR_PRODUCTION, null, 10));
        registry.addForecastSeriesSource(ForecastFixtures.source("stored-baseline", ForecastRole.SOLAR_PRODUCTION,
                ForecastFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, "stored-baseline", Units.WATT,
                        ForecastFixtures.GENERATED_AT, 2500),
                -5));

        assertThat(read(registry), is(2500.0));
    }

    /**
     * Nothing at all for a role is an answered condition rather than an exception, because the corpus's own answer to
     * a missing data plane is to carry on and report.
     */
    @Test
    public void aRoleNothingIsRegisteredForIsReportedRatherThanThrown() {
        ForecastRegistryImpl registry = new ForecastRegistryImpl(clock, Map.of());

        assertThat(registry.getSeries(ForecastRole.WIND), is(Optional.empty()));
        assertThat(registry.getConditions(ForecastRole.WIND), hasItem(ForecastPlaneCondition.SOURCE_UNAVAILABLE));
        assertThat(registry.getConditions(), hasItem(ForecastPlaneCondition.SOURCE_UNAVAILABLE));
    }

    /**
     * A site that declared a maximum age is told when it is planning on something older - and a site that declared
     * none is told <em>that</em>, which is the honest half of shipping no number.
     */
    @Test
    public void anOldRunIsReportedOnlyAgainstAnAgeTheSiteDeclared() {
        ForecastSeriesSource yesterday = ForecastFixtures.source("solarforecast", ForecastRole.SOLAR_PRODUCTION,
                ForecastFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, "solarforecast", Units.WATT,
                        NOW.minusSeconds(30 * 3600), 2000),
                0);

        ForecastRegistryImpl unconfigured = new ForecastRegistryImpl(clock, Map.of());
        unconfigured.addForecastSeriesSource(yesterday);
        assertThat(read(unconfigured), is(2000.0));
        assertThat(unconfigured.getConditions(ForecastRole.SOLAR_PRODUCTION),
                contains(ForecastPlaneCondition.STALENESS_UNCONFIGURED));

        ForecastRegistryImpl configured = new ForecastRegistryImpl(clock, Map.of("staleAfter", 24));
        configured.addForecastSeriesSource(yesterday);
        assertThat("the series is still answered; the site is simply told it is old", read(configured), is(2000.0));
        assertThat(configured.getConditions(ForecastRole.SOLAR_PRODUCTION),
                contains(ForecastPlaneCondition.SOURCE_STALE));
    }

    /**
     * A source publishing the wrong quantity under a role is skipped and reported, never converted into something it
     * is not.
     */
    @Test
    public void aSourcePublishingTheWrongQuantityIsSkippedAndReported() {
        ForecastRegistryImpl registry = new ForecastRegistryImpl(clock, Map.of());
        registry.addForecastSeriesSource(ForecastFixtures.source("confused", ForecastRole.SOLAR_PRODUCTION,
                ForecastFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, "confused", SIUnits.CELSIUS,
                        ForecastFixtures.GENERATED_AT, 20),
                50));
        registry.addForecastSeriesSource(ForecastFixtures.source("sane", ForecastRole.SOLAR_PRODUCTION, ForecastFixtures
                .hourly(ForecastRole.SOLAR_PRODUCTION, "sane", Units.WATT, ForecastFixtures.GENERATED_AT, 900), 0));

        assertThat(read(registry), is(900.0));
        assertThat(registry.getConditions(ForecastRole.SOLAR_PRODUCTION),
                hasItem(ForecastPlaneCondition.UNIT_MISMATCH));
    }

    /**
     * Two sources at the same ranking are ordered by their ids, so the answer never depends on which of them
     * registered first.
     */
    @Test
    public void anEqualRankingIsBrokenDeterministically() {
        ForecastRegistryImpl one = new ForecastRegistryImpl(clock, Map.of());
        one.addForecastSeriesSource(sourceNamed("aaa", 100));
        one.addForecastSeriesSource(sourceNamed("bbb", 200));

        ForecastRegistryImpl other = new ForecastRegistryImpl(clock, Map.of());
        other.addForecastSeriesSource(sourceNamed("bbb", 200));
        other.addForecastSeriesSource(sourceNamed("aaa", 100));

        assertThat(read(one), is(read(other)));
        assertThat(one.getSourceIds(ForecastRole.SOLAR_PRODUCTION),
                is(other.getSourceIds(ForecastRole.SOLAR_PRODUCTION)));
    }

    private static ForecastSeriesSource sourceNamed(String id, double value) {
        return ForecastFixtures.source(id, ForecastRole.SOLAR_PRODUCTION, ForecastFixtures
                .hourly(ForecastRole.SOLAR_PRODUCTION, id, Units.WATT, ForecastFixtures.GENERATED_AT, value), 0);
    }

    /**
     * `extension-surface` _Graceful degradation on contributor loss_ requires the degraded source to be reported. The
     * plane's own conditions are machine-readable; this is where they reach the surface a user actually reads.
     */
    @Test
    public void whatThePlaneSettledForReachesTheReportAUserReads() {
        org.openhab.core.energy.internal.EnergyConfigStatus status = new org.openhab.core.energy.internal.EnergyConfigStatus();
        ForecastRegistryImpl registry = new ForecastRegistryImpl(clock, Map.of());
        registry.setEnergyConfigStatus(status);

        assertThat("nothing has been asked for yet, so there is nothing to say", status.getConfigStatus(), is(empty()));

        registry.getSeries(ForecastRole.SOLAR_PRODUCTION);

        assertThat(status.getConfigStatus(), hasSize(1));
        assertThat(status.getConfigStatus().iterator().next().parameterName, is("solar-production"));
        assertThat(status.getConfigStatus().iterator().next().toString(),
                containsString(ForecastPlaneCondition.SOURCE_UNAVAILABLE.name()));

        registry.addForecastSeriesSource(ForecastFixtures.source("solarforecast", ForecastRole.SOLAR_PRODUCTION,
                ForecastFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, "solarforecast", Units.WATT, NOW, 900), 0));
        registry.getSeries(ForecastRole.SOLAR_PRODUCTION);

        assertThat("a condition that goes away disappears without anything having to clear it",
                status.getConfigStatus().iterator().next().toString(),
                allOf(containsString(ForecastPlaneCondition.STALENESS_UNCONFIGURED.name()),
                        not(containsString(ForecastPlaneCondition.SOURCE_UNAVAILABLE.name()))));
    }

    private static double read(ForecastRegistry registry) {
        return registry.getSeries(ForecastRole.SOLAR_PRODUCTION).orElseThrow().slotAt(0).value();
    }
}
