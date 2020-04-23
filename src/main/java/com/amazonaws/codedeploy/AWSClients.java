/*
 * Copyright 2014 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazonaws.codedeploy;

import static org.apache.commons.lang.StringUtils.isEmpty;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.UUID;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.codedeploy.AmazonCodeDeploy;
import com.amazonaws.services.codedeploy.AmazonCodeDeployClientBuilder;
import com.amazonaws.services.codedeploy.model.GetApplicationRequest;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagement;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder;
import com.amazonaws.services.identitymanagement.model.GetUserResult;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import com.amazonaws.services.securitytoken.model.Credentials;

/**
 * @author gibbon
 */
public class AWSClients {
    /**
     * Index in the colon-separated ARN that contains the account id
     * Sample ARN: arn:aws:iam::123456789012:user/David
     **/
    private static final int ARN_ACCOUNT_ID_INDEX = 4;
    
    /**
     * AWS-CodeDeploy-Jenkins-Plugin/<Version>
     * This will be used as the SDK user agent suffix
     **/
    private static final String USER_AGENT_SUFFIX = "AWS-CodeDeploy-Jenkins-Plugin/1.23";

    public final AmazonCodeDeploy codedeploy;
    public final AmazonS3 s3;

    private final String region;
    private final String proxyHost;
    private final int proxyPort;

    public AWSClients(String region, AWSCredentials credentials, String proxyHost, int proxyPort) {
        this.region = region;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;

        //setup proxy connection:
        ClientConfiguration clientCfg = new ClientConfiguration();
        if (proxyHost != null && proxyPort > 0 ) {
            clientCfg.setProxyHost(proxyHost);
            clientCfg.setProxyPort(proxyPort);
        }
        
        clientCfg.setUserAgentSuffix(USER_AGENT_SUFFIX);

        if (credentials != null) {
            this.codedeploy = AmazonCodeDeployClientBuilder.standard()
                .withRegion(this.region)
                .withClientConfiguration(clientCfg)
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .build();
            this.s3 = AmazonS3ClientBuilder.standard()
                .withRegion(this.region)
                .withClientConfiguration(clientCfg)
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .build();
        } else {
            this.codedeploy = AmazonCodeDeployClientBuilder.standard()
                .withRegion(this.region)
                .withClientConfiguration(clientCfg)
                .build();
            this.s3 = AmazonS3ClientBuilder.standard()
                .withRegion(this.region)
                .withClientConfiguration(clientCfg)
                .build();
        }
    }

    public static AWSClients fromDefaultCredentialChain(String region, String proxyHost, int proxyPort) {
        return new AWSClients(region, null, proxyHost, proxyPort);
    }

    public static AWSClients fromIAMRole(String region, String iamRole, String externalId, String proxyHost, int proxyPort) {
        return new AWSClients(region, getCredentials(iamRole, externalId, region), proxyHost, proxyPort);
    }

    public static AWSClients fromBasicCredentials(String region, String awsAccessKey, String awsSecretKey, String proxyHost, int proxyPort) {
        return new AWSClients(region, new BasicAWSCredentials(awsAccessKey, awsSecretKey), proxyHost, proxyPort);
    }

    /**
     * Via the default provider chain (i.e., global keys for this Jenkins instance),  return the account ID for the
     * currently authenticated user.
     * @param proxyHost hostname of the proxy to use (if any)
     * @param proxyPort port of the proxy to use (if any)
     * @return 12-digit account id
     */
    public static String getAccountId(String proxyHost, int proxyPort) {

        String arn = "";
        try {
            ClientConfiguration clientCfg = new ClientConfiguration();
            if (proxyHost != null && proxyPort > 0 ) {
                clientCfg.setProxyHost(proxyHost);
                clientCfg.setProxyPort(proxyPort);
            }
            final AmazonIdentityManagement iam = AmazonIdentityManagementClientBuilder.standard()
                .withClientConfiguration(clientCfg)
                .build();
            GetUserResult user = iam.getUser();
            arn = user.getUser().getArn();
        } catch (AmazonServiceException e) {
            if (e.getErrorCode().compareTo("AccessDenied") == 0) {
                String msg = e.getMessage();
                int arnIdx = msg.indexOf("arn:aws");
                if (arnIdx != -1) {
                    int arnSpace = msg.indexOf(" ", arnIdx);
                    arn = msg.substring(arnIdx, arnSpace);
                }
            }
        } catch (RuntimeException e) {
            return "";
        }

        String accountId = arn.split(":")[ARN_ACCOUNT_ID_INDEX];
        return accountId;
    }

    public void testConnection(String s3bucket, String codeDeployApplication) throws Exception {
        String testKey = "tmp-" + UUID.randomUUID() + ".txt";
        s3.putObject(s3bucket, testKey, createTestFile());

        codedeploy.getApplication(new GetApplicationRequest().withApplicationName(codeDeployApplication));
    }

    private File createTestFile() throws IOException {
        File file = File.createTempFile("codedeploy-jenkins-plugin", ".txt");
        file.deleteOnExit();

        Writer writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
        writer.write("");
        writer.close();

        return file;
    }

    private static AWSCredentials getCredentials(final String iamRole, final String externalId, final String region) {
        if (isEmpty(iamRole)) {
            return null;
        }
        final String stsEndpointUrl = String.format("https://sts.%s.amazonaws.com", region);
        final EndpointConfiguration regionEndpointConfig = new EndpointConfiguration(stsEndpointUrl, region);
        final AWSSecurityTokenService stsRegionalClient = AWSSecurityTokenServiceClientBuilder.standard()
            .withEndpointConfiguration(regionEndpointConfig)
            .build();

        int credsDuration = (int) (AWSCodeDeployPublisher.DEFAULT_TIMEOUT_SECONDS
                        * AWSCodeDeployPublisher.DEFAULT_POLLING_FREQUENCY_SECONDS);

        if (credsDuration > 3600) {
            credsDuration = 3600;
        }

        final AssumeRoleResult assumeRoleResult = stsRegionalClient.assumeRole(new AssumeRoleRequest()
                        .withRoleArn(iamRole)
                        .withExternalId(externalId)
                        .withDurationSeconds(credsDuration)
                        .withRoleSessionName(AWSCodeDeployPublisher.ROLE_SESSION_NAME)
        );

        Credentials stsCredentials = assumeRoleResult.getCredentials();
        BasicSessionCredentials credentials = new BasicSessionCredentials(
                stsCredentials.getAccessKeyId(),
                stsCredentials.getSecretAccessKey(),
                stsCredentials.getSessionToken()
        );

        return credentials;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public String getProxyHost() {
        return proxyHost;
    }
}
