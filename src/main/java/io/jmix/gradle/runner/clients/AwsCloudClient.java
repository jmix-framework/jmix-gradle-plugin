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
import io.jmix.gradle.runner.CloudClient;
import io.jmix.gradle.runner.ComputeInstance;
import io.jmix.gradle.runner.ssh.SshSession;
import org.gradle.api.Task;
import org.gradle.api.logging.Logger;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class AwsCloudClient implements CloudClient {

    private static final String KEY_PAIR = "aws_key_pair";
    private static final String SECURITY_GROUP = "aws_security_group";
    private static final String EC2 = "aws_ec2";
    private static final String SPOT_REQUEST = "aws_spot_request";

    private Ec2Client ec2Client;
    private SsmClient ssmClient;

    private Task task;
    private File tmpDir;
    private Logger logger;

    private String instanceType;
    private String spotPrice;

    @Override
    public void init(Task task, File tmpDir) {
        this.task = task;
        this.tmpDir = tmpDir;
        this.logger = task.getLogger();

        initClients();

        this.instanceType = projectProperty("instanceType", InstanceType.T2_MICRO.toString());
        this.spotPrice = projectProperty("spotPrice", null);
    }

    private void initClients() {
        AwsCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();

        ec2Client = Ec2Client.builder()
                .credentialsProvider(credentialsProvider)
                .build();

        ssmClient = SsmClient.builder()
                .credentialsProvider(credentialsProvider)
                .build();
    }

    private <T> T projectProperty(String key, T defaultValue) {
        if (task.getProject().hasProperty(key)) {
            return (T) task.getProject().findProperty(key);
        } else {
            return defaultValue;
        }
    }

    @Override
    public ComputeInstance create() throws Exception {
        String keyPair = "jmix-cloud-run-" + UUID.randomUUID().toString();
        String sgName = "jmix-cloud-run-sg-" + UUID.randomUUID().toString();
        String keyFile = createKeyPair(keyPair);
        createSecurityGroup(sgName, 22, 2376, 8080);
        Instance ec2 = createEc2(keyPair, sgName);

        ComputeInstance computeInstance = new ComputeInstance();
        computeInstance.setProvider("aws");
        computeInstance.setHost(ec2.publicDnsName());
        computeInstance.setUsername("ec2-user");
        computeInstance.setKeyFile(keyFile);
        computeInstance.addResource(KEY_PAIR, keyPair);
        computeInstance.addResource(SECURITY_GROUP, sgName);
        computeInstance.addResource(EC2, ec2.instanceId());
        if (spotPrice != null) {
            computeInstance.addResource(SPOT_REQUEST, ec2.spotInstanceRequestId());
        }

        installDocker(computeInstance);

        return computeInstance;
    }

    private String createKeyPair(String keyName) throws IOException {
        CreateKeyPairResponse keyPairResponse = ec2Client.createKeyPair(CreateKeyPairRequest.builder()
                .keyName(keyName)
                .build());
        String keyFileName = tmpDir.getPath() + "/" + keyName + ".pem";
        try (FileWriter fileWriter = new FileWriter(keyFileName);
             BufferedWriter writer = new BufferedWriter(fileWriter)) {
            writer.write(keyPairResponse.keyMaterial());
        }
        logger.lifecycle("Created key pair with name {}", keyName);
        return keyFileName;
    }

    private void createSecurityGroup(String groupName, int... ports) {
        ec2Client.createSecurityGroup(CreateSecurityGroupRequest.builder()
                .groupName(groupName)
                .description("Jmix Cloud Run Security Group")
                .build());
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
                .groupName(groupName)
                .ipPermissions(permissions)
                .build());
        logger.lifecycle("Created security group {}", groupName);
    }

    private Instance createEc2(String keyName, String securityGroup) {
        if (spotPrice != null) {
            return createSpotInstance(keyName, securityGroup);
        } else {
            return createEc2Instance(keyName, securityGroup);
        }
    }

    private Instance createEc2Instance(String keyName, String securityGroup) {
        RunInstancesResponse response = ec2Client.runInstances(RunInstancesRequest.builder()
                .instanceType(InstanceType.fromValue(instanceType))
                .imageId(getLatestImage())
                .keyName(keyName)
                .securityGroups(securityGroup)
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

    private Instance createSpotInstance(String keyName, String securityGroup) {
        RequestSpotInstancesResponse response = ec2Client.requestSpotInstances(RequestSpotInstancesRequest.builder()
                .spotPrice(spotPrice)
                .launchSpecification(RequestSpotLaunchSpecification.builder()
                        .instanceType(InstanceType.fromValue(instanceType))
                        .imageId(getLatestImage())
                        .keyName(keyName)
                        .securityGroups(securityGroup)
                        .build())
                .build());

        if (response.hasSpotInstanceRequests()) {
            String instanceRequestId = response.spotInstanceRequests().get(0).spotInstanceRequestId();

            logger.lifecycle("Created Spot Request {}. Waiting for it to become fulfilled", instanceRequestId);

            ec2Client.waiter().waitUntilSpotInstanceRequestFulfilled(DescribeSpotInstanceRequestsRequest.builder()
                    .spotInstanceRequestIds(instanceRequestId)
                    .build());

            DescribeSpotInstanceRequestsResponse describeSpotInstanceRequestsResponse =
                    ec2Client.describeSpotInstanceRequests(DescribeSpotInstanceRequestsRequest.builder()
                            .spotInstanceRequestIds(instanceRequestId)
                            .build());

            String instanceId = describeSpotInstanceRequestsResponse.spotInstanceRequests().get(0).instanceId();

            logger.lifecycle("Spot Request {} is fulfilled. Created EC2 instance {}", instanceRequestId, instanceId);

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

    private void installDocker(ComputeInstance computeInstance) throws IOException, JSchException {
        try (SshSession sshSession = SshSession.forComputeInstance(computeInstance)) {
            sshSession.execute("sudo amazon-linux-extras install docker");
            sshSession.execute("sudo service docker start");
            sshSession.execute("sudo usermod -a -G docker ec2-user");
            sshSession.execute("sudo curl -L https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m) -o /usr/local/bin/docker-compose");
            sshSession.execute("sudo chmod +x /usr/local/bin/docker-compose");
        }
    }

    @Override
    public void destroy(ComputeInstance instance) throws Exception {
        String spotRequestId = instance.getResource(SPOT_REQUEST);
        if (spotRequestId != null) {
            deleteSpotRequest(spotRequestId);
        }
        terminateEc2(instance.getResource(EC2));
        deleteSecurityGroup(instance.getResource(SECURITY_GROUP));
        deleteKeyPair(instance.getResource(KEY_PAIR));

        Files.deleteIfExists(Paths.get(instance.getKeyFile()));
    }

    private void deleteSpotRequest(String spotRequestId) {
        ec2Client.cancelSpotInstanceRequests(CancelSpotInstanceRequestsRequest.builder()
                .spotInstanceRequestIds(spotRequestId)
                .build());
    }

    private void terminateEc2(String instanceId) {
        ec2Client.terminateInstances(TerminateInstancesRequest.builder()
                .instanceIds(instanceId)
                .build());
        ec2Client.waiter().waitUntilInstanceTerminated(DescribeInstancesRequest.builder()
                .instanceIds(instanceId)
                .build());
    }

    private void deleteSecurityGroup(String groupName) {
        ec2Client.deleteSecurityGroup(DeleteSecurityGroupRequest.builder()
                .groupName(groupName)
                .build());
    }

    private void deleteKeyPair(String keyPair) {
        ec2Client.deleteKeyPair(DeleteKeyPairRequest.builder()
                .keyName(keyPair)
                .build());
    }
}
