// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license.

package com.microsoft.gradle;

import com.microsoft.gradle.api.GradleClosure;
import com.microsoft.gradle.api.GradleDependencyNode;
import com.microsoft.gradle.api.GradleDependencyType;
import com.microsoft.gradle.api.GradleField;
import com.microsoft.gradle.api.GradleMethod;
import com.microsoft.gradle.api.GradleProjectModel;
import com.microsoft.gradle.api.GradleTask;
import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.initialization.dsl.ScriptHandler;
import org.gradle.api.internal.initialization.DefaultScriptHandler;
import org.gradle.api.plugins.Convention;
import org.gradle.api.plugins.ExtensionsSchema;
import org.gradle.api.plugins.ExtensionsSchema.ExtensionSchema;
import org.gradle.api.reflect.TypeOf;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.tooling.provider.model.ToolingModelBuilder;

public class GradleProjectModelBuilder implements ToolingModelBuilder {

	private Set<GradleTask> cachedTasks = new HashSet<>();

	public boolean canBuild(String modelName) {
		return modelName.equals(GradleProjectModel.class.getName());
	}

	public Object buildAll(String modelName, Project project) {
		cachedTasks.clear();
		GradleProjectModel rootModel = buildModel(project, project);
		// add task selectors for root project
		Set<String> taskNames = new HashSet<>();
		for (GradleTask existingTask : rootModel.getTasks()) {
			taskNames.add(existingTask.getName());
		}
		for (GradleTask task : cachedTasks) {
			if (!taskNames.contains(task.getName())) {
				taskNames.add(task.getName());
				GradleTask newTask = new DefaultGradleTask(task.getName(), task.getGroup(), task.getPath(),
						project.getName(), project.getBuildscript().getSourceFile().getAbsolutePath(),
						task.getRootProject(), task.getDescription());
				rootModel.getTasks().add(newTask);
			}
		}
		return rootModel;
	}

	private GradleProjectModel buildModel(Project rootProject, Project project) {
		if (rootProject == null || project == null) {
			return null;
		}
		ScriptHandler buildScript = project.getBuildscript();
		ClassPath classpath = ((DefaultScriptHandler) buildScript).getScriptClassPath();
		List<String> scriptClasspaths = new ArrayList<>();
		classpath.getAsFiles().forEach((file) -> {
			scriptClasspaths.add(file.getAbsolutePath());
		});
		GradleDependencyNode node = generateDefaultGradleDependencyNode(project);
		List<String> plugins = getPlugins(project);
		List<GradleClosure> closures = getPluginClosures(project);
		List<GradleProjectModel> subModels = new ArrayList<>();
		Set<Project> subProjects = project.getSubprojects();
		for (Project subProject : subProjects) {
			GradleProjectModel subModel = buildModel(rootProject, subProject);
			if (subModel != null) {
				subModels.add(subModel);
			}
		}
		List<GradleTask> tasks = getGradleTasks(rootProject, project);
		return new DefaultGradleProjectModel(project.getParent() == null, project.getProjectDir().getAbsolutePath(),
				subModels, tasks, node, plugins, closures, scriptClasspaths);
	}

	private GradleDependencyNode generateDefaultGradleDependencyNode(Project project) {
		DefaultGradleDependencyNode rootNode = new DefaultGradleDependencyNode(project.getName(),
				GradleDependencyType.PROJECT);
		ConfigurationContainer configurationContainer = project.getConfigurations();
		for (String configName : configurationContainer.getNames()) {
			Configuration config = configurationContainer.getByName(configName);
			if (!config.isCanBeResolved()) {
				continue;
			}
			DefaultGradleDependencyNode configNode = new DefaultGradleDependencyNode(config.getName(),
					GradleDependencyType.CONFIGURATION);
			ResolvableDependencies incoming = config.getIncoming();
			ResolutionResult resolutionResult = incoming.getResolutionResult();
			ResolvedComponentResult rootResult = resolutionResult.getRoot();
			Set<? extends DependencyResult> dependencies = rootResult.getDependencies();
			Set<String> dependencySet = new HashSet<>();
			for (DependencyResult dependency : dependencies) {
				if (dependency instanceof ResolvedDependencyResult) {
					DefaultGradleDependencyNode dependencyNode = resolveDependency(
							(ResolvedDependencyResult) dependency, dependencySet);
					configNode.addChildren(dependencyNode);
				}
			}
			if (!configNode.getChildren().isEmpty()) {
				rootNode.addChildren(configNode);
			}
		}
		return rootNode;
	}

