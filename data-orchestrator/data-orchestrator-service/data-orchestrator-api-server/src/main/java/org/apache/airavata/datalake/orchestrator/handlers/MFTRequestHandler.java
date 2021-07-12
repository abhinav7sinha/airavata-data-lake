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

package org.apache.airavata.datalake.orchestrator.handlers;

import com.google.protobuf.Struct;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.apache.airavata.datalake.drms.DRMSServiceAuthToken;
import org.apache.airavata.datalake.drms.resource.GenericResource;
import org.apache.airavata.datalake.drms.storage.*;
import org.apache.airavata.datalake.orchestrator.handlers.mft.MFTDownloadResponse;
import org.apache.airavata.mft.api.client.MFTApiClient;
import org.apache.airavata.mft.api.service.HttpDownloadApiRequest;
import org.apache.airavata.mft.api.service.HttpDownloadApiResponse;
import org.apache.airavata.mft.api.service.MFTApiServiceGrpc;
import org.apache.airavata.mft.common.AuthToken;
import org.apache.airavata.mft.common.UserTokenAuth;
import org.apache.custos.clients.CustosClientProvider;
import org.apache.custos.identity.management.client.IdentityManagementClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class MFTRequestHandler {

    private static final Logger logger = LoggerFactory.getLogger(MFTRequestHandler.class);

    @org.springframework.beans.factory.annotation.Value("${mft.host}")
    private String mftHost;

    @org.springframework.beans.factory.annotation.Value("${mft.port}")
    private int mftPort;

    @org.springframework.beans.factory.annotation.Value("${drms.host}")
    private String drmsHost;

    @org.springframework.beans.factory.annotation.Value("${drms.port}")
    private int drmsPort;

    @GetMapping("/mftdownlaod/{resourceid}")
    public MFTDownloadResponse mftDownload(@PathVariable String resourceid) throws Exception {
        MFTApiServiceGrpc.MFTApiServiceBlockingStub mftClient = MFTApiClient.buildClient(mftHost, mftPort);

        ManagedChannel channel = ManagedChannelBuilder.forAddress(drmsHost, drmsPort).usePlaintext().build();
        ResourceServiceGrpc.ResourceServiceBlockingStub resourceClient = ResourceServiceGrpc.newBlockingStub(channel);
        StoragePreferenceServiceGrpc.StoragePreferenceServiceBlockingStub stoPrefClient = StoragePreferenceServiceGrpc.newBlockingStub(channel);

        String accessToken = getAccessToken();
        DRMSServiceAuthToken authToken = DRMSServiceAuthToken.newBuilder().setAccessToken(accessToken).build();
        ResourceFetchResponse resourceFetchResponse = resourceClient.fetchResource(ResourceFetchRequest.newBuilder()
                .setResourceId(resourceid)
                .setAuthToken(authToken).build());

        GenericResource resource = resourceFetchResponse.getResource();

        if (resource.getResourceId().isEmpty()) {
            throw new Exception("No resource with id " + resourceid + " found");
        }

        HttpDownloadApiRequest.Builder downloadRequest = HttpDownloadApiRequest.newBuilder();
        downloadRequest.setSourceResourceId(resourceid);

        String storageId = null;

        switch (resource.getStorageCase()) {
            case S3_STORAGE:
                storageId = resource.getS3Storage().getStorageId();
                break;
            case SSH_STORAGE:
                storageId = resource.getSshStorage().getStorageId();
                break;
            case STORAGE_NOT_SET:
                logger.error("Not storage type found for resource {}", resourceid);
                throw new Exception("Not storage type found for resource " + resourceid);
        }

        StoragePreferenceSearchResponse stoPrefResults = stoPrefClient
                .searchStoragePreference(StoragePreferenceSearchRequest.newBuilder()
                    .setAuthToken(authToken)
                    .addQueries(StoragePreferenceSearchQuery.newBuilder()
                            .setField("storageId")
                            .setValue(storageId).build()).build());

        List<AnyStoragePreference> storagesPreferenceList = stoPrefResults.getStoragesPreferenceList();

        if (storagesPreferenceList.isEmpty()) {
            logger.error("No storage preference found for resource {}", resourceid);
            throw new Exception("No storage preference found for resource " + resourceid);
        }

        AnyStoragePreference fistPref = storagesPreferenceList.get(0);

        switch (fistPref.getStorageCase()) {
            case S3_STORAGE_PREFERENCE:
                downloadRequest.setSourceType("S3");
                downloadRequest.setSourceToken(fistPref.getS3StoragePreference().getStoragePreferenceId());
                break;
            case SSH_STORAGE_PREFERENCE:
                downloadRequest.setSourceType("SCP");
                downloadRequest.setSourceToken(fistPref.getSshStoragePreference().getStoragePreferenceId());
                break;
            case STORAGE_NOT_SET:
                logger.error("Not storage preference type found for resource {}", resourceid);
                throw new Exception("Not storage preference type found for resource " + resourceid);
        }

        downloadRequest.setMftAuthorizationToken(AuthToken.newBuilder()
                .setUserTokenAuth(UserTokenAuth.newBuilder().setToken(accessToken).build()).build());

        HttpDownloadApiResponse downloadResponse = mftClient.submitHttpDownload(downloadRequest.build());

        return new MFTDownloadResponse().setUrl(downloadResponse.getUrl()).setAgentId(downloadResponse.getTargetAgent());
    }

    private static String getAccessToken() {
        try {

            CustosClientProvider custosClientProvider = new CustosClientProvider.Builder().setServerHost("custos.scigap.org")
                    .setServerPort(31499)
                    .setClientId("custos-whedmgamitu357p4wuke-10002708")
                    .setClientSec("mrMdl86Ia1H94cikW7CvHoh7L0ASNXQVt2aRzSIj").build();

            IdentityManagementClient identityManagementClient = custosClientProvider.getIdentityManagementClient();
            Struct struct = identityManagementClient.getToken(null, null, "isjarana@iu.edu", "IJR@circ@1", null, "password");
            return struct.getFieldsMap().get("access_token").getStringValue();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

}
