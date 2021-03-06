package jenkins.plugins.hygieia.workflow;

import com.capitalone.dashboard.model.BuildStage;
import com.capitalone.dashboard.model.BuildStatus;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hygieia.builder.BuildBuilder;
import jenkins.model.Jenkins;
import jenkins.plugins.hygieia.DefaultHygieiaService;
import jenkins.plugins.hygieia.HygieiaPublisher;
import jenkins.plugins.hygieia.HygieiaResponse;
import jenkins.plugins.hygieia.HygieiaService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.httpclient.HttpStatus;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class HygieiaBuildPublishStep extends AbstractStepImpl {

	private String buildStatus;
	private String applicationName;

	public String getBuildStatus() {
		return buildStatus;
	}

	@DataBoundSetter
	public void setBuildStatus(String buildStatus) {
		this.buildStatus = buildStatus;
	}
	
	public String getApplicationName() {
		return applicationName;
	}

	@DataBoundSetter
	public void setApplicationName(String appname) {
		this.applicationName = appname;
	}
	

	@DataBoundConstructor
	public HygieiaBuildPublishStep(@Nonnull String buildStatus, @Nonnull String applicationName) {
		this.buildStatus = buildStatus;
		this.applicationName = applicationName;
	}
	
	@Extension
	public static class DescriptorImpl extends AbstractStepDescriptorImpl {

		public DescriptorImpl() {
			super(HygieiaBuildPublishStepExecution.class);
		}

		@Override
		public String getFunctionName() {
			return "hygieiaBuildPublishStep";
		}

		@Override
		public String getDisplayName() {
			return "Hygieia Build Publish Step";
		}

		public ListBoxModel doFillBuildStatusItems() {
			ListBoxModel model = new ListBoxModel();
			model.add("Success", BuildStatus.Success.toString());
			model.add("Failure", BuildStatus.Failure.toString());
			model.add("Unstable", BuildStatus.Unstable.toString());
			model.add("Aborted", BuildStatus.Aborted.toString());
			return model;
		}
		
		//Added
		public FormValidation doCheckValue(@QueryParameter String value) {
			if (value.isEmpty()) {
				return FormValidation.warning("You must fill this box!");
			}
			return FormValidation.ok();
		}
	}

	public static class HygieiaBuildPublishStepExecution
			extends AbstractSynchronousNonBlockingStepExecution<List<Integer>> {

		private static final long serialVersionUID = 1L;

		@Inject
		transient HygieiaBuildPublishStep step;

		@StepContextParameter
		transient TaskListener listener;

		@StepContextParameter
		transient Run run;

		// This run MUST return a non-Void object, otherwise it will be executed
		// three times!!!! No idea why
		@Override
		protected List<Integer> run() {

			// default to global config values if not set in step, but allow
			// step to override all global settings
			this.listener.getLogger().println("[INDRESH] APPLICATION NAME: " + step.getApplicationName());
			
			Jenkins jenkins;
			try {
				jenkins = Jenkins.getInstance();
			} catch (NullPointerException ne) {
				this.listener.getLogger().println("[INDRESH] LEAVING RUN() IN BUILD NULL POINTER ON Jenkins.getInstance()");
				listener.error(ne.toString());
				return null;
			}
			HygieiaPublisher.DescriptorImpl hygieiaDesc = jenkins
					.getDescriptorByType(HygieiaPublisher.DescriptorImpl.class);

			// Skip the publish if publish is enabled in global preferences.
			boolean skipPublish = hygieiaDesc.isHygieiaPublishBuildDataGlobal()
					|| hygieiaDesc.isHygieiaPublishSonarDataGlobal()
					|| CollectionUtils.isNotEmpty(hygieiaDesc.getHygieiaPublishGenericCollectorItems());

			if(skipPublish) {
				this.listener.getLogger().println("[INDRESH] hygieiaDesc.isHygieiaPublishBuildDataGlobal() = " + hygieiaDesc.isHygieiaPublishBuildDataGlobal());
				this.listener.getLogger().println("[INDRESH] hygieiaDesc.isHygieiaPublishSonarDataGlobal() = " + hygieiaDesc.isHygieiaPublishSonarDataGlobal());
				this.listener.getLogger().println("[INDRESH] CollectionUtils.isNotEmpty(hygieiaDesc.getHygieiaPublishGenericCollectorItems()) = " + CollectionUtils.isNotEmpty(hygieiaDesc.getHygieiaPublishGenericCollectorItems()));
				
				this.listener.getLogger().println("[INDRESH] LEAVING RUN() IN BUILD skipPublish SET TO TRUE");
				return new ArrayList<>();
			}

			List<String> hygieiaAPIUrls = Arrays.asList(hygieiaDesc.getHygieiaAPIUrl().split(";"));
			List<Integer> responseCodes = new ArrayList<>();
			
			this.listener.getLogger().println("[INDRESH] API URLS PASSED (Build Publish Step): " + hygieiaAPIUrls.size());
			
			for (String hygieiaAPIUrl : hygieiaAPIUrls) {
				this.listener.getLogger().println("Publishing data for API " + hygieiaAPIUrl.toString());
				HygieiaService hygieiaService = getHygieiaService(hygieiaAPIUrl, hygieiaDesc.getHygieiaToken(),
						hygieiaDesc.getHygieiaJenkinsName(), hygieiaDesc.isUseProxy());
				HygieiaResponse buildResponse = hygieiaService.publishBuildData(new BuildBuilder().createBuildRequestFromRun(run, hygieiaDesc.getHygieiaJenkinsName(), listener,
						BuildStatus.fromString(step.buildStatus), step.getApplicationName(), true,new LinkedList<BuildStage>()));
				if (buildResponse.getResponseCode() == HttpStatus.SC_CREATED) {
					listener.getLogger().println("Hygieia: Published Build Complete Data. " + buildResponse.toString());
				} else {
					listener.getLogger()
							.println("Hygieia: Failed Publishing Build Complete Data. " + buildResponse.toString());
				}
				responseCodes.add(Integer.valueOf(buildResponse.getResponseCode()));
			}
			
			return responseCodes;
		}
		
		// streamline unit testing
		HygieiaService getHygieiaService(String hygieiaAPIUrl, String hygieiaToken, String hygieiaJenkinsName,
				boolean useProxy) {
			return new DefaultHygieiaService(hygieiaAPIUrl, hygieiaToken, hygieiaJenkinsName, useProxy);
		}
	}
}