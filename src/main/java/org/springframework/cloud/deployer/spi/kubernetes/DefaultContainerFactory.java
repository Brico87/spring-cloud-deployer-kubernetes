/*
 * Copyright 2015-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.deployer.spi.kubernetes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.VolumeMount;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.bind.YamlConfigurationFactory;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.util.CommandLineTokenizer;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Create a Kubernetes {@link Container} that will be started as part of a
 * Kubernetes Pod by launching the specified Docker image.
 *
 * @author Florian Rosenberg
 * @author Thomas Risberg
 * @author Donovan Muller
 * @author David Turanski
 * @author Chris Schaefer
 */
public class DefaultContainerFactory implements ContainerFactory {

	private static Log logger = LogFactory.getLog(DefaultContainerFactory.class);

	private final KubernetesDeployerProperties properties;

	private final NestedCommaDelimitedVariableParser nestedCommaDelimitedVariableParser = new NestedCommaDelimitedVariableParser();

	public DefaultContainerFactory(KubernetesDeployerProperties properties) {
		this.properties = properties;
	}

	@Override
	@Deprecated
	public Container create(String appId, AppDeploymentRequest request, Integer port, Integer instanceIndex,
		boolean hostNetwork) {
		ContainerConfiguration containerConfiguration = new ContainerConfiguration(appId, request)
				.withHostNetwork(hostNetwork)
				.withExternalPort(port);
		return this.create(containerConfiguration);
	}

	@Override
	@Deprecated
	public Container create(String appId, AppDeploymentRequest request, Integer port, boolean hostNetwork) {
		ContainerConfiguration containerConfiguration = new ContainerConfiguration(appId, request)
				.withHostNetwork(hostNetwork)
				.withExternalPort(port);
		return this.create(containerConfiguration);
	}

	@Override
	public Container create(ContainerConfiguration containerConfiguration) {
		AppDeploymentRequest request = containerConfiguration.getAppDeploymentRequest();

		String image;
		try {
			image = request.getResource().getURI().getSchemeSpecificPart();
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Unable to get URI for " + request.getResource(), e);
		}
		logger.info("Using Docker image: " + image);

		EntryPointStyle entryPointStyle = determineEntryPointStyle(properties, request);
		logger.info("Using Docker entry point style: " + entryPointStyle);

		Map<String, String> envVarsMap = new HashMap<>();
		for (String envVar : properties.getEnvironmentVariables()) {
			String[] strings = envVar.split("=", 2);
			Assert.isTrue(strings.length == 2, "Invalid environment variable declared: " + envVar);
			envVarsMap.put(strings[0], strings[1]);
		}
		//Create EnvVar entries for additional variables set at the app level
		//For instance, this may be used to set JAVA_OPTS independently for each app if the base container
		//image supports it.
		envVarsMap.putAll(getAppEnvironmentVariables(request));

		List<String> appArgs = new ArrayList<>();

		switch (entryPointStyle) {
		case exec:
			appArgs = createCommandArgs(request);
			break;
		case boot:
			if (envVarsMap.containsKey("SPRING_APPLICATION_JSON")) {
				throw new IllegalStateException(
					"You can't use boot entry point style and also set SPRING_APPLICATION_JSON for the app");
			}
			try {
				envVarsMap.put("SPRING_APPLICATION_JSON",
					new ObjectMapper().writeValueAsString(request.getDefinition().getProperties()));
			}
			catch (JsonProcessingException e) {
				throw new IllegalStateException("Unable to create SPRING_APPLICATION_JSON", e);
			}

			appArgs = request.getCommandlineArguments();

			break;
		case shell:
			for (String key : request.getDefinition().getProperties().keySet()) {
				String envVar = key.replace('.', '_').toUpperCase();
				envVarsMap.put(envVar, request.getDefinition().getProperties().get(key));
			}
			break;
		}

		List<EnvVar> envVars = new ArrayList<>();
		for (Map.Entry<String, String> e : envVarsMap.entrySet()) {
			envVars.add(new EnvVar(e.getKey(), e.getValue(), null));
		}
		envVars.add(new EnvVar("SPRING_CLOUD_APPLICATION_GUID", "${HOSTNAME}", null));

		if (request.getDeploymentProperties().get(AppDeployer.GROUP_PROPERTY_KEY) != null) {
			envVars.add(new EnvVar("SPRING_CLOUD_APPLICATION_GROUP",
				request.getDeploymentProperties().get(AppDeployer.GROUP_PROPERTY_KEY), null));
		}

		ContainerBuilder container = new ContainerBuilder();
		container.withName(containerConfiguration.getAppId()).withImage(image).withEnv(envVars).withArgs(appArgs)
			.withVolumeMounts(getVolumeMounts(request));

		Set<Integer> ports = new HashSet<>();

		Integer defaultPort = containerConfiguration.getExternalPort();

		if (defaultPort != null) {
			ports.add(defaultPort);
		}

		ports.addAll(getContainerPorts(request));

		configureReadinessProbe(containerConfiguration, container, ports);
		configureLivenessProbe(containerConfiguration, container, ports);

		if (!ports.isEmpty()) {
			for (Integer containerPort : ports) {
				if (containerConfiguration.isHostNetwork()) {
					container.addNewPort().withContainerPort(containerPort).withHostPort(containerPort).endPort();
				}
				else {
					container.addNewPort().withContainerPort(containerPort).endPort();
				}
			}
		}

		//Override the containers default entry point with one specified during the app deployment
		List<String> containerCommand = getContainerCommand(request);
		if (!containerCommand.isEmpty()) {
			container.withCommand(containerCommand);
		}

		return container.build();
	}

