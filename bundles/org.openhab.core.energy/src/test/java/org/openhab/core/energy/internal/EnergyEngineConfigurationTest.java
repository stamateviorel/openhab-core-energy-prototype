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
package org.openhab.core.energy.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Reading the engine configuration.
 * <p>
 * Two things are pinned here that the cycle tests cannot see. First, the multi-valued parameters are declared
 * {@code multiple="true"}, so the framework hands them over as a collection and not as the comma string an earlier
 * revision assumed - both forms have to work, because a {@code .cfg} file still delivers a string. Second, a
 * malformed entry of one of the two map-shaped parameters is <em>reported</em> rather than dropped in silence: an
 * operator who mistypes a phase budget used to get no feedback at any log level and an unenforced budget on that
 * phase.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EnergyEngineConfigurationTest {

    private final List<String> rejected = new ArrayList<>();

    private EnergyEngineConfiguration read(Map<String, Object> properties) {
        return EnergyEngineConfiguration.fromProperties(properties, rejected::add);
    }

    @Test
    public void aMultiValuedParameterIsReadFromACollectionAndFromACommaString() {
        assertThat(read(Map.of("shadowedAlgorithms", List.of("solar", "boiler"))).shadowedAlgorithms(),
                is(Set.of("solar", "boiler")));
        assertThat(read(Map.of("shadowedAlgorithms", "solar, boiler")).shadowedAlgorithms(),
                is(Set.of("solar", "boiler")));
        assertThat(read(Map.of("shadowedParticipants", List.of("wallbox"))).shadowedParticipants(),
                is(Set.of("wallbox")));
        assertThat(read(Map.of()).shadowedAlgorithms(), is(empty()));
    }

    @Test
    public void aMalformedPhaseBudgetIsReportedAndTheOthersStillApply() {
        EnergyEngineConfiguration config = read(Map.of("phaseBudgets", "1:5750,2:oops,3:5750"));

        assertThat(config.limits().phaseBudgetWatts(1).getAsDouble(), is(5750.0));
        assertThat(config.limits().phaseBudgetWatts(3).getAsDouble(), is(5750.0));
        assertThat(config.limits().phaseBudgetWatts(2).isPresent(), is(false));
        assertThat(rejected, contains(containsString("2:oops")));
    }

    @Test
    public void aWellFormedConfigurationReportsNothing() {
        read(Map.of("phaseBudgets", "1:5750,2:5750,3:5750"));

        assertThat(rejected, is(empty()));
    }

    /**
     * Zero is a value in its own right for the two parameters that declare {@code min="0"}, and has to be told
     * apart from "not configured". Reading it as "not configured" turned a deliberate "no debounce" into the
     * five-second default.
     */
    @Test
    public void zeroIsHonouredWhereTheParameterAllowsIt() {
        assertThat(read(Map.of("triggerDebounce", 0)).triggerDebounce(), is(Duration.ZERO));
        assertThat(read(Map.of()).triggerDebounce(), is(EnergyEngineConfiguration.DEFAULT_TRIGGER_DEBOUNCE));
        assertThat(read(Map.of("staleAfter", 0)).staleAfter(), is(nullValue()));
        assertThat(read(Map.of("staleAfter", 30)).staleAfter(), is(Duration.ofSeconds(30)));

        // but not where a zero would be nonsense: a cycle every no time at all is not a cadence
        assertThat(read(Map.of("cycleInterval", 0)).cycleInterval(),
                is(EnergyEngineConfiguration.DEFAULT_CYCLE_INTERVAL));
    }

    @Test
    public void aValueIsReadWhateverTypeTheFrameworkDeliversItAs() {
        assertThat(read(Map.of("cycleInterval", new BigDecimal("15"))).cycleInterval(), is(Duration.ofSeconds(15)));
        assertThat(read(Map.of("cycleInterval", "15")).cycleInterval(), is(Duration.ofSeconds(15)));
        assertThat(read(Map.of("shadow", false)).shadow(), is(false));
        assertThat(read(Map.of("shadow", "false")).shadow(), is(false));
        assertThat(read(Map.of("budget", new BigDecimal("6000"))).limits().totalBudgetWatts().getAsDouble(),
                is(6000.0));

        // an unreadable value falls back rather than taking the engine's safety floor down with it
        assertThat(read(Map.of("nominalVoltage", "quite a lot")).nominalVoltage(),
                is(EnergyEngineConfiguration.DEFAULT_NOMINAL_VOLTAGE));
    }
}
