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
package org.openhab.core.energy.spi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The rule that decides which of several sources for the same role answers, written once.
 * <p>
 * <strong>Why this is a class and not three comparators.</strong> The price, forecast and carbon planes each
 * hand-rolled this rule, and each expressed it differently - one as {@code max} over an ascending comparator, one as
 * a descending {@code sort} taking the first element, one as {@code min} over a reversed comparator. All three
 * happened to agree, which is the dangerous case: nothing made them agree, no test compared them, and the next edit
 * to any one of them would have changed which source answers in one plane only. A site would then see its named
 * forecast source honoured and its named price source ignored, with no error anywhere. That is a user-facing
 * inconsistency of exactly the kind openHAB review weights first, and it would have been found by a user rather than
 * by a build.
 * <p>
 * <strong>The rule.</strong> Highest {@code service.ranking} wins. Equal rankings are broken by the lowest source id
 * in natural order. The tie-break is not arbitrary decoration: without it the answer depends on OSGi registration
 * order, which varies between boots, so a site with two equally-ranked sources would silently change behaviour on
 * restart. Lowest-id is chosen over highest-id for no reason beyond needing to pick one; what matters is that it is
 * total, deterministic and identical in every plane.
 * <p>
 * <strong>An explicit choice is not a ranking.</strong> When a site names the source it wants, that name is an
 * override rather than a very high ranking, so {@link #named} answers it directly and reports nothing when the named
 * source is absent - the caller decides whether an absent named source falls through to the ranked order or is
 * itself the thing to report, because the two planes legitimately differ on that.
 * <p>
 * The series type is deliberately not a parameter of any method here: precedence is decided by identity and ranking
 * alone, so this rule is the same rule for a price, a forecast and a carbon series.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class SourceRanking {

    private SourceRanking() {
    }

    /**
     * Returns the comparator that puts the source that should answer first.
     *
     * @param <T> the source type
     * @return highest ranking first, ties broken by lowest source id
     */
    public static <T extends EnergySeriesSource<?>> Comparator<T> byPrecedence() {
        Comparator<T> byRanking = Comparator.comparingInt(EnergySeriesSource::getServiceRanking);
        return byRanking.reversed().thenComparing(EnergySeriesSource::getSourceId);
    }

    /**
     * Returns the candidates in the order they should be tried.
     *
     * @param <T> the source type
     * @param candidates the sources for one role
     * @return a new immutable list, best first
     */
    public static <T extends EnergySeriesSource<?>> List<T> ranked(Collection<T> candidates) {
        List<T> ordered = new ArrayList<>(candidates);
        ordered.sort(byPrecedence());
        return List.copyOf(ordered);
    }

    /**
     * Returns the source that should answer for a role.
     *
     * @param <T> the source type
     * @param candidates the sources for one role
     * @return the winner, or empty when there are no candidates
     */
    public static <T extends EnergySeriesSource<?>> Optional<T> best(Collection<T> candidates) {
        return candidates.stream().min(byPrecedence());
    }

    /**
     * Returns the candidate a site named explicitly, if it is present among them.
     *
     * @param <T> the source type
     * @param candidates the sources for one role
     * @param sourceId the source id the site asked for; a blank string names none
     * @return the named source, or empty when none was named or the named one is not registered
     */
    public static <T extends EnergySeriesSource<?>> Optional<T> named(Collection<T> candidates, String sourceId) {
        if (sourceId.isBlank()) {
            return Optional.empty();
        }
        return candidates.stream().filter(source -> sourceId.equals(source.getSourceId())).findFirst();
    }
}
