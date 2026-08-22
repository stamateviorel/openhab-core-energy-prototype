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
package org.openhab.core.energy.level;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;

/**
 * Reads the acceptance fixtures shipped in {@code src/test/resources/fixtures}.
 * <p>
 * Those four CSVs are copied verbatim from the spec corpus
 * ({@code /home/openhab/work/openhab-ems-spec/fixtures}), which in turn extracted them from @masipila's worked
 * example in openhab-core issue #3478, comment 1481931363 (2023-03-23). They are conformance vectors, not
 * illustrations: the corpus' prototype rules make reproducing them exactly a pass/fail gate. They are copied into
 * the bundle so the build stays self-contained.
 * <p>
 * Every fixture has the same shape - an ISO-8601 UTC timestamp naming the start of an hourly slot, and one numeric
 * column.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class FixtureCsv {

    /**
     * One fixture line.
     *
     * @param timestamp the start of the slot
     * @param value the numeric value of the single data column
     */
    public record Row(Instant timestamp, double value) {
    }

    private FixtureCsv() {
    }

    /**
     * Reads a fixture file from the test classpath.
     *
     * @param resource the absolute classpath location, e.g. {@code /fixtures/dayahead-prices.csv}
     * @return every data row, header skipped, in file order
     */
    public static List<Row> read(String resource) {
        List<Row> rows = new ArrayList<>();
        try (InputStream stream = open(resource);
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) {
                throw new IllegalStateException("fixture " + resource + " is empty");
            }
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String[] columns = trimmed.split(",");
                if (columns.length != 2) {
                    throw new IllegalStateException(
                            "fixture " + resource + " has a line with " + columns.length + " columns: " + trimmed);
                }
                rows.add(new Row(Instant.parse(columns[0]), Double.parseDouble(columns[1])));
            }
        } catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(rows);
    }

    /**
     * Opens a fixture on the test classpath, so that the stream is managed by the caller's resource list from the
     * moment it exists.
     *
     * @param resource the absolute classpath location
     * @return the open stream
     */
    private static InputStream open(String resource) {
        InputStream stream = FixtureCsv.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("fixture " + resource + " is missing from the test resources");
        }
        return stream;
    }

    /**
     * Reads the day-ahead price fixture as a {@link SlotSeries}.
     * <p>
     * The fixture names only slot starts, so each slot ends where the next begins and the final slot takes the width
     * of the one before it. On this fixture that is a uniform 60 minutes, but the reconstruction never assumes it.
     *
     * @return the 24-slot price series of 2023-03-24
     */
    public static SlotSeries prices() {
        List<Row> rows = read("/fixtures/dayahead-prices.csv");
        List<Slot> slots = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            Instant end;
            if (i + 1 < rows.size()) {
                end = rows.get(i + 1).timestamp();
            } else {
                Duration previousWidth = Duration.between(rows.get(i - 1).timestamp(), row.timestamp());
                end = row.timestamp().plus(previousWidth);
            }
            slots.add(new Slot(row.timestamp(), end, row.value()));
        }
        return new SlotSeries(slots);
    }
}
