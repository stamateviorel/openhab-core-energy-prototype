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

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.common.registry.Provider;

/**
 * A place energy participant declarations come from.
 * <p>
 * <strong>One SPI carries both declaration mechanisms.</strong> Everything above this interface - the registry, and
 * through it the engine - is written against declarations only, so nothing depends on which mechanism produced one:
 * <ul>
 * <li>{@code MetadataParticipantSource} reads the {@code energy} item-metadata namespace (source id
 * {@code metadata}, origin {@link DeclarationOrigin#EXPLICIT});</li>
 * <li>{@code ProgrammaticParticipantSource} lets bindings and scripts contribute the same information through
 * {@link EnergyParticipantContributor} without any item metadata (source id {@code programmatic}, origin
 * {@link DeclarationOrigin#CONTRIBUTED}).</li>
 * </ul>
 * Which of them is authoritative when both declare the same participant is <strong>not</strong> configuration: the
 * precedence chain is fixed - explicit metadata over contributed over discovered, ties between contributed
 * statements broken by {@code service.ranking} - so that the outcome cannot depend on registration order or on how
 * a site happened to fill in a list. What a site does select is which sources take part at all
 * ({@code sources} on the {@code org.openhab.core.energy.declaration} configuration pid), which is the per-role
 * selection every contribution kind gets.
 * <p>
 * Implementations are registered as OSGi services and are picked up while the framework runs, so a source may
 * appear and disappear at any time. They extend {@link Provider}, so the usual openHAB provider/registry contract
 * applies: fire {@code added}/{@code updated}/{@code removed} for every change, and expect the registry to drop
 * every declaration of a source that goes away. {@link AbstractEnergyParticipantSource} implements that
 * bookkeeping.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface EnergyParticipantSource extends Provider<ParticipantDeclaration> {

    /**
     * Returns the stable id of this source.
     * <p>
     * It identifies the source in configuration (which sources take part) and in log lines, so it must be stable
     * across restarts and unique among the installed sources.
     *
     * @return the source id
     */
    String getSourceId();

    /**
     * Returns the origin every declaration of this source carries.
     * <p>
     * It is the first clause of the fixed precedence chain - explicit metadata over contributed over discovered -
     * and it also ranks a {@link #getBlockedParticipants() block}, which is why it is answerable without reading any
     * declaration: a source whose only statement about an identity is unreadable still has an authority.
     *
     * @return the declaration origin, defaulting to {@link DeclarationOrigin#CONTRIBUTED} - the conservative answer,
     *         because a source that does not say can then never block a user's own explicit declaration
     */
    default DeclarationOrigin getOrigin() {
        return DeclarationOrigin.CONTRIBUTED;
    }

    /**
     * Returns the participant identities this source has a declaration for that it cannot read.
     * <p>
     * A source that finds a declaration malformed reports the identity here <em>instead of</em> simply going quiet
     * about it. The registry then leaves that participant out of the resolved view altogether, rather than letting
     * the next statement down the precedence chain become effective.
     * <p>
     * Source: owner decision <strong>D26</strong> (2026-08-03,
     * {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}). Without it, a typo in a user's own metadata withdraws that
     * declaration and silently promotes an add-on's contribution for the same identity: the device stays managed, on
     * terms its owner never chose, and nothing about its behaviour says which declaration is in force. The principle
     * is that <strong>intent to control survives a wrong text</strong> - a typo has to degrade to "nothing happens",
     * which is diagnosable, and never to "something else happens", which is not.
     * <p>
     * A block reaches only <em>lower-ranked</em> statements about the same identity. A contributed source cannot
     * block a user's own explicit declaration by failing to read its own, which would let an add-on's bug disable a
     * site's configuration.
     * <p>
     * <em>Alternatives preserved:</em> letting the contributed declaration take over, which keeps a device managed
     * through a configuration error at the cost of the silent transfer of control above; and taking over only after
     * the user acknowledges the error, which serves both cases honestly but needs an acknowledgement mechanism the
     * corpus does not have.
     *
     * @return the blocked participant ids, empty for a source that cannot fail to read a declaration - which is
     *         every source whose declarations arrive as typed objects rather than as text
     */
    default Set<String> getBlockedParticipants() {
        return Set.of();
    }

    /**
     * Returns the {@code service.ranking} this source registers at, which breaks ties between two contributed
     * statements about one participant.
     * <p>
     * It has to be answered here rather than read off the service registry: core's
     * {@link org.openhab.core.common.registry.AbstractRegistry} hands a registry the provider object without its
     * {@code ServiceReference}, so a ranking the registry could consult does not reach it. An implementation that
     * registers with a {@code service.ranking} property should return that same value - a component may pass it
     * straight through from its activation properties.
     *
     * @return the service ranking, higher winning, defaulting to the OSGi default of zero
     */
    default int getServiceRanking() {
        return 0;
    }
}