	private DefaultGradleDependencyNode resolveDependency(ResolvedDependencyResult result, Set<String> dependencySet) {
		DefaultGradleDependencyNode dependencyNode = new DefaultGradleDependencyNode(
				result.getSelected().getModuleVersion().getGroup() + ":"
						+ result.getSelected().getModuleVersion().getName() + ":"
						+ result.getSelected().getModuleVersion().getVersion(),
				GradleDependencyType.DEPENDENCY);
		if (dependencySet.add(dependencyNode.getName())) {
			Set<? extends DependencyResult> dependencies = result.getSelected().getDependencies();
			for (DependencyResult dependency : dependencies) {
				if (dependency instanceof ResolvedDependencyResult) {
					DefaultGradleDependencyNode childNode = resolveDependency((ResolvedDependencyResult) dependency,
							dependencySet);
					dependencyNode.addChildren(childNode);
				}
			}
		}
		return dependencyNode;
	}

	private List<String> getPlugins(Project project) {
		Convention convention = project.getConvention();
		return new ArrayList<>(convention.getPlugins().keySet());
	}

	private List<GradleClosure> getPluginClosures(Project project) {
		Convention convention = project.getConvention();
		ExtensionsSchema extensionsSchema = convention.getExtensionsSchema();
		List<GradleClosure> closures = new ArrayList<>();
		for (ExtensionSchema schema : extensionsSchema.getElements()) {
			TypeOf<?> publicType = schema.getPublicType();
			Class<?> concreteClass = publicType.getConcreteClass();
			List<GradleMethod> methods = new ArrayList<>();
			List<GradleField> fields = new ArrayList<>();
			for (Method method : concreteClass.getMethods()) {
				String name = method.getName();
				List<String> parameterTypes = new ArrayList<>();
				for (Class<?> parameterType : method.getParameterTypes()) {
					parameterTypes.add(parameterType.getName());
				}
				methods.add(new DefaultGradleMethod(name, parameterTypes, isDeprecated(method)));
				int modifiers = method.getModifiers();
				// See:
				// https://docs.gradle.org/current/userguide/custom_gradle_types.html#managed_properties
				// we offer managed properties for an abstract getter method
				if (name.startsWith("get") && name.length() > 3 && Modifier.isPublic(modifiers)
						&& Modifier.isAbstract(modifiers)) {
					fields.add(new DefaultGradleField(name.substring(3, 4).toLowerCase() + name.substring(4),
							isDeprecated(method)));
				}
			}
			for (Field field : concreteClass.getFields()) {
				fields.add(new DefaultGradleField(field.getName(), isDeprecated(field)));
			}
			DefaultGradleClosure closure = new DefaultGradleClosure(schema.getName(), methods, fields);
			closures.add(closure);
		}
		return closures;
	}

	private List<GradleTask> getGradleTasks(Project rootProject, Project project) {
		List<GradleTask> tasks = new ArrayList<>();
		TaskContainer taskContainer = project.getTasks();
		taskContainer.forEach(task -> {
			String name = task.getName();
			String group = task.getGroup() == null ? null : task.getGroup();
			String path = task.getPath();
			String projectName = task.getProject().getName();
			String buildFile = task.getProject().getBuildscript().getSourceFile().getAbsolutePath();
			String rootProjectName = rootProject.getName();
			String description = task.getDescription() == null ? null : task.getDescription();
			GradleTask newTask = new DefaultGradleTask(name, group, path, projectName, buildFile, rootProjectName,
					description);
			tasks.add(newTask);
			cachedTasks.add(newTask);
		});
		return tasks;
	}

	private boolean isDeprecated(AccessibleObject object) {
		for (Annotation annotation : object.getDeclaredAnnotations()) {
			if (annotation.toString().contains("Deprecated")) {
				return true;
			}
		}
		return false;
	}
}