	private void configureReadinessProbe(ContainerConfiguration containerConfiguration,
						ContainerBuilder containerBuilder, Set<Integer> ports) {
		Probe readinessProbe = new ReadinessProbeCreator(properties, containerConfiguration).create();

		Integer readinessProbePort = readinessProbe.getHttpGet().getPort().getIntVal();

		if (readinessProbePort != null) {
			containerBuilder.withReadinessProbe(readinessProbe);
			ports.add(readinessProbePort);
		}
	}

	private void configureLivenessProbe(ContainerConfiguration containerConfiguration,
						ContainerBuilder containerBuilder, Set<Integer> ports) {
		Probe livenessProbe = new LivenessProbeCreator(properties, containerConfiguration).create();

		Integer livenessProbePort = livenessProbe.getHttpGet().getPort().getIntVal();

		if (livenessProbePort != null) {
			containerBuilder.withLivenessProbe(livenessProbe);
			ports.add(livenessProbePort);
		}
	}

	/**
	 * Create command arguments
	 */
	protected List<String> createCommandArgs(AppDeploymentRequest request) {
		List<String> cmdArgs = new LinkedList<>();
		// add properties from deployment request
		Map<String, String> args = request.getDefinition().getProperties();
		for (Map.Entry<String, String> entry : args.entrySet()) {
			cmdArgs.add(String.format("--%s=%s", entry.getKey(), entry.getValue()));
		}
		// add provided command line args
		cmdArgs.addAll(request.getCommandlineArguments());
		logger.debug("Using command args: " + cmdArgs);
		return cmdArgs;
	}

	/**
	 * Volume mount deployment properties are specified in YAML format:
	 * <p>
	 * <code>
	 * spring.cloud.deployer.kubernetes.volumeMounts=[{name: 'testhostpath', mountPath: '/test/hostPath'},
	 * {name: 'testpvc', mountPath: '/test/pvc'}, {name: 'testnfs', mountPath: '/test/nfs'}]
	 * </code>
	 * <p>
	 * Volume mounts can be specified as deployer properties as well as app deployment properties.
	 * Deployment properties override deployer properties.
	 *
	 * @param request
	 * @return the configured volume mounts
	 */
	protected List<VolumeMount> getVolumeMounts(AppDeploymentRequest request) {
		List<VolumeMount> volumeMounts = new ArrayList<>();

		String volumeMountDeploymentProperty = request.getDeploymentProperties()
			.getOrDefault("spring.cloud.deployer.kubernetes.volumeMounts", "");
		if (!StringUtils.isEmpty(volumeMountDeploymentProperty)) {
			YamlConfigurationFactory<KubernetesDeployerProperties> volumeMountYamlConfigurationFactory = new YamlConfigurationFactory<>(
				KubernetesDeployerProperties.class);
			volumeMountYamlConfigurationFactory.setYaml("{ volumeMounts: " + volumeMountDeploymentProperty + " }");
			try {
				volumeMountYamlConfigurationFactory.afterPropertiesSet();
				volumeMounts.addAll(volumeMountYamlConfigurationFactory.getObject().getVolumeMounts());
			}
			catch (Exception e) {
				throw new IllegalArgumentException(
					String.format("Invalid volume mount '%s'", volumeMountDeploymentProperty), e);
			}
		}
		// only add volume mounts that have not already been added, based on the volume mount's name
		// i.e. allow provided deployment volume mounts to override deployer defined volume mounts
		volumeMounts.addAll(properties.getVolumeMounts().stream().filter(volumeMount -> volumeMounts.stream()
			.noneMatch(existingVolumeMount -> existingVolumeMount.getName().equals(volumeMount.getName())))
			.collect(Collectors.toList()));

		return volumeMounts;
	}

