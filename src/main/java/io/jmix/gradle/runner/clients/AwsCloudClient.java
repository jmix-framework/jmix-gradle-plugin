/*
 * Copyright 2021 Haulmont.
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

package io.jmix.gradle.runner.clients;

import com.jcraft.jsch.JSchException;
import io.jmix.gradle.runner.ssh.SshSession;
import org.apache.commons.lang3.RandomStringUtils;
import org.gradle.api.logging.Logger;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class AwsCloudClient extends AbstractCloudClient {

    @Inject
    private Logger logger;

    @Named
    private String outputDir;
    @Named
    private String instanceType = InstanceType.T2_MICRO.toString();
    @Named
    private String spotPrice;
    @Named
    private String accessKey;
    @Named
    private String secretAccessKey;
    @Named
    private String region;
    @Named
    private String keyPairName;
    @Named
    private String securityGroupId;
    @Named
    private String instanceId;
    @Named
    private String spotRequestId;

    private Ec2Client ec2Client;
    private SsmClient ssmClient;

    @PostConstruct
    public void init() {
        AwsCredentialsProvider credentialsProvider = initCredentials();
        AwsCredentials credentials = credentialsProvider.resolveCredentials();
        this.accessKey = credentials.accessKeyId();
        this.secretAccessKey = credentials.secretAccessKey();
        initClients(credentialsProvider);
    }

    private void initClients(AwsCredentialsProvider credentialsProvider) {
        Region awsRegion = region != null
                ? Region.of(region)
                : DefaultAwsRegionProviderChain.builder().build().getRegion();
        this.region = awsRegion.id();

        ec2Client = Ec2Client.builder()
                .credentialsProvider(credentialsProvider)
                .region(awsRegion)
                .build();

        ssmClient = SsmClient.builder()
                .credentialsProvider(credentialsProvider)
                .region(awsRegion)
                .build();
    }

    private AwsCredentialsProvider initCredentials() {
        if (accessKey != null && secretAccessKey != null) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretAccessKey));
        }
        return DefaultCredentialsProvider.create();
    }

    @Override
    protected void doCreateResources() throws Exception {
        createKeyPair();
        createSecurityGroup(22, 8080);
        createEc2();
        installDocker();
    }

    private void createKeyPair() throws IOException {
        CreateKeyPairResponse keyPairResponse = ec2Client.createKeyPair(CreateKeyPairRequest.builder()
                .keyName("jmix-cloud-run-" + RandomStringUtils.randomAlphanumeric(10))
                .build());
        keyPairName = keyPairResponse.keyName();
        File keyFile = new File(outputDir, keyPairName + ".pem");
        try (FileWriter fileWriter = new FileWriter(keyFile);
             BufferedWriter writer = new BufferedWriter(fileWriter)) {
            writer.write(keyPairResponse.keyMaterial());
        }
        state.setKeyFile(keyFile.getPath());

        logger.lifecycle("Created key pair with name {}", keyPairName);
    }

    private void createSecurityGroup(int... ports) {
        CreateSecurityGroupResponse response = ec2Client.createSecurityGroup(CreateSecurityGroupRequest.builder()
                .groupName("jmix-cloud-run-sg-" + RandomStringUtils.randomAlphanumeric(10))
                .description("Jmix Cloud Run Security Group")
                .build());
        securityGroupId = response.groupId();
        List<IpPermission> permissions = Arrays.stream(ports)
                .mapToObj(port -> IpPermission.builder()
                        .ipProtocol("tcp")
                        .ipRanges(IpRange.builder()
                                .cidrIp("0.0.0.0/0")
                                .build())
                        .fromPort(port)
                        .toPort(port)
                        .build())
                .collect(Collectors.toList());
        ec2Client.authorizeSecurityGroupIngress(AuthorizeSecurityGroupIngressRequest.builder()
                .groupId(securityGroupId)
                .ipPermissions(permissions)
                .build());
        logger.lifecycle("Created security group {}", securityGroupId);
    }

    private void createEc2() {
        Instance instance = null;
        if (spotPrice != null) {
            instance = createSpotInstance();
        } else {
            instance = createEc2Instance();
        }
        if (instance != null) {
            state.setHost(instance.publicDnsName());
            state.setUsername("ec2-user");
            instanceId = instance.instanceId();
        }
    }

    private Instance createEc2Instance() {
        RunInstancesResponse response = ec2Client.runInstances(RunInstancesRequest.builder()
                .instanceType(InstanceType.fromValue(instanceType))
                .imageId(getLatestImage())
                .keyName(keyPairName)
                .securityGroupIds(securityGroupId)
                .minCount(1)
                .maxCount(1)
                .build());

        if (response.hasInstances()) {
            String instanceId = response.instances().get(0).instanceId();

            logger.lifecycle("Created EC2 instance {}. Waiting for it to become available", instanceId);

            ec2Client.waiter().waitUntilInstanceStatusOk(DescribeInstanceStatusRequest.builder()
                    .instanceIds(instanceId)
                    .build());

            DescribeInstancesResponse describeResponse = ec2Client.describeInstances(DescribeInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build());

            Instance instance = describeResponse.reservations().get(0).instances().get(0);

            logger.lifecycle("EC2 instance {} is ready to accept connections on {} ({})", instanceId,
                    instance.publicDnsName(), instance.publicIpAddress());

            return instance;
        }
        return null;
    }

    private Instance createSpotInstance() {
        RequestSpotInstancesResponse response = ec2Client.requestSpotInstances(RequestSpotInstancesRequest.builder()
                .spotPrice(spotPrice)
                .launchSpecification(RequestSpotLaunchSpecification.builder()
                        .instanceType(InstanceType.fromValue(instanceType))
                        .imageId(getLatestImage())
                        .keyName(keyPairName)
                        .securityGroupIds(securityGroupId)
                        .build())
                .build());

        if (response.hasSpotInstanceRequests()) {
            spotRequestId = response.spotInstanceRequests().get(0).spotInstanceRequestId();

            logger.lifecycle("Created Spot Request {}. Waiting for it to become fulfilled", spotRequestId);

            ec2Client.waiter().waitUntilSpotInstanceRequestFulfilled(DescribeSpotInstanceRequestsRequest.builder()
                    .spotInstanceRequestIds(spotRequestId)
                    .build());

            DescribeSpotInstanceRequestsResponse describeSpotInstanceRequestsResponse =
                    ec2Client.describeSpotInstanceRequests(DescribeSpotInstanceRequestsRequest.builder()
                            .spotInstanceRequestIds(spotRequestId)
                            .build());

            String instanceId = describeSpotInstanceRequestsResponse.spotInstanceRequests().get(0).instanceId();

            logger.lifecycle("Spot Request {} is fulfilled. Created EC2 instance {}", spotRequestId, instanceId);

            DescribeInstancesResponse describeResponse = ec2Client.describeInstances(DescribeInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build());

            Instance instance = describeResponse.reservations().get(0).instances().get(0);

            logger.lifecycle("EC2 instance {} is ready to accept connections on {} ({})", instanceId,
                    instance.publicDnsName(), instance.publicIpAddress());

            return instance;
        }

        return null;
    }

    private String getLatestImage() {
        String image = ssmClient.getParameter(GetParameterRequest.builder()
                .name("/aws/service/ami-amazon-linux-latest/amzn2-ami-hvm-x86_64-gp2")
                .build())
                .parameter()
                .value();
        logger.lifecycle("Using AMI {}", image);
        return image;
    }

    private void installDocker() throws IOException, JSchException {
        try (SshSession ssh = ssh()) {
            logger.lifecycle("Installing docker to EC2 Instance");
            ssh.execute("sudo amazon-linux-extras install docker");
            ssh.execute("sudo service docker start");
            ssh.execute("sudo usermod -a -G docker ec2-user");

            logger.lifecycle("Installing docker-compose to EC2 Instance");
            ssh.execute("sudo curl " +
                    "-L https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m) " +
                    "-o /usr/local/bin/docker-compose");
            ssh.execute("sudo chmod +x /usr/local/bin/docker-compose");
        }
    }

    @Override
    public void destroyResources() throws Exception {
        deleteSpotRequest();
        terminateEc2();
        deleteSecurityGroup();
        deleteKeyPair();
    }

    private void deleteSpotRequest() {
        if (spotRequestId != null) {
            ec2Client.cancelSpotInstanceRequests(CancelSpotInstanceRequestsRequest.builder()
                    .spotInstanceRequestIds(spotRequestId)
                    .build());
            logger.lifecycle("Deleted Spot Request {}", spotRequestId);
            spotRequestId = null;
        }
    }

    private void terminateEc2() {
        if (instanceId != null) {
            logger.lifecycle("Terminating EC2 Instance {} ({})", instanceId, state.getHost());
            ec2Client.terminateInstances(TerminateInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build());
            ec2Client.waiter().waitUntilInstanceTerminated(DescribeInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build());
            logger.lifecycle("Terminated EC2 Instance {} ({})", instanceId, state.getHost());
            instanceId = null;
        }
    }

    private void deleteSecurityGroup() {
        if (securityGroupId != null) {
            ec2Client.deleteSecurityGroup(DeleteSecurityGroupRequest.builder()
                    .groupId(securityGroupId)
                    .build());
            logger.lifecycle("Deleted Security Group {}", securityGroupId);
            securityGroupId = null;
        }
    }

    private void deleteKeyPair() throws IOException {
        if (keyPairName != null) {
            ec2Client.deleteKeyPair(DeleteKeyPairRequest.builder()
                    .keyName(keyPairName)
                    .build());
            logger.lifecycle("Deleted Key Pair {}", keyPairName);
            keyPairName = null;
        }
        Files.deleteIfExists(Paths.get(state.getKeyFile()));
    }
}
