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
package org.openhab.core.energy;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * Foundation tests for the participant model: the invariants the other wave-1 components rely on.
 * <p>
 * The per-scenario tests derived from the requirement scenarios live alongside the components that implement them;
 * this class only pins the data types themselves.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ParticipantModelTest {

    @Test
    public void energyLevelsAreOrderedAndCarryTheFixtureCodes() {
        assertThat(Stream.of(EnergyLevel.values()).map(EnergyLevel::code).toList(), is(List.of(0, 1, 2, 3)));
        assertThat(EnergyLevel.OVERCAPACITY.atLeast(EnergyLevel.ENCOURAGED), is(true));
        assertThat(EnergyLevel.NORMAL.atLeast(EnergyLevel.ENCOURAGED), is(false));
        assertThat(EnergyLevel.fromCode(2), is(Optional.of(EnergyLevel.ENCOURAGED)));
        assertThat(EnergyLevel.fromCode(4).isEmpty(), is(true));
    }

    @Test
    public void levelGateCollapsesTheScaleToAllowOrDeny() {
        LevelGate encouraged = LevelGate.atLeast(EnergyLevel.ENCOURAGED);
        assertThat(encouraged.permits(EnergyLevel.ENCOURAGED), is(true));
        assertThat(encouraged.permits(EnergyLevel.OVERCAPACITY), is(true));
        assertThat(encouraged.permits(EnergyLevel.NORMAL), is(false));
        assertThat(LevelGate.always().permits(EnergyLevel.BLOCKED), is(true));
        assertThat(LevelGate.always().minimumLevel(), is(EnergyLevel.BLOCKED));
    }

    /**
     * "Leave this device alone" is not a level any more. A gate always names one, so the only way to say it is
     * {@link EnergyConsumer#handsOff()} - which is exactly what lets every profile class say it.
     */
    @Test
    public void theGateAlwaysNamesALevelAndNeverMeansHandsOff() {
        for (EnergyLevel level : EnergyLevel.values()) {
            assertThat(LevelGate.atLeast(level).permits(EnergyLevel.OVERCAPACITY), is(true));
        }
        assertThat(EnergyConsumer.of("pump", "Pool_Pump", SimpleProfile.plain(), 5).handsOff(), is(false));
    }

    @Test
    public void providerRolesAndProfileKindsParseTheirAliases() {
        assertThat(ProviderRole.parse("SOLAR"), is(Optional.of(ProviderRole.PV)));
        assertThat(ProviderRole.parse(" storage "), is(Optional.of(ProviderRole.BATTERY)));
        assertThat(ProviderRole.parse("wind").isEmpty(), is(true));
        assertThat(PowerProfile.Kind.parse("mode"), is(Optional.of(PowerProfile.Kind.MODE_CONTROLLABLE)));
        assertThat(PowerProfile.Kind.parse("batch"), is(Optional.of(PowerProfile.Kind.BATCH)));
    }

    @Test
    public void consumersOrderDeterministicallyByPriorityThenId() {
        EnergyConsumer boiler = EnergyConsumer.of("boiler", "Boiler_Switch", SimpleProfile.plain(), 2);
        EnergyConsumer heating = EnergyConsumer.of("heating", "Heating_Switch", SimpleProfile.plain(), 1);
        EnergyConsumer other = EnergyConsumer.of("aaa", "Other_Switch", SimpleProfile.plain(), 2);
        assertThat(Stream.of(boiler, heating, other).sorted(EnergyConsumer.PRIORITY_ORDER).map(EnergyConsumer::id)
                .toList(), is(List.of("heating", "aaa", "boiler")));
    }

    @Test
    public void levelGateIsReachableFromTheConsumerForSimpleConsumersOnly() {
        SimpleProfile gated = SimpleProfile.withGate(LevelGate.atLeast(EnergyLevel.ENCOURAGED));
        assertThat(EnergyConsumer.of("pump", "Pool_Pump", gated, 5).levelGate(),
                is(Optional.of(LevelGate.atLeast(EnergyLevel.ENCOURAGED))));
        assertThat(EnergyConsumer.of("wallbox", "Wallbox_Power", ControllableProfile.amperes(6, 32), 1).levelGate()
                .isEmpty(), is(true));
    }

    /**
     * The requirement's _Hands-off on a class other than Simple_ scenario: an EV charger and a dishwasher can both
     * be marked hands-off, which is the whole point of lifting the flag off the Simple profile.
     */
    @Test
    public void handsOffIsReachableFromEveryProfileClass() {
        List<PowerProfile> everyClass = List.of(SimpleProfile.plain(), ControllableProfile.amperes(6, 32),
                ModeControllableProfile.of("blocked", "normal"), BatchProfile.flat(2000, Duration.ofHours(2)));

        for (PowerProfile profile : everyClass) {
            EnergyConsumer steered = EnergyConsumer.of("device", "Device_Item", profile, 10);
            assertThat("a fresh declaration is steerable", steered.handsOff(), is(false));
            assertThat("class " + profile.kind() + " can be marked hands-off", steered.withHandsOff().handsOff(),
                    is(true));
        }
    }

    /**
     * A hands-off consumer still declares everything it declared, so the electrical-limit floor keeps its
     * visibility of the load - marking a device hands-off must never be the cheapest way to hide it.
     */
    @Test
    public void aHandsOffConsumerStillCarriesItsMeasurementAndItsFigure() {
        EnergyConsumer fridge = EnergyConsumer.of("fridge", "Fridge_Switch", SimpleProfile.switchingAt(150), 30)
                .withMeasurement("Fridge_Power").withPhases(Set.of(1)).withHandsOff();

        assertThat(fridge.handsOff(), is(true));
        assertThat(fridge.isMetered(), is(true));
        assertThat(fridge.measureItemName(), is("Fridge_Power"));
        assertThat(fridge.phases(), is(Set.of(1)));
        assertThat(fridge.powerFigure().orElseThrow().doubleValue(), closeTo(150, 0.001));
    }

    /**
     * A declaration that names no priority is placed at 100 whichever mechanism produced it, and providers share the
     * consumer scale so that "is this battery's charge reclaimable for that consumer?" compares two numbers.
     */
    @Test
    public void anUndeclaredPriorityIsTheSameHundredOnEveryDeclarationPath() {
        assertThat(EnergyParticipant.DEFAULT_PRIORITY, is(100));
        assertThat(EnergyConsumer.DEFAULT_PRIORITY, is(EnergyParticipant.DEFAULT_PRIORITY));
        assertThat(EnergyProvider.DEFAULT_PRIORITY, is(EnergyParticipant.DEFAULT_PRIORITY));
        assertThat(EnergyConsumer.of("Boiler_Switch", SimpleProfile.plain()).priority(), is(100));
        assertThat(EnergyProvider.of("Battery_Power", ProviderRole.BATTERY).priority(), is(100));
        assertThat(EnergyProvider
                .controllable("battery", "Battery_Power", ProviderRole.BATTERY, "Battery_Setpoint", -3000, 3000, null)
                .withPriority(40).priority(), is(40));
    }

    /**
     * Identity is the name of the Item carrying the declaration, so two statements about one device are recognisable
     * as such without either side coordinating with the other.
     */
    @Test
    public void identityDefaultsToTheItemNameAndIsOverridable() {
        assertThat(EnergyConsumer.of("Boiler_Switch", SimpleProfile.plain()).id(), is("Boiler_Switch"));
        assertThat(EnergyProvider.of("Grid_Power", ProviderRole.GRID).id(), is("Grid_Power"));
        assertThat(EnergyConsumer.of("boiler", "Boiler_Switch", SimpleProfile.plain(), 1).id(), is("boiler"));
    }

    @Test
    public void controllableProfileAcceptsPowerOrCurrentButNotAMix() {
        assertThat(ControllableProfile.amperes(6, 32).isCurrentBased(), is(true));
        assertThat(ControllableProfile.watts(0, 11000).isPowerBased(), is(true));
        assertThrows(IllegalArgumentException.class,
                () -> new ControllableProfile(new QuantityType<>("6 A"), new QuantityType<>("11000 W")));
        assertThrows(IllegalArgumentException.class, () -> ControllableProfile.amperes(32, 6));
    }

    @Test
    public void modeControllableProfileNeedsAtLeastTwoDistinctModes() {
        ModeControllableProfile heatPump = ModeControllableProfile.of("blocked", "normal", "encouraged", "forced");
        assertThat(heatPump.size(), is(4));
        assertThat(heatPump.mostRestricted(), is("blocked"));
        assertThat(heatPump.leastRestricted(), is("forced"));
        assertThat(heatPump.indexOf("encouraged").getAsInt(), is(2));
        assertThat(heatPump.indexOf("nope").isEmpty(), is(true));
        assertThrows(IllegalArgumentException.class, () -> ModeControllableProfile.of("only"));
        assertThrows(IllegalArgumentException.class, () -> ModeControllableProfile.of("a", "a"));
    }

    /**
     * A mode change carries no figure unless the site declares one, because what a mode draws is the device's
     * decision - so the model must answer "none" rather than guess, and must refuse a draw for a mode that does not
     * exist.
     */
    @Test
    public void aModeCarriesNoPowerFigureUnlessTheSiteDeclaresOne() {
        ModeControllableProfile plain = ModeControllableProfile.of("blocked", "normal", "encouraged", "forced");
        assertThat(plain.declaresModeDraws(), is(false));
        assertThat(plain.drawOf("forced").isEmpty(), is(true));
        assertThat(EnergyConsumer.of("hp", "HeatPump_Mode", plain, 10).powerFigure().isEmpty(), is(true));
        assertThat(EnergyConsumer.of("hp", "HeatPump_Mode", plain, 10).admissionFigure().isEmpty(), is(true));

        ModeControllableProfile declared = plain.withModeDraws(Map.of("forced", new QuantityType<>(2400, Units.WATT)));
        assertThat(declared.declaresModeDraws(), is(true));
        assertThat(declared.drawOf("forced").orElseThrow().doubleValue(), closeTo(2400, 0.001));
        assertThat("a figure for one mode says nothing about the others", declared.drawOf("normal").isEmpty(),
                is(true));
        assertThrows(IllegalArgumentException.class,
                () -> plain.withModeDraws(Map.of("turbo", new QuantityType<>(2400, Units.WATT))));
    }

    @Test
    public void simpleProfileRejectsInconsistentProtectionTimes() {
        assertThrows(IllegalArgumentException.class, () -> new SimpleProfile(null, null, Duration.ofMinutes(30),
                Duration.ofMinutes(10), null, null, LevelGate.always()));
        assertThrows(IllegalArgumentException.class,
                () -> new SimpleProfile(null, null, null, null, Duration.ofMinutes(-1), null, LevelGate.always()));
    }

    /**
     * The requirement's _Simple consumer without a declared rating_ scenario: the on-threshold is used and the
     * participant carries a declaration gap, and the declaration is emphatically not rejected. Declaring a rating
     * closes the gap and changes the figure - the two numbers are different things.
     */
    @Test
    public void aSimpleConsumerWithoutARatingFallsBackToItsThresholdAndReportsTheGap() {
        SimpleProfile inferred = SimpleProfile.switchingAt(1500);
        EnergyConsumer withoutRating = EnergyConsumer.of("boiler", "Boiler_Switch", inferred, 10);
        assertThat(withoutRating.powerFigure().orElseThrow().doubleValue(), closeTo(1500, 0.001));
        assertThat(withoutRating.ratingIsInferred(), is(true));

        SimpleProfile declared = new SimpleProfile(new QuantityType<>(1500, Units.WATT),
                new QuantityType<>(2200, Units.WATT), null, null, null, null, LevelGate.always());
        EnergyConsumer withRating = EnergyConsumer.of("boiler", "Boiler_Switch", declared, 10);
        assertThat(withRating.powerFigure().orElseThrow().doubleValue(), closeTo(2200, 0.001));
        assertThat(withRating.ratingIsInferred(), is(false));
        assertThat("the switching figure is untouched by the booking figure", declared.onThreshold().doubleValue(),
                closeTo(1500, 0.001));
    }

    /**
     * The class fixes the figure: Controllable books its declared maximum, Batch its rated power scaled by the
     * curve - by the mean for what it costs and by the peak for what the site has to carry while admitting it.
     */
    @Test
    public void thePowerFigureIsFixedByTheProfileClass() {
        EnergyConsumer wallbox = EnergyConsumer.of("wallbox", "Wallbox_Current", ControllableProfile.amperes(6, 32), 1);
        assertThat(wallbox.powerFigure().orElseThrow(), is(new QuantityType<>("32 A")));
        assertThat(wallbox.admissionFigure().orElseThrow(), is(new QuantityType<>("32 A")));

        BatchProfile dishwasher = new BatchProfile(new QuantityType<>(2000, Units.WATT), Duration.ofHours(3),
                LoadCurve.of(0.1, 1.0, 0.2));
        EnergyConsumer batch = EnergyConsumer.of("dishwasher", "Dishwasher_Start", dishwasher, 40);
        assertThat(batch.powerFigure().orElseThrow().doubleValue(), closeTo(866.6667, 0.001));
        assertThat("admission books the peak the site has to survive",
                batch.admissionFigure().orElseThrow().doubleValue(), closeTo(2000, 0.001));
    }

    @Test
    public void batchProfileEnergyFollowsTheLoadCurve() {
        BatchProfile flat = BatchProfile.flat(2000, Duration.ofHours(2));
        assertThat(flat.energy().doubleValue(), closeTo(4000, 0.001));
        LoadCurve curve = LoadCurve.of(0.1, 1.0, 0.2);
        BatchProfile shaped = new BatchProfile(new QuantityType<>(2000, Units.WATT), Duration.ofHours(3), curve);
        assertThat(shaped.meanFraction(), closeTo(0.4333333, 0.0000001));
        assertThat(shaped.energy().doubleValue(), closeTo(2600, 0.001));
        assertThat(curve.sampleInterval(shaped.runtime()), is(Duration.ofHours(1)));
        assertThat(curve.peakFraction(), closeTo(1.0, 0.0000001));
        assertThat(shaped.peakFraction(), closeTo(1.0, 0.0000001));
        assertThat(flat.peakFraction(), closeTo(1.0, 0.0000001));
        assertThat(LoadCurve.of(0.2, 0.5, 0.3).peakFraction(), closeTo(0.5, 0.0000001));
        assertThrows(IllegalArgumentException.class, () -> LoadCurve.of(0, 0));
        assertThrows(IllegalArgumentException.class, () -> BatchProfile.flat(2000, Duration.ZERO));
    }

    @Test
    public void batteryProviderCarriesASignedClampAndAStateOfCharge() {
        EnergyProvider battery = EnergyProvider.controllable("battery", "Battery_Power", ProviderRole.BATTERY,
                "Battery_Setpoint", -3000, 3000, "Battery_SoC");
        assertThat(battery.isControllable(), is(true));
        assertThat(battery.hasStateOfCharge(), is(true));
        assertThat("a positive setpoint is the charging end of the clamp", battery.maxPower().doubleValue(),
                closeTo(3000, 0.001));
        assertThat(EnergyProvider.of("grid", "Grid_Power", ProviderRole.GRID).isControllable(), is(false));
        assertThrows(IllegalArgumentException.class,
                () -> EnergyProvider.controllable("b", "I", ProviderRole.BATTERY, "C", 3000, -3000, null));
        assertThrows(IllegalArgumentException.class, () -> EnergyProvider.of(" ", "I", ProviderRole.GRID));
    }

    /**
     * The requirement's _Battery reading enters site-load math_ and _A setpoint follows its own reading_ scenarios:
     * one convention, fixed centrally, so two calculations cannot read one battery reading two ways.
     */
    @Test
    public void oneSignConventionAnswersEveryRole() {
        assertThat("a battery charging at 2 kW is 2 kW of load", SignConvention.loadWatts(ProviderRole.BATTERY, 2000),
                closeTo(2000, 0.001));
        assertThat("PV production is supply, not load", SignConvention.loadWatts(ProviderRole.PV, 5000),
                closeTo(-5000, 0.001));
        assertThat("grid export is supply, not load", SignConvention.loadWatts(ProviderRole.GRID, 2000),
                closeTo(-2000, 0.001));

        // 5 kW produced, 2 kW into the battery, 1 kW exported: the consumers of the site are drawing 2 kW
        assertThat(SignConvention.siteLoadWatts(5000, 2000, 1000), closeTo(2000, 0.001));

        EnergyConsumer boiler = EnergyConsumer.of("boiler", "Boiler_Switch", SimpleProfile.plain(), 10);
        assertThat("a consumer's power is load as it stands", SignConvention.loadWatts(boiler, 1200),
                closeTo(1200, 0.001));
    }

    /**
     * The requirement's _An inverter that disagrees_ scenario: a device that counts the other way round is
     * normalised at its own boundary, and nothing above the declaration knows it disagreed.
     */
    @Test
    public void aDeviceThatCountsTheOtherWayRoundIsNormalisedAtTheEdge() {
        EnergyProvider hybrid = EnergyProvider
                .controllable("battery", "Battery_Power", ProviderRole.BATTERY, "Battery_Setpoint", -3000, 3000, null)
                .inverted();

        assertThat(hybrid.invert(), is(true));
        assertThat("the inverter reports charging as -2000", hybrid.normalise(-2000), closeTo(2000, 0.001));
        assertThat("and the setpoint takes the same road back", hybrid.normalise(3000), closeTo(-3000, 0.001));
        assertThat("an agreeing device is untouched",
                EnergyProvider.of("grid", "Grid_Power", ProviderRole.GRID).normalise(-2000), closeTo(-2000, 0.001));
    }

    /**
     * The requirement's _A controllable provider without a clamp_ scenario: accepting it would mean an unbounded
     * setpoint write to an inverter, so the participant is refused whole rather than half-accepted.
     */
    @Test
    public void aControllableProviderMustCarryACompleteClamp() {
        assertThrows(IllegalArgumentException.class,
                () -> new EnergyProvider("battery", "Battery_Power", ProviderRole.BATTERY, "Battery_Setpoint", null,
                        null, null, 100, false, Map.of(), null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new EnergyProvider("battery", "Battery_Power", ProviderRole.BATTERY, "Battery_Setpoint",
                        new QuantityType<>(-3000, Units.WATT), null, null, 100, false, Map.of(), null, null, null,
                        null));
        assertThat("an uncontrollable provider needs none",
                EnergyProvider.of("grid", "Grid_Power", ProviderRole.GRID).minPower(), is(nullValue()));
    }

    /**
     * A clamp may be stated in either dimension, exactly like a Controllable consumer's bounds, but not as a mix of
     * the two - which would compare a current against a power.
     */
    @Test
    public void aProviderClampMayBeStatedAsAPowerOrAsACurrent() {
        EnergyProvider amps = new EnergyProvider("battery", "Battery_Power", ProviderRole.BATTERY, "Battery_Setpoint",
                new QuantityType<>("-16 A"), new QuantityType<>("16 A"), null, 100, false, Map.of(), null, null, null,
                null);
        assertThat(amps.isCurrentClamped(), is(true));
        assertThat(amps.isPowerClamped(), is(false));

        EnergyProvider watts = EnergyProvider.controllable("battery", "Battery_Power", ProviderRole.BATTERY,
                "Battery_Setpoint", -3000, 3000, null);
        assertThat(watts.isPowerClamped(), is(true));
        assertThat(watts.isCurrentClamped(), is(false));

        assertThrows(IllegalArgumentException.class,
                () -> new EnergyProvider("battery", "Battery_Power", ProviderRole.BATTERY, "Battery_Setpoint",
                        new QuantityType<>("-16 A"), new QuantityType<>("3000 W"), null, 100, false, Map.of(), null,
                        null, null, null));
    }

    /**
     * The requirement's _Phase declaration_: phases are the integer indices 1, 2 and 3, a participant declaring none
     * is exempt rather than attributed to a guessed one, and a provider may name a reading Item per phase.
     */
    @Test
    public void phasesAreTheIntegerIndicesOneToThree() {
        EnergyConsumer wallbox = EnergyConsumer.of("wallbox", "Wallbox_Current", ControllableProfile.amperes(6, 32), 1)
                .withPhases(Set.of(3, 1, 2));
        assertThat(wallbox.declaresPhases(), is(true));
        assertThat("the set is numerically ordered whatever order it was declared in", wallbox.phases(),
                is(Set.of(1, 2, 3)));
        assertThat(List.copyOf(wallbox.phases()), is(List.of(1, 2, 3)));

        EnergyConsumer unphased = EnergyConsumer.of("boiler", "Boiler_Switch", SimpleProfile.plain(), 10);
        assertThat("no phase declared is not three phases declared", unphased.declaresPhases(), is(false));
        assertThat(unphased.phases(), is(Set.of()));

        assertThrows(IllegalArgumentException.class, () -> unphased.withPhases(Set.of(0)));
        assertThrows(IllegalArgumentException.class, () -> unphased.withPhases(Set.of(4)));

        EnergyProvider grid = EnergyProvider.of("grid", "Grid_Power", ProviderRole.GRID)
                .withPhaseItems(Map.of(1, "Grid_L1", 2, "Grid_L2", 3, "Grid_L3"));
        assertThat(grid.hasPerPhaseReadings(), is(true));
        assertThat(grid.phaseItemName(2), is(Optional.of("Grid_L2")));
        assertThat(EnergyProvider.of("pv", "PV_Power", ProviderRole.PV).hasPerPhaseReadings(), is(false));
        assertThrows(IllegalArgumentException.class, () -> grid.withPhaseItems(Map.of(4, "Grid_L4")));
    }

    /**
     * The requirement's _A participant overrides the site sink_ scenario: the write side is chosen by naming it,
     * per participant as well as site-wide, and never by ranking.
     */
    @Test
    public void aParticipantMayNameItsOwnActuationSink() {
        EnergyConsumer wallbox = EnergyConsumer.of("wallbox", "Wallbox_Current", ControllableProfile.amperes(6, 32), 1);
        assertThat("nothing named means the site-wide sink", wallbox.sinkId(), is(nullValue()));
        assertThat(wallbox.withSink("ocpp").sinkId(), is("ocpp"));
        assertThat(EnergyProvider.of("battery", "Battery_Power", ProviderRole.BATTERY).withSink("modbus").sinkId(),
                is("modbus"));
        assertThrows(IllegalArgumentException.class, () -> wallbox.withSink(" "));
    }

    @Test
    public void dailyDeadlinesWrapPastMidnight() {
        ZoneId zone = ZoneId.of("Europe/Brussels");
        Deadline sevenAm = Deadline.daily(LocalTime.of(7, 0));
        ZonedDateTime beforeDeadline = ZonedDateTime.of(2023, 3, 24, 5, 0, 0, 0, zone);
        ZonedDateTime afterDeadline = ZonedDateTime.of(2023, 3, 24, 9, 0, 0, 0, zone);
        assertThat(sevenAm.resolve(beforeDeadline), is(ZonedDateTime.of(2023, 3, 24, 7, 0, 0, 0, zone).toInstant()));
        assertThat(sevenAm.resolve(afterDeadline), is(ZonedDateTime.of(2023, 3, 25, 7, 0, 0, 0, zone).toInstant()));
        Instant fixed = Instant.parse("2023-03-24T07:00:00Z");
        assertThat(Deadline.at(fixed).resolve(afterDeadline), is(fixed));
    }

    @Test
    public void demandCarriesEnergyDeadlineAndTheConsecutiveFlag() {
        Demand fourKwhBySeven = Demand.kilowattHoursBy(4, LocalTime.of(7, 0));
        assertThat(fourKwhBySeven.kilowattHours(), closeTo(4, 0.001));
        assertThat(fourKwhBySeven.consecutive(), is(false));
        assertThat(new Demand(new QuantityType<>(4, Units.KILOWATT_HOUR), Deadline.daily(LocalTime.NOON), true)
                .consecutive(), is(true));
        assertThrows(IllegalArgumentException.class, () -> Demand.kilowattHoursBy(-1, LocalTime.NOON));
        assertThrows(IllegalArgumentException.class,
                () -> Demand.of(new QuantityType<>("4 W"), Deadline.daily(LocalTime.NOON)));
    }
}
