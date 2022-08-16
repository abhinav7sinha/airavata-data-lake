/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.airavata.drms.api.handlers;

import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.apache.airavata.datalake.drms.AuthenticatedUser;
import org.apache.airavata.datalake.drms.resource.GenericResource;
import org.apache.airavata.datalake.drms.storage.*;
import org.apache.airavata.drms.api.persistance.mapper.ResourceMapper;
import org.apache.airavata.drms.api.persistance.model.Resource;
import org.apache.airavata.drms.api.persistance.model.ResourceProperty;
import org.apache.airavata.drms.api.persistance.repository.ResourcePropertyRepository;
import org.apache.airavata.drms.api.persistance.repository.ResourceRepository;
import org.apache.airavata.drms.api.utils.CustosUtils;
import org.apache.airavata.drms.core.constants.SharingConstants;
import org.apache.custos.clients.CustosClientProvider;
import org.apache.custos.sharing.management.client.SharingManagementClient;
import org.apache.custos.sharing.service.Entity;
import org.apache.custos.sharing.service.GetAllDirectSharingsResponse;
import org.apache.custos.sharing.service.SharingRequest;
import org.json.JSONObject;
import org.lognet.springboot.grpc.GRpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.stream.Collectors;

@GRpcService
public class ResourceServiceHandler extends ResourceServiceGrpc.ResourceServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(ResourceServiceHandler.class);


    @Autowired
    private CustosClientProvider custosClientProvider;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private ResourcePropertyRepository resourcePropertyRepository;


    @Override
    public void fetchResource(ResourceFetchRequest request, StreamObserver<ResourceFetchResponse> responseObserver) {

        try {
            AuthenticatedUser callUser = request.getAuthToken().getAuthenticatedUser();

            String resourceId = request.getResourceId();

            boolean access = CustosUtils.userHasAccess(custosClientProvider, callUser.getTenantId(),
                    callUser.getUsername(), resourceId,
                    SharingConstants.PERMISSION_TYPE_VIEWER);

            if (access) {
                try (SharingManagementClient sharingManagementClient = custosClientProvider.getSharingManagementClient()) {

                    Entity sharedEntity = Entity
                            .newBuilder()
                            .setId(resourceId)
                            .build();

                    Entity entity = sharingManagementClient
                            .getEntity(callUser.getTenantId(), sharedEntity);

                    Optional<Resource> resourceOptional = resourceRepository.findById(resourceId);
                    if (resourceOptional.isPresent()) {

                        GenericResource resource = ResourceMapper.map(resourceOptional.get(), entity);
                        ResourceFetchResponse response = ResourceFetchResponse
                                .newBuilder()
                                .setResource(resource)
                                .build();
                        responseObserver.onNext(response);
                        responseObserver.onCompleted();
                        return;

                    }

                }
            }

            //TODO: ERROR
        } catch (Exception ex) {
            logger.error("Error occurred while fetching child resource {}", request.getResourceId(), ex);
            String msg = "Error occurred while fetching child resource with id" + request.getResourceId();
            responseObserver.onError(Status.INTERNAL.withDescription(msg).asRuntimeException());
        }

    }

    @Override
    public void createResource(ResourceCreateRequest request, StreamObserver<ResourceCreateResponse> responseObserver) {
        try {
            AuthenticatedUser callUser = request.getAuthToken().getAuthenticatedUser();
            String type = request.getResource().getType();
            String parentId = request.getResource().getParentId();
            String entityId = request.getResource().getResourceId();
            String name = request.getResource().getResourceName();

            Optional<Entity> exEntity = CustosUtils.mergeResourceEntity(custosClientProvider, callUser.getTenantId(),
                    parentId, type, entityId,
                    request.getResource().getResourceName(), request.getResource().getResourceName(),
                    callUser.getUsername());

            if (exEntity.isPresent()) {
                Resource resource = ResourceMapper.map(request.getResource(), exEntity.get(), callUser);
                resourceRepository.save(resource);

                GenericResource genericResource = ResourceMapper.map(resource, exEntity.get());

                ResourceCreateResponse response = ResourceCreateResponse
                        .newBuilder()
                        .setResource(genericResource)
                        .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                return;
            }
            //TODO: Error

        } catch (Exception ex) {
            logger.error("Error occurred while creating resource {}", request.getResource().getResourceId(), ex);
            String msg = "Error occurred while creating resource" + ex.getMessage();
            responseObserver.onError(Status.INTERNAL.withDescription(msg).asRuntimeException());
        }

    }


    @Override
    public void fetchChildResources(ChildResourceFetchRequest request,
                                    StreamObserver<ChildResourceFetchResponse> responseObserver) {
        try {
            AuthenticatedUser callUser = request.getAuthToken().getAuthenticatedUser();
            String resourceId = request.getResourceId();
            String type = request.getType();
            int offset = request.getOffset();
            int limit = request.getLimit();
            if (limit == 0) {
                limit = -1;
            }

            boolean status = CustosUtils.userHasAccess(custosClientProvider, callUser.getTenantId(),
                    callUser.getUsername(), resourceId, SharingConstants.PERMISSION_TYPE_VIEWER);
            if (status) {
                try (SharingManagementClient sharingManagementClient = custosClientProvider.getSharingManagementClient()) {
                    List<GenericResource> genericResources = new ArrayList<>();
                    List<Resource> resources;
                    if (limit > 0) {
                        resources = resourceRepository.findAllByParentResourceIdAndTenantIdAndResourceTypeWithPagination(resourceId
                                , callUser.getTenantId(), type, limit, offset);
                    } else {
                        resources = resourceRepository.findAllByParentResourceIdAndTenantIdAndResourceType(resourceId,
                                callUser.getTenantId(), type);
                    }

                    resources.forEach(resource -> {
                        String id = resource.getId();
                        Entity entity = Entity.newBuilder().setDescription(id).build();
                        Entity exEntity = sharingManagementClient.getEntity(callUser.getTenantId(), entity);
                        genericResources.add(ResourceMapper.map(resource, exEntity));

                    });

                    ChildResourceFetchResponse childResourceFetchResponse =
                            ChildResourceFetchResponse
                                    .newBuilder()
                                    .addAllResources(genericResources)
                                    .build();
                    responseObserver.onNext(childResourceFetchResponse);
                    responseObserver.onCompleted();
                    return;
                }
            }
            //TODO:Error

        } catch (Exception ex) {
            logger.error("Error occurred while fetching child resource {}", request.getResourceId(), ex);
            responseObserver.onError(Status.INTERNAL.withDescription("Error occurred while fetching child resource"
                    + ex.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void updateResource(ResourceUpdateRequest
                                       request, StreamObserver<ResourceUpdateResponse> responseObserver) {
        try {
            AuthenticatedUser callUser = request.getAuthToken().getAuthenticatedUser();
            String type = request.getResource().getType();
            String parentId = request.getResource().getParentId();
            String entityId = request.getResource().getResourceId();
            String name = request.getResource().getResourceName();

            Optional<Entity> exEntity = CustosUtils.mergeResourceEntity(custosClientProvider, callUser.getTenantId(),
                    parentId, type, entityId,
                    request.getResource().getResourceName(), request.getResource().getResourceName(),
                    callUser.getUsername());

            if (exEntity.isPresent()) {
                Resource resource = ResourceMapper.map(request.getResource(), exEntity.get(), callUser);
                resourceRepository.save(resource);

                GenericResource genericResource = ResourceMapper.map(resource, exEntity.get());

                ResourceUpdateResponse response = ResourceUpdateResponse
                        .newBuilder()
                        .setResource(genericResource)
                        .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                return;
            }
            //TODO: Error


        } catch (Exception ex) {
            logger.error("Error occurred while creating resource {}", request.getResource().getResourceId(), ex);
            String msg = "Error occurred while creating resource" + ex.getMessage();
            responseObserver.onError(Status.INTERNAL.withDescription(msg).asRuntimeException());
        }
    }

    @Override
    public void deletePreferenceStorage(ResourceDeleteRequest request, StreamObserver<Empty> responseObserver) {
        super.deletePreferenceStorage(request, responseObserver);
    }

    @Override
    public void searchResource(ResourceSearchRequest
                                       request, StreamObserver<ResourceSearchResponse> responseObserver) {
        AuthenticatedUser callUser = request.getAuthToken().getAuthenticatedUser();
        String value = request.getType();

        try (SharingManagementClient sharingManagementClient = custosClientProvider.getSharingManagementClient()) {

            Entity entity = Entity.newBuilder().setType(value).build();

            SharingRequest sharingRequest = SharingRequest.newBuilder()
                    .setEntity(entity)
                    .addOwnerId(callUser.getUsername())
                    .build();

            GetAllDirectSharingsResponse response = sharingManagementClient.getAllDirectSharings(callUser.getTenantId(),
                    sharingRequest);

            List<GenericResource> metadataList = new ArrayList<>();
            response.getSharedDataList().forEach(shrMetadata -> {
                Entity exEntity = shrMetadata.getEntity();
                Optional<Resource> resourceOptional = resourceRepository.findById(exEntity.getId());
                if (resourceOptional.isPresent()) {
                    metadataList.add(ResourceMapper.map(resourceOptional.get(), exEntity));
                }

            });

            ResourceSearchResponse resourceSearchResponse = ResourceSearchResponse
                    .newBuilder()
                    .addAllResources(metadataList)
                    .build();

            responseObserver.onNext(resourceSearchResponse);
            responseObserver.onCompleted();
        } catch (
                Exception e) {
            logger.error("Errored while searching generic resources; Message: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL.withDescription("Errored while searching generic resources "
                    + e.getMessage()).asRuntimeException());
        }

    }


    @Override
    public void addChildMembership(AddChildResourcesMembershipRequest request,
                                   StreamObserver<OperationStatusResponse> responseObserver) {
        try {
//            AuthenticatedUser callUser = request.getAuthToken().getAuthenticatedUser();
//            GenericResource resource = request.getParentResource();
//            List<GenericResource> childResources = request.getChildResourcesList();
//
//            List<GenericResource> allResources = new ArrayList<>();
//            allResources.add(resource);
//            allResources.addAll(childResources);

            responseObserver.onError(Status.UNIMPLEMENTED.asRuntimeException());


        } catch (Exception e) {
            String msg = " Error occurred while adding  child memberships " + e.getMessage();
            logger.error(" Error occurred while adding  child memberships: Messages {} ", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(msg).asRuntimeException());
        }

    }

    @Override
    public void deleteChildMembership(DeleteChildResourcesMembershipRequest request,
                                      StreamObserver<OperationStatusResponse> responseObserver) {
        try {

//            AuthenticatedUser callUser = request.getAuthToken().getAuthenticatedUser();
//            GenericResource resource = request.getParentResource();
//            List<GenericResource> childResources = request.getChildResourcesList();
//
//            List<GenericResource> allResources = new ArrayList<>();
//            allResources.add(resource);
//            allResources.addAll(childResources);

            responseObserver.onError(Status.UNIMPLEMENTED.asRuntimeException());


        } catch (Exception e) {
            String msg = " Error occurred while deleting  child memberships " + e.getMessage();
            logger.error(" Error occurred while fetching  parent resources: Messages {} ", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(msg).asRuntimeException());
        }


    }


    @Override
    public void fetchParentResources(ParentResourcesFetchRequest
                                             request, StreamObserver<ParentResourcesFetchResponse> responseObserver) {
        try {
            AuthenticatedUser callUser = request.getAuthToken().getAuthenticatedUser();
            String resourseId = request.getResourceId();
            String type = request.getType();

            Optional<Resource> optionalResource = resourceRepository.findById(resourseId);

            if (optionalResource.isPresent() && !optionalResource.get().getParentResourceId().isEmpty()) {

                String parentId = optionalResource.get().getParentResourceId();

                boolean status = CustosUtils.userHasAccess(custosClientProvider, callUser.getTenantId(),
                        callUser.getUsername(), parentId, SharingConstants.PERMISSION_TYPE_VIEWER);

                if (status) {
                    try (SharingManagementClient sharingManagementClient = custosClientProvider.getSharingManagementClient()) {
                        Entity enitity = Entity.newBuilder().setId(parentId).build();
                        Entity exEntity = sharingManagementClient.getEntity(callUser.getTenantId(), enitity);
                        Optional<Resource> parentResourceOp = resourceRepository.findById(parentId);
                        GenericResource resource = ResourceMapper.map(parentResourceOp.get(), exEntity);
                        Map<String, GenericResource> genericResourceMap = new HashMap<>();
                        genericResourceMap.put(String.valueOf(0), resource);
                        ParentResourcesFetchResponse resourcesFetchResponse = ParentResourcesFetchResponse
                                .newBuilder()
                                .putAllProperties(genericResourceMap)
                                .build();
                        responseObserver.onNext(resourcesFetchResponse);
                        responseObserver.onCompleted();
                        return;

                    }
                }
            }
            ParentResourcesFetchResponse resourcesFetchResponse = ParentResourcesFetchResponse
                    .newBuilder()
                    .build();
            responseObserver.onNext(resourcesFetchResponse);
            responseObserver.onCompleted();

        } catch (Exception ex) {
            String msg = " Error occurred while fetching  parent resources " + ex.getMessage();
            logger.error(" Error occurred while fetching  parent resources: Messages {} ", ex.getMessage(), ex);
            responseObserver.onError(Status.INTERNAL.withDescription(msg).asRuntimeException());
        }
    }

    @Override
    public void addResourceMetadata(AddResourceMetadataRequest request, StreamObserver<Empty> responseObserver) {
        try {
            AuthenticatedUser callUser = request.getAuthToken().getAuthenticatedUser();
            String resourceId = request.getResourceId();

            Struct struct = request.getMetadata();
            String message = JsonFormat.printer().print(struct);
            JSONObject json = new JSONObject(message);

            Map<String, Object> map = json.toMap();

            boolean status = CustosUtils.userHasAccess(custosClientProvider, callUser.getTenantId(),
                    callUser.getUsername(), resourceId, SharingConstants.PERMISSION_TYPE_VIEWER);

            if (status) {
                Optional<Resource> optionalResource = resourceRepository.findById(resourceId);

                if (optionalResource.isPresent()) {
                    Resource resource = optionalResource.get();
                    Set<ResourceProperty> resourcePropertySet = mergeProperties(resource, map);

                    Optional<ResourceProperty> property = resourcePropertyRepository.
                            findByKeyAndResourceId("metadata", resource.getId());

                    if (property.isPresent()) {
                        resourcePropertyRepository.delete(property.get());
                    }

                    ResourceProperty resourceProperty = new ResourceProperty();
                    resourceProperty.setKey("metadata");
                    resourceProperty.setValue(message);
                    resourcePropertySet.add(resourceProperty);

                    resource.setResourceProperty(resourcePropertySet);
                    resourceRepository.save(resource);
                    responseObserver.onNext(Empty.newBuilder().build());
                    responseObserver.onCompleted();
                }

            }
            //TODO: ERROR
        } catch (Exception ex) {
            String msg = " Error occurred while adding resource metadata " + ex.getMessage();
            logger.error("Error occurred while adding resource metadata: Messages {}", ex.getMessage());
            responseObserver.onError(Status.INTERNAL.withDescription(msg).asRuntimeException());
        }
    }

    @Override
    public void fetchResourceMetadata(FetchResourceMetadataRequest
                                              request, StreamObserver<FetchResourceMetadataResponse> responseObserver) {
        try {
            AuthenticatedUser callUser = request.getAuthToken().getAuthenticatedUser();
            String resourceId = request.getResourceId();

            boolean status = CustosUtils.userHasAccess(custosClientProvider,
                    callUser.getTenantId(), callUser.getUsername(), resourceId, SharingConstants.PERMISSION_TYPE_VIEWER);

            if (status) {

                Optional<Resource> resourceOptional = resourceRepository.findById(resourceId);
                FetchResourceMetadataResponse.Builder builder = FetchResourceMetadataResponse.newBuilder();

                if (resourceOptional.isPresent()) {

                    Optional<ResourceProperty> resourceProperty = resourcePropertyRepository
                            .findByKeyAndResourceId("metadata", resourceOptional.get().getId());
                    if (resourceProperty.isPresent()) {
                        String message = resourceProperty.get().getValue();
                        Struct.Builder structBuilder = Struct.newBuilder();
                        JsonFormat.parser().merge(message, structBuilder);
                        builder.addMetadata(structBuilder.build());
                    }
                }
                responseObserver.onNext(builder.build());
                responseObserver.onCompleted();
            }
        } catch (Exception ex) {
            String msg = " Error occurred while fetching resource metadata " + ex.getMessage();
            logger.error(" Error occurred while fetching resource metadata: Messages {} ", ex.getMessage(), ex);
            responseObserver.onError(Status.INTERNAL.withDescription(msg).asRuntimeException());
        }

    }

    private Set<ResourceProperty> mergeProperties(Resource resource, Map<String, Object> values) {

        Set<ResourceProperty> resourcePropertySet = new HashSet<>();
        Set<ResourceProperty> exisitingProperties = resource.getResourceProperty();

        for (String key : values.keySet()) {
            List<ResourceProperty> matched = exisitingProperties.stream().filter(prop -> {
                if (prop.getKey().equals(key)) {
                    return true;
                } else {
                    return false;
                }
            }).collect(Collectors.toList());

            if (values.get(key) instanceof Map) {
                //TODO: Implement MAP
            } else if (values.get(key) instanceof List) {
                ArrayList arrayList = (ArrayList) values.get(key);
                if (!matched.isEmpty()) {
                    matched.forEach(val -> {
                        resourcePropertyRepository.delete(val);
                    });
                }
                arrayList.forEach(val -> {
                    ResourceProperty resourceProperty = new ResourceProperty();
                    resourceProperty.setKey(key);
                    resourceProperty.setValue(val.toString());
                    resourceProperty.setResource(resource);
                    resourcePropertySet.add(resourceProperty);
                });
            } else {
                String value = String.valueOf(values.get(key));
                if (!matched.isEmpty()) {
                    matched.forEach(val -> {
                        resourcePropertyRepository.delete(val);
                    });
                }
                ResourceProperty resourceProperty = new ResourceProperty();
                resourceProperty.setKey(key);
                resourceProperty.setValue(value);
                resourceProperty.setResource(resource);
                resourcePropertySet.add(resourceProperty);
            }

        }
        return resourcePropertySet;
    }

}
