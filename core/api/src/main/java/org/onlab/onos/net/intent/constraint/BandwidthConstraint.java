/*
 * Copyright 2014 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onlab.onos.net.intent.constraint;

import org.onlab.onos.net.Link;
import org.onlab.onos.net.resource.Bandwidth;
import org.onlab.onos.net.resource.BandwidthResourceRequest;
import org.onlab.onos.net.resource.LinkResourceService;
import org.onlab.onos.net.resource.ResourceRequest;
import org.onlab.onos.net.resource.ResourceType;

import java.util.Objects;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Constraint that evaluates links based on available bandwidths.
 */
public class BandwidthConstraint extends BooleanConstraint {

    private final Bandwidth bandwidth;

    /**
     * Creates a new bandwidth constraint.
     *
     * @param bandwidth required bandwidth
     */
    public BandwidthConstraint(Bandwidth bandwidth) {
        this.bandwidth = checkNotNull(bandwidth, "Bandwidth cannot be null");
    }

    @Override
    public boolean isValid(Link link, LinkResourceService resourceService) {
        for (ResourceRequest request : resourceService.getAvailableResources(link)) {
            if (request.type() == ResourceType.BANDWIDTH) {
                BandwidthResourceRequest brr = (BandwidthResourceRequest) request;
                if (brr.bandwidth().toDouble() >= bandwidth.toDouble()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the bandwidth required by this constraint.
     *
     * @return required bandwidth
     */
    public Bandwidth bandwidth() {
        return bandwidth;
    }

    @Override
    public int hashCode() {
        return Objects.hash(bandwidth);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final BandwidthConstraint other = (BandwidthConstraint) obj;
        return Objects.equals(this.bandwidth, other.bandwidth);
    }

    @Override
    public String toString() {
        return toStringHelper(this).add("bandwidth", bandwidth).toString();
    }
}