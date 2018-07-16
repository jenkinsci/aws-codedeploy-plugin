package com.amazonaws.codedeploy;

import hudson.model.FreeStyleProject;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.LocalData;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

public class AWSCodeDeployPublisherTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void testRoundTripConfiguration() throws Exception {
        final AWSCodeDeployPublisher publisher = new AWSCodeDeployPublisher(
                "testBucket",
                "testPrefix",
                "testApplicationName",
                "testDeploymentGroupName",
                "testDeploymentConfig",
                "us-west-2",
                false,
                true,
                60L,
                10L,
                "awsAccessKey",
                "",
                "deploy",
                "testAccessKey",
                "testSecretKey",
                "",
                null,
                "",
                "",
                0,
                "",
                "");

        final AWSCodeDeployPublisher afterPublisher = j.configRoundtrip(publisher);
        j.assertEqualDataBoundBeans(publisher, afterPublisher);
    }

    @Test
    @LocalData
    public void testSaveUsesSecret() throws Exception {
        FreeStyleProject project = (FreeStyleProject) j.jenkins.getItem("testSecrets");
        FreeStyleProject after = j.configRoundtrip(project);
        assertThat(after.getConfigFile().asString(), not(containsString("TEST_SECRET")));
    }
}