	/**
	 * The list represents a single command with many arguments.
	 *
	 * @param request AppDeploymentRequest - used to gather application overridden
	 *                container command
	 * @return a list of strings that represents the command and any arguments for that command
	 */
	private List<String> getContainerCommand(AppDeploymentRequest request) {
		String containerCommand = request.getDeploymentProperties()
			.getOrDefault("spring.cloud.deployer.kubernetes.containerCommand", "");
		return new CommandLineTokenizer(containerCommand).getArgs();
	}

	/**
	 * @param request AppDeploymentRequest - used to gather additional container ports
	 * @return a list of Integers to add to the container
	 */
	private List<Integer> getContainerPorts(AppDeploymentRequest request) {
		List<Integer> containerPortList = new ArrayList<>();
		String containerPorts = request.getDeploymentProperties()
			.get("spring.cloud.deployer.kubernetes.containerPorts");
		if (containerPorts != null) {
			String[] containerPortSplit = containerPorts.split(",");
			for (String containerPort : containerPortSplit) {
				logger.trace("Adding container ports from AppDeploymentRequest: " + containerPort);
				Integer port = Integer.parseInt(containerPort.trim());
				containerPortList.add(port);
			}
		}
		return containerPortList;
	}

	/**
	 * @param request AppDeploymentRequest - used to gather application specific
	 *                environment variables
	 * @return a List of EnvVar objects for app specific environment settings
	 */
	private Map<String, String> getAppEnvironmentVariables(AppDeploymentRequest request) {
		Map<String, String> appEnvVarMap = new HashMap<>();
		String appEnvVar = request.getDeploymentProperties()
			.get("spring.cloud.deployer.kubernetes.environmentVariables");
		if (appEnvVar != null) {
			String[] appEnvVars = nestedCommaDelimitedVariableParser.parse(appEnvVar);
			for (String envVar : appEnvVars) {
				logger.trace("Adding environment variable from AppDeploymentRequest: " + envVar);
				String[] strings = envVar.split("=", 2);
				Assert.isTrue(strings.length == 2, "Invalid environment variable declared: " + envVar);
				appEnvVarMap.put(strings[0], strings[1]);
			}
		}
		return appEnvVarMap;
	}

	static class NestedCommaDelimitedVariableParser {
		static final String REGEX = "(\\w+='.+?'),?";
		static final Pattern pattern = Pattern.compile(REGEX);

		String[] parse(String value) {

			String[] vars = value.replaceAll(pattern.pattern(), "").split(",");

			Matcher m = pattern.matcher(value);
			while (m.find()) {
				vars = Arrays.copyOf(vars, vars.length + 1);
				vars[vars.length - 1] = m.group(1).replaceAll("'","");
			}
			return vars;
		}
	}

	private EntryPointStyle determineEntryPointStyle(KubernetesDeployerProperties properties,
		AppDeploymentRequest request) {
		EntryPointStyle entryPointStyle = null;
		String deployProperty = request.getDeploymentProperties()
			.get("spring.cloud.deployer.kubernetes.entryPointStyle");
		if (deployProperty != null) {
			try {
				entryPointStyle = EntryPointStyle.valueOf(deployProperty.toLowerCase());
			}
			catch (IllegalArgumentException ignore) {
			}
		}
		if (entryPointStyle == null) {
			entryPointStyle = properties.getEntryPointStyle();
		}
		return entryPointStyle;
	}
}
