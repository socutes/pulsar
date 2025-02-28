/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.admin.impl;

import static org.apache.pulsar.broker.cache.ConfigurationCacheService.POLICIES;
import static org.apache.pulsar.broker.cache.ConfigurationCacheService.RESOURCEGROUPS;
import java.util.Iterator;
import java.util.List;
import javax.ws.rs.core.Response;
import org.apache.pulsar.broker.admin.AdminResource;
import org.apache.pulsar.broker.web.RestException;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.policies.data.Policies;
import org.apache.pulsar.common.policies.data.ResourceGroup;
import org.apache.pulsar.metadata.api.MetadataStoreException;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ResourceGroupsBase extends AdminResource {
    protected List<String> internalGetResourceGroups() {
        try {
            validateSuperUserAccess();
            return getListOfResourcegroups("abc");
        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to get ResourceGroups list ", clientAppId());
            throw new RestException(Response.Status.NOT_FOUND, "Property does not exist");
        } catch (Exception e) {
            log.error("[{}] Failed to get ResourceGroups list: {}", clientAppId(), e);
            throw new RestException(e);
        }
    }

    protected ResourceGroup internalGetResourceGroup(String rgName) {
        try {
            final String resourceGroupPath = AdminResource.path(RESOURCEGROUPS, rgName);
            validateSuperUserAccess();
            ResourceGroup resourceGroup = resourceGroupResources().get(resourceGroupPath)
                    .orElseThrow(() -> new RestException(Response.Status.NOT_FOUND, "ResourceGroup does not exist"));
            return resourceGroup;
        } catch (RestException re) {
            throw re;
        } catch (Exception e) {
            log.error("[{}] Failed to get ResourceGroup  {}", clientAppId(), rgName, e);
            throw new RestException(e);
        }
    }

    protected void internalUpdateResourceGroup(String rgName, ResourceGroup rgConfig) {
        final String resourceGroupPath = AdminResource.path(RESOURCEGROUPS, rgName);

        try {
            ResourceGroup resourceGroup = resourceGroupResources().get(resourceGroupPath).orElseThrow(() ->
                    new RestException(Response.Status.NOT_FOUND, "ResourceGroup does not exist"));

            /*
             * assuming read-modify-write
             */
            resourceGroup.setPublishRateInMsgs(rgConfig.getPublishRateInMsgs());
            resourceGroup.setPublishRateInBytes(rgConfig.getPublishRateInBytes());
            resourceGroup.setDispatchRateInMsgs(rgConfig.getDispatchRateInMsgs());
            resourceGroup.setDispatchRateInBytes(rgConfig.getDispatchRateInBytes());

            // write back the new ResourceGroup config.
            resourceGroupResources().set(resourceGroupPath, r -> resourceGroup);
            log.info("[{}] Successfully updated the ResourceGroup {}", clientAppId(), rgName);
        } catch (RestException pfe) {
            throw pfe;
        } catch (Exception e) {
            log.error("[{}] Failed to update configuration for ResourceGroup {}", clientAppId(), rgName, e);
            throw new RestException(e);
        }
    }

    protected void internalCreateResourceGroup(String rgName, ResourceGroup rgConfig) {
        final String resourceGroupPath = AdminResource.path(RESOURCEGROUPS, rgName);
        try {
            resourceGroupResources().create(resourceGroupPath, rgConfig);
            log.info("[{}] Created ResourceGroup {}", clientAppId(), rgName);
        } catch (MetadataStoreException.AlreadyExistsException e) {
            log.warn("[{}] Failed to create ResourceGroup {} - already exists", clientAppId(), rgName);
            throw new RestException(Response.Status.CONFLICT, "ResourceGroup already exists");
        } catch (Exception e) {
            log.error("[{}] Failed to create ResourceGroup {}", clientAppId(), rgName, e);
            throw new RestException(e);
        }

    }
    protected void internalCreateOrUpdateResourceGroup(String rgName, ResourceGroup rgConfig) {
        try {
            validateSuperUserAccess();
            checkNotNull(rgConfig);
            /*
             * see if ResourceGroup exists and treat the request as a update if it does.
             */
            final String resourceGroupPath = AdminResource.path(RESOURCEGROUPS, rgName);
            boolean rgExists = false;
            try {
                rgExists = resourceGroupResources().exists(resourceGroupPath);
            } catch (Exception e) {
                log.error("[{}] Failed to create/update ResourceGroup {}: {}", clientAppId(), rgName, e);
            }

            try {
                if (rgExists) {
                    internalUpdateResourceGroup(rgName, rgConfig);
                } else {
                    internalCreateResourceGroup(rgName, rgConfig);
                }
            } catch (Exception e) {
                log.error("[{}] Failed to create/update ResourceGroup {}: {}", clientAppId(), rgName, e);
                throw new RestException(e);
            }
        } catch (Exception e) {
            log.error("[{}] Failed to create/update ResourceGroup {}: {}", clientAppId(), rgName, e);
            throw new RestException(e);
        }
    }

    protected boolean internalCheckRgInUse(String rgName) {
        List<String> tenants;
        try {
            tenants = tenantResources().getChildren(path(POLICIES));
            Iterator<String> tenantsIterator = tenants.iterator();
            while (tenantsIterator.hasNext()) {
                String tenant = tenantsIterator.next();
                List<String> namespaces = getListOfNamespaces(tenant);
                Iterator<String> namespaceIterator = namespaces.iterator();
                while (namespaceIterator.hasNext()) {
                    String namespace = namespaceIterator.next();
                    Policies policies = getNamespacePolicies(NamespaceName.get(namespace));
                    if (null != policies && rgName.equals(policies.resource_group_name)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.error("[{}] Failed to get tenant/namespace list {}: {}", clientAppId(), rgName, e);
            throw new RestException(e);
        }
        return false;
    }

    protected void internalDeleteResourceGroup(String rgName) {
        /*
         * need to walk the namespaces and make sure it is not in use
         */
        try {
            validateSuperUserAccess();
            /*
             * walk the namespaces and make sure it is not in use.
             */
            if (internalCheckRgInUse(rgName)) {
                throw new RestException(Response.Status.PRECONDITION_FAILED, "ResourceGroup is in use");
            }
            final String globalZkResourceGroupPath = path(RESOURCEGROUPS, rgName);
            resourceGroupResources().delete(globalZkResourceGroupPath);
            log.info("[{}] Deleted ResourceGroup {}", clientAppId(), rgName);
        } catch (Exception e) {
            log.error("[{}] Failed to delete ResourceGroup {}.", clientAppId(), rgName, e);
            throw new RestException(e);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ResourceGroupsBase.class);
}
